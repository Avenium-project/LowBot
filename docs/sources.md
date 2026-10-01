# Źródła i weryfikacja

Data weryfikacji: **2026-10-01** (środowisko: zdalny kontener z proxy egress).

| Źródło | Wynik | Uwagi |
|---|---|---|
| https://docs.x.ai/grok-bot/overview (+ bots, chat-and-collaboration, computer-and-apps, skills-routines-and-automations, files-and-results, mobile, approvals-security-and-privacy, settings-and-notifications, team-bots, teams-and-enterprises) | ❌ niedostępne | `connect_rejected` (polityka egress) — curl i WebFetch |
| https://cursor.com/docs/grok-bot | ❌ niedostępne | j.w. |
| https://modelcontextprotocol.io/specification/latest | ❌ niedostępne | użyto SDK `mcp==1.28.1` (wersje protokołu: 2024-11-05, 2025-03-26, 2025-06-18, 2025-11-25) |
| https://v2.tauri.app/distribute/windows-installer/ | ❌ niedostępne | konfiguracja wg znanego schematu Tauri 2; `cargo check` przechodzi |
| https://capacitorjs.com/docs/android | ❌ niedostępne | projekt wygenerowany przez `@capacitor/cli` 7 (`npx cap add android`) |
| https://playwright.dev/docs/api/class-browsertype | ❌ niedostępne | użyto `playwright==1.56.0` + Chromium 1194; `launch_persistent_context` przetestowane |
| https://github.com/Anil-matcha/open-dots | ✅ sklonowane | commit bazowy `5abe1b3d65d5176af936ed92de1b731634d2bcb5` (2026-10-01) |

Wnioski o funkcjach Grok Bot pochodzą wyłącznie z briefu projektu i są w macierzy oznaczone jako
niezweryfikowane. Rozbieżności między dokumentami Groka nie mogły zostać sprawdzone.

## Audyt projektu bazowego (upstream)

Najważniejsze ustalenia (ścieżki zweryfikowane w rewizji bazowej):

- `server/app/routers/chat.py` — praca modelu i narzędzi działa **wewnątrz generatora SSE**; zamknięcie
  strumienia przerywa zadanie, a ponowne otwarcie uruchamia model ponownie. Jedno narzędzie na turę,
  tylko jawne komendy `/workspace`, `/connector`, `/search`.
- `server/app/services/approval_broker.py` — zgody jako `asyncio.Future` w pamięci; restart je gubi.
- `server/app/services/action_gateway.py` — dobra rejestracja akcji i redakcja, ale stan oczekujących
  akcji w słownikach procesu.
- `server/app/services/provider_service.py` — API „prediction” + Responses bez narzędzi; brak Chat Completions.
- `server/app/services/storage_service.py` — SQLite (WAL) z JSON-owymi `payload`; `thread_id == bot_id`.
- `server/app/services/docker_computer_provider.py`, `runtime/driver.mjs` — przeglądarka w kontenerze per bot
  (browser-only).
- `client/components/ChatWindow.jsx`, `ComputerPanel.jsx`, `lib/api.js` — UI desktopowe, SSE przez EventSource.

Decyzja: nie przepisywać upstream; dodać `app/v2` obok (osobna baza `opendots-v2.sqlite3`),
zostawić `/api/v1` dla zgodności.
