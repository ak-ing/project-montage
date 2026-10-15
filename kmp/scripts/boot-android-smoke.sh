#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
mkdir -p build/ui-smoke/android
sdkmanager 'system-images;android-35;google_apis;x86_64' 'emulator'
printf 'no\n' | avdmanager create avd --force --name montage-smoke --device pixel_2 --package 'system-images;android-35;google_apis;x86_64'
"$ANDROID_HOME/emulator/emulator" -avd montage-smoke -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on -memory 2048 > build/ui-smoke/android/emulator.log 2>&1 &
python3 - <<'PY'
import subprocess, time
end = time.monotonic() + 300
while time.monotonic() < end:
    p = subprocess.run(['adb', '-s', 'emulator-5554', 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True, timeout=15)
    if p.stdout.strip() == '1':
        break
    time.sleep(3)
else:
    raise SystemExit('Emulator did not boot in 300 seconds')
PY
adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP
adb -s emulator-5554 shell input keyevent KEYCODE_MENU
adb -s emulator-5554 shell settings put global window_animation_scale 0
adb -s emulator-5554 shell settings put global transition_animation_scale 0
adb -s emulator-5554 shell settings put global animator_duration_scale 0
adb -s emulator-5554 install -r build/tasks/_android-app_buildAndroidDebug/*.apk
python3 scripts/android-ui-smoke.py
