#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
mkdir -p build/ui-smoke/android
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'system-images;android-35;google_apis;x86_64' 'emulator'
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name montage-smoke --path "$PWD/build/android-smoke-avd" --device pixel_2 --package 'system-images;android-35;google_apis;x86_64'
# The current emulator defaults to 12 GB of userdata, exceeding free hosted disk
# after the toolchain build. This local-only draft needs a much smaller volume.
python3 - <<'PY'
from pathlib import Path
import re
p = Path('build/android-smoke-avd/config.ini')
s = p.read_text()
s = re.sub(r'^disk.dataPartition.size=.*\n?', '', s, flags=re.M)
p.write_text(s + '\ndisk.dataPartition.size=2048M\n')
PY
"$ANDROID_HOME/emulator/emulator" -avd montage-smoke -partition-size 2048 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on -memory 2048 > build/ui-smoke/android/emulator.log 2>&1 &
smoke_emulator_pid=$!
python3 - "$smoke_emulator_pid" <<'PY'
import os, subprocess, sys, time
from pathlib import Path
end = time.monotonic() + 300
while time.monotonic() < end:
    try:
        os.kill(int(sys.argv[1]), 0)
    except ProcessLookupError:
        raise SystemExit(Path('build/ui-smoke/android/emulator.log').read_text()[-4000:])
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
