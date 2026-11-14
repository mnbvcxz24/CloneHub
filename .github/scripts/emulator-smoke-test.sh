#!/usr/bin/env bash
set +e
mkdir -p emulator-diagnostics

adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
install_status=$?
if [ "$install_status" -ne 0 ]; then
  adb logcat -d -v threadtime > emulator-diagnostics/install-failure-logcat.txt
  exit "$install_status"
fi

adb logcat -c
adb shell am force-stop com.moldyhub.clonehub
adb shell monkey -p com.moldyhub.clonehub 1
launch_status=$?
sleep 15

adb logcat -d -v threadtime > emulator-diagnostics/clonehub-startup-logcat.txt
adb shell dumpsys activity activities > emulator-diagnostics/activity-state.txt
adb shell pidof com.moldyhub.clonehub > emulator-diagnostics/app-pid.txt 2>/dev/null
adb shell dumpsys package com.moldyhub.clonehub > emulator-diagnostics/package-state.txt

if grep -E -q "FATAL EXCEPTION|Fatal signal|UnsatisfiedLinkError|NoClassDefFoundError|Process: com.moldyhub.clonehub" emulator-diagnostics/clonehub-startup-logcat.txt; then
  echo "CloneHub emitted a fatal startup error. See the uploaded logcat artifact."
  exit 1
fi

if [ "$launch_status" -ne 0 ] || [ ! -s emulator-diagnostics/app-pid.txt ]; then
  echo "CloneHub did not remain running after launch. See the uploaded diagnostics."
  exit 1
fi

echo "CloneHub remained running for the 15-second startup smoke test."
