"""Durable approvals.

An approval is bound to: the approving user, the run step, the tool, and the
hash of the exact arguments. It expires, can be consumed exactly once, and is
invalidated when the draft is edited (a new approval is issued for the new
arguments). It survives process restarts because it lives only in SQLite.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

from app.v2.core import OWNER_USER_ID, Core
from app.v2.db import loads
from app.v2.util import args_hash, iso_in, new_id, now_iso, redact


class ApprovalError(ValueError):
    pass


class ApprovalService:
    def __init__(self, core: Core):
        self.core, self.db = core, core.db

    def _row(self, r: Dict[str, Any]) -> Dict[str, Any]:
        r = dict(r)
        r["display"] = loads(r.pop("display_json"), {})
        return r

    def get(self, approval_id: str) -> Optional[Dict[str, Any]]:
        row = self.db.one("SELECT * FROM approvals WHERE id = ?", (approval_id,))
        return self._row(row) if row else None

    def list(self, status: Optional[str] = "pending") -> List[Dict[str, Any]]:
        if status:
            rows = self.db.all("SELECT * FROM approvals WHERE status = ? ORDER BY created_at DESC LIMIT 200", (status,))
        else:
            rows = self.db.all("SELECT * FROM approvals ORDER BY created_at DESC LIMIT 200")
        return [self._row(r) for r in rows]

    def open(self, *, run: Dict[str, Any], step_id: str, task: Dict[str, Any], tool: str, arguments: Dict[str, Any],
             card: Dict[str, str]) -> str:
        """Called inside the engine's fenced transaction."""
        aid = new_id("apr")
        self.db.insert("approvals", {
            "id": aid, "run_id": run["id"], "step_id": step_id, "task_id": task["id"], "bot_id": run["bot_id"],
            "conversation_id": task.get("conversation_id"), "tool": tool, "args_hash": args_hash(tool, arguments),
            "display_json": redact(arguments), "summary": card.get("summary", tool),
            "effect": card.get("effect", ""), "target": card.get("target", ""), "status": "pending",
            "approver_user_id": OWNER_USER_ID, "expires_at": iso_in(self.core.settings.approval_ttl_s),
            "created_at": now_iso(),
        })
        self.core.emit("approval.requested", conversation_id=task.get("conversation_id"), task_id=task["id"],
                       run_id=run["id"], bot_id=run["bot_id"], approval_id=aid, tool=tool, summary=card.get("summary"))
        self.core.notify("approval", f"Approval needed: {card.get('summary', tool)}", card.get("effect", ""),
                         task_id=task["id"], approval_id=aid, conversation_id=task.get("conversation_id"),
                         bot_id=run["bot_id"])
        self.core.audit("approval.open", actor_type="bot", actor_id=run["bot_id"], task_id=task["id"],
                        run_id=run["id"], approval_id=aid, tool=tool)
        return aid

    def decide(self, approval_id: str, *, user_id: str, decision: str, args_hash_seen: str) -> Dict[str, Any]:
        if decision not in ("approve", "always", "deny"):
            raise ApprovalError("decision must be approve, always or deny")
        with self.db.tx():
            a = self.get(approval_id)
            if not a:
                raise ApprovalError("Unknown approval.")
            if a["approver_user_id"] != user_id:
                raise ApprovalError("This approval belongs to another user.")
            if a["status"] != "pending":
                raise ApprovalError(f"Approval is {a['status']}, not pending.")
            if a["expires_at"] <= now_iso():
                self._expire(a)
                raise ApprovalError("Approval expired.")
            if args_hash_seen != a["args_hash"]:
                raise ApprovalError("The action changed since you viewed it. Review it again.")
            status = "approved" if decision == "approve" else "denied"
            n = self.db.update("approvals", {"id": approval_id, "status": "pending"},
                               {"status": status, "decided_by": user_id, "decided_at": now_iso()})
            if n != 1:
                raise ApprovalError("Approval was decided concurrently.")
            if decision == "always":  # Grok-style "Always allow": a rule for this bot + tool
                rule_id = new_id("pol")
                self.db.insert("policy_rules", {"id": rule_id, "bot_id": a["bot_id"], "tool_pattern": a["tool"],
                                                "effect": "allow", "created_at": now_iso()})
                self.core.audit("policy.add", actor_type="user", actor_id=user_id, rule_id=rule_id, tool=a["tool"],
                                effect="allow", source="always_allow")
            self._requeue(a["run_id"])
            self.core.emit(f"approval.{status}", conversation_id=a["conversation_id"], task_id=a["task_id"],
                           run_id=a["run_id"], bot_id=a["bot_id"], approval_id=approval_id)
            self.core.audit("approval.decide", actor_type="user", actor_id=user_id, task_id=a["task_id"],
                            run_id=a["run_id"], approval_id=approval_id, decision=status, tool=a["tool"])
        self.core.bus.wake_workers()
        return self.get(approval_id)

    def edit(self, approval_id: str, *, user_id: str, arguments: Dict[str, Any], validate) -> Dict[str, Any]:
        """Editing a draft invalidates the old approval and issues a new one."""
        error = validate(arguments)
        if error:
            raise ApprovalError(error)
        with self.db.tx():
            a = self.get(approval_id)
            if not a or a["status"] != "pending":
                raise ApprovalError("Only pending approvals can be edited.")
            if a["approver_user_id"] != user_id:
                raise ApprovalError("This approval belongs to another user.")
            self.db.update("approvals", {"id": approval_id}, {"status": "invalidated", "decided_at": now_iso()})
            step = self.db.one("SELECT * FROM run_steps WHERE id = ?", (a["step_id"],))
            step_input = loads(step["input_json"], {})
            step_input["arguments"] = arguments
            step_input["edited_by_user"] = True
            self.db.update("run_steps", {"id": a["step_id"]}, {"input_json": json.dumps(step_input), "updated_at": now_iso()})
            new_id_ = new_id("apr")
            self.db.insert("approvals", {
                **{k: a[k] for k in ("run_id", "step_id", "task_id", "bot_id", "conversation_id", "tool", "summary",
                                     "effect", "target", "approver_user_id")},
                "id": new_id_, "args_hash": args_hash(a["tool"], arguments), "display_json": redact(arguments),
                "status": "pending", "expires_at": iso_in(self.core.settings.approval_ttl_s), "created_at": now_iso(),
            })
            self.core.emit("approval.edited", conversation_id=a["conversation_id"], task_id=a["task_id"],
                           run_id=a["run_id"], approval_id=new_id_, replaces=approval_id)
            self.core.audit("approval.edit", actor_type="user", actor_id=user_id, approval_id=new_id_,
                            replaces=approval_id)
        return self.get(new_id_)

    def consume(self, approval_id: str, tool: str, arguments: Dict[str, Any]) -> bool:
        """Single use: atomically flips approved -> consumed iff the arguments
        still hash to what the user approved and it has not expired."""
        n = self.db.execute(
            "UPDATE approvals SET status = 'consumed', consumed_at = ? WHERE id = ? AND status = 'approved' "
            "AND args_hash = ? AND tool = ? AND expires_at > ?",
            (now_iso(), approval_id, args_hash(tool, arguments), tool, now_iso())).rowcount
        return n == 1

    def _requeue(self, run_id: str) -> None:
        self.db.execute(
            "UPDATE runs SET status = 'queued', updated_at = ? WHERE id = ? AND status = 'waiting_approval'",
            (now_iso(), run_id))

    def _expire(self, a: Dict[str, Any]) -> None:
        self.db.update("approvals", {"id": a["id"], "status": "pending"}, {"status": "expired", "decided_at": now_iso()})
        self._requeue(a["run_id"])
        self.core.emit("approval.expired", conversation_id=a["conversation_id"], task_id=a["task_id"],
                       run_id=a["run_id"], approval_id=a["id"])
        self.core.audit("approval.expire", actor_type="system", approval_id=a["id"], run_id=a["run_id"])

    def sweep_expired(self) -> int:
        rows = self.db.all("SELECT * FROM approvals WHERE status = 'pending' AND expires_at <= ?", (now_iso(),))
        if not rows:
            return 0
        with self.db.tx():
            for r in rows:
                self._expire(self._row(r))
        self.core.bus.wake_workers()
        return len(rows)

    def next_expiry(self) -> Optional[str]:
        return self.db.scalar("SELECT MIN(expires_at) FROM approvals WHERE status = 'pending'")
