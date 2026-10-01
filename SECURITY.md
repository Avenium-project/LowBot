# Bezpieczeństwo — Open Dots v2

**Status: prototyp do samodzielnego hostingu przez jednego właściciela. Nie jest „production-ready”
dla wielu użytkowników ani do uruchamiania wrogiego kodu.**

## Co jest egzekwowane w kodzie

- **Gateway narzędzi** (`server/app/v2/engine.py`): każde wywołanie — wbudowane, przeglądarka, shell,
  MCP — przechodzi walidację JSON Schema, politykę allow/ask/deny (najbardziej restrykcyjna reguła
  wygrywa; narzędzia `hard_ask` nie dają się przełączyć na allow), zgodę i audyt. Prompt systemowy nie
  zastępuje tych kontroli.
- **Zgody**: związane z użytkownikiem, krokiem, narzędziem i hashem argumentów; wygasają; jednorazowe;
  edycja unieważnia poprzednią. Karta pokazuje cel, skutek i parametry.
- **Domyślnie „ask”**: `http.post`, `browser.upload`, `bot.create`, `routine.create`, `shell.run`,
  narzędzia MCP bez `readOnlyHint`; kliknięcia/wysyłki formularzy w przeglądarce o etykietach typu
  wyślij/opublikuj/zapłać/kup/usuń/potwierdź są eskalowane do zgody (heurystyka — nie wykrywa wszystkiego;
  dodaj regułę `browser.* → ask`, jeśli chcesz zatwierdzać każdą interakcję).
- **Niezaufane dane**: wyniki narzędzi trafiają do modelu w ogrodzeniu `<untrusted_tool_output>`.
  To utrudnia, ale nie uniemożliwia prompt injection — granicą jest gateway, nie model.
- **SSRF** (`netguard.py`): tylko http/https, bez danych logowania w URL, każdy rozwiązany adres musi
  być publiczny (chyba że `ALLOW_PRIVATE_NETWORK=1`), metadane chmurowe zawsze blokowane, każde
  przekierowanie sprawdzane ponownie, połączenie przypięte do sprawdzonego IP (DNS rebinding).
  W Chromium podżądania strony przechodzą przez route guard (cache DNS 60 s).
- **Workspace**: ścieżki ograniczone do katalogu workspace; zapis atomowy z blokadą i opcjonalnym
  `expected_sha256` (wykrywanie konfliktów).
- **Sekrety**: szyfrowane Fernetem w `secret_references`, klucz w `APP_ENCRYPTION_KEY` lub
  `DATA_DIR/.encryption.key` (0600). Nie trafiają do promptów, zdarzeń, audytu, eksportu botów ani
  zmiennych `NEXT_PUBLIC_*`. API nigdy ich nie zwraca.
- **Uwierzytelnianie**: sesja WWW w ciasteczku HttpOnly (upstream) + sprawdzanie `Origin`; klienci
  natywni używają tokenu urządzenia (Bearer) przechowywanego w Windows Credential Manager (Tauri +
  `keyring`) lub Android Keystore (`capacitor-secure-storage-plugin`). Parowanie jednorazowym kodem
  (10 min) — QR nie zawiera tokenu właściciela. Urządzenia można odwołać pojedynczo. Żadnych tokenów w URL
  ani `localStorage`. Strumień SSE jest czytany przez `fetch` z nagłówkiem, nie EventSource z tokenem w URL.
- **Webhooki**: HMAC-SHA256 nad `timestamp.body`, okno 5 min, deduplikacja po `X-OpenDots-Delivery`.
- **Elicitation MCP**: formularze z polami wyglądającymi na sekrety są odrzucane; URL wymaga zgody i
  pokazuje domenę; brak auto-akceptacji.
- **Pobieranie plików**: autoryzowane, `Content-Security-Policy: sandbox`, `nosniff`.
- **Audyt**: kto/kiedy/zadanie/decyzja/wynik w `audit_log` (zredagowany), eksport JSONL, retencja.

## Granice — czego NIE gwarantujemy

- **Tryb shared to nie granica bezpieczeństwa.** Boty w trybie shared współdzielą profil przeglądarki
  (logowania) i workspace. Osobna karta ≠ izolacja.
- **Tryb isolated** daje osobny profil Chromium i katalog, ale ten sam proces serwera i użytkownika OS.
- **Sekrety a wspólny proces**: serwer ma klucz szyfrujący; kod uruchomiony w tym samym procesie lub
  jako ten sam użytkownik OS mógłby go odczytać. `shell.run` (domyślnie wyłączony, `SHELL_TOOL=1`) ma
  tylko rlimity i oczyszczone środowisko — **to nie sandbox**.
- **Kontener Docker** z `read_only`, `cap_drop: ALL`, `no-new-privileges`, limitami RAM/CPU/PID nie jest
  gwarancją bezpieczeństwa wobec wrogiego kodu. Chromium działa z domyślnym sandboxem Playwright.
- Sesje WWW (upstream) są w pamięci procesu i wygasają przy restarcie.

## Profil silniejszej izolacji (zalecany dla nieufnych treści)

1. Uruchamiaj przeglądarkę i shell w osobnej maszynie wirtualnej lub mikro-VM (Firecracker, Kata,
   gVisor `runsc`) z osobnym użytkownikiem i bez dostępu do `DATA_DIR`.
2. Sekrety przez osobny gateway poświadczeń wydający krótkotrwałe tokeny; nigdy nie montuj magazynu
   sekretów w środowisku bota.
3. Egress przez proxy z listą dozwolonych domen (`EGRESS_ALLOWLIST`).
4. Nigdy nie montuj gniazda Dockera ani katalogu domowego.

## Zgłaszanie problemów

Zgłoś prywatnie właścicielowi repozytorium (Security Advisory w GitHub). Nie publikuj szczegółów
podatności w publicznym issue.
