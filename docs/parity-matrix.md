# Macierz zgodności — Open Dots v2 (LowBot) vs. Grok Bot

Stan na: **2026-10-01**, rewizja bazowa upstream `Anil-matcha/open-dots@5abe1b3d65d5176af936ed92de1b731634d2bcb5`.

**Weryfikacja źródeł:** wszystkie strony `docs.x.ai/grok-bot/*`, `cursor.com/docs/grok-bot`,
`modelcontextprotocol.io`, `v2.tauri.app`, `capacitorjs.com` i `playwright.dev` były **niedostępne** z
tego środowiska (proxy egress: `connect_rejected`, sprawdzone 2026-10-01). Opis funkcji Groka poniżej
pochodzi wyłącznie ze streszczenia w briefie projektu (`MASTER_PROMPT`, sekcje 2–12) i jest
oznaczony **„niezweryfikowane”**. Nie rozstrzygam, czy dana funkcja Groka jest dostępna, wdrażana
stopniowo czy planowana — tego nie dało się sprawdzić. Kontrakty MCP zweryfikowano względem
zainstalowanego SDK `mcp==1.28.1` (`LATEST_PROTOCOL_VERSION = 2025-11-25`), a Playwright względem
`playwright==1.56.0` z Chromium 1194. Lista źródeł: [`sources.md`](sources.md).

Legenda kolumny **Typ**: **G** – zgodność z udokumentowanym sposobem pracy Groka (wg briefu),
**R** – moje rozszerzenie, **T** – ograniczenie techniczne.
Legenda **Status**: ✅ zrobione i przetestowane · 🟡 częściowo · ⛔ brak · 🔒 zablokowane w tym środowisku.

| # | Funkcja | Typ | Źródło | Obecna implementacja | Luka | Wymagany test | Status |
|---|---|---|---|---|---|---|---|
| 1 | Wiele nazwanych, trwałych botów (20–50+, bez limitu produktu) | G | brief §2, §5 (niezweryf.) | `app/v2/bots.py`, tabela `bots`; brak limitu liczby | — | A: 50 botów, restart | ✅ |
| 2 | Bot bez pracy śpi, nie woła modelu | G | brief §5 | worker pobiera tylko kolejkowane `runs`; brak pollingu modelu | — | A, bench (0 wywołań w 30 s) | ✅ |
| 3 | Limity aktywnych przebiegów / ekranów niezależne od liczby botów | G/R | brief §2, §5 | `MAX_ACTIVE_RUNS` (4), `MAX_ACTIVE_SURFACES` (2) | brak per-bot limitu równoległości | `test_surface_limit…` | ✅ |
| 4 | Zadanie trwa po zamknięciu klienta | G | brief §2, §4 | POST zapisuje wiadomość+task w 1 transakcji; worker w tle; SSE tylko obserwuje | — | C | ✅ |
| 5 | Ponowne połączenie nie powtarza pracy | G | brief §4 | `client_msg_id` (unikalny), kursor zdarzeń, outbox | — | C, M (offline) | ✅ |
| 6 | Pętla agentowa z wieloma narzędziami | G | brief §4 | `engine.py`: model → walidacja schematu → polityka → zgoda → wykonanie → checkpoint | brak streamingu tokenów do UI | B, D, I | ✅ |
| 7 | Stany: queued…cancelled + `unknown_outcome` | G/R | brief §4 | CHECK w `runs.status` | — | D | ✅ |
| 8 | Dzierżawy, heartbeat, fencing, przejęcie po awarii | R | brief §4 | `lease_version` jako fencing token, `recover_expired` | — | D (stale worker, crash) | ✅ |
| 9 | Idempotencja efektów zewnętrznych, brak ślepego ponawiania | R | brief §4 | tabela `operations`, `Idempotency-Key`, `unknown_outcome` + decyzja człowieka | brak gotowych „reconcilerów” dla konkretnych API | D (crash przy wysyłce) | ✅ |
| 10 | Retry z backoffem i jitterem, limity kroków/czasu/kosztu | R | brief §4 | `_schedule_retry`, `max_steps`, `deadline_at`, budżet przed wywołaniem | brak limitu kosztu per run (jest dzienny i tokenowy) | D (retry), K (budżet) | ✅ |
| 11 | Stop zatrzymuje i uczciwie raportuje | G | brief §4 | `control=cancel`, raport wykonanych i przerwanych akcji | — | D (stop) | ✅ |
| 12 | Pauza/wznowienie zadania i bota | G | brief §5 | `pause`/`resume`, `bots.paused` | — | L | ✅ |
| 13 | Priorytet wiadomości użytkownika nad tłem | R | brief §4 | priorytety 80/60/30 w claim | brak wywłaszczania trwającego przebiegu | — (logika claim) | 🟡 |
| 14 | Zgody trwałe, związane z użytkownikiem, krokiem i hashem argumentów, jednorazowe, wygasające | G/R | brief §9 | `approvals.py` | — | E | ✅ |
| 15 | Edycja draftu unieważnia zgodę | G | brief §9 | `approvals.edit` | — | E | ✅ |
| 16 | allow/ask/deny, bardziej restrykcyjna wygrywa | R | brief §9 | `policy.py`, reguły globalne/bot | brak reguł zależnych od argumentów (poza eskalacją przeglądarki) | `test_policy…` | ✅ |
| 17 | Domyślne wstrzymanie wysyłki/publikacji/płatności/usuwania | G | brief §9 | `http.post`, `browser.upload`, MCP non-readonly → `ask`; kliknięcia „Wyślij/Publikuj/Zapłać/Usuń…” eskalują | heurystyka etykiet nie wykryje każdego przycisku | F (risky click) | 🟡 |
| 18 | Rozmowy prywatne i grupowe, @wzmianki | G | brief §5 | `conversations`, `memberships`, routing wzmianek | wątki: pole `thread_root_id` bez UI wątków | B (ping-pong), API | 🟡 |
| 19 | Delegowanie z właścicielem, kontekstem i oczekiwanym wynikiem; wynik budzi zlecającego | G | brief §5 | `task.delegate` (wait/async), `handoffs`, `_propagate` | — | B | ✅ |
| 20 | Narzędzia wewnętrzne `bot.create/bot.message/task.delegate/task.get_status/task.complete/artifact.share` | G | brief §5 | `tools/builtin.py` | — | B | ✅ |
| 21 | Ochrona przed pętlami: korelacja, deduplikacja, głębokość, budżet, cykle | R | brief §5 | `guard_delegation`, `dedupe_delegation` | — | B (guards, ping-pong) | ✅ |
| 22 | Nowy bot nie dostaje więcej uprawnień niż twórca | R | brief §5 | `BotService.create(created_by_bot=…)` | — | B | ✅ |
| 23 | Hierarchia CEO→Head→Manager→Worker (opcjonalna) | R | brief §5 | `org_role`, `reports_to`, flaga `hierarchy_enforced` | brak reguł raportowania w górę (tylko zlecanie w dół) | B (hierarchy) | 🟡 |
| 24 | Tworzenie/edycja/przypinanie/ukrywanie/pauza/duplikat/usuwanie botów | G | brief §5 | API + UI | — | L | ✅ |
| 25 | Duplikat bez sekretów/historii/pamięci, harmonogramy wyłączone | G | brief §5 | `duplicate` | — | L | ✅ |
| 26 | Wspólny komputer (wspólne logowania), osobne powierzchnie botów | G | brief §6 | `browser.py`: 1 profil = 1 właściciel (flock), karta na bota | — | G | ✅ |
| 27 | Tryb isolated | G | brief §6 | osobny profil i workspace na bota | brak osobnej tożsamości procesu/OS | G | ✅ |
| 28 | Nawigacja, odczyt, klik, pisanie, skróty, scroll, screenshot, upload/download, weryfikacja wyniku | G | brief §6 | 9 narzędzi `browser.*`, każde zwraca stan strony | download/upload bez testu akceptacyjnego | F, G | 🟡 |
| 29 | Blokada sterowania na powierzchni; Take over/Resume atomowo | G | brief §6 | `computer_sessions.controller` + `lock_version`, sprawdzane pod blokadą | — | F, M | ✅ |
| 30 | Live view z aktywnym botem i stanem połączenia | G | brief §6 | zrzut JPEG co 0,7–1,5 s + stan sterowania | to nie strumień wideo | M | 🟡 |
| 31 | Pełny pulpit (aplikacje niebędące przeglądarką) | G | brief §6 | — (upstream Docker runtime też jest browser-only) | brak środowiska graficzne + kanał wejścia | test z aplikacją nie-przeglądarkową | ⛔ |
| 32 | Terminal / pliki | G | brief §6 | `workspace.*` (atomowy zapis, wykrywanie konfliktów), opcjonalny `shell.run` (rlimit, `ask`) | `shell.run` to nie sandbox | `test_I_workspace…` | 🟡 |
| 33 | Trwałość workspace/sesji, backup, reset z potwierdzeniem | G | brief §6, §9 | profile na wolumenie, reset przenosi profil (nie usuwa), backup/restore | brak backupu profili przeglądarki | L (backup) | 🟡 |
| 34 | Adaptery: OpenAI Responses, Chat Completions, xAI, OpenRouter, lokalny | R | brief §7 | `providers/openai_compat.py` + presety | xAI/OpenRouter tylko jako zgodne z Chat Completions — kontrakt zweryfikujesz przez Test connection | K (mock HTTP) + live (pominięty) | 🟡 |
| 35 | Test connection realnych możliwości (tekst, narzędzia, streaming, vision) | R | brief §7 | `ProviderService.test` | vision tylko na żądanie (koszt) | K | ✅ |
| 36 | Brak zmyślonych modeli i cen | R | brief §7 | model wpisuje użytkownik; koszt tylko przy cenach wpisanych przez użytkownika | — | K (budżet) | ✅ |
| 37 | Budżet sprawdzany przed wywołaniem, także równolegle | R | brief §7 | rezerwacja w transakcji `BEGIN IMMEDIATE` | — | K | ✅ |
| 38 | Fallback na innego dostawcę za zgodą | R | brief §7 | pola `fallback_profile_id`, `allow_fallback` | logika fallbacku niezaimplementowana | — | ⛔ |
| 39 | Klient MCP: stdio + Streamable HTTP, discovery, walidacja, timeouty, anulowanie, postęp, logi | G/R | brief §7 | `mcp_client.py` | OAuth dla MCP niezaimplementowany (tylko token Bearer) | J | 🟡 |
| 40 | Elicitation form/url: zgoda/odmowa/anulowanie, bez sekretów w formularzach | G/R | brief §7 | trwałe `elicitations`, odmowa pól sekretnych, domena dla URL | po restarcie trwające wywołanie MCP przepada (oznaczane `orphaned`) | J | ✅ |
| 41 | Sekrety szyfrowane poza środowiskiem agentów | R | brief §7, §9 | `secret_references` (Fernet), nigdy w promptach/eventach | wspólny proces serwera ma klucz | K, I | ✅ |
| 42 | Composio opcjonalnie | R | brief §7 | upstream `/api/v1` (nie zintegrowane z v2) | — | — | 🟡 |
| 43 | Pamięć bota / wiedza zespołu z zakresem, źródłem, korektą, usuwaniem; wyszukiwanie FTS | G | brief §8 | `memory.py` (FTS5), panel UI | brak embeddingów (celowo) | L, API | ✅ |
| 44 | Skills wersjonowane, wywoływane przez `/`, import/eksport bez sekretów | G | brief §8 | `skills.py`, granica narzędzi skillu w gatewayu | brak tworzenia skilla „rozmową” przez narzędzie | — | 🟡 |
| 45 | Teach a task (demonstracja → edytowalny draft) | G | brief §8 | `draft_from_run` (zgoda, redakcja, placeholdery) | brak nagrywania działań człowieka w live view | — | 🟡 |
| 46 | Routines: jednorazowe, cron, zdarzenia (webhook HMAC) | G | brief §8 | `scheduler.py` | — | H | ✅ |
| 47 | Język naturalny → konfiguracja + podgląd najbliższych uruchomień, Europe/Warsaw | G | brief §8 | `parse_schedule` (PL/EN, typowe wzorce) | tylko typowe frazy | H, M | ✅ |
| 48 | DST, restart, zaległe, nakładanie, pauza, deduplikacja webhooków, jeden scheduler | G/R | brief §8 | lease `scheduler`, `routine_runs` UNIQUE, polityki catch-up/overlap | — | H | ✅ |
| 49 | Symulacja vs Test run; historia z wejściem/wynikiem/błędem/czasem/kosztem | G | brief §8 | `simulate`, `test_run`, `history` | — | H | ✅ |
| 50 | Audyt, retencja, eksport | R | brief §9 | `audit_log`, `/audit/export`, `/audit/retention` | — | `test_I_redaction…` | ✅ |
| 51 | SSRF (także po przekierowaniu), metadane chmurowe, LAN wymaga polityki | R | brief §9 | `netguard.py` (re-check każdego hopu, pin IP), route guard w Chromium | — | I | ✅ |
| 52 | Treść stron/narzędzi jako niezaufane dane | G | brief §9 | ogrodzenie `<untrusted_tool_output>` + egzekwowanie w gatewayu | ogrodzenie nie jest gwarancją wobec modelu | I | ✅ |
| 53 | Sesje urządzeń, parowanie kodem/QR, odwołanie | R | brief §9, §11 | `devices.py`, kod jednorazowy 10 min, token tylko w magazynie OS | sesje WWW nadal w pamięci (upstream) | API (pairing) | ✅ |
| 54 | UI komunikatora PL/EN, telefon ~360 px | G/R | brief §10, §13M | `/bots` (Next.js, eksport statyczny) | — | M | ✅ |
| 55 | Zgody, pytania, blokery, właściciel zadania, podzadania widoczne w UI | G | brief §10 | karty zgód w rozmowie, drzewo zadań, kroki | — | M | ✅ |
| 56 | Globalna wyszukiwarka | G | brief §10 | `/search` + panel | LIKE, nie FTS na wiadomościach | API | ✅ |
| 57 | Artefakty z wersją, zadaniem, autoryzowanym pobraniem | G | brief §10 | `artifacts.py`, CSP sandbox przy pobieraniu | — | API | ✅ |
| 58 | Dyktowanie, notatki głosowe z transkrypcją, TTS | G | brief §10 | Web Speech (urządzenie), STT/TTS przez `/audio/*` | real-time voice: **niedostępne** | `test_voice` (mock HTTP) | 🟡 |
| 59 | Skrzynka powiadomień jako źródło prawdy, push jako dodatek | G | brief §10 | `notifications`; `push_status=not_configured` | brak Web Push/FCM | — | 🟡 |
| 60 | Android APK | G | brief §11 | `mobile/`: natywny shell (WebView, Keystore, mikrofon, pliki, deep link), `build-apk.sh` | APK zbudowany i podpisany tutaj; instalacja na emulatorze tylko w CI | N | 🟡 |
| 61 | Windows EXE | G | brief §11 | `desktop/` Tauri 2 (+ keyring), workflow NSIS/MSI | **EXE nie zbudowany tutaj**; `cargo check` na `x86_64-pc-windows-msvc` przechodzi | N | 🔒 |
| 62 | Kreator: instancja → parowanie → model → test → pierwszy bot | G | brief §11 | `SetupWizard.jsx` | — | M | ✅ |
| 63 | Lokalny backend w kliencie Windows | R | brief §11 | — (klient łączy się ze zdalnym/self-hosted serwerem) | brak | — | ⛔ |
| 64 | Self-hosting: Compose, wolumeny, healthcheck, limity, HTTPS | R | brief §11 | `Dockerfile`, `deploy/` (Caddy) | Compose nie uruchomiony end-to-end (sam obraz tak) | docker smoke | 🟡 |
| 65 | Team Bots (wielu ludzi), Slack, publikacja w zespole | G | brief §12 | — (jeden właściciel) | cały etap 5 | O | ⛔ |
| 66 | Polityki administracyjne, klucze sprzętowe, routing przez lokalny komputer | G | brief §12 (niezweryf.) | — | brak | — | ⛔ |
| 67 | Wybór modelu per bot, duże grupy, pełna edycja routines na telefonie | R | brief §12 | zrobione | — | M | ✅ |
| 69 | ChatGPT przez Codex CLI (logowanie kodem urządzenia, `codex exec --json`) | R | brief §7 | `agents_cli.py`, profil `codex_cli`, narzędzie `codex.run` | narzędzia LowBot niedostępne dla bota na profilu Codex; logowanie tylko na serwerze z Codex CLI | `test_agent_cli.py` (prawdziwy CLI, fałszywy serwer modelu) | 🟡 |
| 70 | OpenCode Go | R | katalog OpenCode 1.18.34 (`opencode-go`: `@ai-sdk/openai-compatible`, `https://opencode.ai/zen/go/v1`) | preset `opencode_go` (pełne narzędzia LowBot), `opencode_cli`, `opencode.run` | endpoint nieosiągalny stąd — brak testu na żywo | `test_agent_cli.py` | 🟡 |
| 68 | PostgreSQL | T | brief §3 | SQL przenośny poza FTS5; brak adaptera | adapter | — | ⛔ |

**Nie deklaruję pełnej zgodności z Grok Bot.** Pozycje ⛔/🔒/🟡 są otwarte.
