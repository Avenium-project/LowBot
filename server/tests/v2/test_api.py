"""HTTP-level tests of /api/v2 through the real FastAPI app and lifespan."""

import time

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.services.auth_service import auth_service


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.setenv("EMBEDDED_SCHEDULER", "0")
    with TestClient(app, base_url="http://testserver") as c:
        c.headers["Authorization"] = f"Bearer {auth_service.token}"
        yield c


def wait(pred, timeout=10):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = pred()
        if v:
            return v
        time.sleep(0.05)
    raise AssertionError("timeout")


def setup_bot(c, script, **bot):
    p = c.post("/api/v2/providers", json={"kind": "scripted_mock", "name": "Mock", "default_model": "scripted-mock",
                                          "script": script}).json()
    b = c.post("/api/v2/bots", json={"name": "Api bot", "provider_profile_id": p["id"], **bot}).json()
    conv = c.post(f"/api/v2/bots/{b['id']}/conversation").json()
    return b, conv


def test_auth_required_and_public_paths(client):
    anon = TestClient(app)
    assert anon.get("/api/v2/bots").status_code == 401
    assert anon.get("/api/v2/health").status_code == 200
    assert anon.post("/api/v2/hooks/rtn_x", content=b"{}").status_code == 401  # signature, not session
    assert client.get("/api/v2/bots", headers={"Origin": "https://evil.example"}).status_code == 403


def test_message_flow_and_idempotent_post(client):
    b, conv = setup_bot(client, [{"reply": "pong"}])
    body = {"text": "ping", "client_msg_id": "abc-1"}
    r1 = client.post(f"/api/v2/conversations/{conv['id']}/messages", json=body).json()
    r2 = client.post(f"/api/v2/conversations/{conv['id']}/messages", json=body).json()
    assert r2["duplicate"] and r1["message_id"] == r2["message_id"]
    msgs = wait(lambda: (m := client.get(f"/api/v2/conversations/{conv['id']}/messages").json()) and len(m) == 2 and m)
    assert msgs[1]["text"] == "pong"
    ev = client.get("/api/v2/events", params={"after": 0, "conversation_id": conv["id"]}).json()["events"]
    assert any(e["type"] == "run.completed" for e in ev)
    tree = client.get(f"/api/v2/tasks/{r1['tasks'][0]['id']}").json()
    assert tree["run"]["status"] == "completed" and tree["steps"][0]["kind"] == "model"


def test_approval_over_http(client):
    b, conv = setup_bot(client, [{"when": "remember", "call": {"name": "memory.save", "arguments": {"content": "x"}}},
                                 {"on": "tool", "reply": "stored"}],
                        policy=[{"tool": "memory.save", "effect": "ask"}])
    client.post(f"/api/v2/conversations/{conv['id']}/messages", json={"text": "remember x"})
    ap = wait(lambda: client.get("/api/v2/approvals").json())[0]
    assert ap["tool"] == "memory.save" and ap["display"] == {"content": "x"}
    bad = client.post(f"/api/v2/approvals/{ap['id']}/decide", json={"decision": "approve", "args_hash": "nope"})
    assert bad.status_code == 409
    ok = client.post(f"/api/v2/approvals/{ap['id']}/decide", json={"decision": "approve", "args_hash": ap["args_hash"]})
    assert ok.status_code == 200
    msgs = wait(lambda: (m := client.get(f"/api/v2/conversations/{conv['id']}/messages").json()) and len(m) == 2 and m)
    assert msgs[1]["text"] == "stored"
    again = client.post(f"/api/v2/approvals/{ap['id']}/decide", json={"decision": "approve", "args_hash": ap["args_hash"]})
    assert again.status_code == 409


def test_pairing_device_token_and_native_origin(client):
    code = client.post("/api/v2/pair/code").json()
    assert "opendots://pair" in code["qr_payload"] and auth_service.token not in code["qr_payload"]
    anon = TestClient(app)
    tok = anon.post("/api/v2/pair/exchange", json={"code": code["code"], "name": "Phone", "platform": "android"}).json()
    hdr = {"Authorization": f"Bearer {tok['token']}", "Origin": "https://appassets.androidplatform.net"}
    assert anon.get("/api/v2/bots", headers=hdr).status_code == 200
    # A cookie-style request from the native origin (no bearer) is rejected.
    assert anon.get("/api/v2/bots", headers={"Origin": "https://appassets.androidplatform.net"}).status_code == 403
    assert anon.post("/api/v2/pair/exchange", json={"code": code["code"], "name": "x"}).status_code == 401
    client.delete(f"/api/v2/devices/{tok['device_id']}")
    assert anon.get("/api/v2/bots", headers=hdr).status_code == 401


def test_artifact_upload_and_authorized_download(client):
    up = client.post("/api/v2/artifacts/upload", files={"file": ("notes.txt", b"hello", "text/plain")}).json()
    r = client.get(f"/api/v2/artifacts/{up['id']}/download")
    assert r.status_code == 200 and r.content == b"hello" and r.headers["content-security-policy"] == "sandbox"
    assert TestClient(app).get(f"/api/v2/artifacts/{up['id']}/download").status_code == 401
    assert client.get("/api/v2/artifacts/art_missing/download").status_code == 404


def test_routine_parse_and_search(client):
    p = client.post("/api/v2/routines/parse", json={"text": "w dni robocze o 7:30"}).json()
    assert p["schedule"]["cron"] == "30 7 * * 1-5" and p["timezone"] == "Europe/Warsaw"
    assert len(p["next_runs_local"]) == 5
    b, conv = setup_bot(client, [{"reply": "ok"}])
    client.post(f"/api/v2/conversations/{conv['id']}/messages", json={"text": "unique-needle-42"})
    res = client.get("/api/v2/search", params={"q": "needle-42"}).json()
    assert res["messages"]


def test_backup_endpoint(client):
    r = client.post("/api/v2/admin/backup").json()
    assert r["name"].startswith("opendots-backup-")
    assert client.get("/api/v2/admin/backups").json()["backups"]
