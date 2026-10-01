"""Core container wiring the database, secrets, event bus and audit log.

A :class:`Core` is created once per process (see ``app.v2.runtime``) and
explicitly per test, so tests never share hidden global state.
"""

from __future__ import annotations

import asyncio
import json
import threading
from typing import Any, Dict, List, Optional

from app.services.secret_store import SecretStore
from app.v2.config import V2Settings
from app.v2.db import Database, loads
from app.v2.util import new_id, now_iso, redact

OWNER_USER_ID = "local-user"


class EventBus:
    """Wakes in-process subscribers after an event row is committed.

    The ``events`` table is the source of truth; this bus is only a latency
    optimisation. Subscribers in other processes fall back to polling the
    indexed cursor.
    """

    def __init__(self) -> None:
        self._waiters: List[tuple] = []
        self._lock = threading.Lock()
        self.work_signal: Optional[asyncio.Event] = None
        self._work_loop: Optional[asyncio.AbstractEventLoop] = None

    def bind_work_signal(self, loop: asyncio.AbstractEventLoop, event: asyncio.Event) -> None:
        self._work_loop, self.work_signal = loop, event

    def notify(self) -> None:
        with self._lock:
            waiters, self._waiters = self._waiters, []
        for loop, ev in waiters:
            if not loop.is_closed():
                loop.call_soon_threadsafe(ev.set)

    def wake_workers(self) -> None:
        if self._work_loop and self.work_signal and not self._work_loop.is_closed():
            self._work_loop.call_soon_threadsafe(self.work_signal.set)

    async def wait(self, timeout: float) -> None:
        loop = asyncio.get_running_loop()
        ev = asyncio.Event()
        with self._lock:
            self._waiters.append((loop, ev))
        try:
            await asyncio.wait_for(ev.wait(), timeout)
        except asyncio.TimeoutError:
            pass


class SecretVault:
    """Encrypted secret references. Plaintext never leaves the server process
    and is never written to events, audit, exports or bot instructions."""

    def __init__(self, db: Database, store: SecretStore):
        self.db, self.store = db, store

    def put(self, name: str, value: str, kind: str = "generic", secret_id: Optional[str] = None) -> str:
        sid = secret_id or new_id("sec")
        with self.db.tx():
            if self.db.one("SELECT id FROM secret_references WHERE id = ?", (sid,)):
                self.db.update("secret_references", {"id": sid}, {"ciphertext": self.store.encrypt(value), "name": name})
            else:
                self.db.insert("secret_references", {
                    "id": sid, "name": name, "kind": kind,
                    "ciphertext": self.store.encrypt(value), "created_at": now_iso(),
                })
        return sid

    def get(self, secret_id: Optional[str]) -> Optional[str]:
        if not secret_id:
            return None
        row = self.db.one("SELECT ciphertext FROM secret_references WHERE id = ?", (secret_id,))
        if not row:
            return None
        self.db.execute("UPDATE secret_references SET last_used_at = ? WHERE id = ?", (now_iso(), secret_id))
        return self.store.decrypt(row["ciphertext"])

    def delete(self, secret_id: Optional[str]) -> None:
        if secret_id:
            with self.db.tx():
                self.db.execute("DELETE FROM secret_references WHERE id = ?", (secret_id,))

    def describe(self) -> List[Dict[str, Any]]:
        return self.db.all("SELECT id, name, kind, created_at, last_used_at FROM secret_references ORDER BY created_at")


class Core:
    def __init__(self, settings: V2Settings):
        self.settings = settings
        settings.data_dir.mkdir(parents=True, exist_ok=True)
        settings.files_dir.mkdir(parents=True, exist_ok=True)
        settings.shared_workspace.mkdir(parents=True, exist_ok=True)
        self.db = Database(settings.db_path)
        self.secrets = SecretVault(self.db, SecretStore(settings.data_dir))
        self.bus = EventBus()
        # Filled by runtime wiring (tools, providers, browser broker, mcp).
        self.services: Dict[str, Any] = {}

    # -- events --------------------------------------------------------------
    def emit(self, type_: str, *, conversation_id=None, task_id=None, run_id=None, bot_id=None, **payload) -> None:
        """Append an event in the caller's transaction (outbox pattern)."""
        if conversation_id is None and task_id:
            conversation_id = self.db.scalar("SELECT conversation_id FROM tasks WHERE id = ?", (task_id,))
        self.db.insert("events", {
            "type": type_, "conversation_id": conversation_id, "task_id": task_id,
            "run_id": run_id, "bot_id": bot_id,
            "payload_json": json.dumps(redact(payload), ensure_ascii=False, default=str),
            "created_at": now_iso(),
        })
        self.db.after_commit(self.bus.notify)

    def events_after(self, cursor: int, limit: int = 200, conversation_id: Optional[str] = None) -> List[Dict[str, Any]]:
        if conversation_id:
            rows = self.db.all(
                "SELECT * FROM events WHERE id > ? AND conversation_id = ? ORDER BY id LIMIT ?",
                (cursor, conversation_id, limit),
            )
        else:
            rows = self.db.all("SELECT * FROM events WHERE id > ? ORDER BY id LIMIT ?", (cursor, limit))
        for r in rows:
            r["payload"] = loads(r.pop("payload_json"), {})
        return rows

    # -- audit ---------------------------------------------------------------
    def audit(self, action: str, *, actor_type: str, actor_id: Optional[str] = None, task_id=None, run_id=None,
              approval_id=None, decision=None, outcome=None, **summary) -> None:
        self.db.insert("audit_log", {
            "actor_type": actor_type, "actor_id": actor_id, "action": action,
            "task_id": task_id, "run_id": run_id, "approval_id": approval_id,
            "decision": decision, "outcome": outcome,
            "summary_json": json.dumps(redact(summary), ensure_ascii=False, default=str),
            "created_at": now_iso(),
        })

    # -- notifications -------------------------------------------------------
    def notify(self, kind: str, title: str, body: str = "", **refs) -> str:
        nid = new_id("ntf")
        self.db.insert("notifications", {
            "id": nid, "kind": kind, "title": title[:200], "body": redact(body)[:2000],
            "task_id": refs.get("task_id"), "approval_id": refs.get("approval_id"),
            "conversation_id": refs.get("conversation_id"), "bot_id": refs.get("bot_id"),
            "created_at": now_iso(),
        })
        self.emit("notification.created", conversation_id=refs.get("conversation_id"),
                  task_id=refs.get("task_id"), bot_id=refs.get("bot_id"), notification_id=nid, kind=kind, title=title)
        return nid

    # -- kv ------------------------------------------------------------------
    def kv_get(self, key: str, default: Any = None) -> Any:
        row = self.db.one("SELECT value FROM kv WHERE key = ?", (key,))
        return loads(row["value"], default) if row else default

    def kv_set(self, key: str, value: Any) -> None:
        with self.db.tx():
            self.db.execute(
                "INSERT INTO kv(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, json.dumps(value)),
            )
