# LowBot — Android APK

**[Pobierz LowBot.apk](LowBot.apk)** → na GitHubie kliknij plik, potem „Download raw file”.

- Version **2.4.0** — one consistent design across all screens (borderless cards, pill buttons, grey section
  labels, bot characters everywhere); English-only; bot memory files. SHA-256:
  `ad77bfcae9c2fdbda8f77c82ecb52535eee9689006814f212824205d3b69bb7c`
- Android 8.0+ (API 26). Podpis kluczem testowym — instaluje się jako aktualizacja poprzedniej wersji
  z tego katalogu. Instalacja spoza Sklepu Play: zezwól przeglądarce/menedżerowi plików na
  „instalowanie nieznanych aplikacji”.
- Start: wybierz dostawcę modelu (np. xAI, OpenCode Go, OpenAI albo „ChatGPT account” — nieoficjalne
  logowanie kontem ChatGPT, na własne ryzyko), wklej klucz / zaloguj się, utwórz bota.
- Status testów: self-test backendu na emulatorze Androida 15 — **PASS 39/39** (kod 2.1.0: czat, narzędzia,
  zgody, sekrety, delegowanie, pamięć, rutyny z DST, odzyskiwanie po restarcie, logowanie ChatGPT bez sieci,
  SSRF, przeglądarka). UI 2.2.0 sprawdzone w Chromium (360 px, test e2e); przebieg emulatora dla 2.2.0 w CI.
  Prawdziwe logowanie ChatGPT i rozmowy z modelami nie były testowane (wymagają Twojego konta/klucza).
