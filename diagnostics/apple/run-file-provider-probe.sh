#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
probe_root="${RUNNER_TEMP:-/tmp}/montage-native-probe"
out="$probe_root/evidence"
mkdir -p "$out"
test "$(uname -m)" = arm64
xcodebuild -version > "$out/xcode-version.txt"
sw_vers > "$out/host-version.txt"
xcrun simctl list devices available -j > "$out/available-devices.json"
read -r runtime device_type < <(python3 - "$out/available-devices.json" <<'PY'
import json,sys
devices=json.load(open(sys.argv[1]))['devices']
phones=[(r,d) for r in sorted(devices,reverse=True) if 'iOS' in r for d in devices[r] if 'iPhone' in d['name']]
r,d=phones[0]
print(r,d['deviceTypeIdentifier'])
PY
)
device=$(xcrun simctl create "Montage native Files probe ${GITHUB_RUN_ID:-local}" "$device_type" "$runtime")
trap 'xcrun simctl shutdown "$device" >/dev/null 2>&1 || true' EXIT
python3 - "$device" "$runtime" "$device_type" "$out/simulator.json" <<'PY'
import json,sys
from pathlib import Path
Path(sys.argv[4]).write_text(json.dumps({'id':sys.argv[1],'runtime':sys.argv[2],'device_type':sys.argv[3],
    'fresh_device':True,'reset_between_cancel_and_reopen':False,'kotlin_product_built':False},indent=2)+'\n')
PY
xcrun simctl boot "$device"
xcrun simctl bootstatus "$device" -b
python3 "$repo/diagnostics/apple/make-probe-project.py" "$probe_root"
plutil -lint "$probe_root/probe.xcodeproj/project.pbxproj"
xcodebuild build-for-testing -project "$probe_root/probe.xcodeproj" -scheme probe \
  -destination "platform=iOS Simulator,id=$device" -destination-timeout 120 \
  -derivedDataPath "$probe_root/DerivedData" CODE_SIGNING_ALLOWED=NO \
  2>&1 | tee "$out/build.log"
# The full suite launches the control as a separate app, not XCTest's injected target.
xcrun simctl install "$device" "$probe_root/DerivedData/Build/Products/Debug-iphonesimulator/MontageFixtureReader.app"
source_app="$probe_root/MontageFixtureSource.app"
mkdir -p "$source_app/Fixtures"
cp "$repo/kmp/ios-app/ui-tests/fixture-source/Info.plist" "$source_app/Info.plist"
cp "$repo/kmp/test-data/import-smoke.mp4" "$source_app/Fixtures/Montage-import-smoke.mp4"
cp "$repo/kmp/test-data/preview-smoke.mp4" "$source_app/Fixtures/Montage-preview-smoke.mp4"
printf 'Invalid video fixture\n' > "$source_app/Fixtures/Montage-preview-invalid.mp4"
sdk=$(xcrun --sdk iphonesimulator --show-sdk-path)
xcrun swiftc -parse-as-library -target arm64-apple-ios15.0-simulator -sdk "$sdk" -framework UIKit \
  "$repo/kmp/ios-app/ui-tests/fixture-source/FixtureSource.swift" -o "$source_app/MontageFixtureSource"
codesign --force --sign - --timestamp=none "$source_app"
xcrun simctl install "$device" "$source_app"
xcrun simctl launch "$device" dev.montage.fixture-source
source_data=$(xcrun simctl get_app_container "$device" dev.montage.fixture-source data)
python3 - "$source_data" "$out" "$repo" <<'PY'
import hashlib,json,sys,time
from pathlib import Path
root,out,repo=map(Path,sys.argv[1:])
p=root/'Library/Caches/montage-fixtures.json'
for _ in range(120):
    if p.exists():break
    time.sleep(1)
creation=json.loads(p.read_text());assert creation['status']=='ready',creation
expected={'Montage-import-smoke.mp4':(repo/'kmp/test-data/import-smoke.mp4').read_bytes(),
          'Montage-preview-smoke.mp4':(repo/'kmp/test-data/preview-smoke.mp4').read_bytes(),
          'Montage-preview-invalid.mp4':b'Invalid video fixture\n'}
records={}
for name,b in expected.items():
    f=root/'Documents'/name;assert f.read_bytes()==b
    s=f.stat();records[name]={'sha256':hashlib.sha256(b).hexdigest(),'inode':s.st_ino,'device':s.st_dev,
        'uid':s.st_uid,'gid':s.st_gid,'mode':oct(s.st_mode),'size':s.st_size,'path':str(f)}
(out/'source-before.json').write_text(json.dumps({'creation':creation,'files':records},indent=2)+'\n')
PY
xcrun clang -fobjc-arc -framework Foundation "$repo/diagnostics/apple/file-reference-probe.m" -o "$probe_root/host-file-probe"
xcrun clang -fobjc-arc -target arm64-apple-ios15.0-simulator -isysroot "$sdk" -framework Foundation \
  "$repo/diagnostics/apple/file-reference-probe.m" -o "$probe_root/simulator-file-probe"
fixtures=("$source_data/Documents/Montage-import-smoke.mp4" "$source_data/Documents/Montage-preview-smoke.mp4" "$source_data/Documents/Montage-preview-invalid.mp4")
test_status=0
xcodebuild test-without-building -project "$probe_root/probe.xcodeproj" -scheme probe \
  -destination "platform=iOS Simulator,id=$device" -destination-timeout 120 \
  -only-testing:MontageUITests/DraftUiSmokeTests/testFileProviderNativeControl \
  -test-timeouts-enabled YES -default-test-execution-time-allowance 180 -maximum-test-execution-time-allowance 240 \
  -parallel-testing-enabled NO -derivedDataPath "$probe_root/DerivedData" -resultBundlePath "$probe_root/NativeProbe.xcresult" \
  CODE_SIGNING_ALLOWED=NO 2>&1 | tee "$out/test.log" || test_status=$?
xcrun simctl spawn "$device" log show --last 20m --style compact \
  --predicate 'subsystem CONTAINS[c] "FileProvider" OR subsystem CONTAINS[c] "DocumentManager" OR process == "MontageFixtureReader"' \
  > "$out/provider.log" || true
xcrun xcresulttool get test-results summary --path "$probe_root/NativeProbe.xcresult" > "$out/result.json"
xcrun xcresulttool export attachments --path "$probe_root/NativeProbe.xcresult" --output-path "$out/attachments"
reader_data=$(xcrun simctl get_app_container "$device" dev.montage.fixture-reader data)
if test -f "$reader_data/Library/Application Support/MontageProbe/native-picker-control.json"; then
  cp "$reader_data/Library/Application Support/MontageProbe/native-picker-control.json" "$out/native-picker-control.json"
fi
python3 "$repo/diagnostics/apple/collect-provider-state.py" "$(dirname "$(dirname "$(dirname "$(dirname "$source_data")")")")" "$out"
source_current=$(xcrun simctl get_app_container "$device" dev.montage.fixture-source data)
fixtures=("$source_current/Documents/Montage-import-smoke.mp4" "$source_current/Documents/Montage-preview-smoke.mp4" "$source_current/Documents/Montage-preview-invalid.mp4")
# Observe file-reference resolution only after the UI attempt, without warming its cold path.
"$probe_root/host-file-probe" "${fixtures[@]}" > "$out/host-file-references.json"
xcrun simctl spawn "$device" "$probe_root/simulator-file-probe" "${fixtures[@]}" > "$out/simulator-file-references.json"
python3 - "$source_current" "$out" "$source_data" <<'PY'
import hashlib,json,sys
from pathlib import Path
root,out,old_root=map(Path,sys.argv[1:]);before=json.loads((out/'source-before.json').read_text())
after={name:hashlib.sha256((root/'Documents'/name).read_bytes()).hexdigest() for name in before['files']}
assert after=={n:v['sha256'] for n,v in before['files'].items()}
(out/'source-after.json').write_text(json.dumps({'files':after,'source_preserved':True,
    'container_before':str(old_root),'container_after':str(root),'container_mapping_changed':old_root!=root},indent=2)+'\n')
PY
test "$test_status" -eq 0
python3 - "$out" <<'PY'
from pathlib import Path
import json,sys
p=Path(sys.argv[1]);r=json.loads((p/'result.json').read_text());assert r['passedTests']==r['totalTestCount']==1 and r['failedTests']==r['skippedTests']==0,r
c=json.loads((p/'native-picker-control.json').read_text());assert c['cancelled']==1 and len(c['reads'])==3 and not c['error'],c
print('Native-only cancellation/reopen and all three real source reads passed')
PY
