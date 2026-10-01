"""Health, providers, policy, devices, artifacts, usage, audit, backup."""

from __future__ import annotations

import json
from typing import Any, Dict, Optional

from fastapi import APIRouter, File, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, StreamingResponse

from app.v2.api import bad, rt
from app.v2.backup import create_backup, list_backups
from app.v2.devices import DeviceService, PairingError
from app.v2.providers.base import ProviderError
from app.v2.providers.service import PRESETS
from app.v2.util import new_id, now_iso

router = APIRouter(prefix="/api/v2", tags=["v2-system"])
public = APIRouter(prefix="/api/v2", tags=["v2-public"])


@public.get("/health")
def health(request: Request):
    r = rt(request)
    db = r.core.db
    return {
        "status": "ok", "api": "v2", "schema_version": db.schema_version(),
        "workers": db.all("SELECT id, heartbeat_at FROM workers ORDER BY heartbeat_at DESC LIMIT 5"),
        "capabilities": sorted(r.services.get("capabilities") or []),
        "limits": {"max_active_runs": r.settings.max_active_runs, "max_active_surfaces": r.settings.max_active_surfaces},
        "timezone": r.settings.default_timezone,
    }


# --------------------------------------------------------------- pairing ---
@router.post("/pair/code")
def pairing_code(request: Request):
    r = rt(request)
    server = r.settings.public_server_url or str(request.base_url).rstrip("/")
    return DeviceService(r.core).create_code(server)


@public.post("/pair/exchange")
def pairing_exchange(body: Dict[str, str], request: Request):
    try:
        return DeviceService(rt(request).core).exchange(body.get("code", ""), body.get("name", ""),
                                                        body.get("platform", "other"))
    except PairingError as exc:
        raise HTTPException(401, str(exc))


@router.get("/devices")
def devices(request: Request):
    return DeviceService(rt(request).core).list()


@router.delete("/devices/{device_id}")
def revoke_device(device_id: str, request: Request):
    try:
        DeviceService(rt(request).core).revoke(device_id)
    except PairingError as exc:
        raise bad(exc, 404)
    return {"revoked": device_id}


# ------------------------------------------------------------- providers ---
@router.get("/providers/presets")
def presets():
    return [{"kind": k, **{kk: v for kk, v in p.items() if kk != "adapter"}} for k, p in PRESETS.items()]


@router.get("/providers")
def providers(request: Request):
    r = rt(request)
    return {"profiles": r.services["providers"].list(),
            "default_profile_id": r.core.kv_get("default_provider_profile_id")}


@router.post("/providers")
def create_provider(body: Dict[str, Any], request: Request):
    try:
        return rt(request).services["providers"].upsert(body)
    except ValueError as exc:
        raise bad(exc, 422)


@router.patch("/providers/{pid}")
def update_provider(pid: str, body: Dict[str, Any], request: Request):
    try:
        return rt(request).services["providers"].upsert(body, pid)
    except ValueError as exc:
        raise bad(exc, 422)


@router.delete("/providers/{pid}")
def delete_provider(pid: str, request: Request):
    rt(request).services["providers"].delete(pid)
    return {"deleted": pid}


@router.post("/providers/{pid}/default")
def default_provider(pid: str, request: Request):
    r = rt(request)
    if not r.services["providers"].get(pid):
        raise HTTPException(404, "Unknown profile")
    r.core.kv_set("default_provider_profile_id", pid)
    return {"default_profile_id": pid}


@router.post("/providers/{pid}/test")
async def test_provider(pid: str, request: Request, body: Optional[Dict[str, Any]] = None):
    body = body or {}
    try:
        return await rt(request).services["providers"].test(pid, body.get("model"), bool(body.get("probe_vision")))
    except (ValueError, ProviderError) as exc:
        raise bad(exc, 422)


# ----------------------------------------------------------------- policy ---
@router.get("/policies")
def policies(request: Request):
    r = rt(request)
    return {"rules": r.core.db.all("SELECT * FROM policy_rules ORDER BY created_at"),
            "hierarchy_enforced": bool(r.core.kv_get("hierarchy_enforced", False)),
            "tools": [{"name": n, "effect_kind": s.effect_kind, "default": s.default_effect, "hard_ask": s.hard_ask,
                       "description": s.description}
                      for n, s in sorted(r.services["tools"].all_for({"id": "", "tools": ["*"]}).items())]}


@router.post("/policies")
def add_policy(body: Dict[str, Any], request: Request):
    if body.get("effect") not in ("allow", "ask", "deny") or not body.get("tool"):
        raise HTTPException(422, "Need tool pattern and effect allow|ask|deny")
    db = rt(request).core.db
    pid = new_id("pol")
    with db.tx():
        db.insert("policy_rules", {"id": pid, "bot_id": body.get("bot_id"), "tool_pattern": body["tool"],
                                   "effect": body["effect"], "created_at": now_iso()})
        rt(request).core.audit("policy.add", actor_type="user", rule_id=pid, tool=body["tool"], effect=body["effect"])
    return {"id": pid}


@router.delete("/policies/{pid}")
def delete_policy(pid: str, request: Request):
    db = rt(request).core.db
    with db.tx():
        db.execute("DELETE FROM policy_rules WHERE id = ?", (pid,))
        rt(request).core.audit("policy.delete", actor_type="user", rule_id=pid)
    return {"deleted": pid}


@router.post("/settings/hierarchy")
def set_hierarchy(body: Dict[str, bool], request: Request):
    rt(request).core.kv_set("hierarchy_enforced", bool(body.get("enforced")))
    return {"hierarchy_enforced": bool(body.get("enforced"))}


# -------------------------------------------------------------- artifacts ---
@router.get("/artifacts")
def artifacts(request: Request, conversation_id: Optional[str] = None, task_id: Optional[str] = None):
    return rt(request).services["artifacts"].list(conversation_id, task_id)


@router.post("/artifacts/upload")
async def upload(request: Request, file: UploadFile = File(...), conversation_id: Optional[str] = None):
    data = await file.read(25 * 1024 * 1024 + 1)
    try:
        return rt(request).services["artifacts"].create(name=file.filename or "upload", data=data,
                                                        mime=file.content_type, conversation_id=conversation_id)
    except ValueError as exc:
        raise bad(exc, 413)


@router.get("/artifacts/{aid}/download")
def download(aid: str, request: Request):
    r = rt(request)
    meta, path = r.services["artifacts"].get(aid), r.services["artifacts"].path(aid)
    if not meta or not path:
        raise HTTPException(404, "File does not exist")
    return FileResponse(path, media_type=meta["mime"], filename=meta["name"],
                        headers={"X-Content-Type-Options": "nosniff", "Cache-Control": "private, no-store",
                                 "Content-Security-Policy": "sandbox"})


# ------------------------------------------------------------ usage/audit ---
@router.get("/usage")
def usage(request: Request, bot_id: Optional[str] = None):
    return rt(request).services["providers"].usage_summary(bot_id)


@router.get("/audit")
def audit(request: Request, limit: int = Query(200, le=2000), task_id: Optional[str] = None):
    db = rt(request).core.db
    if task_id:
        rows = db.all("SELECT * FROM audit_log WHERE task_id = ? ORDER BY id DESC LIMIT ?", (task_id, limit))
    else:
        rows = db.all("SELECT * FROM audit_log ORDER BY id DESC LIMIT ?", (limit,))
    for r in rows:
        r["summary"] = json.loads(r.pop("summary_json") or "{}")
    return rows


@router.get("/audit/export")
def audit_export(request: Request):
    db = rt(request).core.db

    def gen():
        last = 0
        while True:
            rows = db.all("SELECT * FROM audit_log WHERE id > ? ORDER BY id LIMIT 500", (last,))
            if not rows:
                return
            for r in rows:
                last = r["id"]
                yield json.dumps(r) + "\n"
    return StreamingResponse(gen(), media_type="application/x-ndjson",
                             headers={"Content-Disposition": "attachment; filename=opendots-audit.jsonl"})


@router.post("/audit/retention")
def audit_retention(body: Dict[str, int], request: Request):
    from datetime import timedelta
    from app.v2.util import iso, now_dt
    days = max(7, int(body.get("days", 365)))
    cutoff = iso(now_dt() - timedelta(days=days))
    db = rt(request).core.db
    with db.tx():
        n1 = db.execute("DELETE FROM audit_log WHERE created_at < ?", (cutoff,)).rowcount
        n2 = db.execute("DELETE FROM events WHERE created_at < ?", (cutoff,)).rowcount
    return {"deleted_audit": n1, "deleted_events": n2, "cutoff": cutoff}


# ----------------------------------------------------------------- backup ---
@router.post("/admin/backup")
def backup(request: Request):
    r = rt(request)
    path = create_backup(r.settings.data_dir, r.settings.db_path, r.settings.data_dir / "backups")
    r.core.kv_set("last_backup", {"name": path.name, "at": now_iso()})
    return {"name": path.name, "size": path.stat().st_size}


@router.get("/admin/backups")
def backups(request: Request):
    r = rt(request)
    return {"backups": list_backups(r.settings.data_dir / "backups"),
            "restore": "Stop the server, then run: python -m app.v2.backup restore <archive>",
            "note": "Back up APP_ENCRYPTION_KEY / .encryption.key separately."}
