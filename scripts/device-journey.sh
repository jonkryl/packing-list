#!/usr/bin/env bash
set -euo pipefail
mkdir -p ci-artifacts
adb wait-for-device
device_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
font_scale_changed=false

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

# Google APIs test images are debuggable AOSP emulator images, with no user account.
# Root avoids API 24's shell restriction on Wi-Fi controls and airplane-mode broadcasts.
adb root > ci-artifacts/offline-adb-root.txt
adb wait-for-device
test "$(adb shell id -u | tr -d '\r')" = '0'
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true > ci-artifacts/offline-airplane-mode.txt
adb emu gsm data off > ci-artifacts/offline-emulator-data.txt
adb shell dumpsys connectivity > ci-artifacts/offline-connectivity.txt
adb shell settings get global airplane_mode_on > ci-artifacts/offline-setting.txt

run_instrumentation() {
    local report="$1"
    shift
    prepare_display
    adb shell dumpsys power > "${report%.txt}-power-before.txt"
    timeout 480s adb shell am instrument -w -r "$@" com.jonkryl.packinglist.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
    python3 scripts/check-instrumentation.py "$report"
}

run_instrumentation ci-artifacts/01-packing-journey.txt -e notClass com.jonkryl.packinglist.RestartAndExportTest,com.jonkryl.packinglist.LargeFontAccessibilityTest
adb shell am force-stop com.jonkryl.packinglist
run_instrumentation ci-artifacts/02-process-restart-export.txt -e class com.jonkryl.packinglist.RestartAndExportTest
font_scale_changed=true
adb shell settings put system font_scale 2.0
adb shell am force-stop com.jonkryl.packinglist
run_instrumentation ci-artifacts/03-font-200-percent.txt -e class com.jonkryl.packinglist.LargeFontAccessibilityTest
adb shell settings put system font_scale 1.0
font_scale_changed=false
adb pull /sdcard/Android/data/com.jonkryl.packinglist/files/screenshots ci-artifacts/screenshots
test -n "$(find ci-artifacts/screenshots -name '*.png' -print -quit)"
