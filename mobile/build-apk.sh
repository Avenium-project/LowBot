#!/usr/bin/env bash
# Builds the LowBot APK without Gradle or any Google Maven dependency.
#
#   mobile/build-apk.sh [test|release]
#
# Needs: JDK 17+, client/out (npm --prefix client run build:export) and an
# Android SDK piece set:
#   ANDROID_JAR   platforms/android-35/android.jar
#   BUILD_TOOLS   directory with aapt2, zipalign, apksigner and d8 (or dx)
#   ANDROID_LINK_JAR (optional) platform jar for aapt2 linking, default ANDROID_JAR
# If ANDROID_HOME is set these are found automatically.
#
# Signing:
#   release: LOWBOT_KEYSTORE, LOWBOT_KEYSTORE_PASSWORD, LOWBOT_KEY_ALIAS, LOWBOT_KEY_PASSWORD
#   test:    a local test key (mobile/.keystore/test.jks) is generated once.
#            It is for testing only and is never committed.
set -euo pipefail
MODE="${1:-test}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="$HERE/build"
VERSION_NAME="${VERSION_NAME:-2.0.0}"
# Keep the new build series above previous APKs (CI codes 1–51).
# Each CI run increases the code; explicit overrides remain available locally.
VERSION_CODE="${VERSION_CODE:-$((1000 + ${GITHUB_RUN_NUMBER:-0}))}"
MIN_SDK=26
TARGET_SDK=35

if [ -z "${ANDROID_JAR:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  ANDROID_JAR="$(ls -d "$ANDROID_HOME"/platforms/android-35/android.jar 2>/dev/null | head -1)"
fi
if [ -z "${BUILD_TOOLS:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
fi
: "${ANDROID_JAR:?set ANDROID_JAR or ANDROID_HOME}"
: "${BUILD_TOOLS:?set BUILD_TOOLS or ANDROID_HOME}"
# Resources may be linked against an older platform jar when the available aapt2
# predates the compile SDK (e.g. Ubuntu's aapt2 29); Java still compiles against ANDROID_JAR.
ANDROID_LINK_JAR="${ANDROID_LINK_JAR:-$ANDROID_JAR}"
AAPT2="$BUILD_TOOLS/aapt2"; ZIPALIGN="$BUILD_TOOLS/zipalign"; APKSIGNER="$BUILD_TOOLS/apksigner"
[ -f "$ROOT/client/out/bots/index.html" ] || { echo "client/out missing: run npm --prefix client run build:export"; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT"/{res,gen,classes,dex,assets/www}
echo "• bundling web UI"
cp -R "$ROOT/client/out/." "$OUT/assets/www/"
rm -rf "$OUT/assets/www/alternatives"   # marketing pages are not needed in the app
# Linux terminal: pinned Alpine rootfs (URL + SHA-256), if the runtime has been fetched.
if [ -f "$HERE/linux-runtime/alpine.json" ]; then mkdir -p "$OUT/assets/linux" && cp "$HERE/linux-runtime/alpine.json" "$OUT/assets/linux/"; fi

echo "• resources (aapt2)"
"$AAPT2" compile --dir "$HERE/res" -o "$OUT/res/compiled.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_LINK_JAR" --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A "$OUT/assets" --java "$OUT/gen" --auto-add-overlay "$OUT/res/compiled.zip"

echo "• java"
DEBUGGABLE=false
mkdir -p "$OUT/gen/io/lowbot/app"
cat > "$OUT/gen/io/lowbot/app/BuildConfigLite.java" <<EOF
package io.lowbot.app;
final class BuildConfigLite { static final String VERSION_NAME = "$VERSION_NAME"; static final boolean DEBUG = $DEBUGGABLE; }
EOF
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" $(find "$HERE/src" "$OUT/gen" -name '*.java')

echo "• dex"
if [ -x "$BUILD_TOOLS/d8" ]; then
  "$BUILD_TOOLS/d8" --min-api $MIN_SDK --release \
    --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
elif command -v dalvik-exchange >/dev/null 2>&1; then
  dalvik-exchange --dex --min-sdk-version=$MIN_SDK --output="$OUT/dex/classes.dex" "$OUT/classes"
else
  echo "no d8/dx found"; exit 1
fi
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -qj "$OUT/unsigned.apk" classes.dex)
# Native executables for the Linux terminal (proot), stored uncompressed under lib/<abi>/.
if [ -d "$HERE/jniLibs" ] && ls "$HERE"/jniLibs/*/libproot.so >/dev/null 2>&1; then
  echo "• linux runtime (proot)"
  rm -rf "$OUT/native" && mkdir -p "$OUT/native/lib" && cp -R "$HERE/jniLibs/." "$OUT/native/lib/"
  (cd "$OUT/native" && zip -q -0 -r "$OUT/unsigned.apk" lib)
else
  echo "• linux runtime not present (mobile/jniLibs) — the terminal will report it as unavailable"
fi

if [ "$MODE" = "test" ]; then
  KS="$HERE/.keystore/test.jks"
  if [ ! -f "$KS" ]; then
    mkdir -p "$HERE/.keystore"
    keytool -genkeypair -keystore "$KS" -storepass lowbot-test -keypass lowbot-test -alias lowbot-test \
      -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=LowBot test key, O=LowBot" >/dev/null 2>&1
  fi
  STORE="$KS"; STOREPASS="lowbot-test"; ALIAS="lowbot-test"; KEYPASS="lowbot-test"
else
  : "${LOWBOT_KEYSTORE:?release signing needs LOWBOT_KEYSTORE (production key from your secrets)}"
  STORE="$LOWBOT_KEYSTORE"; STOREPASS="$LOWBOT_KEYSTORE_PASSWORD"; ALIAS="$LOWBOT_KEY_ALIAS"; KEYPASS="${LOWBOT_KEY_PASSWORD:-$LOWBOT_KEYSTORE_PASSWORD}"
fi

echo "• align + sign ($MODE)"
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
NAME="LowBot-$VERSION_NAME-$MODE.apk"
"$APKSIGNER" sign --ks "$STORE" --ks-pass "pass:$STOREPASS" --ks-key-alias "$ALIAS" --key-pass "pass:$KEYPASS" \
  --min-sdk-version $MIN_SDK --out "$OUT/$NAME" "$OUT/aligned.apk"
"$APKSIGNER" verify --min-sdk-version $MIN_SDK --print-certs "$OUT/$NAME" | head -3
echo "✔ $OUT/$NAME"
