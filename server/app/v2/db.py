"""SQLite persistence for the v2 engine.

Design notes
------------
* WAL mode, ``busy_timeout`` and explicit ``BEGIN IMMEDIATE`` write
  transactions: a writer takes the RESERVED lock up front, so two workers
  never deadlock on a read->write upgrade.
* All SQL lives behind :class:`Database` / the repository modules and uses
  portable constructs (no SQLite-only types) except the FTS5 index, which is
  isolated in :mod:`app.v2.memory` so a PostgreSQL backend can swap it for
  ``tsvector`` later.
* Migrations are numbered and append-only.
"""

from __future__ import annotations

import json
import os
import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional, Sequence

from app.v2.schema import MIGRATIONS
from app.v2.util import now_iso


class Database:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._local = threading.local()
        self.migrate()
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass

    # -- connections -------------------------------------------------------
    def _conn(self) -> sqlite3.Connection:
        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = sqlite3.connect(str(self.path), timeout=30, isolation_level=None, check_same_thread=False)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode = WAL")
            conn.execute("PRAGMA synchronous = NORMAL")
            conn.execute("PRAGMA foreign_keys = ON")
            conn.execute("PRAGMA busy_timeout = 30000")
            self._local.conn = conn
            self._local.depth = 0
        return conn

    def close(self) -> None:
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            conn.close()
            self._local.conn = None

    @contextmanager
    def tx(self) -> Iterator[sqlite3.Connection]:
        """Write transaction (re-entrant: nested calls join the outer one)."""
        conn = self._conn()
        if self._local.depth:
            self._local.depth += 1
            try:
                yield conn
            finally:
                self._local.depth -= 1
            return
        conn.execute("BEGIN IMMEDIATE")
        self._local.depth = 1
        self._local.after = []
        try:
            yield conn
            conn.execute("COMMIT")
        except BaseException:
            conn.execute("ROLLBACK")
            self._local.after = []
            raise
        finally:
            self._local.depth = 0
        callbacks, self._local.after = self._local.after, []
        for fn in callbacks:
            fn()

    def after_commit(self, fn) -> None:
        """Run ``fn`` after the current transaction commits (or now if none)."""
        if getattr(self._local, "depth", 0):
            self._local.after.append(fn)
        else:
            fn()

    # -- helpers -----------------------------------------------------------
    def one(self, sql: str, params: Sequence[Any] = ()) -> Optional[Dict[str, Any]]:
        row = self._conn().execute(sql, params).fetchone()
        return dict(row) if row else None

    def all(self, sql: str, params: Sequence[Any] = ()) -> List[Dict[str, Any]]:
        return [dict(r) for r in self._conn().execute(sql, params).fetchall()]

    def execute(self, sql: str, params: Sequence[Any] = ()) -> sqlite3.Cursor:
        return self._conn().execute(sql, params)

    def scalar(self, sql: str, params: Sequence[Any] = ()) -> Any:
        row = self._conn().execute(sql, params).fetchone()
        return row[0] if row else None

    def insert(self, table: str, values: Dict[str, Any]) -> None:
        cols = list(values)
        self.execute(
            f"INSERT INTO {table} ({', '.join(cols)}) VALUES ({', '.join('?' for _ in cols)})",
            [_encode(values[c]) for c in cols],
        )

    def update(self, table: str, key: Dict[str, Any], values: Dict[str, Any]) -> int:
        sets = ", ".join(f"{c} = ?" for c in values)
        where = " AND ".join(f"{c} = ?" for c in key)
        cur = self.execute(
            f"UPDATE {table} SET {sets} WHERE {where}",
            [_encode(v) for v in values.values()] + list(key.values()),
        )
        return cur.rowcount

    # -- migrations --------------------------------------------------------
    def migrate(self) -> None:
        conn = self._conn()
        conn.execute(
            "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)"
        )
        applied = {r[0] for r in conn.execute("SELECT version FROM schema_migrations")}
        for version, sql in sorted(MIGRATIONS.items()):
            if version in applied:
                continue
            with self.tx() as c:
                for statement in _split_sql(sql):
                    c.execute(statement)
                c.execute("INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)", (version, now_iso()))

    def schema_version(self) -> int:
        return int(self.scalar("SELECT COALESCE(MAX(version), 0) FROM schema_migrations") or 0)


def _encode(value: Any) -> Any:
    if isinstance(value, (dict, list, tuple)):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, bool):
        return int(value)
    return value


def _split_sql(sql: str) -> List[str]:
    """Split a migration on ``;`` while keeping trigger bodies intact."""
    statements, buf, depth = [], [], 0
    for line in sql.splitlines():
        stripped = line.strip().upper()
        if stripped.startswith("CREATE TRIGGER"):
            depth = 1
        buf.append(line)
        if depth and stripped.startswith("END;"):
            depth = 0
            statements.append("\n".join(buf))
            buf = []
        elif not depth and stripped.endswith(";"):
            statements.append("\n".join(buf))
            buf = []
    tail = "\n".join(buf).strip()
    if tail:
        statements.append(tail)
    return [s for s in (st.strip() for st in statements) if s and s != ";"]


def loads(value: Any, default: Any = None) -> Any:
    if value is None or value == "":
        return default
    if isinstance(value, (dict, list)):
        return value
    try:
        return json.loads(value)
    except (TypeError, ValueError):
        return default
