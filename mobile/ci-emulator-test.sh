#!/usr/bin/env bash
# Runs on a CI Android emulator: installs the APK, checks the first screen and runs the
# on-device self-test of the phone-hosted backend (SelfTest.java).
set -euo pipefail
APK=$(ls apk/LowBot-*-test.apk | head -1)
adb install -r "$APK"
adb shell pm grant io.lowbot.app android.permission.POST_NOTIFICATIONS || true
adb logcat -c || true
adb shell am start -W -n io.lowbot.app/.MainActivity
# Cold WebView start on a CI emulator varies a lot: wait up to ~90 s for the first screen.
for i in $(seq 1 18); do
  sleep 5
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1 || true
  grep -qE "Set up LowBot|Konfiguracja LowBot" ui.xml 2>/dev/null && break
done
adb shell pidof io.lowbot.app
adb exec-out screencap -p > lowbot-launch.png
grep -oE 'text="[^"]{2,120}"' ui.xml | head -40 || true
grep -qE "Set up LowBot|Konfiguracja LowBot" ui.xml
# The wizard text comes from the local backend path (no server step).
grep -qE "na tym telefonie|on this phone" ui.xml
grep -qE "xAI API|OpenCode Go" ui.xml && echo "provider presets loaded through the native bridge" || echo "presets not visible in the accessibility tree (not fatal)"

# Home-screen widgets must be offered by the launcher's widget picker.
widgets=$(adb shell dumpsys appwidget | grep -oE "io\.lowbot\.app/\.(BotsWidget|CardWidget)" | sort -u | tr '\n' ' ')
echo "widget providers: $widgets"
case "$widgets" in *BotsWidget*CardWidget*) echo "home-screen widgets registered" ;; *) echo "home-screen widgets missing"; exit 1 ;; esac

adb shell am broadcast -n io.lowbot.app/.SelfTest
result=""
for i in $(seq 1 240); do
  result=$(adb logcat -d -s LOWBOT_SELFTEST:I | grep -oE "LOWBOT_SELFTEST (PASS|FAIL).*" | tail -1 || true)
  [ -n "$result" ] && break
  sleep 2
done
adb logcat -d -s LOWBOT_SELFTEST:* | tail -80 > selftest-log.txt || true
cat selftest-log.txt
adb logcat -d | grep -E "AndroidRuntime|LowBot" | tail -60 || true
echo "$result"
case "$result" in
  "LOWBOT_SELFTEST PASS"*) echo "self-test passed" ;;
  *) echo "self-test failed or timed out"; exit 1 ;;
esac
