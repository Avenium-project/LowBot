"""Conversations, messages and the task/run lifecycle (everything except the
step loop itself, which lives in :mod:`app.v2.engine`)."""

from __future__ import annotations

import json
import re
from typing import Any, Dict, List, Optional, Tuple

from app.v2.bots import BotService
from app.v2.core import OWNER_USER_ID, Core
from app.v2.db import loads
from app.v2.util import new_id, now_iso, truncate

MENTION_RE = re.compile(r"(?<![\w@])@([A-Za-z0-9][A-Za-z0-9_\-]{0,40})")
TERMINAL = ("completed", "failed", "cancelled")
ACTIVE_RUN = ("queued", "running", "waiting_approval", "waiting_input", "waiting_dependency",
              "paused", "retry_scheduled", "unknown_outcome")

PRIORITY_USER = 80
PRIORITY_DELEGATED = 60
PRIORITY_BACKGROUND = 30


class TaskError(ValueError):
    pass


def parse_mentions(text: str) -> List[str]:
    return list(dict.fromkeys(m.group(1).lower() for m in MENTION_RE.finditer(text or "")))


class TaskService:
    def __init__(self, core: Core, bots: BotService):
        self.core, self.db, self.bots = core, core.db, bots

    # -- conversations -------------------------------------------------------
    def create_conversation(self, kind: str, bot_ids: List[str], title: str = "") -> Dict[str, Any]:
        if kind not in ("private", "group"):
            raise TaskError("kind must be private or group")
        if not bot_ids:
            raise TaskError("A conversation needs at least one bot.")
        if kind == "private" and len(bot_ids) != 1:
            raise TaskError("A private conversation has exactly one bot.")
        for b in bot_ids:
            self.bots.require(b)
        conv_id, now = new_id("conv"), now_iso()
        with self.db.tx():
            self.db.insert("conversations", {
                "id": conv_id, "kind": kind, "title": title or "", "default_bot_id": bot_ids[0],
                "created_at": now, "updated_at": now,
            })
            self.db.insert("memberships", {"conversation_id": conv_id, "member_type": "user",
                                           "member_id": OWNER_USER_ID, "joined_at": now})
            for b in dict.fromkeys(bot_ids):
                self.db.insert("memberships", {"conversation_id": conv_id, "member_type": "bot",
                                               "member_id": b, "joined_at": now})
            self.core.emit("conversation.created", conversation_id=conv_id, kind=kind)
        return self.get_conversation(conv_id)

    def private_conversation(self, bot_id: str) -> Dict[str, Any]:
        row = self.db.one(
            "SELECT id FROM conversations WHERE kind = 'private' AND default_bot_id = ? AND archived = 0 "
            "ORDER BY created_at LIMIT 1", (bot_id,))
        if row:
            return self.get_conversation(row["id"])
        return self.create_conversation("private", [bot_id], "")

    def get_conversation(self, conv_id: str) -> Dict[str, Any]:
        conv = self.db.one("SELECT * FROM conversations WHERE id = ?", (conv_id,))
        if not conv:
            raise TaskError("Unknown conversation.")
        conv["bot_ids"] = [r["member_id"] for r in self.db.all(
            "SELECT member_id FROM memberships WHERE conversation_id = ? AND member_type = 'bot' ORDER BY joined_at",
            (conv_id,))]
        conv["archived"] = bool(conv["archived"])
        last = self.db.one(
            "SELECT seq, text, author_type, author_id, created_at FROM messages WHERE conversation_id = ? "
            "ORDER BY seq DESC LIMIT 1", (conv_id,))
        conv["last_seq"] = (last or {}).get("seq") or 0
        conv["last_message"] = {"text": last["text"][:160], "author_type": last["author_type"],
                                "author_id": last["author_id"], "created_at": last["created_at"]} if last else None
        conv["unread"] = self.db.scalar(
            "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND seq > ? AND author_type != 'user'",
            (conv_id, conv["last_read_seq"])) or 0
        return conv

    def list_conversations(self) -> List[Dict[str, Any]]:
        ids = [r["id"] for r in self.db.all(
            "SELECT id FROM conversations WHERE archived = 0 ORDER BY updated_at DESC")]
        return [self.get_conversation(i) for i in ids]

    def add_member(self, conv_id: str, bot_id: str) -> Dict[str, Any]:
        conv = self.get_conversation(conv_id)
        self.bots.require(bot_id)
        if conv["kind"] == "private":
            raise TaskError("Convert to a group conversation to add bots.")
        with self.db.tx():
            self.db.execute(
                "INSERT OR IGNORE INTO memberships(conversation_id, member_type, member_id, joined_at) VALUES (?, 'bot', ?, ?)",
                (conv_id, bot_id, now_iso()))
            self.core.emit("conversation.member_added", conversation_id=conv_id, bot_id=bot_id)
        return self.get_conversation(conv_id)

    def mark_read(self, conv_id: str, seq: int) -> None:
        with self.db.tx():
            self.db.execute(
                "UPDATE conversations SET last_read_seq = MAX(last_read_seq, ?) WHERE id = ?", (seq, conv_id))
            self.db.execute("UPDATE tasks SET unread = 0 WHERE conversation_id = ?", (conv_id,))

    def mark_unread(self, conv_id: str) -> None:
        """Show the conversation as unread again from its latest bot/system message."""
        seq = self.db.scalar("SELECT MAX(seq) FROM messages WHERE conversation_id = ? AND author_type != 'user'", (conv_id,))
        with self.db.tx():
            self.db.execute("UPDATE conversations SET last_read_seq = ? WHERE id = ?", (max(0, (seq or 1) - 1), conv_id))

    # -- messages ------------------------------------------------------------
    def messages(self, conv_id: str, after_seq: int = 0, limit: int = 200, *,
                 before_seq: Optional[int] = None, latest: bool = False) -> List[Dict[str, Any]]:
        # Seek using (conversation_id, seq), never scan/transfer the whole history.
        if before_seq is not None:
            rows = self.db.all(
                "SELECT * FROM messages WHERE conversation_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?",
                (conv_id, before_seq, limit))
            rows.reverse()
        elif latest:
            rows = self.db.all(
                "SELECT * FROM messages WHERE conversation_id = ? ORDER BY seq DESC LIMIT ?", (conv_id, limit))
            rows.reverse()
        else:
            rows = self.db.all(
                "SELECT * FROM messages WHERE conversation_id = ? AND seq > ? ORDER BY seq LIMIT ?",
                (conv_id, after_seq, limit))
        for r in rows:
            r["mentions"] = loads(r.pop("mentions_json"), [])
            r["attachments"] = loads(r.pop("attachments_json"), [])
            r["meta"] = loads(r.pop("meta_json"), {})
        return rows

    def _insert_message(self, conv_id: str, author_type: str, author_id: Optional[str], text: str, *,
                        mentions=None, task_id=None, client_msg_id=None, thread_root_id=None,
                        attachments=None, meta=None) -> Dict[str, Any]:
        msg_id = new_id("msg")
        self.db.insert("messages", {
            "id": msg_id, "conversation_id": conv_id, "author_type": author_type, "author_id": author_id,
            "text": text, "mentions_json": mentions or [], "task_id": task_id, "client_msg_id": client_msg_id,
            "thread_root_id": thread_root_id, "attachments_json": attachments or [], "meta_json": meta or {},
            "created_at": now_iso(),
        })
        self.db.execute("UPDATE conversations SET updated_at = ? WHERE id = ?", (now_iso(), conv_id))
        row = self.db.one("SELECT seq FROM messages WHERE id = ?", (msg_id,))
        self.core.emit("message.created", conversation_id=conv_id, task_id=task_id,
                       bot_id=author_id if author_type == "bot" else None,
                       message_id=msg_id, seq=row["seq"], author_type=author_type)
        return {"id": msg_id, "seq": row["seq"]}

    def post_user_message(self, conv_id: str, text: str, *, client_msg_id: Optional[str] = None,
                          attachments: Optional[List[Dict[str, Any]]] = None,
                          thread_root_id: Optional[str] = None) -> Dict[str, Any]:
        """Persist a user message and the tasks it creates in ONE transaction.

        Idempotent on ``client_msg_id``: a retried POST (flaky mobile network,
        two open clients replaying an outbox) returns the original message and
        never creates a second task.
        """
        text = (text or "").strip()
        if not text and not attachments:
            raise TaskError("Message is empty.")
        conv = self.get_conversation(conv_id)
        if client_msg_id:
            existing = self.db.one(
                "SELECT id, seq FROM messages WHERE conversation_id = ? AND client_msg_id = ?", (conv_id, client_msg_id))
            if existing:
                tasks = self.db.all("SELECT id, bot_id, status FROM tasks WHERE source_message_id = ?", (existing["id"],))
                return {"message_id": existing["id"], "seq": existing["seq"], "tasks": tasks, "duplicate": True}
        mentions = parse_mentions(text)
        targets = self._route(conv, mentions)
        skill = self._skill_for(text)
        with self.db.tx():
            msg = self._insert_message(conv_id, "user", OWNER_USER_ID, text, mentions=mentions,
                                       client_msg_id=client_msg_id, attachments=attachments,
                                       thread_root_id=thread_root_id)
            tasks = []
            for bot in targets:
                tasks.append(self.create_task(
                    bot_id=bot["id"], conversation_id=conv_id, requester_type="user",
                    requester_id=OWNER_USER_ID, instructions=text, title=truncate(text, 80),
                    priority=PRIORITY_USER, source_message_id=msg["id"], skill=skill, _in_tx=True,
                ))
        self.core.bus.wake_workers()
        return {"message_id": msg["id"], "seq": msg["seq"], "tasks": tasks, "duplicate": False}

    def _route(self, conv: Dict[str, Any], mentions: List[str]) -> List[Dict[str, Any]]:
        members = [self.bots.get(b) for b in conv["bot_ids"]]
        members = [m for m in members if m]
        if mentions:
            chosen = [m for m in members if m["handle"].lower() in mentions]
            if chosen:
                return chosen
        default = next((m for m in members if m["id"] == conv["default_bot_id"]), members[0] if members else None)
        if not default:
            raise TaskError("No bot is available in this conversation.")
        return [default]

    def _skill_for(self, text: str) -> Optional[Dict[str, Any]]:
        m = re.match(r"^/([a-z0-9][a-z0-9\-]*)\b", text.strip())
        if not m:
            return None
        row = self.db.one(
            "SELECT s.id, s.slug, v.version, v.instructions, v.tools_json, v.completion_criteria "
            "FROM skills s JOIN skill_versions v ON v.skill_id = s.id AND v.version = s.current_version WHERE s.slug = ?",
            (m.group(1),))
        return row

    def post_bot_message(self, task: Dict[str, Any], text: str, *, meta: Optional[Dict[str, Any]] = None,
                         route_mentions: bool = True) -> Dict[str, Any]:
        """A bot speaks in a conversation. @mentions of member bots create
        asynchronous bot->bot tasks bounded by the delegation guards."""
        conv_id = task["conversation_id"]
        if not conv_id:
            raise TaskError("This task has no conversation to post to.")
        conv = self.get_conversation(conv_id)
        mentions = parse_mentions(text)
        with self.db.tx():
            msg = self._insert_message(conv_id, "bot", task["bot_id"], text, mentions=mentions,
                                       task_id=task["id"], meta=meta)
            self.db.execute("UPDATE tasks SET unread = 1 WHERE id = ?", (task["id"],))
            created = []
            if route_mentions and mentions:
                members = {b: self.bots.get(b) for b in conv["bot_ids"]}
                for bot in members.values():
                    if not bot or bot["handle"].lower() not in mentions or bot["id"] == task["bot_id"]:
                        continue
                    try:
                        created.append(self.create_task(
                            bot_id=bot["id"], conversation_id=conv_id, requester_type="bot",
                            requester_id=task["bot_id"], instructions=text, title=truncate(text, 80),
                            parent_task=task, priority=PRIORITY_DELEGATED, source_message_id=msg["id"], _in_tx=True,
                        ))
                    except TaskError as exc:
                        # Loop/budget guards: the mention is shown but does not wake the bot.
                        self.core.emit("delegation.rejected", conversation_id=conv_id, task_id=task["id"],
                                       bot_id=task["bot_id"], target_bot_id=bot["id"], reason=str(exc))
        if created:
            self.core.bus.wake_workers()
        return {**msg, "tasks": created}

    # -- tasks ---------------------------------------------------------------
    def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        return self.db.one("SELECT * FROM tasks WHERE id = ?", (task_id,))

    def require_task(self, task_id: str) -> Dict[str, Any]:
        t = self.get_task(task_id)
        if not t:
            raise TaskError("Unknown task.")
        return t

    def run_for_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        return self.db.one("SELECT * FROM runs WHERE task_id = ? ORDER BY created_at DESC LIMIT 1", (task_id,))

    def guard_delegation(self, parent: Dict[str, Any], to_bot_id: str) -> None:
        settings = self.core.settings
        if parent["depth"] + 1 > settings.max_delegation_depth:
            raise TaskError(f"Delegation depth limit ({settings.max_delegation_depth}) reached.")
        count = self.db.scalar("SELECT COUNT(*) FROM tasks WHERE correlation_id = ?", (parent["correlation_id"],))
        if count >= settings.max_tasks_per_correlation:
            raise TaskError(f"Message budget for this conversation chain ({settings.max_tasks_per_correlation}) exhausted.")
        # Cycle: the target is already an (unfinished) ancestor of this task.
        cursor = parent
        while cursor:
            if cursor["bot_id"] == to_bot_id and cursor["status"] not in TERMINAL:
                raise TaskError("Delegation cycle detected: the target bot is already waiting on this chain.")
            cursor = self.get_task(cursor["parent_task_id"]) if cursor.get("parent_task_id") else None
        from_bot, to_bot = self.bots.get(parent["bot_id"]), self.bots.require(to_bot_id)
        if from_bot:
            reason = self.bots.can_delegate(from_bot, to_bot)
            if reason:
                raise TaskError(reason)

    def create_task(self, *, bot_id: str, conversation_id: Optional[str], requester_type: str,
                    requester_id: Optional[str], instructions: str, title: str = "", expected_output: str = "",
                    parent_task: Optional[Dict[str, Any]] = None, priority: int = PRIORITY_USER,
                    source_message_id: Optional[str] = None, skill: Optional[Dict[str, Any]] = None,
                    routine_run_id: Optional[str] = None, _in_tx: bool = False) -> Dict[str, Any]:
        bot = self.bots.require(bot_id)
        if parent_task is not None:
            self.guard_delegation(parent_task, bot_id)
        task_id, run_id, now = new_id("task"), new_id("run"), now_iso()
        correlation = parent_task["correlation_id"] if parent_task else new_id("corr")
        root = (parent_task.get("root_task_id") or parent_task["id"]) if parent_task else None
        depth = parent_task["depth"] + 1 if parent_task else 0
        settings = self.core.settings

        def _write():
            self.db.insert("tasks", {
                "id": task_id, "conversation_id": conversation_id, "bot_id": bot_id,
                "requester_type": requester_type, "requester_id": requester_id,
                "parent_task_id": parent_task["id"] if parent_task else None, "root_task_id": root,
                "correlation_id": correlation, "depth": depth, "title": title or truncate(instructions, 80),
                "instructions": instructions, "expected_output": expected_output, "status": "queued",
                "priority": priority, "skill_id": skill["id"] if skill else None,
                "skill_version": skill["version"] if skill else None, "source_message_id": source_message_id,
                "routine_run_id": routine_run_id, "created_at": now, "updated_at": now,
            })
            self.db.insert("runs", {
                "id": run_id, "task_id": task_id, "bot_id": bot_id,
                "status": "paused" if bot["paused"] else "queued", "priority": priority,
                "max_attempts": settings.default_max_attempts,
                "max_steps": 0,  # Legacy NOT NULL column; the engine no longer enforces a step cap.
                "created_at": now, "updated_at": now,
            })
            self.core.emit("task.created", conversation_id=conversation_id, task_id=task_id, run_id=run_id,
                           bot_id=bot_id, title=title, requester_type=requester_type, requester_id=requester_id,
                           parent_task_id=parent_task["id"] if parent_task else None)

        if _in_tx:
            _write()
        else:
            with self.db.tx():
                _write()
            self.core.bus.wake_workers()
        return {"id": task_id, "run_id": run_id, "bot_id": bot_id, "status": "queued"}

    def task_tree(self, task_id: str) -> Dict[str, Any]:
        task = self.require_task(task_id)
        run = self.run_for_task(task_id)
        children = self.db.all("SELECT id FROM tasks WHERE parent_task_id = ? ORDER BY created_at", (task_id,))
        handoffs = self.db.all("SELECT * FROM handoffs WHERE from_task_id = ? OR to_task_id = ?", (task_id, task_id))
        return {"task": task, "run": run, "handoffs": handoffs,
                "children": [self.task_tree(c["id"]) for c in children]}

    # -- control -------------------------------------------------------------
    def cancel(self, task_id: str, actor: str = "user") -> Dict[str, Any]:
        """Stop: no new actions start. A running step is signalled to abort;
        side effects already performed are reported, not hidden."""
        task = self.require_task(task_id)
        now = now_iso()
        with self.db.tx():
            run = self.run_for_task(task_id)
            if run and run["status"] == "running":
                self.db.execute("UPDATE runs SET control = 'cancel', updated_at = ? WHERE id = ?", (now, run["id"]))
            elif run and run["status"] not in TERMINAL:
                self.db.execute(
                    "UPDATE runs SET status = 'cancelled', finished_at = ?, updated_at = ?, lease_owner = NULL WHERE id = ?",
                    (now, now, run["id"]))
                self._finish_task(task, "cancelled", None, "Cancelled by user.")
            self.db.execute("UPDATE approvals SET status = 'invalidated' WHERE task_id = ? AND status = 'pending'", (task_id,))
            for child in self.db.all(
                    "SELECT id FROM tasks WHERE parent_task_id = ? AND status NOT IN ('completed','failed','cancelled')",
                    (task_id,)):
                self.cancel(child["id"], actor)
            self.core.audit("task.cancel", actor_type=actor, task_id=task_id)
        return self.require_task(task_id)

    def pause(self, task_id: str) -> None:
        run = self.run_for_task(task_id)
        if not run or run["status"] in TERMINAL:
            raise TaskError("Nothing to pause.")
        with self.db.tx():
            if run["status"] == "running":
                self.db.execute("UPDATE runs SET control = 'pause' WHERE id = ?", (run["id"],))
            elif run["status"] in ("queued", "retry_scheduled"):
                self.db.execute("UPDATE runs SET status = 'paused', updated_at = ? WHERE id = ?", (now_iso(), run["id"]))
                self.core.emit("run.paused", task_id=task_id, run_id=run["id"], bot_id=run["bot_id"])
            else:
                self.db.execute("UPDATE runs SET control = 'pause' WHERE id = ?", (run["id"],))

    def resume(self, task_id: str) -> None:
        run = self.run_for_task(task_id)
        if not run:
            raise TaskError("Nothing to resume.")
        with self.db.tx():
            if run["status"] == "paused":
                self.db.execute(
                    "UPDATE runs SET status = 'queued', control = '', updated_at = ? WHERE id = ?", (now_iso(), run["id"]))
                self.core.emit("run.resumed", task_id=task_id, run_id=run["id"], bot_id=run["bot_id"])
            else:
                self.db.execute("UPDATE runs SET control = '' WHERE id = ?", (run["id"],))
        self.core.bus.wake_workers()

    def answer_input(self, task_id: str, answer: str) -> None:
        """Answer a bot's question (run in waiting_input)."""
        run = self.run_for_task(task_id)
        if not run or run["status"] != "waiting_input":
            raise TaskError("This task is not waiting for input.")
        waiting = loads(run["waiting_json"], {})
        step_id = waiting.get("step_id")
        with self.db.tx():
            if step_id:
                self.db.execute(
                    "UPDATE run_steps SET status = 'completed', output_json = ?, updated_at = ? WHERE id = ? AND status = 'waiting'",
                    (json.dumps({"answer": answer}), now_iso(), step_id))
            n = self.db.update("runs", {"id": run["id"], "status": "waiting_input"},
                               {"status": "queued", "waiting_json": {}, "updated_at": now_iso()})
            if n != 1:
                raise TaskError("The task state changed; refresh and retry.")
            task = self.require_task(task_id)
            if task["conversation_id"]:
                self._insert_message(task["conversation_id"], "user", OWNER_USER_ID, answer, task_id=task_id,
                                     meta={"answer_to_task": task_id})
            self.core.emit("run.input_received", task_id=task_id, run_id=run["id"], bot_id=run["bot_id"])
        self.core.bus.wake_workers()

    # -- completion & handoffs ----------------------------------------------
    def _finish_task(self, task: Dict[str, Any], status: str, result: Optional[str], error: Optional[str]) -> None:
        now = now_iso()
        self.db.execute(
            "UPDATE tasks SET status = ?, result_text = ?, error = ?, updated_at = ?, completed_at = ?, unread = 1 WHERE id = ?",
            (status, result, error, now, now, task["id"]))
        self.core.emit(f"task.{status}", conversation_id=task["conversation_id"], task_id=task["id"],
                       bot_id=task["bot_id"], result=truncate(result or "", 400), error=error)
        bot = self.bots.get(task["bot_id"])
        name = bot["name"] if bot else task["bot_id"]
        if task["requester_type"] in ("user", "routine"):
            kind = {"completed": "task_completed", "failed": "task_failed"}.get(status)
            if kind:
                self.core.notify(kind, f"{name}: {task['title']}", truncate(result or error or "", 400),
                                 task_id=task["id"], conversation_id=task["conversation_id"], bot_id=task["bot_id"])
        self._propagate(task, status, result, error)

    def _propagate(self, task: Dict[str, Any], status: str, result: Optional[str], error: Optional[str]) -> None:
        """Completion wakes whoever is waiting on the result."""
        for h in self.db.all("SELECT * FROM handoffs WHERE to_task_id = ? AND status = 'open'", (task["id"],)):
            self.db.execute("UPDATE handoffs SET status = ?, completed_at = ? WHERE id = ?",
                            (status, now_iso(), h["id"]))
            self.core.emit("handoff.completed", conversation_id=task["conversation_id"], task_id=h["from_task_id"],
                           bot_id=h["from_bot_id"], handoff_id=h["id"], to_task_id=task["id"], status=status)
            payload = {"task_id": task["id"], "status": status, "result": result, "error": error}
            if h["wait"] and h["step_id"]:
                self.db.execute(
                    "UPDATE run_steps SET status = 'completed', output_json = ?, updated_at = ? WHERE id = ? AND status = 'waiting'",
                    (json.dumps(payload), now_iso(), h["step_id"]))
                parent_run = self.run_for_task(h["from_task_id"])
                if parent_run and parent_run["status"] == "waiting_dependency":
                    pending = self.db.scalar(
                        "SELECT COUNT(*) FROM run_steps WHERE run_id = ? AND status = 'waiting'", (parent_run["id"],))
                    if not pending:
                        self.db.execute(
                            "UPDATE runs SET status = 'queued', waiting_json = '{}', updated_at = ? WHERE id = ?",
                            (now_iso(), parent_run["id"]))
                        self.core.emit("run.woken", task_id=h["from_task_id"], run_id=parent_run["id"],
                                       bot_id=h["from_bot_id"], by_task=task["id"])
            else:
                parent = self.get_task(h["from_task_id"])
                if parent and parent["status"] not in ("cancelled",):
                    # Fire-and-forget delegation: wake the requester with a follow-up task.
                    try:
                        self.create_task(
                            bot_id=h["from_bot_id"], conversation_id=parent["conversation_id"], requester_type="bot",
                            requester_id=task["bot_id"],
                            instructions=f"Result of delegated task {task['id']} ({status}):\n{result or error}",
                            title=f"Result from delegated task: {task['title']}", parent_task=None,
                            priority=PRIORITY_DELEGATED, _in_tx=True,
                        )
                    except Exception:  # pragma: no cover - guard failures leave the handoff completed
                        pass
        self.core.bus.wake_workers()

    def open_handoff(self, parent: Dict[str, Any], child_task_id: str, to_bot_id: str, wait: bool,
                     step_id: Optional[str]) -> str:
        hid = new_id("hof")
        self.db.insert("handoffs", {
            "id": hid, "from_task_id": parent["id"], "from_bot_id": parent["bot_id"], "to_task_id": child_task_id,
            "to_bot_id": to_bot_id, "wait": wait, "step_id": step_id, "status": "open", "created_at": now_iso(),
        })
        self.core.emit("handoff.created", conversation_id=parent["conversation_id"], task_id=parent["id"],
                       bot_id=parent["bot_id"], handoff_id=hid, to_task_id=child_task_id, to_bot_id=to_bot_id, wait=wait)
        return hid

    def dedupe_delegation(self, parent: Dict[str, Any], to_bot_id: str, instructions: str) -> Optional[str]:
        row = self.db.one(
            "SELECT t.id FROM handoffs h JOIN tasks t ON t.id = h.to_task_id "
            "WHERE h.from_task_id = ? AND h.to_bot_id = ? AND t.instructions = ?",
            (parent["id"], to_bot_id, instructions))
        return row["id"] if row else None
