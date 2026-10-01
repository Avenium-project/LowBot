# Architektura Open Dots v2

```text
 Android (Capacitor)   Windows (Tauri)   Przeglądarka / PWA
        │  Bearer: token urządzenia  │  Cookie HttpOnly (sesja WWW)
        └──────────────┬─────────────┘
                       ▼
              FastAPI  /api/v2  (auth, origin/CSRF, walidacja pydantic)
                       │  POST zapisuje → zwraca id      GET/SSE tylko obserwuje
                       ▼
        ┌──────────── SQLite (WAL, BEGIN IMMEDIATE, migracje) ────────────┐
        │ bots · conversations · memberships · messages · tasks · runs    │
        │ run_steps (checkpoint) · events (outbox) · handoffs · approvals │
        │ operations (ledger) · routines · routine_runs · memories+FTS5   │
        │ skills · skill_versions · provider_profiles · tool_connections  │
        │ secret_references (Fernet) · artifacts · usage_entries          │
        │ computers · computer_sessions · notifications · devices · audit │
        └─────────────────────────────────────────────────────────────────┘
             ▲                     ▲                       ▲
     Worker(y) — Engine      Scheduler (lider)        Broker przeglądarki
     lease + fencing         lease „scheduler”        Playwright/Chromium
     model → gateway →       cron/once/webhook        1 profil = 1 właściciel
     narzędzia → checkpoint  DST, catch-up, dedupe    karta na bota, Take over
             │
     Gateway narzędzi: schema → polityka allow/ask/deny → zgoda → wykonanie
     ├─ workspace.*  web.fetch (SSRF guard)  http.post (ledger)  shell.run (opc.)
     ├─ memory.*  user.ask  artifact.share  routine.create
     ├─ bot.create  bot.message  task.delegate/get_status/complete
     ├─ browser.*  (broker)
     └─ mcp.<połączenie>.<narzędzie>  (stdio / Streamable HTTP)
```

## Kluczowe decyzje

1. **Praca nie zależy od strumienia.** `POST /conversations/{id}/messages` w jednej transakcji zapisuje
   wiadomość, zadania i przebiegi (`runs`) i zwraca identyfikatory. SSE (`/events/stream`) czyta tabelę
   `events` od kursora (`Last-Event-ID`/`after`); ponowne połączenie niczego nie uruchamia.
2. **Identyfikatory rozdzielone:** `bot_id`, `conversation_id`, `message_id`, `task_id`, `run_id`,
   `computer_id`, `surface_id`. Rozmowa ma wielu botów (`memberships`), bot wiele rozmów i przebiegów.
3. **Silnik (``engine.py``):** claim w `BEGIN IMMEDIATE` z limitem aktywnych przebiegów; dzierżawa
   (`lease_owner`, `lease_expires_at`) + **fencing token** `lease_version` sprawdzany przy każdym zapisie;
   heartbeat odnawia dzierżawę i przenosi sygnał Stop. Każda odpowiedź modelu i każdy wynik narzędzia
   to wiersz `run_steps` — to jest checkpoint. Po awarii `recover_expired` kolejkuje przebieg ponownie
   (z licznikiem prób), a nowy worker kontynuuje od ostatniego kroku.
4. **Co najmniej jednokrotne dostarczanie + ledger.** Narzędzia o skutku zewnętrznym zapisują
   `operations(idempotency_key, status=started)` przed wywołaniem. Jeśli worker padnie w trakcie i nie da
   się sprawdzić wyniku (`reconcile`), przebieg przechodzi w `unknown_outcome` i czeka na decyzję
   człowieka (wykonało się / nie — ponów / porzuć). Brak obietnicy exactly-once.
5. **Czekanie nie zużywa zasobów.** Zgoda, pytanie (`user.ask`), delegacja z `wait=true` i Take over
   zwalniają dzierżawę (`waiting_*`). Wznowienie to zmiana stanu w bazie (decyzja, odpowiedź, zakończenie
   podzadania), które budzi worker.
6. **Zgody** są wierszami: użytkownik, krok, narzędzie, `args_hash`, termin, jednorazowe zużycie
   (`approved → consumed` w jednym UPDATE z warunkiem na hash i termin). Edycja tworzy nową zgodę.
7. **Delegacja** tworzy podzadanie z `parent_task_id`, `correlation_id`, `depth` i `handoff`.
   Strażnicy: limit głębokości, budżet zadań na korelację, wykrywanie cykli, deduplikacja, opcjonalna
   hierarchia. Wzmianki bot→bot przechodzą przez tych samych strażników.
8. **Scheduler** działa tylko u lidera (lease), śpi do najbliższego terminu (bez minutowego pollingu),
   rejestruje każde odpalenie z unikalnym `dedupe_key` (slot czasu lub `X-OpenDots-Delivery`).
9. **Przeglądarka:** jeden proces Chromium na profil (blokada `flock`), karta (surface) na bota,
   serializacja akcji per karta, równoległość między kartami, globalny limit kart z LRU. Tryb isolated
   = osobny profil. Route guard blokuje adresy prywatne/metadane w podżądaniach strony.
10. **Sekrety** w `secret_references` (Fernet, klucz poza bazą). Do modelu trafiają tylko wyniki narzędzi
    w ogrodzeniu `<untrusted_tool_output>`; zdarzenia i audyt przechodzą przez `redact()`.
11. **Persystencja:** SQL przenośny poza FTS5 (izolowany w `memory.py`), co umożliwi adapter PostgreSQL.
    Brak Redis/Kafka/Kubernetes.

## Maszyna stanów przebiegu

```text
queued ──claim──▶ running ──(model bez narzędzi | task.complete)──▶ completed
  ▲  ▲               │ ├─ ask ─────────▶ waiting_approval ──decyzja/wygaśnięcie──┐
  │  │               │ ├─ user.ask / takeover ▶ waiting_input ──odpowiedź/Resume──┤
  │  │               │ ├─ task.delegate(wait) ▶ waiting_dependency ──wynik───────┤
  │  │               │ ├─ błąd przejściowy ▶ retry_scheduled ──not_before─────────┤
  │  │               │ ├─ pauza ▶ paused ──resume─────────────────────────────────┤
  │  │               │ ├─ awaria w trakcie efektu zewn. ▶ unknown_outcome ──decyzja┤
  │  └───────────────┼─────────────────────────────────────────────────────────────┘
  └─ lease wygasł ───┘ (recover_expired)      ├─ błąd trwały / limity ▶ failed
                                              └─ Stop ▶ cancelled
```

## Model wdrożenia

Telefon / Windows / przeglądarka → uwierzytelniony backend → trwałe workery → narzędzia i przeglądarka.
Klient nie jest właścicielem zadania: zamknięcie aplikacji nie zatrzymuje pracy na działającym serwerze.
**Wyłączenie maszyny-serwera zatrzymuje obliczenia**; po starcie dzierżawy wygasają, a przebiegi są
bezpiecznie podejmowane od checkpointu (efekty zewnętrzne niepewne → `unknown_outcome`).
Na Androidzie nie emulujemy Dockera — telefon jest tylko klientem.

Profil startowy (konfigurowalny): 50 zapisanych botów, `MAX_ACTIVE_RUNS=4`, `MAX_ACTIVE_SURFACES=2`.
