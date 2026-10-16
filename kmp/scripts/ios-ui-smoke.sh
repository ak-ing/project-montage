#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
mkdir -p build/ui-smoke/ios
simulator_id=$(xcrun simctl list devices available -j | python3 -c '
import json,sys
runtimes=json.load(sys.stdin)["devices"]
phones=[x for runtime in sorted(runtimes, reverse=True) if "iOS" in runtime for x in runtimes[runtime] if "iPhone" in x["name"]]
print(next((x["udid"] for x in phones if x["state"] == "Booted"), phones[0]["udid"]))')
xcrun simctl boot "$simulator_id" || true
xcrun simctl bootstatus "$simulator_id" -b
# Build once, then seed the application sandbox before test-without-building installs/runs tests.
xcodebuild build-for-testing -project ios-app/module.xcodeproj -scheme app \
  -destination "platform=iOS Simulator,id=$simulator_id" \
  -destination-timeout 120 -parallel-testing-enabled NO \
  -derivedDataPath build/ui-smoke/ios/DerivedData \
  CODE_SIGNING_ALLOWED=NO 2>&1 | tee build/ui-smoke/ios/xcodebuild.log
# Seed an independent test installation's Files folder with our generated 1-second fixture.
app_path="$PWD/build/ui-smoke/ios/DerivedData/Build/Products/Debug-iphonesimulator/ios-app.app"
test -d "$app_path"
xcrun simctl install "$simulator_id" "$app_path"
app_data=$(xcrun simctl get_app_container "$simulator_id" dev.montage.app data)
mkdir -p "$app_data/Documents"
cp test-data/import-smoke.mp4 "$app_data/Documents/Montage-import-smoke.mp4"
cp test-data/preview-smoke.mp4 "$app_data/Documents/Montage-preview-smoke.mp4"
printf 'Invalid video fixture\n' > "$app_data/Documents/Montage-preview-invalid.mp4"
xcode_status=0
xcodebuild test-without-building -project ios-app/module.xcodeproj -scheme app \
  -destination "platform=iOS Simulator,id=$simulator_id" \
  -destination-timeout 120 -test-timeouts-enabled YES \
  -default-test-execution-time-allowance 180 -maximum-test-execution-time-allowance 240 \
  -parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 \
  -resultBundlePath build/ui-smoke/ios/DraftUiSmoke.xcresult \
  -derivedDataPath build/ui-smoke/ios/DerivedData \
  CODE_SIGNING_ALLOWED=NO 2>&1 | tee -a build/ui-smoke/ios/xcodebuild.log || xcode_status=$?
xcrun simctl spawn "$simulator_id" log show --last 30m --style compact \
  --predicate 'process == "ios-app" AND eventMessage BEGINSWITH "Montage video import:"' \
  > build/ui-smoke/ios/import-stages.log || true
xcrun simctl spawn "$simulator_id" log show --last 30m --style compact \
  --predicate 'process == "ios-app" AND eventMessage BEGINSWITH "Montage video preview:"' \
  > build/ui-smoke/ios/preview-stages.log || true
xcrun xcresulttool get test-results summary --path build/ui-smoke/ios/DraftUiSmoke.xcresult > build/ui-smoke/ios/result.json
xcrun xcresulttool export attachments --path build/ui-smoke/ios/DraftUiSmoke.xcresult --output-path build/ui-smoke/ios/attachments
test "$xcode_status" -eq 0
app_data=$(xcrun simctl get_app_container "$simulator_id" dev.montage.app data)
python3 - "$app_data" <<'PY'
from pathlib import Path
import hashlib, json, sys
root=Path(sys.argv[1])
original=root/'Documents/Montage-import-smoke.mp4'
fixture=Path('test-data/import-smoke.mp4')
assert original.read_bytes()==fixture.read_bytes(), 'Import/removal changed the original test video'
preview=Path('test-data/preview-smoke.mp4')
assert (root/'Documents/Montage-preview-smoke.mp4').read_bytes()==preview.read_bytes()
assert (root/'Documents/Montage-preview-invalid.mp4').read_bytes()==b'Invalid video fixture\n'
workspace=root/'Library/Application Support/ProjectMontage/workspace.json'
assert not json.loads(workspace.read_text())['videos']
media=workspace.parent/'media'
assert not list(media.iterdir()), 'Managed media was not removed after successful save'
assert not list((root/'tmp/ProjectMontagePreview').glob('*')), 'Preview aliases were not released'
Path('build/ui-smoke/ios/storage-result.json').write_text(json.dumps({'source_preserved':True,'managed_cleanup_empty':True,'preview_cache_cleanup_empty':True,'fixture_sha256':hashlib.sha256(fixture.read_bytes()).hexdigest(),'preview_fixture_sha256':hashlib.sha256(preview.read_bytes()).hexdigest()},indent=2)+'\n')
PY
python3 - <<'PY'
import json
p=json.load(open('build/ui-smoke/ios/result.json'))
assert p['passedTests'] == p['totalTestCount'] == 3 and p['failedTests'] == 0 and p['skippedTests'] == 0, p
print('iOS UI smoke passed: draft/import recovery, real video metadata/thumbnail/play/pause/seek/restart and corrupt-file handling')
PY
