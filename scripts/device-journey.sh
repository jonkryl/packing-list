#!/usr/bin/env bash
set -euo pipefail
mkdir -p ci-artifacts
timeout 30s adb wait-for-device > ci-artifacts/initial-adb-wait.txt 2>&1
device_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
font_scale_changed=false
tablet_keyboard_previous=""

collect_device_proof() {
    local status=$?
    trap - EXIT
    set +e
    if [ "$status" -ne 0 ]; then
        timeout 10s adb shell dumpsys power > ci-artifacts/failure-power.txt
        timeout 10s adb shell dumpsys window > ci-artifacts/failure-window.txt
        timeout 10s adb shell dumpsys activity top > ci-artifacts/failure-activity.txt
        timeout 10s adb exec-out screencap -p > ci-artifacts/failure-screen.png
    fi
    timeout 15s adb pull /sdcard/Android/data/com.jonkryl.packinglist/files/screenshots ci-artifacts/screenshots
    timeout 10s adb shell dumpsys package com.jonkryl.packinglist > ci-artifacts/package.txt
    timeout 10s adb logcat -d -v threadtime > ci-artifacts/logcat.txt
    if [ "$font_scale_changed" = true ]; then
        timeout 10s adb shell settings put system font_scale 1.0 >/dev/null 2>&1
    fi
    if [ -n "$tablet_keyboard_previous" ]; then
        if [ "$tablet_keyboard_previous" = null ]; then
            timeout 10s adb shell settings delete secure show_ime_with_hard_keyboard >/dev/null 2>&1
        else
            timeout 10s adb shell settings put secure show_ime_with_hard_keyboard "$tablet_keyboard_previous" >/dev/null 2>&1
        fi
    fi
    exit "$status"
}
trap collect_device_proof EXIT

prepare_display() {
    adb shell settings put system screen_off_timeout 1800000
    adb shell svc power stayon true
    adb shell input keyevent 224
    if [ "$device_api" -ge 26 ]; then
        adb shell wm dismiss-keyguard || adb shell input keyevent 82
    else
        adb shell input keyevent 82
    fi
}
prepare_display
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
app_apk="$(find ci-apks -name 'app-debug.apk' -print -quit)"
test_apk="$(find ci-apks -name 'app-debug-androidTest.apk' -print -quit)"
test -n "$app_apk" && test -n "$test_apk"
adb install -r "$app_apk"
adb install -r "$test_apk"
adb shell pm clear com.jonkryl.packinglist

# Google APIs CI emulator images are rootable; API 24 shell otherwise cannot toggle Wi-Fi.
root_verified=false
for root_attempt in 1 2 3; do
    printf '\nRoot attempt %s\n' "$root_attempt" >> ci-artifacts/offline-adb-root.txt
    # A successful daemon restart can close this command's connection before its reply arrives.
    # Capture that error, reconnect with bounded waits, and trust only the actual shell UID.
    if timeout 15s adb root >> ci-artifacts/offline-adb-root.txt 2>&1; then
        root_command_status=0
    else
        root_command_status=$?
    fi
    printf 'adb root exit: %s\n' "$root_command_status" >> ci-artifacts/offline-adb-root.txt
    if timeout 20s adb wait-for-device >> ci-artifacts/offline-adb-root.txt 2>&1; then
        if root_uid="$(timeout 5s adb shell id -u 2>> ci-artifacts/offline-adb-root.txt | tr -d '\r\n')"; then
            printf 'Observed shell UID: %s\n' "$root_uid" >> ci-artifacts/offline-adb-root.txt
            if [ "$root_uid" = '0' ]; then
                root_verified=true
                break
            fi
        else
            printf 'UID probe failed: %s\n' "$?" >> ci-artifacts/offline-adb-root.txt
        fi
    else
        printf 'Device reconnect failed: %s\n' "$?" >> ci-artifacts/offline-adb-root.txt
    fi
    sleep 1
done
test "$root_verified" = true
test "$(timeout 10s adb shell id -u 2>> ci-artifacts/offline-adb-root.txt | tr -d '\r\n')" = '0'
adb shell svc wifi disable
adb shell service list > ci-artifacts/offline-services.txt 2> ci-artifacts/offline-services-stderr.txt
if grep -Eq '^[[:space:]]*[0-9]+[[:space:]]+phone:[[:space:]]+\[com\.android\.internal\.telephony\.ITelephony\][[:space:]]*$' ci-artifacts/offline-services.txt; then
    adb shell svc data disable > ci-artifacts/offline-mobile-data.txt 2> ci-artifacts/offline-mobile-data-stderr.txt
else
    # API 36 may list a placeholder "phone: []" without a usable ITelephony binder.
    printf 'API %s has no usable ITelephony interface; emulator GSM data, Wi-Fi and airplane-mode controls remain required.\n' "$device_api" > ci-artifacts/offline-mobile-data.txt
fi
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true > ci-artifacts/offline-airplane-mode.txt
adb emu gsm data off > ci-artifacts/offline-emulator-data.txt
# Radio shutdown is asynchronous. Require the real default network to disappear before testing.
offline_confirmed=false
for attempt in $(seq 1 15); do
    adb shell dumpsys connectivity > ci-artifacts/offline-connectivity.txt
    if grep -Eq '^Active default network: (none|null)[[:space:]]*$' ci-artifacts/offline-connectivity.txt; then
        offline_confirmed=true
        break
    fi
    sleep 1
done
test "$offline_confirmed" = true
adb shell settings get global airplane_mode_on > ci-artifacts/offline-setting.txt
test "$(tr -d '\r\n' < ci-artifacts/offline-setting.txt)" = '1'
run_instrumentation() {
    local report="$1"
    shift
    prepare_display
    adb shell dumpsys power > "${report%.txt}-power-before.txt"
    timeout 480s adb shell am instrument -w -r "$@" com.jonkryl.packinglist.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
    python3 scripts/check-instrumentation.py "$report"
}

run_instrumentation ci-artifacts/01-packing-journey.txt -e notClass com.jonkryl.packinglist.RestartAndExportTest,com.jonkryl.packinglist.LargeFontAccessibilityTest,com.jonkryl.packinglist.TabletJourneyTest
adb shell am force-stop com.jonkryl.packinglist
run_instrumentation ci-artifacts/02-process-restart-export.txt -e class com.jonkryl.packinglist.RestartAndExportTest
font_scale_changed=true
adb shell settings put system font_scale 2.0
adb shell am force-stop com.jonkryl.packinglist
run_instrumentation ci-artifacts/03-font-200-percent.txt -e class com.jonkryl.packinglist.LargeFontAccessibilityTest
adb shell settings put system font_scale 1.0
font_scale_changed=false
adb shell wm size > ci-artifacts/tablet-display-size.txt
adb shell wm density > ci-artifacts/tablet-display-density.txt
tablet_width_dp="$(python3 - <<'PY'
import re
from pathlib import Path
size=re.findall(r'(?:Physical|Override) size:\s*(\d+)x(\d+)',Path('ci-artifacts/tablet-display-size.txt').read_text())
density=re.findall(r'(?:Physical|Override) density:\s*(\d+)',Path('ci-artifacts/tablet-display-density.txt').read_text())
assert size and density,'Actual emulator display size/density is required'
print(int(size[-1][0])*160//int(density[-1]))
PY
)"
if [ "$tablet_width_dp" -ge 600 ]; then
    tablet_keyboard_previous="$(adb shell settings get secure show_ime_with_hard_keyboard | tr -d '\r\n')"
    adb shell settings put secure show_ime_with_hard_keyboard 1
    adb shell am force-stop com.jonkryl.packinglist
    run_instrumentation ci-artifacts/04-tablet-journey.txt -e class com.jonkryl.packinglist.TabletJourneyTest -e ownedPortfolioEmulator true
    if [ "$tablet_keyboard_previous" = null ]; then
        adb shell settings delete secure show_ime_with_hard_keyboard
    else
        adb shell settings put secure show_ime_with_hard_keyboard "$tablet_keyboard_previous"
    fi
    tablet_keyboard_previous=""
fi
adb pull /sdcard/Android/data/com.jonkryl.packinglist/files/screenshots ci-artifacts/screenshots
test -n "$(find ci-artifacts/screenshots -name '*.png' -print -quit)"
