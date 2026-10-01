"""ChatGPT/Codex and OpenCode integrations, driven through the REAL CLIs.

The CLIs talk to a local fake model server (tests/fixtures/fake_model_server.py)
instead of OpenAI / opencode.ai, so this proves LowBot's process handling,
JSONL parsing, sandbox/permission flags and secret plumbing — not the cloud
services themselves (those need a real ChatGPT sign-in / OpenCode Go key).
Skipped when the CLIs are not installed.
"""

import asyncio
import json
import os
import socket
import stat
import subprocess
import sys
import time
from pathlib import Path

import httpx
import pytest

from app.v2.providers.base import ModelResponse, ToolCall
from app.v2.tools.registry import wire_name
from tests.v2.conftest import make_runtime, mock_profile

FAKE = Path(__file__).resolve().parents[1] / "fixtures" / "fake_model_server.py"


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


@pytest.fixture
def fake(tmp_path):
    port, log = free_port(), tmp_path / "fake.log"
    proc = subprocess.Popen([sys.executable, str(FAKE), str(port), str(log)])
    for _ in range(50):
        try:
            socket.create_connection(("127.0.0.1", port), 0.2).close()
            break
        except OSError:
            time.sleep(0.1)
    yield {"url": f"http://127.0.0.1:{port}/v1", "log": log}
    proc.terminate()
    proc.wait(5)


def wire_codex(rt, fake):
    cli = rt.services["agent_cli"]
    cli.extra_env["FAKE_KEY"] = "fk-codex"
    cli.codex_extra_args = ["-c", "model_provider=fake", "-c", 'model_providers.fake.name="fake"',
                            "-c", f'model_providers.fake.base_url="{fake["url"]}"',
                            "-c", 'model_providers.fake.env_key="FAKE_KEY"', "-c", 'model_providers.fake.wire_api="responses"']
    return cli


def wire_opencode(rt, fake):
    cli = rt.services["agent_cli"]
    cli.opencode_extra_config = {"provider": {"fake": {"npm": "@ai-sdk/openai-compatible", "name": "Fake",
                                                       "options": {"baseURL": fake["url"], "apiKey": "fk-oc"},
                                                       "models": {"fake-model": {"name": "Fake"}}}}}
    return cli


def requests(fake):
    return [json.loads(x) for x in fake["log"].read_text().splitlines()] if fake["log"].exists() else []


needs_codex = pytest.mark.skipif(not os.popen("command -v codex").read().strip() and not os.getenv("CODEX_BIN"),
                                 reason="codex CLI not installed")
needs_opencode = pytest.mark.skipif(not os.popen("command -v opencode").read().strip() and not os.getenv("OPENCODE_BIN"),
                                    reason="opencode CLI not installed")


@needs_codex
def test_bot_powered_by_codex_cli(tmp_path, fake, monkeypatch):
    monkeypatch.setenv("APP_AUTH_TOKEN", "server-owner-secret-should-not-leak")
    rt = make_runtime(tmp_path)
    cli = wire_codex(rt, fake)
    prof = rt.services["providers"].upsert({"kind": "codex_cli", "name": "ChatGPT (Codex)"})
    assert prof["capabilities"]["tools"] is False
    bot = rt.services["bots"].create({"name": "Koder", "provider_profile_id": prof["id"]})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    tid = rt.services["tasks"].post_user_message(conv["id"], "Napisz cześć")["tasks"][0]["id"]
    asyncio.run(rt.engine.drain(120))
    task = rt.services["tasks"].get_task(tid)
    assert task["status"] == "completed", task
    assert task["result_text"].startswith("FAKE-REPLY")
    req = requests(fake)[-1]
    assert req["path"].endswith("/responses") and req["auth"].startswith("Bearer fk-co")
    env = cli.codex_env()
    assert "APP_AUTH_TOKEN" not in env and env["CODEX_HOME"].startswith(str(tmp_path))
    assert oct(os.stat(env["CODEX_HOME"]).st_mode & 0o777) == "0o700"
    usage = rt.core.db.one("SELECT * FROM usage_entries WHERE status = 'confirmed'")
    assert usage["input_tokens"] == 11 and usage["output_tokens"] == 7
    rt.core.db.close()


@needs_codex
def test_codex_run_tool_requires_approval_and_reports(tmp_path, fake):
    rt = make_runtime(tmp_path)
    wire_codex(rt, fake)
    steps = iter([[("codex.run", {"task": "Opisz repozytorium"})]])

    def brain(req):
        try:
            return ModelResponse("", [ToolCall("c1", wire_name(n), a) for n, a in next(steps)])
        except StopIteration:
            return ModelResponse([m for m in req.messages if m["role"] == "tool"][-1]["content"], [])
    prof = mock_profile(rt, fn=brain)
    bot = rt.services["bots"].create({"name": "Lead", "provider_profile_id": prof["id"], "tools": ["codex.*"]})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    tid = rt.services["tasks"].post_user_message(conv["id"], "go")["tasks"][0]["id"]
    asyncio.run(rt.engine.drain(60))
    ap = rt.services["approvals"].list()[0]          # default policy: ask
    assert ap["tool"] == "codex.run" and "external AI service" in ap["effect"]
    rt.services["approvals"].decide(ap["id"], user_id="local-user", decision="approve", args_hash_seen=ap["args_hash"])
    asyncio.run(rt.engine.drain(120))
    task = rt.services["tasks"].get_task(tid)
    assert task["status"] == "completed" and "FAKE-REPLY: Opisz repozytorium" in task["result_text"]
    rt.core.db.close()


@needs_opencode
def test_bot_powered_by_opencode_cli(tmp_path, fake):
    rt = make_runtime(tmp_path)
    cli = wire_opencode(rt, fake)
    rt.services["agent_cli"].set_opencode_key("oc-go-key-123456789")
    prof = rt.services["providers"].upsert({"kind": "opencode_cli", "name": "OpenCode", "default_model": "fake/fake-model"})
    bot = rt.services["bots"].create({"name": "Open", "provider_profile_id": prof["id"]})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    tid = rt.services["tasks"].post_user_message(conv["id"], "hej")["tasks"][0]["id"]
    asyncio.run(rt.engine.drain(180))
    task = rt.services["tasks"].get_task(tid)
    assert task["status"] == "completed", task
    assert "FAKE-REPLY" in task["result_text"]
    env = cli.opencode_env({"bash": "deny"})
    assert env["OPENCODE_API_KEY"] == "oc-go-key-123456789"
    assert json.loads(env["OPENCODE_CONFIG_CONTENT"])["permission"]["bash"] == "deny"
    # the key is stored encrypted, never in plaintext in the database
    assert b"oc-go-key-123456789" not in (tmp_path / "opendots-v2.sqlite3").read_bytes()
    rt.core.db.close()


def test_opencode_go_preset_uses_go_endpoint_and_key(rt):
    seen = []

    def handler(req: httpx.Request):
        seen.append(req)
        return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}, "finish_reason": "stop"}],
                                         "usage": {"prompt_tokens": 1, "completion_tokens": 1}})
    rt.services["agent_cli"].set_opencode_key("oc-go-key-abcdefgh")
    prof = rt.services["providers"].upsert({"kind": "opencode_go", "name": "OpenCode Go", "default_model": "kimi-k2.6"})
    assert prof["base_url"] == "https://opencode.ai/zen/go/v1"
    rt.services["providers"].transports[prof["id"]] = httpx.MockTransport(handler)
    bot = rt.services["bots"].create({"name": "Go", "provider_profile_id": prof["id"]})
    t = rt.services["tasks"].create_task(bot_id=bot["id"], conversation_id=None, requester_type="user",
                                         requester_id="u", instructions="hi")
    asyncio.run(rt.engine.drain(20))
    assert rt.services["tasks"].get_task(t["id"])["status"] == "completed"
    assert str(seen[0].url) == "https://opencode.ai/zen/go/v1/chat/completions"
    assert seen[0].headers["authorization"] == "Bearer oc-go-key-abcdefgh"
    assert json.loads(seen[0].content)["tools"]  # full LowBot tool calling through OpenCode Go


def test_codex_device_login_flow_with_stub_cli(tmp_path, monkeypatch):
    """The official flow prints a verification URL and code; LowBot relays
    them to the phone. A stub stands in for `codex` (OpenAI is unreachable here)."""
    stub = tmp_path / "codex"
    stub.write_text("#!/bin/sh\n"
                    "if [ \"$1 $2\" = \"login --device-auth\" ]; then\n"
                    "  echo 'Follow these steps to sign in with ChatGPT using device code authorization:'\n"
                    "  echo '1. Open this link in your browser: https://auth.openai.com/codex/device'\n"
                    "  echo '2. Enter this one-time code: ABCD-12345'\n"
                    "  sleep 1; touch \"$CODEX_HOME/auth.json\"; exit 0\n"
                    "fi\n"
                    "if [ \"$1 $2\" = \"login status\" ]; then\n"
                    "  if [ -f \"$CODEX_HOME/auth.json\" ]; then echo 'Logged in using ChatGPT'; exit 0; fi\n"
                    "  echo 'Not logged in'; exit 1\n"
                    "fi\n")
    stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
    monkeypatch.setenv("CODEX_BIN", str(stub))
    rt = make_runtime(tmp_path / "data")
    cli = rt.services["agent_cli"]

    async def flow():
        before = await cli.status()
        assert before["codex"]["logged_in"] is False
        state = await cli.start_codex_login()
        assert state["url"] == "https://auth.openai.com/codex/device" and state["code"] == "ABCD-12345"
        for _ in range(50):
            if (rt.core.kv_get("codex_login") or {}).get("status") == "completed":
                break
            await asyncio.sleep(0.1)
        after = await cli.status()
        assert after["codex"]["logged_in"] is True and after["codex"]["login"]["status"] == "completed"
    asyncio.run(flow())
    rt.core.db.close()
