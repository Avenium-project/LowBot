# LowBot — Android APK

**[Pobierz LowBot.apk](LowBot.apk)** → na GitHubie kliknij plik, potem „Download raw file”.

- Version **2.9.6** — Settings again has your name, Safety & permissions (Auto Review, tool rules), MCP servers and
  Backup & audit export, in the same plain list style.
  SHA-256: `3c8d405e4e74494f96093c1c8447bb85f6a66660f413966ed4591967ebb37180`
- 2.9.5 — bot profile is a plain list: Character, Model, Routines, Notifications, Workspaces (switch per
  workspace), Terminal — no captions, tabs, memory files, template sharing or Advanced section (pin/hide/pause/duplicate/delete
  stay in the ⋯ menu).
- 2.9.4 — Settings is a short list without explanations: Appearance (text size, animations, previews),
  Model (ChatGPT account, providers), Routines, Notifications (per bot), Workspaces, Terminal (Linux, bot commands, LAN).
- 2.9.3 — Markdown in replies: tables, headings, lists, checklists, quotes, links; every bot can always
  manage other bots (the "can create bots" option is gone; creating/changing/deleting still asks you); a bot sets its
  own role from what you ask it (self.set_role); the Linux terminal is on for every bot as soon as Linux is installed
  (switch it off per bot in the profile).
- 2.9.2 — the top-left button on the home screen is a settings (gear) button instead of the brown dot.
- 2.9.1 — bots SEE their browser screenshots (the image is sent to the model, not just a file); every bot
  has the Linux terminal tools and is told it has them; images in chat show as thumbnails (tap for full screen), files as
  cards; no more "wrote a handoff" notices in chat; delete/edit confirmations are in-app dialogs instead of
  "message from the page at …".
- 2.9.0 — Linux terminal for bots (Alpine 3.24 via proot): Settings → Linux terminal → Install (≈4 MB,
  SHA-256-checked), then switch on "Linux terminal" in a bot's profile. The bot runs commands with `linux.run`
  (`apk add python3 git …`) in its own persistent shell; each command asks you first (Settings → Execution).
  Computer → Terminals shows the shells live and lets you type into them. Network inside Linux is NOT covered by
  LowBot's LAN/SSRF guard. Tested: install on the Android 15 emulator; running commands could only be tested on arm64
  hardware (the x86_64 emulator blocks fork) — not yet verified on a real phone.
- 2.8.3 — every bot is a character: new bots (also ones created by other bots) get a random shape in a
  random colour, never an emoji; existing emoji bots are switched once to a random character.
- 2.8.2 — search on the home screen finds message text and files (not only chat names), includes hidden
  chats, highlights the match and says when nothing was found.
- 2.8.1 — bot.create works for bots made before 2.8 (a new bot gets the part of the default tools its
  creator has, instead of failing); bots see the tools they really called in earlier replies (no more invented or denied
  errors); long code in replies wraps inside the bubble; Grok-style "<bot> is working" line with a shimmering text.
- 2.8.0 — bots manage the team: they can list, create, change and delete other bots (creating, changing
  and deleting always needs your approval) and write to each other; shared **Workspaces** (Menu → Workspaces): a folder
  with shared files, one AGENTS.md of rules for every member bot, members and a team chat. Fixed browser takeover:
  no more "shape:drop:#…" in the status line, and an empty tab shows a start page instead of a white rectangle.
- Android 8.0+ (API 26). Podpis kluczem testowym — instaluje się jako aktualizacja poprzedniej wersji
  z tego katalogu. Instalacja spoza Sklepu Play: zezwól przeglądarce/menedżerowi plików na
  „instalowanie nieznanych aplikacji”.
- Start: wybierz dostawcę modelu (np. xAI, OpenCode Go, OpenAI albo „ChatGPT account” — nieoficjalne
  logowanie kontem ChatGPT, na własne ryzyko), wklej klucz / zaloguj się, utwórz bota.
- Status testów: self-test backendu na emulatorze Androida 15 uruchamiany w CI dla każdej wersji (kod 2.1.0 miał **PASS 39/39**: czat, narzędzia,
  zgody, sekrety, delegowanie, pamięć, rutyny z DST, odzyskiwanie po restarcie, logowanie ChatGPT bez sieci,
  SSRF, przeglądarka). UI 2.2.0 sprawdzone w Chromium (360 px, test e2e); przebieg emulatora dla 2.2.0 w CI.
  Prawdziwe logowanie ChatGPT i rozmowy z modelami nie były testowane (wymagają Twojego konta/klucza).
