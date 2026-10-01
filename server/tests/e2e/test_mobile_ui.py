"""Acceptance M: the web UI on a ~360 px phone viewport, driven by Chromium.

Requires the exported UI (``cd client && NEXT_OUTPUT=export npx next build``).
Skipped (never passed) when the bundle or Playwright is missing.
This proves the responsive web/PWA client; it does NOT prove APK/EXE (test N).
"""

import json
import os
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import pytest

pytest.importorskip("playwright")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]
UI = ROOT / "client" / "out"
SHOTS = Path(os.getenv("E2E_SCREENSHOTS", "/tmp/opendots-e2e"))
TOKEN = "e2e-owner-token-0123456789"

pytestmark = pytest.mark.skipif(not (UI / "bots" / "index.html").exists(), reason="client/out not built")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


@pytest.fixture(scope="module")
def server(tmp_path_factory):
    port = free_port()
    data = tmp_path_factory.mktemp("data")
    env = {**os.environ, "DATA_DIR": str(data), "APP_AUTH_TOKEN": TOKEN, "UI_DIST": str(UI),
           "ALLOW_PRIVATE_NETWORK": "1", "PORT": str(port), "CORS_ORIGINS": f"http://127.0.0.1:{port}"}
    proc = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--port", str(port)],
                            cwd=ROOT / "server", env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    base = f"http://127.0.0.1:{port}"
    for _ in range(100):
        try:
            urllib.request.urlopen(f"{base}/api/v2/health", timeout=1)
            break
        except OSError:
            time.sleep(0.1)
    yield base
    proc.terminate()
    proc.wait(10)


def api(base, path, body=None, method=None):
    req = urllib.request.Request(f"{base}/api/v2{path}", data=json.dumps(body).encode() if body is not None else None,
                                 method=method or ("POST" if body is not None else "GET"),
                                 headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read() or b"null")


def no_horizontal_scroll(page):
    return page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth + 1")


def test_M_phone_flow(server):
    SHOTS.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx = browser.new_context(viewport={"width": 360, "height": 740}, is_mobile=True, has_touch=True,
                                  locale="en-US", device_scale_factor=2)
        page = ctx.new_page()
        page.goto(f"{server}/bots/")
        # Wizard: server -> sign in -> model -> test -> first bot
        expect(page.get_by_text("Set up LowBot")).to_be_visible()
        page.get_by_role("button", name="Next").click()
        page.get_by_role("textbox", name="Owner token").fill(TOKEN)
        page.get_by_role("button", name="Next").click()
        page.locator("select").first.select_option("scripted_mock")
        page.get_by_role("textbox", name="Model").fill("scripted-mock")
        page.screenshot(path=SHOTS / "01-wizard-model.png")
        page.get_by_role("button", name="Next").click()
        page.get_by_role("button", name="Test connection").click()
        expect(page.get_by_text("Test (mock) provider")).to_be_visible()
        page.get_by_role("button", name="Next").click()
        page.get_by_role("button", name="Done").click()
        # Home: one chat list -> conversation
        expect(page.get_by_text("Assistant")).to_be_visible()
        assert no_horizontal_scroll(page)
        page.screenshot(path=SHOTS / "02-chats.png")
        assert page.get_by_text("Assistant").first.evaluate("e => getComputedStyle(e).userSelect") == "none"  # home list
        page.get_by_text("Assistant").click()
        page.get_by_placeholder("Ask Assistant").fill("cześć z telefonu")
        page.get_by_role("button", name="Send").click()
        expect(page.get_by_text("[mock] cześć z telefonu")).to_be_visible(timeout=15000)
        assert no_horizontal_scroll(page)
        page.screenshot(path=SHOTS / "03-conversation.png")
        sel = lambda loc: loc.evaluate("e => getComputedStyle(e).userSelect || getComputedStyle(e).webkitUserSelect")
        assert sel(page.get_by_text("[mock] cześć z telefonu")) == "text"      # bot message: selectable
        assert sel(page.get_by_text("cześć z telefonu", exact=True)) == "none"  # own message: not
        assert sel(page.get_by_placeholder("Ask Assistant")) == "text"         # input: selectable
        # Bot profile (Grok-style): character, model/provider, instructions, routines
        page.locator("header button").nth(1).click()
        expect(page.get_by_text("Character")).to_be_visible()
        page.get_by_role("button", name="triangle").click()
        expect(page.get_by_text("Saved")).to_be_visible(timeout=5000)
        assert api(server, "/bots")[0]["avatar"].startswith("shape:triangle:")
        expect(page.get_by_text("Provider", exact=True)).to_be_visible()
        assert no_horizontal_scroll(page)
        page.wait_for_timeout(500)
        page.screenshot(path=SHOTS / "04-bot-profile.png")
        page.locator(".lb-page-in .overflow-y-auto").evaluate("el => el.scrollTo(0, 900)")
        page.wait_for_timeout(300)
        page.screenshot(path=SHOTS / "05-bot-profile-model.png")
        page.locator(".lb-page-in").get_by_role("button", name="back").click()
        expect(page.get_by_text("Character")).to_have_count(0)

        # Approval inline in the conversation.
        prof = api(server, "/providers", {"kind": "scripted_mock", "name": "Approver", "default_model": "scripted-mock",
                                          "script": [{"when": "zapamiętaj", "call": {"name": "memory.save", "arguments": {"content": "kawa bez cukru"}}},
                                                     {"on": "tool", "reply": "Zapisane."}]})
        bot = api(server, "/bots", {"name": "Sekretarz", "provider_profile_id": prof["id"],
                                    "policy": [{"tool": "memory.save", "effect": "ask"}]})
        conv = api(server, f"/bots/{bot['id']}/conversation", {})
        api(server, f"/conversations/{conv['id']}/messages", {"text": "zapamiętaj: kawa bez cukru", "client_msg_id": "e2e-1"})
        page.get_by_role("button", name="back").click()
        expect(page.get_by_text("needs approval")).to_be_visible(timeout=15000)
        page.screenshot(path=SHOTS / "04-list-attention.png")
        page.get_by_text("Sekretarz").click()
        expect(page.get_by_text("Approval request")).to_be_visible(timeout=15000)
        assert no_horizontal_scroll(page)
        page.screenshot(path=SHOTS / "05-approval-inline.png")
        page.get_by_role("button", name="Allow once").click()
        expect(page.get_by_text("Zapisane.")).to_be_visible(timeout=15000)
        expect(page.get_by_text("Approved")).to_be_visible(timeout=15000)
        page.screenshot(path=SHOTS / "06-approved.png")

        # Offline -> send -> back online: exactly one message, no duplicate work.
        ctx.set_offline(True)
        page.get_by_placeholder("Ask Sekretarz").fill("wiadomość offline")
        page.get_by_role("button", name="Send").click()
        expect(page.get_by_text("⏳ wiadomość offline")).to_be_visible()
        ctx.set_offline(False)
        page.evaluate("() => window.dispatchEvent(new Event('online'))")
        time.sleep(3)
        msgs = api(server, f"/conversations/{conv['id']}/messages")
        assert sum(1 for m in msgs if m["text"] == "wiadomość offline") == 1

        # Routines from the avatar menu: natural language -> preview in Europe/Warsaw.
        page.get_by_role("button", name="back").click()
        page.get_by_role("button", name="Menu").click()
        page.screenshot(path=SHOTS / "07-menu.png")
        page.get_by_role("button", name="Routines").click()
        page.get_by_role("button", name="+ New routine").click()
        page.get_by_role("textbox", name="Schedule").fill("w dni robocze o 7:30")
        expect(page.get_by_text("cron: 30 7 * * 1-5")).to_be_visible(timeout=5000)
        page.get_by_role("textbox", name="Prompt").fill("Poranny raport")
        page.get_by_role("button", name="Create").click()
        expect(page.get_by_text("w dni robocze o 7:30").first).to_be_visible()
        assert no_horizontal_scroll(page)
        page.screenshot(path=SHOTS / "08-routines.png")
        page.get_by_role("button", name="back").click()

        # Monitor button in the bot's header: live view + take over.
        prof2 = api(server, "/providers", {"kind": "scripted_mock", "name": "Nav", "default_model": "scripted-mock",
                                           "script": [{"when": "otwórz", "call": {"name": "browser.navigate", "arguments": {"url": f"{server}/api/v2/health"}}},
                                                      {"on": "tool", "reply": "Otwarte."}]})
        nav = api(server, "/bots", {"name": "Przeglądacz", "provider_profile_id": prof2["id"]})
        c2 = api(server, f"/bots/{nav['id']}/conversation", {})
        api(server, f"/conversations/{c2['id']}/messages", {"text": "otwórz stronę"})
        for _ in range(100):
            if api(server, f"/conversations/{c2['id']}/messages")[-1]["text"] == "Otwarte.":
                break
            time.sleep(0.1)
        page.get_by_text("Przeglądacz").click()
        page.get_by_role("button", name="Computer").click()
        expect(page.get_by_role("button", name="Take over")).to_be_visible(timeout=10000)
        page.get_by_role("button", name="Take over").click()
        expect(page.get_by_role("button", name="Resume bot")).to_be_visible(timeout=10000)
        expect(page.get_by_alt_text("Live view")).to_be_visible(timeout=10000)
        assert no_horizontal_scroll(page)
        page.screenshot(path=SHOTS / "09-takeover.png")
        page.get_by_role("button", name="Resume bot").click()
        expect(page.get_by_role("button", name="Take over")).to_be_visible(timeout=10000)
        browser.close()


BRIDGE_MOCK = """
window.__store = {};
window.__saved = [];
window.LowBotNative = {
  platform: () => 'android', appVersion: () => 'test',
  secretGet: (k) => window.__store[k] || null,
  secretSet: (k, v) => { if (k !== 'device_token') return false; window.__store[k] = v; return true; },
  secretRemove: (k) => { delete window.__store[k]; },
  saveFile: (n, m, b) => { window.__saved.push(n); return true; },
  openExternal: () => true, startDictation: () => {}, exitApp: () => {},
};
"""


def test_android_bridge_code_path(server):
    """Runs the web app as the LowBot Android shell would (simulated bridge).
    Not a substitute for the emulator test in .github/workflows/android.yml."""
    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx = browser.new_context(viewport={"width": 360, "height": 740}, is_mobile=True, locale="en-US")
        ctx.add_init_script(BRIDGE_MOCK)
        page = ctx.new_page()
        seen_auth = []
        page.on("request", lambda r: seen_auth.append(r.headers.get("authorization", "")) if "/api/v2/bots" in r.url else None)
        page.goto(f"{server}/bots/")
        page.get_by_role("textbox", name="Server URL").fill(server)
        page.get_by_role("button", name="Next").click()
        page.get_by_role("textbox", name="Owner token").fill(TOKEN)
        page.get_by_role("button", name="Next").click()
        expect(page.get_by_role("button", name="Menu")).to_be_visible(timeout=15000)  # existing bots -> chat list
        token = page.evaluate("() => window.__store.device_token")
        assert token and token.startswith("odd_") and token != TOKEN          # only a device token is kept
        assert page.evaluate("() => Object.keys(localStorage).join(',')").find(TOKEN) == -1
        assert any(h == f"Bearer {token}" for h in seen_auth)                 # API calls use the device token
        devices = api(server, "/devices")
        assert any(d["platform"] == "android" and d["name"] == "LowBot (Android)" for d in devices)
        # Android Back: open a conversation, Back closes it, Back again leaves the app (not handled).
        page.get_by_text("Assistant").first.click()
        assert page.evaluate("() => window.__lowbotBack()") is True
        expect(page.get_by_role("button", name="Menu")).to_be_visible()
        assert page.evaluate("() => window.__lowbotBack()") is False
        browser.close()
