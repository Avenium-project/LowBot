"""Durable bot memory and shared team knowledge.

Separate from message history and run checkpoints. Every record has a scope,
source, timestamps and can be corrected or deleted. Search is FTS5 full-text
(no embedding dependency).
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Optional

from app.v2.core import Core
from app.v2.util import new_id, now_iso


def _fts_query(text: str) -> str:
    terms = [t for t in re.findall(r"\w+", text or "", re.UNICODE) if len(t) > 1][:12]
    return " OR ".join(f'"{t}"*' for t in terms)


class MemoryService:
    def __init__(self, core: Core):
        self.core, self.db = core, core.db

    def save(self, *, content: str, scope: str, bot_id: Optional[str], source: str, created_by: str) -> Dict[str, Any]:
        if scope not in ("bot", "team"):
            raise ValueError("scope must be bot or team")
        content = content.strip()
        if not content:
            raise ValueError("Memory content is empty.")
        mid, now = new_id("mem"), now_iso()
        with self.db.tx():
            self.db.insert("memories", {"id": mid, "scope": scope, "bot_id": bot_id if scope == "bot" else None,
                                        "content": content[:4000], "source": source[:300], "created_by": created_by,
                                        "created_at": now, "updated_at": now})
            self.db.execute("INSERT INTO memories_fts(content, memory_id) VALUES (?, ?)", (content[:4000], mid))
            self.core.emit("memory.saved", bot_id=bot_id, memory_id=mid, scope=scope)
        return self.get(mid)

    def get(self, mid: str) -> Optional[Dict[str, Any]]:
        return self.db.one("SELECT * FROM memories WHERE id = ? AND deleted_at IS NULL", (mid,))

    def visible_clause(self, bot: Optional[Dict[str, Any]]) -> tuple:
        if bot is None:  # owner sees everything
            return "1 = 1", ()
        if bot.get("team_memory_access"):
            return "(m.scope = 'team' OR m.bot_id = ?)", (bot["id"],)
        return "(m.scope = 'bot' AND m.bot_id = ?)", (bot["id"],)

    def search(self, query: str, *, bot: Optional[Dict[str, Any]] = None, limit: int = 10) -> List[Dict[str, Any]]:
        clause, params = self.visible_clause(bot)
        q = _fts_query(query)
        if not q:
            return self.list(bot=bot, limit=limit)
        return self.db.all(
            f"SELECT m.*, bm25(memories_fts) AS score FROM memories_fts f JOIN memories m ON m.id = f.memory_id "
            f"WHERE memories_fts MATCH ? AND m.deleted_at IS NULL AND {clause} ORDER BY score LIMIT ?",
            (q, *params, limit))

    def list(self, *, bot: Optional[Dict[str, Any]] = None, bot_id: Optional[str] = None,
             limit: int = 100) -> List[Dict[str, Any]]:
        clause, params = self.visible_clause(bot)
        extra, extra_params = ("AND (m.bot_id = ? OR m.scope = 'team')", (bot_id,)) if bot_id else ("", ())
        return self.db.all(
            f"SELECT m.* FROM memories m WHERE m.deleted_at IS NULL AND {clause} {extra} "
            f"ORDER BY m.updated_at DESC LIMIT ?", (*params, *extra_params, limit))

    def update(self, mid: str, content: str) -> Dict[str, Any]:
        with self.db.tx():
            n = self.db.update("memories", {"id": mid}, {"content": content[:4000], "updated_at": now_iso()})
            if not n:
                raise ValueError("Unknown memory.")
            self.db.execute("DELETE FROM memories_fts WHERE memory_id = ?", (mid,))
            self.db.execute("INSERT INTO memories_fts(content, memory_id) VALUES (?, ?)", (content[:4000], mid))
            self.core.audit("memory.update", actor_type="user", memory_id=mid)
        return self.get(mid)

    def delete(self, mid: str) -> None:
        with self.db.tx():
            self.db.update("memories", {"id": mid}, {"deleted_at": now_iso(), "content": ""})
            self.db.execute("DELETE FROM memories_fts WHERE memory_id = ?", (mid,))
            self.core.audit("memory.delete", actor_type="user", memory_id=mid)
