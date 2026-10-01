"""Acceptance J: real MCP servers over stdio and Streamable HTTP."""

import asyncio
import socket
import subprocess
import sys
import time
from pathlib import Path

import pytest

from app.v2.providers.base import ModelResponse, ToolCall
from app.v2.tools.registry import wire_name
from tests.v2.conftest import make_runtime, mock_profile

SERVER = str(Path(__file__).with_name("mcp_test_server.py"))


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


@pytest.fixture
def http_server():
    port = free_port()
    proc = subprocess.Popen([sys.executable, SERVER, "streamable-http", str(port)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(100):
        try:
            socket.create_connection(("127.0.0.1", port), 0.2).close()
            break
        except OSError:
            time.sleep(0.1)
    yield f"http://127.0.0.1:{port}/mcp"
    proc.terminate()
    proc.wait(5)


def setup(tmp_path, conn):
    rt = make_runtime(tmp_path)
    rt.load_extensions()
    m = rt.services["mcp"]
    row = m.save(conn)
    return rt, m, row


def script_calls(*calls):
    it = iter(calls)

    def fn(req):
        try:
            name, args = next(it)
        except StopIteration:
            last = [m for m in req.messages if m["role"] == "tool"][-1]["content"]
            return ModelResponse(f"result: {last}", [])
        return ModelResponse("", [ToolCall("c", wire_name(name), args)])
    return fn


async def run_until(rt, predicate, timeout=20):
    stop = asyncio.Event()
    loop_task = asyncio.ensure_future(rt.engine.run_forever(stop))
    t0 = time.monotonic()
    try:
        while time.monotonic() - t0 < timeout:
            if predicate():
                return True
            await asyncio.sleep(0.05)
        return False
    finally:
        stop.set()
        rt.core.bus.wake_workers()
        await asyncio.wait_for(loop_task, 10)


@pytest.mark.parametrize("transport", ["stdio", "http"])
def test_J_real_mcp_tool_call(tmp_path, transport, request):
    conn = {"name": "calc", "transport": "stdio", "command": sys.executable, "args": [SERVER, "stdio"], "timeout_s": 20}
    if transport == "http":
        conn = {"name": "calc", "transport": "http", "url": request.getfixturevalue("http_server"), "timeout_s": 20}
    rt, m, row = setup(tmp_path, conn)

    async def go():
        refreshed = await m.refresh(row["id"])
        assert refreshed["last_status"].startswith("ok"), refreshed
        names = {t["name"] for t in refreshed["tools"]}
        assert {"add", "greet", "login"} <= names
        prof = mock_profile(rt, fn=script_calls(("mcp.calc.add", {"a": 2, "b": 40})))
        bot = rt.services["bots"].create({"name": "Calc", "provider_profile_id": prof["id"], "tools": ["mcp.*"]})
        spec = rt.services["tools"].get(bot, "mcp.calc.add")
        assert spec.default_effect == "allow"           # readOnlyHint
        assert spec.validate({"a": "x", "b": 1})        # schema enforced
        assert rt.services["tools"].get(bot, "mcp.calc.greet").default_effect == "ask"
        conv = rt.services["tasks"].private_conversation(bot["id"])
        tid = rt.services["tasks"].post_user_message(conv["id"], "2+40?")["tasks"][0]["id"]
        ok = await run_until(rt, lambda: rt.services["tasks"].get_task(tid)["status"] == "completed")
        assert ok
        assert "42" in rt.services["tasks"].get_task(tid)["result_text"]
        await m.close()
    asyncio.run(go())
    rt.core.db.close()


@pytest.mark.parametrize("action,expected", [("accept", "Hello, Ada!"), ("decline", "No greeting (decline)"),
                                             ("cancel", "No greeting (cancel)")])
def test_J_elicitation_accept_decline_cancel(tmp_path, action, expected):
    rt, m, row = setup(tmp_path, {"name": "greeter", "transport": "stdio", "command": sys.executable,
                                  "args": [SERVER, "stdio"], "timeout_s": 20})

    async def go():
        await m.refresh(row["id"])
        prof = mock_profile(rt, fn=script_calls(("mcp.greeter.greet", {})))
        bot = rt.services["bots"].create({"name": "G", "provider_profile_id": prof["id"], "tools": ["mcp.*"],
                                          "policy": [{"tool": "mcp.greeter.greet", "effect": "allow"}]})
        conv = rt.services["tasks"].private_conversation(bot["id"])
        tid = rt.services["tasks"].post_user_message(conv["id"], "greet me")["tasks"][0]["id"]

        def answered():
            pend = m.pending_elicitations()
            if pend:
                assert pend[0]["mode"] == "form" and "name" in pend[0]["schema"]["properties"]
                m.respond(pend[0]["id"], action, {"name": "Ada"} if action == "accept" else None)
            return rt.services["tasks"].get_task(tid)["status"] == "completed"
        assert await run_until(rt, answered)
        assert expected in rt.services["tasks"].get_task(tid)["result_text"]
        await m.close()
    asyncio.run(go())
    rt.core.db.close()


def test_J_password_form_is_refused_without_leak(tmp_path):
    rt, m, row = setup(tmp_path, {"name": "acct", "transport": "stdio", "command": sys.executable,
                                  "args": [SERVER, "stdio"], "timeout_s": 20})

    async def go():
        await m.refresh(row["id"])
        prof = mock_profile(rt, fn=script_calls(("mcp.acct.login", {})))
        bot = rt.services["bots"].create({"name": "L", "provider_profile_id": prof["id"], "tools": ["mcp.*"],
                                          "policy": [{"tool": "mcp.*", "effect": "allow"}]})
        conv = rt.services["tasks"].private_conversation(bot["id"])
        tid = rt.services["tasks"].post_user_message(conv["id"], "log in")["tasks"][0]["id"]
        assert await run_until(rt, lambda: rt.services["tasks"].get_task(tid)["status"] == "completed")
        assert "login:decline" in rt.services["tasks"].get_task(tid)["result_text"]
        row2 = rt.core.db.one("SELECT * FROM elicitations")
        assert row2["status"] == "refused" and "secret" in row2["refusal_reason"]
        with pytest.raises(ValueError):
            m.respond(row2["id"], "accept", {"password": "x"})
        await m.close()
    asyncio.run(go())
    rt.core.db.close()


def test_J_broken_server_gives_honest_status(tmp_path):
    rt, m, row = setup(tmp_path, {"name": "broken", "transport": "stdio", "command": "/bin/false", "args": [],
                                  "timeout_s": 5})
    out = asyncio.run(m.refresh(row["id"]))
    assert out["last_status"].startswith("error")
    rt.core.db.close()
