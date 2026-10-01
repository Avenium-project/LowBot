# LowBot for Android — backend hosted on the phone

The APK contains the **whole LowBot backend**: there is no server to install and nothing to pair.
Framework-only Android app (no AndroidX, no Google libraries); the UI is the same web app as the
browser version, served from the APK's own assets and talking to the in-app backend through the
`window.LowBotNative` bridge (no network involved).

| Part | Where | Notes |
|---|---|---|
| Database | SQLite (WAL) in app-private storage | `io.lowbot.core.Schema` mirrors `server/app/v2/schema.py` |
| Bots, chats, tasks, approvals, memory (FTS4), skills, routines, artifacts | `io.lowbot.core.*` | ports of the Python services |
| Run engine | `io.lowbot.engine.Engine` | checkpoints in `run_steps`, owner fencing, retries, Stop/Pause, `unknown_outcome`, recovery after the app is killed |
| Models | `io.lowbot.engine.Model` | xAI (Grok models), OpenCode Go, OpenAI Responses, OpenRouter, own HTTPS endpoint, scripted mock (labelled) |
| Tools | `io.lowbot.tools.Builtin` | workspace files, `web.fetch` (SSRF guard), `http.post`, memory, `user.ask`, `secret.request`, artifacts, delegation, `routine.create`, `terminal.run` (Android `sh`) |
| MCP | `io.lowbot.tools.Mcp` | Streamable HTTP only (stdio servers cannot run on a phone) |
| Computer | `io.lowbot.app.Computer` | a real browser (WebView) per bot with shared cookies; live view; **Take over** shows the real page full-screen, **Give back control** resumes the bot; **Record** captures your steps for *Teach a task* |
| Routines | `Routines` + `AlarmReceiver` | Europe/Warsaw by default, DST rules, AlarmManager wake-ups, missed slots recorded |
| Background work | `WorkService` | foreground service (dataSync) while tasks run; stops when idle |
| Notifications | `LowBotApp.notifyOs` | finished / needs you; approvals with **Allow once / Deny** actions; muted while the app is on screen |
| Bot memory files | `Mind` | per bot folder `bots/<id>/`: `soul.md` (purpose + behaviour, sent first in every prompt), `agents.md` (handoff: **cleared and replaced** instead of context compaction — when a conversation passes ~40 messages/48k chars the bot writes the exact goal, done, next steps, and continues with a fresh context), `memories/*.md` (small long-term notes via `memory.save`); shared workspaces `workspace/<name>/` with one `AGENTS.md` of rules for all member bots, shared files and a member list kept in the database (`project.create`, `project.use`, `project.add_member`, `project.update_rules`; Menu → Workspaces, team chat) |
| Linux terminal | `Linux` (app) | Alpine Linux through proot (Termux build, shipped as `lib*.so` in `mobile/jniLibs`, fetched by the `linux-runtime` workflow with SHA-256 checks). Settings → Linux terminal downloads the Alpine minirootfs pinned in `linux-runtime/alpine.json` and verifies its SHA-256. One persistent shell per bot (`linux.run`, `linux.reset`), workspace at `/workspace`, timeouts restart the shell. Bot commands go through approvals (Settings → Execution); the owner can type into a bot's shell in Computer → Terminals (audited). Network inside Linux is **not** covered by LowBot's egress guard. Per-bot toggle in the bot profile. |
| Team management | `Builtin` `bot.*` | every bot can `bot.list`, `bot.message`, `bot.create` (new bot gets the creator's provider/model, no more tools than its creator, optional soul + workspace), `bot.update` (profile/soul, never tools or permissions) and `bot.delete` (never itself). Create/update ask for approval; delete always asks (no "always allow"). Limit 50 bots. Existing bots get `bot.*` once on upgrade. |
| Secrets | `Core.secretPut` | AES-256-GCM, key in the Android Keystore; never in messages, events or the model context |

**Limits (honest):** work continues when you leave the app, but **not when the phone is off**;
Android may stop a foreground `dataSync` service after ~6 h/day (Android 15) — it resumes the next
time the app opens. ChatGPT sign-in (Codex CLI), stdio MCP servers, webhooks and Team Bots need a
computer/server and are not available in the phone-only build. Model calls go to the provider you
configure with your own API key.

## On-device self-test

```bash
adb shell am broadcast -n io.lowbot.app/.SelfTest   # needs the shell's DUMP permission
adb logcat -s LOWBOT_SELFTEST                       # → "LOWBOT_SELFTEST PASS <n> …"
```

Runs against a separate database with the scripted mock provider: chat, idempotent sends, tools,
approval binding/deny/always-allow, `user.ask`, secure secrets, delegation, memory search, Duplicate,
DST routines and slot dedupe, crash recovery, SSRF guard, and a real WebView page load. CI runs it on
an Android 15 emulator (`.github/workflows/android.yml`, `mobile/ci-emulator-test.sh`).

## Build

```bash
npm --prefix ../client ci && npm --prefix ../client run build:export
ANDROID_HOME=$HOME/Android/Sdk ./build-apk.sh test        # test-signed APK
LOWBOT_KEYSTORE=… LOWBOT_KEYSTORE_PASSWORD=… LOWBOT_KEY_ALIAS=… ./build-apk.sh release
```

Requires `platforms/android-35` and build-tools (aapt2, d8, zipalign, apksigner). No Gradle and no
access to Google Maven are needed.
