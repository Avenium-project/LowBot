# LowBot — Android APK

**[Pobierz LowBot.apk](LowBot.apk)** → na GitHubie kliknij plik, potem „Download raw file”.

- Version **2.7.4** — long-press menu on a chat no longer closes by itself when you lift your finger;
  includes 2.7.3 (voice chat removed), 2.7.2 (Android speech recognition), 2.7.1 ("(no response)" fix).
  SHA-256: `2036a841be64ab217eafcddb8963870587252286f053f34fe07d1a054e2fefed`
- Android 8.0+ (API 26). Podpis kluczem testowym — instaluje się jako aktualizacja poprzedniej wersji
  z tego katalogu. Instalacja spoza Sklepu Play: zezwól przeglądarce/menedżerowi plików na
  „instalowanie nieznanych aplikacji”.
- Start: wybierz dostawcę modelu (np. xAI, OpenCode Go, OpenAI albo „ChatGPT account” — nieoficjalne
  logowanie kontem ChatGPT, na własne ryzyko), wklej klucz / zaloguj się, utwórz bota.
- Status testów: self-test backendu na emulatorze Androida 15 — **PASS 39/39** (kod 2.1.0: czat, narzędzia,
  zgody, sekrety, delegowanie, pamięć, rutyny z DST, odzyskiwanie po restarcie, logowanie ChatGPT bez sieci,
  SSRF, przeglądarka). UI 2.2.0 sprawdzone w Chromium (360 px, test e2e); przebieg emulatora dla 2.2.0 w CI.
  Prawdziwe logowanie ChatGPT i rozmowy z modelami nie były testowane (wymagają Twojego konta/klucza).
