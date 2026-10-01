# BUILD — jak zbudować i uruchomić

Status każdej ścieżki: **✅ wykonane w tym repozytorium (2026-10-01)**, **🔒 nie dało się wykonać tutaj**
(z dokładnym powodem), **📄 tylko workflow CI (nieweryfikowane)**.

## 1. Serwer (API + worker + scheduler) — ✅

```bash
cd server
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt                 # rdzeń
pip install -r requirements-browser.txt         # opcjonalnie: przeglądarka botów
python -m playwright install chromium           # opcjonalnie (z --with-deps na czystym Linuxie)
export APP_AUTH_TOKEN="$(python -c 'import secrets;print(secrets.token_urlsafe(32))')"
export APP_ENCRYPTION_KEY="$(python -c 'from cryptography.fernet import Fernet;print(Fernet.generate_key().decode())')"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Najważniejsze zmienne: `DATA_DIR` (domyślnie `~/.open-dots`), `MAX_ACTIVE_RUNS` (4),
`MAX_ACTIVE_SURFACES` (2), `DEFAULT_TIMEZONE` (`Europe/Warsaw`), `ALLOW_PRIVATE_NETWORK` (0),
`EGRESS_ALLOWLIST`, `SHELL_TOOL` (0), `EMBEDDED_WORKER`/`EMBEDDED_SCHEDULER` (1),
`PUBLIC_SERVER_URL`, `UI_DIST` (katalog z UI do serwowania z tego samego originu).

Osobne workery: uruchom kolejne procesy z `EMBEDDED_SCHEDULER=0` — koordynują się przez dzierżawy w bazie.
Scheduler działa zawsze tylko u jednego lidera.

## 2. Interfejs WWW / PWA — ✅

```bash
cd client
npm ci
npm run build:export          # statyczny eksport do client/out (używany też przez Android/Windows)
UI_DIST=$PWD/out python -m uvicorn app.main:app ...   # serwer podaje UI pod /bots/
# tryb deweloperski: npm run dev  ->  http://127.0.0.1:3000/bots
```

## 3. Docker / Compose (self-hosted) — ✅ obraz, 📄 Compose

Obraz zawiera też Codex CLI i OpenCode CLI (`WITH_AGENT_CLIS=1`, domyślnie) — zweryfikowane w kontenerze
(`/api/v2/integrations` zgłasza oba jako zainstalowane, read-only rootfs).

```bash
docker build -t open-dots .                                  # z Chromium
docker build --build-arg WITH_BROWSER=0 -t open-dots .       # mniejszy, bez przeglądarki
# za proxy przechwytującym TLS: --secret id=extra_ca,src=ca.pem ; mirror obrazów: --build-arg BASE_REGISTRY=mirror.gcr.io/library
cp deploy/.env.example deploy/.env    # uzupełnij DOMAIN, APP_AUTH_TOKEN, APP_ENCRYPTION_KEY
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
```

Zweryfikowano: obraz `WITH_BROWSER=0` buduje się i działa jako `uid 10001` z `--read-only`,
`--cap-drop ALL`, healthcheck `healthy`, `/bots/` 200, API bez tokenu 401, dane przetrwały restart
kontenera, klucz API nie jest zapisany jawnie w bazie. Compose z Caddy (HTTPS) nie był uruchomiony
end-to-end (wymaga domeny i portów 80/443).

**Nie wystawiaj `uvicorn` ani `next dev` bezpośrednio do internetu** — tylko przez proxy HTTPS.

## 4. Android APK (LowBot) — ✅ zbudowany tutaj, 📄 test na emulatorze w CI

Aplikacja Android to mały natywny „shell” bez AndroidX/Gradle (`mobile/`), więc buduje się bez
dostępu do Google Maven:

```bash
npm --prefix client ci && npm --prefix client run build:export
ANDROID_HOME=~/Android/Sdk mobile/build-apk.sh test       # APK podpisany kluczem testowym
LOWBOT_KEYSTORE=… LOWBOT_KEYSTORE_PASSWORD=… LOWBOT_KEY_ALIAS=… mobile/build-apk.sh release
```

Wykonano tutaj (2026-10-01): `mobile/build-apk.sh test` z `android.jar` API 35 (kompilacja Java),
`android.jar` API 29 do linkowania zasobów (aapt2 2.19 z Ubuntu nie czyta tabeli zasobów API 35),
`dalvik-exchange` (dx) jako dexer, `zipalign` i `apksigner` z Ubuntu → `LowBot-2.0.0-test.apk`
(~410 KB): `io.lowbot.app`, minSdk 26, targetSdk 35, podpis v2+v3 zweryfikowany przez
`apksigner verify`. **Nie uruchomiono go na urządzeniu ani emulatorze** (brak obrazów systemu —
dl.google.com zablokowane). Workflow `.github/workflows/android.yml` buduje ten sam APK na oficjalnym
SDK (d8, aapt2 35), instaluje go na emulatorze Android 15 i zapisuje zrzut ekranu po uruchomieniu.
Klucz testowy jest generowany lokalnie (`mobile/.keystore/`, nie trafia do repozytorium); APK z innym
kluczem wymaga odinstalowania poprzedniej wersji.

## 5. Windows EXE — 🔒 tutaj, 📄 w CI

```powershell
npm --prefix client ci; npm --prefix client run build:export
cd desktop; npm ci; npx tauri build     # src-tauri/target/release/bundle/nsis/*.exe oraz msi/*.msi
```

Wykonano tutaj: `cargo check --target x86_64-pc-windows-msvc` dla `desktop/src-tauri` — **przechodzi**
(kompilacja bez linkowania). **Nie zbudowano instalatora**: brak runnera Windows (linker MSVC, NSIS,
WebView2). Workflow `.github/workflows/windows.yml` buduje NSIS/MSI na `windows-latest` i wykonuje cichą
instalację oraz uruchomienie aplikacji. Instalator nie wymaga Node/Pythona; WebView2 jest doinstalowywany
przez bootstrapper, jeśli go brakuje. Klient Windows nie dostarcza lokalnego backendu ani dostępu do
lokalnego komputera.

## 6. Testy

```bash
cd server
pip install -r requirements-dev.txt && python -m playwright install chromium
python -m pytest -q tests            # upstream + v2 + e2e (e2e wymaga client/out)
python -m pytest -q -rs tests/live   # prawdziwy model; pominięty bez LIVE_PROVIDER_* (to nie jest zaliczenie)
python scripts/bench.py --bots 50 --tasks 50 --idle 30
```
