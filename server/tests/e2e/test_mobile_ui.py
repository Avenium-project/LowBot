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
                                  locale="pl-PL", device_scale_factor=2)
        page = ctx.new_page()
        page.goto(f"{server}/bots/")
        # Wizard: server -> sign in -> model -> test -> first bot
        expect(page.get_by_text("Konfiguracja Open Dots")).to_be_visible()
        page.get_by_role("button", name="Dalej").click()
        page.get_by_role("textbox", name="Token właściciela").fill(TOKEN)
        page.get_by_role("button", name="Dalej").click()
        page.locator("select").first.select_option("scripted_mock")
        page.get_by_role("textbox", name="Model").fill("scripted-mock")
        page.screenshot(path=SHOTS / "01-wizard-model.png")
        page.get_by_role("button", name="Dalej").click()
        page.get_by_role("button", name="Testuj połączenie").click()
        expect(page.get_by_text("Dostawca testowy (atrapa)")).to_be_visible()
        page.get_by_role("button", name="Dalej").click()
        page.get_by_role("button", name="Gotowe").click()
        # Home: one chat list -> conversation
        expect(page.get_by_text("Asystent")).to_be_visible()
        assert no_horizontal_scroll(page)
        page.screenshot(path=SHOTS / "02-chats.png")
        page.get_by_text("Asystent").click()
        page.get_by_placeholder("Zapytaj Asystent").fill("cześć z telefonu")
        page.get_by_role("button", name="Wyślij").click()
        expect(page.get_by_text("[mock] cześć z telefonu")).to_be_visible(timeout=15000)
        assert no_horizontal_scroll(page)
        page.screenshot(path=SHOTS / "03-conversation.png")

        # Approval inline in the conversation.
        prof = api(server, "/providers", {"kind": "scripted_mock", "name": "Approver", "default_model": "scripted-mock",
                                          "script": [{"when": "zapamiętaj", "call": {"name": "memory.save", "arguments": {"content": "kawa bez cukru"}}},
                                                     {"on": "tool", "reply": "Zapisane."}]})
        bot = api(server, "/bots", {"name": "Sekretarz", "provider_profile_id": prof["id"],
                                    "policy": [{"tool": "memory.save", "effect": "ask"}]})
        conv = api(server, f"/bots/{bot['id']}/conversation", {})
        api(server, f"/conversations/{conv['id']}/messages", {"text": "zapamiętaj: kawa bez cukru", "client_msg_id": "e2e-1"})
        page.get_by_role("button", name="back").click()
        expect(page.get_by_text("czeka na zgodę")).to_be_visible(timeout=15000)
        page.screenshot(path=SHOTS / "04-list-attention.png")
        page.get_by_text("Sekretarz").click()
        expect(page.get_by_text("Prośba o zgodę")).to_be_visible(timeout=15000)
        assert no_horizontal_scroll(page)
        page.screenshot(path=SHOTS / "05-approval-inline.png")
        page.get_by_role("button", name="Zatwierdź").click()
        expect(page.get_by_text("Zapisane.")).to_be_visible(timeout=15000)
        expect(page.get_by_text("Zatwierdzono")).to_be_visible(timeout=15000)
        page.screenshot(path=SHOTS / "06-approved.png")

        # Offline -> send -> back online: exactly one message, no duplicate work.
        ctx.set_offline(True)
        page.get_by_placeholder("Zapytaj Sekretarz").fill("wiadomość offline")
        page.get_by_role("button", name="Wyślij").click()
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
        page.get_by_role("button", name="Rutyny").click()
        page.get_by_role("textbox", name="Harmonogram").fill("w dni robocze o 7:30")
        expect(page.get_by_text("cron: 30 7 * * 1-5")).to_be_visible(timeout=5000)
        page.get_by_role("textbox", name="Polecenie").fill("Poranny raport")
        page.get_by_role("button", name="Utwórz").click()
        expect(page.get_by_text("Europe/Warsaw").first).to_be_visible()
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
        page.get_by_role("button", name="Komputer").click()
        expect(page.get_by_role("button", name="Przejmij sterowanie")).to_be_visible(timeout=10000)
        page.get_by_role("button", name="Przejmij sterowanie").click()
        expect(page.get_by_role("button", name="Oddaj sterowanie")).to_be_visible(timeout=10000)
        expect(page.get_by_alt_text("Podgląd na żywo")).to_be_visible(timeout=10000)
        assert no_horizontal_scroll(page)
        page.screenshot(path=SHOTS / "09-takeover.png")
        page.get_by_role("button", name="Oddaj sterowanie").click()
        expect(page.get_by_role("button", name="Przejmij sterowanie")).to_be_visible(timeout=10000)
        browser.close()
