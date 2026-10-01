# LowBot — Android APK

**[Pobierz LowBot.apk](LowBot.apk)** → na GitHubie kliknij plik, potem „Download raw file”.

- Wersja **2.1.0** — cały backend działa w telefonie (bez serwera). SHA-256:
  `40974330b60d19c671275a4cd7b498a35e58c9158648616ffb382e6e00f28864`
- Android 8.0+ (API 26). Podpis kluczem testowym — instaluje się jako aktualizacja poprzedniej wersji
  z tego katalogu. Instalacja spoza Sklepu Play: zezwól przeglądarce/menedżerowi plików na
  „instalowanie nieznanych aplikacji”.
- Start: wybierz dostawcę modelu (np. xAI, OpenCode Go, OpenAI albo „ChatGPT account” — nieoficjalne
  logowanie kontem ChatGPT, na własne ryzyko), wklej klucz / zaloguj się, utwórz bota.
- Status testów: kompilacja i podpis ✅; na emulatorze Androida 15 potwierdzono ekran startowy działający
  na lokalnym backendzie oraz pierwsze kontrole self-testu. Pełny self-test na emulatorze: w toku
  (`.github/workflows/android.yml`). Prawdziwe logowanie ChatGPT i rozmowy z modelami nie były
  testowane (wymagają Twojego konta/klucza).
