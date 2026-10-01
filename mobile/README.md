# LowBot for Android

A deliberately small, framework-only Android app (no AndroidX, no Google
libraries, ~400 KB). It shows the same LowBot interface as the web version
from the APK's own assets and talks to **your LowBot server over HTTPS**.
Bots keep working on the server when the phone is locked or the app is closed.

- Sign in: server URL + one-time pairing code (Settings → Devices on another
  device) **or** the owner token, which the app uses once to create its own
  revocable device token and then forgets.
- Device token: AES-256-GCM, key in the Android Keystore; app backup disabled.
- Only `https://appassets.androidplatform.net` (bundled UI) runs inside the app;
  all other links open in the system browser. Cleartext HTTP is blocked.
- Native bits: microphone (asked only when you tap dictation/voice note),
  speech-to-text, file picker, saving files to `Downloads/LowBot`, Back button,
  `lowbot://pair?server=…&code=…` pairing links.

## Build

```bash
npm --prefix ../client ci && npm --prefix ../client run build:export
ANDROID_HOME=$HOME/Android/Sdk ./build-apk.sh test        # test-signed APK
LOWBOT_KEYSTORE=… LOWBOT_KEYSTORE_PASSWORD=… LOWBOT_KEY_ALIAS=… ./build-apk.sh release
```

Requires `platforms/android-35` and build-tools (aapt2, d8, zipalign, apksigner).
No Gradle and no network access to Google Maven are needed.
