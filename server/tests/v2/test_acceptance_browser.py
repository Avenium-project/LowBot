"""Acceptance F and G with a real headless Chromium (Playwright).

Skipped (not passed) when Playwright/Chromium is unavailable.
"""

import asyncio
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

import pytest

pytest.importorskip("playwright")

from app.v2.providers.base import ModelResponse, ToolCall  # noqa: E402
from app.v2.tools.builtin import ToolError  # noqa: E402
from app.v2.tools.registry import wire_name  # noqa: E402
from tests.v2.conftest import make_runtime, mock_profile  # noqa: E402


class App(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, html, headers=()):
        body = f"<html><head><title>T</title></head><body>{html}</body></html>".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        for k, v in headers:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        cookie = self.headers.get("Cookie", "")
        user = cookie.split("session=")[1].split(";")[0] if "session=" in cookie else None
        if self.path.startswith("/me"):
            return self._send(f"<p id=who>{'Logged in as ' + user if user else 'Anonymous'}</p>")
        if self.path.startswith("/login"):
            return self._send("<form method=post action=/login><input name=user aria-label=Username>"
                              "<button type=submit>Log in</button></form>")
        if self.path.startswith("/page"):
            return self._send(f"<h1>{self.path}</h1><button onclick=\"document.body.dataset.c='1'\">Next</button>"
                              "<button>Send message</button>")
        return self._send("<a href=/page/a>A</a>")

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        user = parse_qs(self.rfile.read(n).decode()).get("user", ["?"])[0]
        self.send_response(303)
        self.send_header("Set-Cookie", f"session={user}; Max-Age=86400; Path=/")
        self.send_header("Location", "/me")
        self.end_headers()


@pytest.fixture
def site():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), App)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


def browser_rt(tmp_path):
    rt = make_runtime(tmp_path, allow_private_network=True)  # the test site is on localhost
    rt.load_extensions()
    if "browser" not in rt.services:
        pytest.skip("browser broker unavailable")
    return rt


def scripted(steps_by_bot):
    """steps_by_bot: name -> list of (tool, args); afterwards reply with last tool output."""
    iters = {k: iter(v) for k, v in steps_by_bot.items()}

    def fn(req):
        me = req.system.split("You are ", 1)[1].split(" (@", 1)[0]
        try:
            step = next(iters[me])
        except StopIteration:
            tools = [m for m in req.messages if m["role"] == "tool"]
            return ModelResponse(tools[-1]["content"] if tools else "nothing", [])
        name, args = step[0], step[1]
        if len(step) > 2:
            step[2]()  # side effect at this exact moment (e.g. a human takes over)
        return ModelResponse("", [ToolCall(f"c{time.monotonic_ns()}", wire_name(name), args)])
    return fn


async def loop_until(rt, predicate, timeout=30):
    stop = asyncio.Event()
    task = asyncio.ensure_future(rt.engine.run_forever(stop))
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
        await asyncio.wait_for(task, 15)


def test_F_separate_surfaces_and_takeover_blocks_until_resume(tmp_path, site):
    rt = browser_rt(tmp_path)
    broker = rt.services["browser"]
    state = {}

    def human_takes_over():
        row = [r for r in broker.list_surfaces() if r["bot_id"] == state["one"]][0]
        broker.take_over(row["id"], "local-user")

    fn = scripted({
        "One": [("browser.navigate", {"url": f"{site}/page/one"}), ("browser.read", {}),
                ("browser.click", {"selector": "text=Next"}, human_takes_over),
                # after Resume the bot re-observes and retries
                ("browser.read", {}), ("browser.click", {"selector": "text=Next"})],
        "Two": [("browser.navigate", {"url": f"{site}/page/two"}), ("browser.read", {})],
    })
    prof = mock_profile(rt, fn=fn)
    tools = ["browser.*"]
    one = rt.services["bots"].create({"name": "One", "provider_profile_id": prof["id"], "tools": tools})
    two = rt.services["bots"].create({"name": "Two", "provider_profile_id": prof["id"], "tools": tools})
    state["one"] = one["id"]
    tasks = rt.services["tasks"]

    async def go():
        t1 = tasks.post_user_message(tasks.private_conversation(one["id"])["id"], "go")["tasks"][0]["id"]
        t2 = tasks.post_user_message(tasks.private_conversation(two["id"])["id"], "go")["tasks"][0]["id"]
        # The click proposed right after the takeover is refused and parks the run.
        assert await loop_until(rt, lambda: tasks.get_task(t2)["status"] == "completed" and rt.core.db.scalar(
            "SELECT status FROM runs WHERE task_id = ?", (t1,)) == "waiting_input")
        surfaces = {r["bot_id"]: r for r in broker.list_surfaces()}
        assert surfaces[one["id"]]["id"] != surfaces[two["id"]]["id"]
        assert surfaces[two["id"]]["url"].endswith("/page/two")
        assert surfaces[one["id"]]["controller"] == "human:local-user"
        # Bot One clicked nothing while taken over.
        page_one = broker._surfaces[surfaces[one["id"]]["id"]].page
        assert await page_one.evaluate("() => document.body.dataset.c || ''") == ""
        # The human can act; then explicitly resume.
        await broker.human_input(surfaces[one["id"]]["id"], "local-user", {"type": "scroll", "dy": 100})
        with pytest.raises(ToolError):
            await broker.human_input(surfaces[two["id"]]["id"], "local-user", {"type": "scroll", "dy": 100})
        broker.resume(surfaces[one["id"]]["id"], "local-user")
        assert await loop_until(rt, lambda: tasks.get_task(t1)["status"] == "completed")
        assert await page_one.evaluate("() => document.body.dataset.c || ''") == "1"
        await rt.stop()
    asyncio.run(go())
    rt.core.db.close()


def test_G_shared_login_persists_isolated_does_not_inherit(tmp_path, site):
    rt = browser_rt(tmp_path)
    fn = scripted({
        "Alice": [("browser.navigate", {"url": f"{site}/login"}), ("browser.type", {"selector": "input[name=user]", "text": "alice"}),
                  ("browser.click", {"selector": "button[type=submit]"})],
        "Bob": [("browser.navigate", {"url": f"{site}/me"})],
        "Iso": [("browser.navigate", {"url": f"{site}/me"})],
    })
    prof = mock_profile(rt, fn=fn)
    t = ["browser.*"]
    bots = rt.services["bots"]
    alice = bots.create({"name": "Alice", "provider_profile_id": prof["id"], "tools": t,
                         "policy": [{"tool": "browser.*", "effect": "allow"}]})
    bob = bots.create({"name": "Bob", "provider_profile_id": prof["id"], "tools": t})
    iso = bots.create({"name": "Iso", "provider_profile_id": prof["id"], "tools": t, "computer_mode": "isolated"})
    tasks = rt.services["tasks"]

    async def phase1():
        ta = tasks.post_user_message(tasks.private_conversation(alice["id"])["id"], "log in")["tasks"][0]["id"]

        def approve_and_done():
            for a in rt.services["approvals"].list():  # 'Log in' may not escalate; approve if asked
                rt.services["approvals"].decide(a["id"], user_id="local-user", decision="approve", args_hash_seen=a["args_hash"])
            return tasks.get_task(ta)["status"] == "completed"
        assert await loop_until(rt, approve_and_done)
        assert "Logged in as alice" in tasks.get_task(ta)["result_text"]
        tb = tasks.post_user_message(tasks.private_conversation(bob["id"])["id"], "who am i")["tasks"][0]["id"]
        ti = tasks.post_user_message(tasks.private_conversation(iso["id"])["id"], "who am i")["tasks"][0]["id"]
        assert await loop_until(rt, lambda: all(tasks.get_task(x)["status"] == "completed" for x in (tb, ti)))
        assert "Logged in as alice" in tasks.get_task(tb)["result_text"]      # declared shared session
        assert "Anonymous" in tasks.get_task(ti)["result_text"]               # isolated: no inheritance
        # One owner per profile: another process cannot open the same profile.
        from app.v2.browser import BrowserBroker, ProfileLock
        from pathlib import Path
        shared = rt.core.db.one("SELECT * FROM computers WHERE mode = 'shared'")
        with pytest.raises(ToolError):
            ProfileLock(Path(shared["profile_dir"])).acquire()
        await rt.stop()
    asyncio.run(phase1())
    rt.core.db.close()

    rt2 = browser_rt(tmp_path)  # restart: profile must be intact
    rt2.services["providers"].overrides[prof["id"]] = __import__(
        "app.v2.providers.scripted", fromlist=["ScriptedAdapter"]).ScriptedAdapter(
        fn=scripted({"Bob": [("browser.navigate", {"url": f"{site}/me"})]}))

    async def phase2():
        tb = rt2.services["tasks"].post_user_message(
            rt2.services["tasks"].private_conversation(bob["id"])["id"], "again")["tasks"][0]["id"]
        assert await loop_until(rt2, lambda: rt2.services["tasks"].get_task(tb)["status"] == "completed")
        assert "Logged in as alice" in rt2.services["tasks"].get_task(tb)["result_text"]
        await rt2.stop()
    asyncio.run(phase2())
    rt2.core.db.close()


def test_F_risky_click_escalates_to_approval(tmp_path, site):
    rt = browser_rt(tmp_path)
    fn = scripted({"Clicker": [("browser.navigate", {"url": f"{site}/page/x"}), ("browser.read", {}),
                               ("browser.click", {"ref": 2})]})
    prof = mock_profile(rt, fn=fn)
    bot = rt.services["bots"].create({"name": "Clicker", "provider_profile_id": prof["id"], "tools": ["browser.*"]})
    tasks = rt.services["tasks"]

    async def go():
        tid = tasks.post_user_message(tasks.private_conversation(bot["id"])["id"], "go")["tasks"][0]["id"]
        assert await loop_until(rt, lambda: rt.core.db.scalar("SELECT status FROM runs WHERE task_id = ?", (tid,))
                                == "waiting_approval")
        a = rt.services["approvals"].list()[0]
        assert "Send message" in a["effect"]
        await rt.stop()
    asyncio.run(go())
    rt.core.db.close()


def test_surface_limit_is_independent_of_bot_count(tmp_path, site):
    rt = browser_rt(tmp_path)
    rt.settings.max_active_surfaces = 1
    fn = scripted({f"B{i}": [("browser.navigate", {"url": f"{site}/page/{i}"})] for i in range(3)})
    prof = mock_profile(rt, fn=fn)
    tasks = rt.services["tasks"]
    ids = []
    for i in range(3):
        b = rt.services["bots"].create({"name": f"B{i}", "provider_profile_id": prof["id"], "tools": ["browser.*"]})
        ids.append(tasks.post_user_message(tasks.private_conversation(b["id"])["id"], "go")["tasks"][0]["id"])

    async def go():
        assert await loop_until(rt, lambda: all(tasks.get_task(t)["status"] == "completed" for t in ids))
        live = [s for s in rt.services["browser"].list_surfaces() if s["live"]]
        assert len(live) <= 1
        await rt.stop()
    asyncio.run(go())
    rt.core.db.close()
