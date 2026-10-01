"""Bot profiles: lifecycle, permission narrowing, duplication and export."""

from __future__ import annotations

import fnmatch
from typing import Any, Dict, List, Optional

from app.v2.core import Core
from app.v2.db import loads
from app.v2.util import new_id, now_iso, slugify

DEFAULT_TOOLS = [
    "workspace.*", "web.fetch", "memory.*", "user.ask",
    "task.delegate", "task.get_status", "task.complete", "bot.message", "artifact.share",
]
ORG_ROLES = ("ceo", "head", "manager", "worker")
EDITABLE = {
    "name", "avatar", "role_description", "instructions", "provider_profile_id", "model",
    "tools", "policy", "budget", "org_role", "reports_to", "can_create_bots",
    "team_memory_access", "computer_mode", "pinned", "hidden",
}


class BotError(ValueError):
    pass


def _row_to_bot(row: Dict[str, Any]) -> Dict[str, Any]:
    bot = dict(row)
    bot["tools"] = loads(bot.pop("tools_json"), [])
    bot["policy"] = loads(bot.pop("policy_json"), [])
    bot["budget"] = loads(bot.pop("budget_json"), {})
    for flag in ("can_create_bots", "team_memory_access", "pinned", "hidden", "paused"):
        bot[flag] = bool(bot[flag])
    return bot


def tool_allowed(patterns: List[str], tool: str) -> bool:
    return any(fnmatch.fnmatchcase(tool, p) for p in patterns)


def tools_subset(child: List[str], parent: List[str]) -> bool:
    """Every child pattern must be covered by a parent pattern."""
    for pattern in child:
        if not any(fnmatch.fnmatchcase(pattern, p) or p == pattern for p in parent):
            return False
    return True


class BotService:
    def __init__(self, core: Core):
        self.core = core
        self.db = core.db

    # -- queries -------------------------------------------------------------
    def get(self, bot_id: str) -> Optional[Dict[str, Any]]:
        row = self.db.one("SELECT * FROM bots WHERE id = ?", (bot_id,))
        return _row_to_bot(row) if row else None

    def require(self, bot_id: str) -> Dict[str, Any]:
        bot = self.get(bot_id)
        if not bot:
            raise BotError(f"Unknown bot: {bot_id}")
        return bot

    def by_handle(self, handle: str) -> Optional[Dict[str, Any]]:
        row = self.db.one("SELECT * FROM bots WHERE handle = ? COLLATE NOCASE", (handle.lstrip("@"),))
        return _row_to_bot(row) if row else None

    def list(self, include_hidden: bool = True) -> List[Dict[str, Any]]:
        sql = "SELECT * FROM bots"
        if not include_hidden:
            sql += " WHERE hidden = 0"
        sql += " ORDER BY pinned DESC, name COLLATE NOCASE"
        bots = [_row_to_bot(r) for r in self.db.all(sql)]
        status = self.statuses()
        for b in bots:
            b["status"] = "paused" if b["paused"] else status.get(b["id"], "idle")
        return bots

    def statuses(self) -> Dict[str, str]:
        """Status shown in the UI is derived from backend run state only."""
        order = ["waiting_approval", "waiting_input", "unknown_outcome", "running", "waiting_dependency",
                 "retry_scheduled", "queued"]
        label = {
            "waiting_approval": "needs_approval", "waiting_input": "needs_input",
            "unknown_outcome": "needs_resolution", "running": "working",
            "waiting_dependency": "waiting", "retry_scheduled": "retrying", "queued": "queued",
        }
        rank = {s: i for i, s in enumerate(order)}
        best: Dict[str, str] = {}
        rows = self.db.all(
            "SELECT bot_id, status FROM runs WHERE status IN (%s)" % ",".join("?" * len(order)), order
        )
        for r in rows:
            current = best.get(r["bot_id"])
            if current is None or rank[r["status"]] < rank[current]:
                best[r["bot_id"]] = r["status"]
        return {bot_id: label[status] for bot_id, status in best.items()}

    # -- mutations -----------------------------------------------------------
    def _unique_handle(self, name: str) -> str:
        base = slugify(name)[:32]
        handle, n = base, 2
        while self.db.one("SELECT 1 FROM bots WHERE handle = ?", (handle,)):
            handle = f"{base}-{n}"
            n += 1
        return handle

    def _validate(self, data: Dict[str, Any]) -> None:
        if "name" in data and not str(data["name"]).strip():
            raise BotError("Bot name is required.")
        if data.get("org_role") not in (None, "", *ORG_ROLES):
            raise BotError(f"org_role must be one of {ORG_ROLES}.")
        if data.get("computer_mode") not in (None, "shared", "isolated"):
            raise BotError("computer_mode must be shared or isolated.")
        for rule in data.get("policy") or []:
            if not isinstance(rule, dict) or rule.get("effect") not in ("allow", "ask", "deny") or not rule.get("tool"):
                raise BotError("Policy rules need {tool, effect: allow|ask|deny}.")
        if data.get("reports_to"):
            if not self.get(data["reports_to"]):
                raise BotError("reports_to must reference an existing bot.")

    def create(self, data: Dict[str, Any], *, created_by_bot: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        unknown = set(data) - EDITABLE - {"handle"}
        if unknown:
            raise BotError(f"Unsupported bot fields: {', '.join(sorted(unknown))}")
        self._validate({"name": data.get("name", "New bot"), **data})
        tools = list(data.get("tools") or DEFAULT_TOOLS)
        policy = list(data.get("policy") or [])
        can_create = bool(data.get("can_create_bots", False))
        if created_by_bot is not None:
            # A bot can never mint a more privileged bot than itself.
            if not created_by_bot["can_create_bots"]:
                raise BotError("This bot is not allowed to create bots.")
            if not tools_subset(tools, created_by_bot["tools"]):
                raise BotError("A created bot cannot receive tools its creator does not have.")
            policy = list(created_by_bot["policy"]) + [r for r in policy if r.get("effect") in ("ask", "deny")]
            can_create = False
        now = now_iso()
        bot_id = new_id("bot")
        handle = data.get("handle") or self._unique_handle(data.get("name") or "bot")
        if self.db.one("SELECT 1 FROM bots WHERE handle = ?", (handle,)):
            handle = self._unique_handle(handle)
        with self.db.tx():
            self.db.insert("bots", {
                "id": bot_id, "name": data.get("name") or "New bot", "handle": handle,
                "avatar": data.get("avatar") or "🤖",
                "role_description": data.get("role_description", ""),
                "instructions": data.get("instructions", ""),
                "provider_profile_id": data.get("provider_profile_id"),
                "model": data.get("model"),
                "tools_json": tools, "policy_json": policy,
                "budget_json": data.get("budget") or {},
                "org_role": data.get("org_role") or None,
                "reports_to": data.get("reports_to") or None,
                "can_create_bots": can_create,
                "created_by_bot_id": created_by_bot["id"] if created_by_bot else None,
                "team_memory_access": bool(data.get("team_memory_access", False)),
                "computer_mode": data.get("computer_mode") or "shared",
                "pinned": bool(data.get("pinned", False)), "hidden": bool(data.get("hidden", False)),
                "paused": False, "created_at": now, "updated_at": now,
            })
            self.core.emit("bot.created", bot_id=bot_id, name=data.get("name"))
            self.core.audit("bot.create", actor_type="bot" if created_by_bot else "user",
                            actor_id=created_by_bot["id"] if created_by_bot else None, bot_id=bot_id)
        return self.require(bot_id)

    def update(self, bot_id: str, data: Dict[str, Any]) -> Dict[str, Any]:
        self.require(bot_id)
        unknown = set(data) - EDITABLE
        if unknown:
            raise BotError(f"Unsupported bot fields: {', '.join(sorted(unknown))}")
        self._validate(data)
        if data.get("reports_to") == bot_id:
            raise BotError("A bot cannot report to itself.")
        if data.get("reports_to"):
            # Reject hierarchy cycles.
            cursor, seen = data["reports_to"], {bot_id}
            while cursor:
                if cursor in seen:
                    raise BotError("reports_to would create a cycle.")
                seen.add(cursor)
                parent = self.get(cursor)
                cursor = parent["reports_to"] if parent else None
        values: Dict[str, Any] = {}
        for key, value in data.items():
            column = {"tools": "tools_json", "policy": "policy_json", "budget": "budget_json"}.get(key, key)
            values[column] = value
        values["updated_at"] = now_iso()
        with self.db.tx():
            self.db.update("bots", {"id": bot_id}, values)
            self.core.emit("bot.updated", bot_id=bot_id, fields=sorted(data))
            self.core.audit("bot.update", actor_type="user", bot_id=bot_id, fields=sorted(data))
        return self.require(bot_id)

    def set_paused(self, bot_id: str, paused: bool) -> Dict[str, Any]:
        """Pause holds new and queued runs; it does not hide the bot."""
        self.require(bot_id)
        with self.db.tx():
            self.db.update("bots", {"id": bot_id}, {"paused": paused, "updated_at": now_iso()})
            if paused:
                self.db.execute(
                    "UPDATE runs SET status = 'paused', updated_at = ? WHERE bot_id = ? AND status IN ('queued', 'retry_scheduled')",
                    (now_iso(), bot_id),
                )
                self.db.execute(
                    "UPDATE runs SET control = 'pause' WHERE bot_id = ? AND status = 'running'", (bot_id,)
                )
            else:
                self.db.execute(
                    "UPDATE runs SET status = 'queued', control = '', updated_at = ? WHERE bot_id = ? AND status = 'paused'",
                    (now_iso(), bot_id),
                )
            self.core.emit("bot.paused" if paused else "bot.resumed", bot_id=bot_id)
            self.core.audit("bot.pause" if paused else "bot.resume", actor_type="user", bot_id=bot_id)
        self.core.bus.wake_workers()
        return self.require(bot_id)

    def duplicate(self, bot_id: str) -> Dict[str, Any]:
        """Copies profile only. No secrets, history or learned memory; routines
        are copied disabled so they never double-fire."""
        src = self.require(bot_id)
        data = {k: src[k] for k in EDITABLE if k in src and k not in ("pinned", "hidden")}
        data["name"] = f"{src['name']} (copy)"
        copy = self.create(data)
        with self.db.tx():
            for row in self.db.all("SELECT skill_id FROM bot_skills WHERE bot_id = ?", (bot_id,)):
                self.db.insert("bot_skills", {"bot_id": copy["id"], "skill_id": row["skill_id"]})
            for r in self.db.all("SELECT * FROM routines WHERE bot_id = ?", (bot_id,)):
                now = now_iso()
                self.db.insert("routines", {
                    **{k: r[k] for k in ("name", "kind", "schedule_json", "timezone", "prompt", "overlap_policy", "catchup_policy")},
                    "id": new_id("rtn"), "bot_id": copy["id"], "conversation_id": None, "enabled": 0,
                    "webhook_secret_id": None, "next_run_at": None, "last_run_at": None,
                    "created_at": now, "updated_at": now,
                })
            self.core.audit("bot.duplicate", actor_type="user", source_bot_id=bot_id, bot_id=copy["id"])
        return copy

    def export(self, bot_id: str) -> Dict[str, Any]:
        bot = self.require(bot_id)
        skills = self.db.all(
            "SELECT s.slug FROM bot_skills bs JOIN skills s ON s.id = bs.skill_id WHERE bs.bot_id = ?", (bot_id,)
        )
        return {
            "format": "opendots.bot.v1",
            "bot": {k: bot[k] for k in EDITABLE if k in bot and k not in ("provider_profile_id", "reports_to")},
            "skills": [s["slug"] for s in skills],
            # Intentionally excluded: secrets, provider credentials, history, memories, sessions.
        }

    def import_(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        if payload.get("format") != "opendots.bot.v1":
            raise BotError("Unsupported export format.")
        data = {k: v for k, v in (payload.get("bot") or {}).items() if k in EDITABLE}
        return self.create(data)

    def delete(self, bot_id: str) -> None:
        """Delete cancels active work and removes the profile, memories and
        routines. Audit history is retained."""
        self.require(bot_id)
        now = now_iso()
        with self.db.tx():
            self.db.execute(
                "UPDATE runs SET status = 'cancelled', finished_at = ?, updated_at = ?, error = 'bot deleted' "
                "WHERE bot_id = ? AND status IN ('queued','paused','retry_scheduled','waiting_approval','waiting_input','waiting_dependency')",
                (now, now, bot_id),
            )
            self.db.execute("UPDATE runs SET control = 'cancel' WHERE bot_id = ? AND status = 'running'", (bot_id,))
            self.db.execute(
                "UPDATE tasks SET status = 'cancelled', updated_at = ? WHERE bot_id = ? AND status NOT IN ('completed','failed','cancelled')",
                (now, bot_id),
            )
            self.db.execute("UPDATE approvals SET status = 'invalidated' WHERE bot_id = ? AND status = 'pending'", (bot_id,))
            self.db.execute("DELETE FROM routines WHERE bot_id = ?", (bot_id,))
            for m in self.db.all("SELECT id FROM memories WHERE bot_id = ? AND scope = 'bot'", (bot_id,)):
                self.db.execute("DELETE FROM memories_fts WHERE memory_id = ?", (m["id"],))
            self.db.execute("DELETE FROM memories WHERE bot_id = ? AND scope = 'bot'", (bot_id,))
            self.db.execute("DELETE FROM bot_skills WHERE bot_id = ?", (bot_id,))
            self.db.execute("DELETE FROM memberships WHERE member_type = 'bot' AND member_id = ?", (bot_id,))
            self.db.execute("UPDATE bots SET reports_to = NULL WHERE reports_to = ?", (bot_id,))
            self.db.execute(
                "UPDATE conversations SET archived = 1 WHERE kind = 'private' AND default_bot_id = ?", (bot_id,)
            )
            self.db.execute("DELETE FROM bots WHERE id = ?", (bot_id,))
            self.core.emit("bot.deleted", bot_id=bot_id)
            self.core.audit("bot.delete", actor_type="user", bot_id=bot_id)

    # -- hierarchy -----------------------------------------------------------
    def is_subordinate(self, manager_id: str, bot_id: str) -> bool:
        cursor, seen = self.get(bot_id), set()
        while cursor and cursor.get("reports_to") and cursor["id"] not in seen:
            seen.add(cursor["id"])
            if cursor["reports_to"] == manager_id:
                return True
            cursor = self.get(cursor["reports_to"])
        return False

    def can_delegate(self, from_bot: Dict[str, Any], to_bot: Dict[str, Any]) -> Optional[str]:
        """Return a reason string if hierarchy rules forbid the delegation."""
        hierarchy_on = bool(self.core.kv_get("hierarchy_enforced", False))
        if not hierarchy_on or not from_bot.get("org_role") or not to_bot.get("org_role"):
            return None  # simple teams need no hierarchy
        if self.is_subordinate(from_bot["id"], to_bot["id"]):
            return None
        return f"Hierarchy rules: {from_bot['name']} may only delegate to its reports."
