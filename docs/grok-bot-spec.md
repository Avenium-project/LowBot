# Grok Bot — specyfikacja funkcji i jej odwzorowanie w LowBot (telefon)

Źródło: oficjalna dokumentacja `docs.x.ai/grok-bot/*`, odczytana **2026-10-01** przez fragmenty
wyników wyszukiwarki (bezpośredni dostęp do docs.x.ai jest zablokowany w środowisku, w którym
powstaje kod). Każdy punkt ma adres strony źródłowej. Cytaty etykiet UI pochodzą z tych fragmentów.

Legenda: ✅ LowBot robi to samo · 🟨 odpowiednik z różnicą (opisaną) · ⛔ nie da się na telefonie/poza Groka.

## 1. Boty — [bots](https://docs.x.ai/grok-bot/bots), [overview](https://docs.x.ai/grok-bot/overview)

| Grok Bot | LowBot (Android, backend w telefonie) |
|---|---|
| Boty z nazwą, jedną główną pracą i kontekstem, który narasta w czasie | ✅ profil: Name, Label (optional), Description, avatar, instrukcje |
| „New → Create new Bot” lub wpisanie nazwy i „Create "name" Bot” | ✅ przycisk „+” → New Bot / New Group Chat |
| Pamięć: stałe preferencje, kontekst roli, podsumowania pracy; pamięć osobna per bot | ✅ pamięć bota + podsumowania zadań |
| **Pin**, **Hide from sidebar**, **Hidden Bots**, **Unhide**, **Show Hidden Bots** | ✅ |
| **Duplicate**: kopia „<name> copy” z profilem, ustawieniami, skillami, rutynami, avatarem; bez historii, pamięci i załączników | ✅ (rutyny kopii wyłączone do świadomego włączenia — wymóg briefu) |
| **Edit Profile**; ustawienie **Notifications** per bot | ✅ |
| Usunięcie bota usuwa jego rutyny | ✅ |
| Model: Grok (konto Cursor / SuperGrok) | 🟨 klucz API xAI (modele Grok) lub OpenCode Go / OpenAI / OpenRouter / lokalny — wybór per bot |

## 2. Rozmowy — [chat-and-collaboration](https://docs.x.ai/grok-bot/chat-and-collaboration)

| Grok Bot | LowBot |
|---|---|
| Tekst, wklejanie linków i obrazów, załączniki, dyktowanie, **Start voice chat** (gdy composer pusty) | ✅ (voice chat: rozpoznawanie mowy + synteza mowy Androida) |
| `@` wzmianka bota, grupy, rutyny, konektora | 🟨 boty i `@everyone`; rutyny/konektory przez menu |
| Grupa: 2–6 botów, widoczne przekazania | ✅ |
| Bez wzmianki „boty same decydują, kto odpowiada”; `@bot` — właściciel; kilka wzmianek; `@everyone` oszczędnie | 🟨 `@bot`, kilka wzmianek i `@everyone` ✅; bez wzmianki odpowiada prowadzący bot, który z instrukcji systemowej decyduje, czy odpowiedzieć sam, czy przekazać (`task.delegate`/@wzmianka) lepiej dopasowanemu członkowi |
| Bot→bot: asynchroniczna wiadomość, odbiorca się budzi, odpowiada później; przekazanie widoczne w rozmowie | ✅ |
| „Stop now” zatrzymuje pracę, nie cofa wykonanych akcji | ✅ przycisk Stop + raport |

## 3. Komputer — [computer-and-apps](https://docs.x.ai/grok-bot/computer-and-apps), [computers](https://docs.x.ai/grok-bot/computers)

| Grok Bot | LowBot |
|---|---|
| Trwały komputer w chmurze: przeglądarka, system plików, terminal; działa bez otwartego laptopa | 🟨 komputer = telefon: przeglądarka (niewidoczny WebView), pliki w `/workspace` aplikacji, terminal = `sh` Androida (toybox). Praca trwa przy zamkniętej aplikacji (usługa pierwszoplanowa), **nie** przy wyłączonym telefonie |
| Wszystkie boty dzielą komputer: ciasteczka, sesje, pliki, poświadczenia CLI | ✅ wspólne ciasteczka i `/workspace` |
| Otwórz komputer: oglądaj, **przejmij** dla hasła/2FA/CAPTCHA, oddaj sterowanie | ✅ podgląd na żywo, Take over / Resume |
| Bot nie wpisuje haseł; przy logowaniu oddaje komputer człowiekowi | ✅ (instrukcja + zablokowane pola password w narzędziu pisania) |
| Retry / Recover computer | 🟨 „Uruchom ponownie przeglądarkę” |

## 4. Skille i rutyny — [skills-routines-and-automations](https://docs.x.ai/grok-bot/skills-routines-and-automations)

| Grok Bot | LowBot |
|---|---|
| Skill = wielokrotne instrukcje (kroki, reguły, wymagania wyniku, granice zgód) | ✅ |
| **Teach a task**: wykonaj raz w przeglądarce → szkic skilla do przeglądu i testu | ✅ nagrywanie działań człowieka w trybie przejęcia |
| Rutyna jednego bota: harmonogram (np. „every weekday at 8:00 AM”) lub zdarzenie | ✅ harmonogram (Europe/Warsaw, DST); zdarzenia: webhook ⛔ (telefon nie ma publicznego adresu) |
| **View conversation details → Routines**: enable/pause, test, edycja, historia sukcesów/błędów | ✅ |
| **Test run** wykonuje prawdziwą pracę | ✅ (+ symulacja bez zapisu) |

## 5. Zgody i bezpieczeństwo — [approvals-security-and-privacy](https://docs.x.ai/grok-bot/approvals-security-and-privacy), [security](https://docs.x.ai/grok-bot/security)

| Grok Bot | LowBot |
|---|---|
| Karta zgody pokazuje operację i wejścia; **Allow once**, **Always allow** (zapisuje regułę), **Deny** | ✅ (+ Edit) — także z powiadomienia Androida |
| Zgoda dotyczy proponowanej akcji, nie wykonanej pracy | ✅ |
| Wysyłanie, publikowanie, zakupy, usuwanie, produkcja — za zgodą | ✅ domyślne reguły |
| **Auto Review**: niezależny model ocenia ryzykowne akcje (allow / require approval / deny); reguły „Ask first”, „Allow automatically” | ✅ opcjonalnie (Ustawienia → Auto-review) |
| Bezpieczne żądanie sekretu: wartość zamaskowana, poza transkryptem i modelem | ✅ narzędzie `secret.request` |

## 6. Pliki i wyniki — [files-and-results](https://docs.x.ai/grok-bot/files-and-results)

Pliki, obrazy, linki i wyniki narzędzi jako karty; podgląd; zapis; `/workspace` z folderami projektów → ✅.
Szablony udostępniane przez x.ai (Public link / Team-only) → ⛔ (wymaga usługi x.ai).

## 8. Gdzie to jest w kodzie (Android)

`mobile/src/io/lowbot/core` (dane i usługi), `engine` (silnik, modele, rejestr narzędzi), `tools`
(narzędzia, SSRF, MCP), `app` (Android: usługa w tle, alarmy, powiadomienia, przeglądarka i przejęcie,
most JS). Weryfikacja na urządzeniu: `SelfTest.java` uruchamiany w CI na emulatorze Androida 15.

## 7. Mobile, ustawienia, powiadomienia — [mobile](https://docs.x.ai/grok-bot/mobile), [settings-and-notifications](https://docs.x.ai/grok-bot/settings-and-notifications)

| Grok Bot | LowBot |
|---|---|
| Ekran główny: **+** → **New Bot** / **New Group Chat**; wyszukiwanie w **Messages**, **Bots**, **Group Chats**, **Files**, **Routines**, **All** | ✅ |
| Android: share sheet przyjmuje tekst | ✅ |
| Powiadomienia gdy bot skończy / potrzebuje odpowiedzi; zgoda urządzenia + ustawienie bota; wyciszone gdy aplikacja na wierzchu | ✅ |
| Appearance: Follow System / Light / Dark; Language: Follow System / … | ✅ (PL/EN) |
| Execution on Local Computer: Ask every time / Always allow / Never allow | 🟨 dotyczy terminala telefonu (domyślnie Ask every time) |
| Konektory OAuth (Gmail, Slack, GitHub…) z tokenami po stronie Cursor | 🟨 MCP przez HTTP (własne serwery/tokeny); OAuth konektorów xAI ⛔ |
| Team Bots, Slack, administracja | ⛔ poza zakresem jednej osoby na telefonie |
