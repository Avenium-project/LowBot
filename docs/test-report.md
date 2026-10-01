# Raport testów — 2026-10-01

Środowisko: kontener Linux, 4 vCPU, 16 GB RAM, Python 3.11.15, Node 22.22, Chromium 1194
(Playwright 1.56.0), `mcp` 1.28.1. Modele w testach deterministycznych to **jawnie oznaczona atrapa**
`scripted_mock` — wyniki dowodzą logiki orkiestracji/trwałości, nie jakości modelu.

Statusy: **wykonany ✅**, **nieudany ❌**, **pominięty ⏭**, **nieweryfikowany ❔**.

## Komendy faktycznie uruchomione

| Komenda | Wynik |
|---|---|
| `cd server && python -m pytest -q -rs tests` | ✅ 143 passed, 1 skipped (live), 22 subtests passed |
| `python -m pytest -q tests/v2/test_acceptance_mcp.py` | ✅ 7 passed (prawdziwy serwer MCP stdio i HTTP) |
| `python -m pytest -q tests/v2/test_acceptance_browser.py` | ✅ 4 passed (prawdziwy headless Chromium) |
| `python -m pytest -q tests/e2e` | ✅ 1 passed (UI 360 px w Chromium, serwer uvicorn) |
| `python -m pytest -q -rs tests/live` | ⏭ skipped: „no live provider credentials configured” |
| `cd client && npm run lint` | ✅ bez błędów (ostrzeżenia `<img>` z upstream i nowego kodu) |
| `cd client && npm run build:export` | ✅ eksport statyczny (`/bots` 31,6 kB) |
| `cd desktop/src-tauri && cargo check --target x86_64-pc-windows-msvc` | ✅ |
| `cd mobile && npx cap add android` | ✅ projekt wygenerowany |
| `./gradlew assembleDebug` | 🔒 nie uruchomiono — brak Android SDK (dl.google.com zablokowane) |
| `npx tauri build` (Windows) | 🔒 nie uruchomiono — brak runnera Windows |
| `docker build --build-arg WITH_BROWSER=0 …` + `docker run --read-only --cap-drop ALL …` | ✅ healthy, UI 200, API 401 bez tokenu, dane po restarcie zachowane |
| `docker build` z Chromium (`WITH_BROWSER=1`) | ❔ nie uruchomiono tutaj (workflow `ci.yml/docker`) |
| `docker compose -f deploy/docker-compose.yml up` | ❔ nie uruchomiono (wymaga domeny/HTTPS) |
| `python scripts/bench.py --bots 50 --tasks 50 --idle 30` | ✅ wyniki niżej |

## Aktualizacja: LowBot, ChatGPT/Codex, OpenCode Go, APK (2026-10-01)

| Komenda | Wynik |
|---|---|
| `python -m pytest -q tests/v2/test_agent_cli.py` | ✅ 5 passed — prawdziwe `codex-cli 0.159.3` i `opencode 1.18.34` przeciw lokalnemu fałszywemu serwerowi modelu; logowanie ChatGPT na atrapie CLI |
| `python -m pytest -q tests/e2e` | ✅ 2 passed (telefon 360 px + ścieżka Androida z symulowanym mostem `LowBotNative`) |
| `mobile/build-apk.sh test` | ✅ `LowBot-2.0.0-test.apk`, `apksigner verify` v2+v3 OK, `aapt dump badging`: io.lowbot.app, minSdk 26, targetSdk 35 |
| Instalacja APK na urządzeniu/emulatorze | ❔ tylko w CI (`android.yml`), nie tutaj |
| `docker build` (WITH_AGENT_CLIS=1) + `docker run --read-only` | ✅ health zgłasza `codex`, `opencode`; `/integrations` działa |
| Logowanie ChatGPT / wywołanie OpenCode Go na żywo | ⏭ auth.openai.com i opencode.ai zablokowane w tym środowisku |

## Testy akceptacyjne (brief §13)

| Test | Plik / test | Status |
|---|---|---|
| A. 50 botów trwa po restarcie; bot bez zadania nie woła modelu, nie trzyma pulpitu | `test_acceptance_engine.py::test_A_*` | ✅ |
| B. Researcher→Writer→Reviewer, Manager dostaje raport; osobne przebiegi i ślad delegacji; pętle/cykle/budżet/eskalacja uprawnień | `test_B_*` (5 testów) | ✅ |
| C. Praca bez klienta; dwa klienty i dwa workery nie podwajają wykonania | `test_C_*` | ✅ |
| D. Restart workera → checkpoint; fencing starego workera; awaria przy wysyłce → `unknown_outcome` bez ponownej wysyłki; retry; Stop | `test_D_*` (5 testów) | ✅ |
| E. Zgoda przetrwa restart; nie działa dla innej operacji, innych argumentów, innego użytkownika, ponownie ani po wygaśnięciu | `test_E_*` | ✅ |
| F. Osobne powierzchnie, Take over blokuje do Resume; ryzykowny klik → zgoda; limit ekranów | `test_acceptance_browser.py` | ✅ |
| G. Logowanie raz, drugi bot współdzieli sesję, restart nie psuje profilu, isolated nie dziedziczy; blokada profilu | `test_G_*` | ✅ |
| H. Europe/Warsaw, zmiana czasu, restart, zaległe, pauza, powtórzony webhook, jeden lider, symulacja vs test run | `test_acceptance_routines_security.py::test_H_*` | ✅ |
| I. Wstrzyknięta instrukcja nie omija gatewaya, nie czyta sekretów, nie wysyła na niedozwolony adres | `test_I_*` | ✅ (z atrapą „posłusznego” modelu) |
| J. MCP stdio i HTTP wykonują narzędzie; elicitation zgoda/odmowa/anulowanie; formularz z hasłem odrzucony | `test_acceptance_mcp.py` | ✅ |
| K. Różni dostawcy; brak tool calling / vision, błąd API (401), limit kosztu, brak providera → poprawny stan | `test_K_*` (mock HTTP) | ✅ logika; ⏭ prawdziwi dostawcy |
| L. Duplikat/eksport bez sekretów; ukrycie ≠ pauza ≠ usunięcie; backup → restore | `test_L_*` | ✅ |
| M. Telefon 360 px: czat, zgody, routines, takeover, offline bez duplikatów | `tests/e2e/test_mobile_ui.py` | ✅ (WWW w Chromium mobile) |
| N. APK na Androidzie, EXE na czystym Windows | workflow `android.yml`, `windows.yml` | 🔒 nieuruchomione tutaj |
| O. Team Bots: izolacja użytkowników | — | ⏭ etap 5 niezaimplementowany |

## Pomiary (`scripts/bench.py`, atrapa modelu, bez przeglądarki)

Maszyna: 4 vCPU, 16 GB RAM; jeden proces uvicorn (API + worker + scheduler), `MAX_ACTIVE_RUNS=4`.

| Metryka | Wartość |
|---|---|
| RSS po starcie | 76,0 MB |
| RSS po utworzeniu 50 botów i rozmów | 76,4 MB |
| CPU w 30 s bezczynności (50 botów) | 0,07 s (0,23 %) |
| Wywołania modelu w bezczynności | 0 |
| Żądania klienta w bezczynności | 0 (SSE: 1 połączenie, keep-alive co 15–20 s) |
| 50 zadań (2 wywołania modelu + 1 narzędzie każde) | 0,69 s, 72 zadania/s |
| POST wiadomości p50 / max | 10,3 ms / 63,5 ms |
| GET /bots p50 | 6,0 ms |
| RSS szczytowy pod obciążeniem | 77,4 MB |

Ograniczenia: atrapa odpowiada natychmiast, więc to narzut orkiestracji, nie wydajność prawdziwych
agentów (zdominowana przez opóźnienie modelu i przeglądarkę). Pamięć Chromium z 2 aktywnymi ekranami
**nie była zmierzona** (❔).
