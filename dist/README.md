# LowBot — Android APK

**[Pobierz LowBot.apk](LowBot.apk)** → na GitHubie kliknij plik, potem „Download raw file”.

- Version **2.8.1** — bot.create works for bots made before 2.8 (a new bot gets the part of the default tools its
  creator has, instead of failing); bots see the tools they really called in earlier replies (no more invented or denied
  errors); long code in replies wraps inside the bubble; Grok-style "<bot> is working" line with a shimmering text.
  SHA-256: `aa30c582d3625d9ae55bd42a904a183b9b5c400e1ab8f515a7999144070fa986`
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
