#!/usr/bin/env bash
set -euo pipefail
round="${1:?Test round required}"
reports=native-android/app/build/reports/device
mkdir -p "$reports"
case "$round" in
  1) ;; # baseline
  2) adb shell settings put system font_scale 1.5 ;;
  3) adb shell svc wifi disable; adb shell svc data disable ;;
  4) adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1 ;;
  5) ;; # force-stop/relaunch checks below, without breaking ActivityScenario
  *) exit 2 ;;
esac
gradle -p native-android assembleDebug assembleDebugAndroidTest
adb install -r native-android/app/build/outputs/apk/debug/app-debug.apk
adb install -r native-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -r com.minddeck.nativeapp.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/instrumentation.txt"
adb pull /sdcard/Android/data/com.minddeck.nativeapp/files/screenshots "$reports/screenshots" || true
# Android's instrumentation command may exit zero even when tests fail.
grep -Eq '^OK \(5 tests\)' "$reports/instrumentation.txt"
if [ "$round" = 5 ]; then
  for attempt in 1 2 3 4 5; do
    adb shell am force-stop com.minddeck.nativeapp
    adb shell am start -W -n com.minddeck.nativeapp/.MainActivity | tee "$reports/relaunch-$attempt.txt"
    grep -q 'Status: ok' "$reports/relaunch-$attempt.txt"
    sleep 2
    adb shell pidof com.minddeck.nativeapp
  done
  adb exec-out screencap -p > "$reports/relaunch.png"
fi
adb logcat -d -b crash > "$reports/crash-log.txt"
if grep -q 'Process: com.minddeck.nativeapp,' "$reports/crash-log.txt"; then
  echo 'Native app crash found'; exit 1
fi
