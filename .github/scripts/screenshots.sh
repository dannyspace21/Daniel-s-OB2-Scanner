#!/usr/bin/env bash
# Takes README screenshots on an Android emulator (run by .github/workflows/screenshots.yml).
# Opens screens by tapping their on-screen text, found with uiautomator, so it
# doesn't depend on exact pixel positions.
set -u
PKG=com.daniel.carscanner
OUT=docs/screenshots
mkdir -p "$OUT"

adb install -r app/build/outputs/apk/debug/app-debug.apk
# Offline, so the screenshots don't show an "Update available" card for this test build.
adb shell svc wifi disable || true
adb shell svc data disable || true
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$p || true; done

shot() { sleep 3; dismiss_dialogs; adb exec-out screencap -p > "$OUT/$1.png"; echo "saved $1"; }

# Taps the first element whose text (or description) is exactly $1.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  local b
  b=$(python3 - "$1" <<'PY'
import re, sys
xml = open('/tmp/ui.xml', encoding='utf-8').read()
want = sys.argv[1]
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r' content-desc="([^"]*)"', n)
    if (t and t.group(1) == want) or (d and d.group(1) == want):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
  if [ -n "$b" ]; then adb shell input tap $b; return 0; fi
  echo "could not find '$1'"; return 1
}
# The emulator's own apps sometimes freeze at boot ("Pixel Launcher isn't responding").
# Close any such system dialog so it doesn't end up in a screenshot.
dismiss_dialogs() {
  for i in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    grep -q "isn&apos;t responding\|isn't responding\|keeps stopping" /tmp/ui.xml 2>/dev/null || return 0
    tap_text "Wait" || tap_text "Close app" || adb shell input keyevent 4
    sleep 2
  done
}
scroll_down() { adb shell input swipe 540 1900 540 700 500; sleep 1; }
back() { adb shell input keyevent 4; sleep 2; }

# Let the emulator finish starting its own apps before we begin.
sleep 25
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true

for mode in dark light; do
  adb shell cmd uimode night $([ $mode = dark ] && echo yes || echo no)
  adb shell am force-stop $PKG
  adb shell am start -n $PKG/.MainActivity >/dev/null
  sleep 8
  dismiss_dialogs
  shot "home-$mode"
  [ $mode = light ] && continue    # one light-mode shot is enough
  scroll_down; shot "menu-dark"
  scroll_down; shot "menu-2-dark"
  for screen in "Adapter:connect" "Active tests:tests" "Body live view:body-data" "Maintenance:maintenance" "Settings:settings"; do
    label=${screen%%:*}; name=${screen##*:}
    tap_text "$label" || { scroll_down; tap_text "$label"; } || continue
    shot "$name-dark"
    back
  done
done
ls -la "$OUT"
