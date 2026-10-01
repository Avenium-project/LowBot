"""Routines, webhooks, skills and memory."""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel

from app.v2.api import bad, rt
from app.v2.scheduler import ScheduleError, describe_local, next_runs, parse_schedule
from app.v2.skills import SkillError

router = APIRouter(prefix="/api/v2", tags=["v2-automation"])


class RoutineIn(BaseModel):
    bot_id: Optional[str] = None
    name: Optional[str] = None
    prompt: Optional[str] = None
    schedule: Optional[str] = None
    kind: Optional[str] = None
    timezone: Optional[str] = None
    conversation_id: Optional[str] = None
    overlap_policy: Optional[str] = None
    catchup_policy: Optional[str] = None
    enabled: Optional[bool] = None


@router.post("/routines/parse")
def parse(body: Dict[str, str], request: Request):
    tz = body.get("timezone") or rt(request).settings.default_timezone
    try:
        sched = parse_schedule(body.get("text", ""), tz)
    except (ScheduleError, ValueError) as exc:
        raise bad(exc, 422)
    runs = next_runs(sched, tz, 5)
    return {"schedule": sched, "timezone": tz, "next_runs_utc": runs, "next_runs_local": describe_local(runs, tz)}


@router.get("/routines")
def list_routines(request: Request, bot_id: Optional[str] = None):
    return rt(request).services["routines"].list(bot_id)


@router.post("/routines")
def create_routine(body: RoutineIn, request: Request):
    r = rt(request)
    if not body.bot_id or not r.services["bots"].get(body.bot_id):
        raise HTTPException(422, "bot_id is required")
    try:
        return r.services["routines"].create(
            bot_id=body.bot_id, name=body.name or "Routine", prompt=body.prompt or "", schedule=body.schedule,
            kind=body.kind, timezone_name=body.timezone, conversation_id=body.conversation_id,
            overlap_policy=body.overlap_policy or "skip", catchup_policy=body.catchup_policy or "latest",
            enabled=True if body.enabled is None else body.enabled)
    except (ScheduleError, ValueError) as exc:
        raise bad(exc, 422)


@router.patch("/routines/{rid}")
def update_routine(rid: str, body: RoutineIn, request: Request):
    try:
        return rt(request).services["routines"].update(rid, body.model_dump(exclude_unset=True))
    except (ScheduleError, ValueError) as exc:
        raise bad(exc, 422)


@router.delete("/routines/{rid}")
def delete_routine(rid: str, request: Request):
    rt(request).services["routines"].delete(rid)
    return {"deleted": rid}


@router.get("/routines/{rid}/history")
def routine_history(rid: str, request: Request):
    return rt(request).services["routines"].history(rid)


@router.post("/routines/{rid}/simulate")
def simulate(rid: str, request: Request):
    try:
        return rt(request).services["routines"].simulate(rid)
    except ScheduleError as exc:
        raise bad(exc, 404)


@router.post("/routines/{rid}/test-run")
def test_run(rid: str, request: Request):
    try:
        return rt(request).services["routines"].test_run(rid)
    except ScheduleError as exc:
        raise bad(exc, 404)


@router.post("/hooks/{rid}")
async def webhook(rid: str, request: Request):
    """Public endpoint; authenticity comes from the HMAC signature only."""
    body = await request.body()
    if len(body) > 256 * 1024:
        raise HTTPException(413, "Payload too large")
    try:
        return rt(request).services["routines"].receive_webhook(rid, body, {k.lower(): v for k, v in request.headers.items()})
    except PermissionError as exc:
        raise HTTPException(401, str(exc))


# ------------------------------------------------------------------ skills ---
@router.get("/skills")
def list_skills(request: Request):
    return rt(request).services["skills"].list()


@router.post("/skills")
def create_skill(body: Dict[str, Any], request: Request):
    try:
        return rt(request).services["skills"].create(body)
    except SkillError as exc:
        raise bad(exc, 422)


@router.patch("/skills/{sid}")
def update_skill(sid: str, body: Dict[str, Any], request: Request):
    try:
        return rt(request).services["skills"].update(sid, body)
    except SkillError as exc:
        raise bad(exc, 422)


@router.post("/skills/{sid}/rollback")
def rollback_skill(sid: str, body: Dict[str, int], request: Request):
    try:
        return rt(request).services["skills"].rollback(sid, int(body.get("version", 0)))
    except SkillError as exc:
        raise bad(exc, 422)


@router.delete("/skills/{sid}")
def delete_skill(sid: str, request: Request):
    rt(request).services["skills"].delete(sid)
    return {"deleted": sid}


@router.get("/skills/{sid}/export")
def export_skill(sid: str, request: Request):
    try:
        return rt(request).services["skills"].export(sid)
    except SkillError as exc:
        raise bad(exc, 404)


@router.post("/skills/import")
def import_skill(body: Dict[str, Any], request: Request):
    try:
        return rt(request).services["skills"].import_(body)
    except SkillError as exc:
        raise bad(exc, 422)


@router.post("/skills/teach")
def teach(body: Dict[str, Any], request: Request):
    try:
        return rt(request).services["skills"].draft_from_run(body.get("run_id", ""), body.get("name", "Taught task"),
                                                             bool(body.get("consent")))
    except SkillError as exc:
        raise bad(exc, 422)


# ------------------------------------------------------------------ memory ---
@router.get("/memories")
def memories(request: Request, q: Optional[str] = None, bot_id: Optional[str] = None,
             limit: int = Query(100, le=500)):
    m = rt(request).services["memory"]
    if q:
        rows = m.search(q, limit=limit)
        return [r for r in rows if not bot_id or r["bot_id"] in (bot_id, None)]
    return m.list(bot_id=bot_id, limit=limit)


@router.post("/memories")
def add_memory(body: Dict[str, Any], request: Request):
    try:
        return rt(request).services["memory"].save(content=body.get("content", ""), scope=body.get("scope", "bot"),
                                                   bot_id=body.get("bot_id"), source="user", created_by="user")
    except ValueError as exc:
        raise bad(exc, 422)


@router.patch("/memories/{mid}")
def edit_memory(mid: str, body: Dict[str, str], request: Request):
    try:
        return rt(request).services["memory"].update(mid, body.get("content", ""))
    except ValueError as exc:
        raise bad(exc, 404)


@router.delete("/memories/{mid}")
def delete_memory(mid: str, request: Request):
    rt(request).services["memory"].delete(mid)
    return {"deleted": mid}
