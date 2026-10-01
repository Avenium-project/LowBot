"""Generated files with metadata, versioning and authorised download."""

from __future__ import annotations

import hashlib
import mimetypes
import os
import re
from pathlib import Path
from typing import Any, Dict, List, Optional

from app.v2.core import Core
from app.v2.util import new_id, now_iso

MAX_ARTIFACT_BYTES = 25 * 1024 * 1024


def safe_name(name: str) -> str:
    name = re.sub(r"[^\w.\- ]+", "_", os.path.basename(name or "file")).strip(" .") or "file"
    return name[:120]


class ArtifactService:
    def __init__(self, core: Core):
        self.core, self.db = core, core.db
        self.root = core.settings.files_dir / "artifacts"
        self.root.mkdir(parents=True, exist_ok=True)

    def create(self, *, name: str, data: bytes, mime: Optional[str] = None, task: Optional[Dict[str, Any]] = None,
               run_id: Optional[str] = None, bot_id: Optional[str] = None,
               conversation_id: Optional[str] = None) -> Dict[str, Any]:
        if len(data) > MAX_ARTIFACT_BYTES:
            raise ValueError("Artifact too large.")
        name = safe_name(name)
        mime = mime or mimetypes.guess_type(name)[0] or "application/octet-stream"
        aid = new_id("art")
        digest = hashlib.sha256(data).hexdigest()
        path = self.root / aid
        path.write_bytes(data)
        os.chmod(path, 0o600)
        conv = conversation_id or (task or {}).get("conversation_id")
        with self.db.tx():
            version = 1 + int(self.db.scalar(
                "SELECT COALESCE(MAX(version), 0) FROM artifacts WHERE name = ? AND COALESCE(conversation_id, '') = ?",
                (name, conv or "")) or 0)
            self.db.insert("artifacts", {
                "id": aid, "task_id": (task or {}).get("id"), "run_id": run_id,
                "bot_id": bot_id or (task or {}).get("bot_id"), "conversation_id": conv, "name": name, "mime": mime,
                "size": len(data), "sha256": digest, "version": version, "storage_path": str(path),
                "created_at": now_iso(),
            })
            self.core.emit("artifact.created", conversation_id=conv, task_id=(task or {}).get("id"),
                           bot_id=bot_id, artifact_id=aid, name=name, version=version, size=len(data))
        return self.get(aid)

    def get(self, aid: str) -> Optional[Dict[str, Any]]:
        row = self.db.one("SELECT * FROM artifacts WHERE id = ?", (aid,))
        if row:
            row.pop("storage_path", None)
        return row

    def path(self, aid: str) -> Optional[Path]:
        row = self.db.one("SELECT storage_path FROM artifacts WHERE id = ?", (aid,))
        if not row:
            return None
        p = Path(row["storage_path"])
        return p if p.exists() and p.resolve().parent == self.root.resolve() else None

    def list(self, conversation_id: Optional[str] = None, task_id: Optional[str] = None) -> List[Dict[str, Any]]:
        sql, params = "SELECT * FROM artifacts WHERE 1 = 1", []
        if conversation_id:
            sql += " AND conversation_id = ?"
            params.append(conversation_id)
        if task_id:
            sql += " AND task_id = ?"
            params.append(task_id)
        rows = self.db.all(sql + " ORDER BY created_at DESC LIMIT 200", params)
        for r in rows:
            r.pop("storage_path", None)
        return rows
