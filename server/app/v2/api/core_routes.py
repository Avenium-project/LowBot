"""Bots, conversations, messages, tasks, approvals, events, notifications, search."""

from __future__ import annotations

import asyncio
import json
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel, Field
from sse_starlette.sse import EventSourceResponse

from app.v2.api import bad, rt, user_id
from app.v2.approvals import ApprovalError
from app.v2.bots import BotError
from app.v2.db import loads
from app.v2.tasks import TaskError

router = APIRouter(prefix="/api/v2", tags=["v2"])


# ------------------------------------------------------------------ bots ---
class BotIn(BaseModel):
    name: Optional[str] = None
    avatar: Optional[str] = None
    role_description: Optional[str] = None
    instructions: Optional[str] = None
    provider_profile_id: Optional[str] = None
    model: Optional[str] = None
    tools: Optional[List[str]] = None
    policy: Optional[List[Dict[str, Any]]] = None
    budget: Optional[Dict[str, Any]] = None
    org_role: Optional[str] = None
    reports_to: Optional[str] = None
    can_create_bots: Optional[bool] = None
    team_memory_access: Optional[bool] = None
    computer_mode: Optional[str] = None
    pinned: Optional[bool] = None
    hidden: Optional[bool] = None

    def data(self) -> Dict[str, Any]:
        return self.model_dump(exclude_unset=True)


@router.get("/bots")
def list_bots(request: Request, include_hidden: bool = True):
    return rt(request).services["bots"].list(include_hidden=include_hidden)


@router.post("/bots")
def create_bot(body: BotIn, request: Request):
    try:
        return rt(request).services["bots"].create(body.data())
    except BotError as exc:
        raise bad(exc, 422)


@router.get("/bots/{bot_id}")
def get_bot(bot_id: str, request: Request):
    bot = rt(request).services["bots"].get(bot_id)
    if not bot:
        raise HTTPException(404, "Bot not found")
    return bot


@router.patch("/bots/{bot_id}")
def update_bot(bot_id: str, body: BotIn, request: Request):
    try:
        return rt(request).services["bots"].update(bot_id, body.data())
    except BotError as exc:
        raise bad(exc, 422)


@router.delete("/bots/{bot_id}")
def delete_bot(bot_id: str, request: Request):
    try:
        rt(request).services["bots"].delete(bot_id)
    except BotError as exc:
        raise bad(exc, 404)
    return {"deleted": bot_id}


@router.post("/bots/{bot_id}/pause")
def pause_bot(bot_id: str, request: Request):
    return rt(request).services["bots"].set_paused(bot_id, True)


@router.post("/bots/{bot_id}/resume")
def resume_bot(bot_id: str, request: Request):
    return rt(request).services["bots"].set_paused(bot_id, False)


@router.post("/bots/{bot_id}/duplicate")
def duplicate_bot(bot_id: str, request: Request):
    return rt(request).services["bots"].duplicate(bot_id)


@router.get("/bots/{bot_id}/export")
def export_bot(bot_id: str, request: Request):
    return rt(request).services["bots"].export(bot_id)


@router.post("/bots/import")
def import_bot(payload: Dict[str, Any], request: Request):
    try:
        return rt(request).services["bots"].import_(payload)
    except BotError as exc:
        raise bad(exc, 422)


@router.post("/bots/{bot_id}/conversation")
def bot_conversation(bot_id: str, request: Request):
    try:
        return rt(request).services["tasks"].private_conversation(bot_id)
    except (TaskError, BotError) as exc:
        raise bad(exc, 404)


# --------------------------------------------------------- conversations ---
class ConversationIn(BaseModel):
    kind: str = "group"
    bot_ids: List[str]
    title: str = ""


class MessageIn(BaseModel):
    text: str = Field("", max_length=50_000)
    client_msg_id: Optional[str] = Field(None, max_length=80)
    attachments: List[Dict[str, Any]] = Field(default_factory=list)
    thread_root_id: Optional[str] = None


@router.get("/conversations")
def list_conversations(request: Request):
    return rt(request).services["tasks"].list_conversations()


@router.post("/conversations")
def create_conversation(body: ConversationIn, request: Request):
    try:
        return rt(request).services["tasks"].create_conversation(body.kind, body.bot_ids, body.title)
    except (TaskError, BotError) as exc:
        raise bad(exc, 422)


@router.get("/conversations/{conv_id}")
def get_conversation(conv_id: str, request: Request):
    try:
        return rt(request).services["tasks"].get_conversation(conv_id)
    except TaskError as exc:
        raise bad(exc, 404)


@router.post("/conversations/{conv_id}/members")
def add_member(conv_id: str, body: Dict[str, str], request: Request):
    try:
        return rt(request).services["tasks"].add_member(conv_id, body.get("bot_id", ""))
    except (TaskError, BotError) as exc:
        raise bad(exc, 422)


@router.get("/conversations/{conv_id}/messages")
def get_messages(conv_id: str, request: Request, after: int = 0, limit: int = Query(200, le=500)):
    r = rt(request)
    try:
        r.services["tasks"].get_conversation(conv_id)
    except TaskError as exc:
        raise bad(exc, 404)
    return r.services["tasks"].messages(conv_id, after, limit)


@router.post("/conversations/{conv_id}/messages")
def post_message(conv_id: str, body: MessageIn, request: Request):
    """Persists the message and its task(s) transactionally and returns ids.
    The work runs on a durable worker; observe it via /events/stream."""
    r = rt(request)
    for a in body.attachments:
        if a.get("artifact_id") and not r.services["artifacts"].get(a["artifact_id"]):
            raise HTTPException(422, "Unknown attachment.")
        if a.get("kind") == "image" and a.get("artifact_id"):
            a["url"] = _artifact_data_url(r, a["artifact_id"])
    try:
        return r.services["tasks"].post_user_message(conv_id, body.text, client_msg_id=body.client_msg_id,
                                                     attachments=body.attachments, thread_root_id=body.thread_root_id)
    except TaskError as exc:
        raise bad(exc, 422)


def _artifact_data_url(r, artifact_id: str) -> str:
    import base64
    meta = r.services["artifacts"].get(artifact_id)
    path = r.services["artifacts"].path(artifact_id)
    if not meta or not path or not meta["mime"].startswith("image/") or meta["size"] > 8_000_000:
        raise HTTPException(422, "Attachment must be an image up to 8 MB.")
    return f"data:{meta['mime']};base64," + base64.b64encode(path.read_bytes()).decode()


@router.post("/conversations/{conv_id}/unread")
def mark_unread(conv_id: str, request: Request):
    rt(request).services["tasks"].mark_unread(conv_id)
    return {"ok": True}


@router.post("/conversations/{conv_id}/read")
def mark_read(conv_id: str, body: Dict[str, int], request: Request):
    rt(request).services["tasks"].mark_read(conv_id, int(body.get("seq", 0)))
    return {"ok": True}


# ------------------------------------------------------------------ tasks ---
@router.get("/tasks")
def list_tasks(request: Request, status: Optional[str] = None, bot_id: Optional[str] = None,
               conversation_id: Optional[str] = None, limit: int = Query(100, le=500)):
    sql, params = "SELECT t.*, r.id AS run_id, r.status AS run_status FROM tasks t LEFT JOIN runs r ON r.task_id = t.id WHERE 1 = 1", []
    if status == "active":
        sql += " AND t.status NOT IN ('completed','failed','cancelled')"
    elif status:
        sql += " AND t.status = ?"
        params.append(status)
    if bot_id:
        sql += " AND t.bot_id = ?"
        params.append(bot_id)
    if conversation_id:
        sql += " AND t.conversation_id = ?"
        params.append(conversation_id)
    sql += " ORDER BY t.created_at DESC LIMIT ?"
    params.append(limit)
    return rt(request).core.db.all(sql, params)


@router.get("/tasks/{task_id}")
def get_task(task_id: str, request: Request):
    r = rt(request)
    try:
        tree = r.services["tasks"].task_tree(task_id)
    except TaskError as exc:
        raise bad(exc, 404)
    if tree["run"]:
        steps = r.core.db.all("SELECT * FROM run_steps WHERE run_id = ? ORDER BY seq", (tree["run"]["id"],))
        for s in steps:
            s["input"] = loads(s.pop("input_json"), {})
            s["output"] = loads(s.pop("output_json"), {})
        tree["steps"] = steps
        tree["run"]["waiting"] = loads(tree["run"].pop("waiting_json"), {})
    return tree


@router.post("/tasks/{task_id}/cancel")
def cancel_task(task_id: str, request: Request):
    try:
        return rt(request).services["tasks"].cancel(task_id)
    except TaskError as exc:
        raise bad(exc, 404)


@router.post("/tasks/{task_id}/pause")
def pause_task(task_id: str, request: Request):
    try:
        rt(request).services["tasks"].pause(task_id)
    except TaskError as exc:
        raise bad(exc, 409)
    return {"ok": True}


@router.post("/tasks/{task_id}/resume")
def resume_task(task_id: str, request: Request):
    try:
        rt(request).services["tasks"].resume(task_id)
    except TaskError as exc:
        raise bad(exc, 409)
    return {"ok": True}


@router.post("/tasks/{task_id}/answer")
def answer_task(task_id: str, body: Dict[str, str], request: Request):
    try:
        rt(request).services["tasks"].answer_input(task_id, body.get("answer", ""))
    except TaskError as exc:
        raise bad(exc, 409)
    return {"ok": True}


@router.post("/runs/{run_id}/resolve")
def resolve_run(run_id: str, body: Dict[str, str], request: Request):
    try:
        rt(request).engine.resolve_unknown(run_id, body.get("outcome", ""), body.get("note", ""), user_id(request))
    except ValueError as exc:
        raise bad(exc, 409)
    return {"ok": True}


# -------------------------------------------------------------- approvals ---
class DecisionIn(BaseModel):
    decision: str
    args_hash: str


@router.get("/approvals")
def list_approvals(request: Request, status: Optional[str] = "pending"):
    return rt(request).services["approvals"].list(status or None)


@router.post("/approvals/{approval_id}/decide")
def decide(approval_id: str, body: DecisionIn, request: Request):
    try:
        return rt(request).services["approvals"].decide(approval_id, user_id=user_id(request),
                                                        decision=body.decision, args_hash_seen=body.args_hash)
    except ApprovalError as exc:
        raise bad(exc, 409)


@router.post("/approvals/{approval_id}/edit")
def edit_approval(approval_id: str, body: Dict[str, Any], request: Request):
    r = rt(request)
    a = r.services["approvals"].get(approval_id)
    if not a:
        raise HTTPException(404, "Unknown approval")
    bot = r.services["bots"].get(a["bot_id"])
    spec = r.services["tools"].get(bot, a["tool"]) if bot else None
    if not spec:
        raise HTTPException(409, "Tool no longer available")
    try:
        return r.services["approvals"].edit(approval_id, user_id=user_id(request),
                                            arguments=body.get("arguments") or {}, validate=spec.validate)
    except ApprovalError as exc:
        raise bad(exc, 409)


# ----------------------------------------------------------------- events ---
@router.get("/events")
def events_page(request: Request, after: int = 0, conversation_id: Optional[str] = None,
                limit: int = Query(200, le=1000)):
    r = rt(request)
    rows = r.core.events_after(after, limit, conversation_id)
    return {"events": rows, "cursor": rows[-1]["id"] if rows else after}


@router.get("/events/stream")
async def events_stream(request: Request, after: Optional[int] = None, conversation_id: Optional[str] = None):
    """Observation only: (re)connecting never starts or repeats work.
    Resumes from ``Last-Event-ID`` / ``after`` so reconnects lose nothing."""
    r = rt(request)
    header = request.headers.get("last-event-id")
    cursor = int(header) if header and header.isdigit() else (after if after is not None else
                                                              int(r.core.db.scalar("SELECT COALESCE(MAX(id), 0) FROM events")))

    async def gen():
        nonlocal cursor
        yield {"event": "hello", "data": json.dumps({"cursor": cursor})}
        while True:
            if await request.is_disconnected():
                return
            rows = r.core.events_after(cursor, 200, conversation_id)
            for row in rows:
                cursor = row["id"]
                yield {"id": str(row["id"]), "event": "event", "data": json.dumps(row, default=str)}
            if not rows:
                await r.core.bus.wait(15.0)  # in-process wake-up; also a keep-alive tick
                if not r.core.events_after(cursor, 1, conversation_id):
                    yield {"event": "ping", "data": "{}"}

    return EventSourceResponse(gen(), ping=20)


# ---------------------------------------------------------- notifications ---
@router.get("/notifications")
def notifications(request: Request, unread: bool = False, limit: int = Query(100, le=500)):
    sql = "SELECT * FROM notifications" + (" WHERE read_at IS NULL" if unread else "") + " ORDER BY created_at DESC LIMIT ?"
    r = rt(request)
    return {"items": r.core.db.all(sql, (limit,)),
            "unread": r.core.db.scalar("SELECT COUNT(*) FROM notifications WHERE read_at IS NULL")}


@router.post("/notifications/{nid}/read")
def read_notification(nid: str, request: Request):
    from app.v2.util import now_iso
    db = rt(request).core.db
    with db.tx():
        db.execute("UPDATE notifications SET read_at = ? WHERE id = ? AND read_at IS NULL", (now_iso(), nid))
    return {"ok": True}


@router.post("/notifications/read-all")
def read_all(request: Request):
    from app.v2.util import now_iso
    db = rt(request).core.db
    with db.tx():
        db.execute("UPDATE notifications SET read_at = ? WHERE read_at IS NULL", (now_iso(),))
    return {"ok": True}


# ----------------------------------------------------------------- search ---
@router.get("/search")
def search(request: Request, q: str = Query(..., min_length=2, max_length=200)):
    r = rt(request)
    like = f"%{q.replace('%', '').replace('_', '')}%"
    db = r.core.db
    return {
        "bots": db.all("SELECT id, name, handle, avatar FROM bots WHERE name LIKE ? OR handle LIKE ? OR role_description LIKE ? LIMIT 20",
                       (like, like, like)),
        "messages": db.all("SELECT id, conversation_id, seq, author_type, author_id, substr(text, 1, 240) AS text, created_at "
                           "FROM messages WHERE text LIKE ? ORDER BY seq DESC LIMIT 30", (like,)),
        "tasks": db.all("SELECT id, bot_id, title, status, conversation_id FROM tasks WHERE title LIKE ? OR instructions LIKE ? "
                        "ORDER BY created_at DESC LIMIT 20", (like, like)),
        "memories": r.services["memory"].search(q, limit=20),
        "artifacts": db.all("SELECT id, name, conversation_id, version FROM artifacts WHERE name LIKE ? LIMIT 20", (like,)),
    }
