"""Computer surfaces (live view, Take over / Resume) and MCP connections."""

from __future__ import annotations

from typing import Any, Dict

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import Response

from app.v2.api import bad, rt, user_id
from app.v2.tools.builtin import ToolError

router = APIRouter(prefix="/api/v2", tags=["v2-extensions"])


def _browser(request: Request):
    b = rt(request).services.get("browser")
    if b is None:
        raise HTTPException(503, "Browser broker is not available on this server (Playwright missing).")
    return b


def _mcp(request: Request):
    m = rt(request).services.get("mcp")
    if m is None:
        raise HTTPException(503, "MCP support is not available on this server.")
    return m


# -------------------------------------------------------------- computers ---
@router.get("/computers/surfaces")
def surfaces(request: Request):
    b = _browser(request)
    return {"surfaces": b.list_surfaces(), "max_active_surfaces": rt(request).settings.max_active_surfaces,
            "kind": "browser-only (Chromium). Full desktop is not provided by this broker."}


@router.get("/computers/surfaces/{sid}/screenshot")
async def surface_screenshot(sid: str, request: Request):
    try:
        img = await _browser(request).screenshot(sid)
    except ToolError as exc:
        raise bad(exc, 404)
    return Response(img, media_type="image/jpeg", headers={"Cache-Control": "no-store"})


@router.post("/computers/surfaces/{sid}/takeover")
def takeover(sid: str, request: Request):
    try:
        return _browser(request).take_over(sid, user_id(request))
    except ToolError as exc:
        raise bad(exc, 404)


@router.post("/computers/surfaces/{sid}/resume")
def resume(sid: str, request: Request):
    try:
        return _browser(request).resume(sid, user_id(request))
    except ToolError as exc:
        raise bad(exc, 404)


@router.post("/computers/surfaces/{sid}/input")
async def human_input(sid: str, body: Dict[str, Any], request: Request):
    try:
        return await _browser(request).human_input(sid, user_id(request), body)
    except ToolError as exc:
        raise bad(exc, 409)
    except Exception as exc:  # navigation errors etc.
        raise bad(exc, 422)


@router.post("/computers/{computer_id}/stop")
async def stop_computer(computer_id: str, request: Request):
    await _browser(request).stop_computer(computer_id)
    return {"stopped": computer_id}


@router.post("/computers/{computer_id}/reset")
async def reset_computer(computer_id: str, body: Dict[str, Any], request: Request):
    """Destructive: requires typing the computer id to confirm. The profile is
    moved aside (recoverable), never silently deleted."""
    if body.get("confirm") != computer_id:
        raise HTTPException(409, "Confirm by sending {\"confirm\": \"<computer id>\"}.")
    import shutil
    from pathlib import Path
    from app.v2.util import now_iso
    b = _browser(request)
    await b.stop_computer(computer_id)
    row = rt(request).core.db.one("SELECT * FROM computers WHERE id = ?", (computer_id,))
    if not row:
        raise HTTPException(404, "Unknown computer")
    p = Path(row["profile_dir"])
    moved = None
    if p.exists():
        moved = p.with_name(p.name + "-reset-" + now_iso().replace(":", "")[:15])
        shutil.move(str(p), str(moved))
    rt(request).core.audit("computer.reset", actor_type="user", computer_id=computer_id, moved_to=str(moved))
    return {"reset": computer_id, "previous_profile_moved_to": str(moved) if moved else None}


# -------------------------------------------------------------------- MCP ---
@router.get("/mcp/connections")
def mcp_list(request: Request):
    return _mcp(request).rows()


@router.post("/mcp/connections")
def mcp_create(body: Dict[str, Any], request: Request):
    try:
        return _mcp(request).save(body)
    except ValueError as exc:
        raise bad(exc, 422)


@router.patch("/mcp/connections/{cid}")
def mcp_update(cid: str, body: Dict[str, Any], request: Request):
    try:
        return _mcp(request).save(body, cid)
    except ValueError as exc:
        raise bad(exc, 422)


@router.delete("/mcp/connections/{cid}")
async def mcp_delete(cid: str, request: Request):
    await _mcp(request).delete(cid)
    return {"deleted": cid}


@router.post("/mcp/connections/{cid}/test")
async def mcp_test(cid: str, request: Request):
    return await _mcp(request).refresh(cid)


@router.get("/mcp/elicitations")
def elicitations(request: Request):
    return _mcp(request).pending_elicitations()


@router.post("/mcp/elicitations/{eid}")
def answer_elicitation(eid: str, body: Dict[str, Any], request: Request):
    try:
        return _mcp(request).respond(eid, body.get("action", ""), body.get("content"))
    except ValueError as exc:
        raise bad(exc, 409)
