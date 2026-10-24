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
# Install the app, but keep source fixtures in Files' provider-owned storage.
# Xcode may reinstall/migrate the target app's container before XCTest starts;
# its Documents URLs can otherwise retain stale FileProvider item identifiers.
app_path="$PWD/build/ui-smoke/ios/DerivedData/Build/Products/Debug-iphonesimulator/ios-app.app"
test -d "$app_path"
xcrun simctl install "$simulator_id" "$app_path"
app_data=$(xcrun simctl get_app_container "$simulator_id" dev.montage.app data)
if xcrun simctl get_app_container "$simulator_id" com.apple.DocumentsApp app >/dev/null 2>&1; then
  xcrun simctl launch "$simulator_id" com.apple.DocumentsApp
fi
fixture_sources=$(python3 - "$app_data" <<'PY'
from pathlib import Path
import hashlib, json, shutil, sys, time
device_data=Path(sys.argv[1]).parents[3]
for attempt in range(30):
    roots=list((device_data/'Containers/Shared/AppGroup').glob('*/File Provider Storage'))
    if len(roots)==1: break
    time.sleep(1)
assert len(roots)==1, f'Expected one initialized Files LocalStorage root, found {len(roots)}'
folder=roots[0]/'Montage smoke fixtures'
folder.mkdir(mode=0o755, exist_ok=True)
for name,source in [('Montage-import-smoke.mp4','test-data/import-smoke.mp4'),('Montage-preview-smoke.mp4','test-data/preview-smoke.mp4')]:
    shutil.copyfile(source,folder/name)
(folder/'Montage-preview-invalid.mp4').write_bytes(b'Invalid video fixture\n')
names=['Montage-import-smoke.mp4','Montage-preview-smoke.mp4','Montage-preview-invalid.mp4']
for name in names: (folder/name).chmod(0o644)
assert not folder.is_relative_to(Path(sys.argv[1]))
Path('build/ui-smoke/ios/fixture-source.json').write_text(json.dumps({
    'provider':'Files LocalStorage','folder':'On My iPhone/Montage smoke fixtures',
    'outside_target_app_container':True,
    'files':{name:hashlib.sha256((folder/name).read_bytes()).hexdigest() for name in names},
},indent=2)+'\n')
print(folder)
PY
)
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
  --predicate 'process == "ios-app" AND (eventMessage BEGINSWITH "Montage video import:" OR eventMessage BEGINSWITH "Montage video cleanup:")' \
  > build/ui-smoke/ios/import-stages.log || true
xcrun simctl spawn "$simulator_id" log show --last 30m --style compact \
  --predicate 'process == "ios-app" AND eventMessage BEGINSWITH "Montage video preview:"' \
  > build/ui-smoke/ios/preview-stages.log || true
xcrun simctl spawn "$simulator_id" log show --last 20m --style compact \
  --predicate '(process == "ios-app" OR subsystem CONTAINS[c] "DocumentManager" OR subsystem CONTAINS[c] "FileProvider") AND (messageType == 16 OR messageType == 17)' \
  > build/ui-smoke/ios/file-provider-errors.log || true
xcrun xcresulttool get test-results summary --path build/ui-smoke/ios/DraftUiSmoke.xcresult > build/ui-smoke/ios/result.json
xcrun xcresulttool export attachments --path build/ui-smoke/ios/DraftUiSmoke.xcresult --output-path build/ui-smoke/ios/attachments
app_data=$(xcrun simctl get_app_container "$simulator_id" dev.montage.app data)
python3 - "$app_data" "$fixture_sources" <<'PY'
from pathlib import Path
import hashlib, json, sys
root=Path(sys.argv[1])
sources=Path(sys.argv[2])
original=sources/'Montage-import-smoke.mp4'
fixture=Path('test-data/import-smoke.mp4')
preview=Path('test-data/preview-smoke.mp4')
source_preserved=(original.read_bytes()==fixture.read_bytes() and
    (sources/'Montage-preview-smoke.mp4').read_bytes()==preview.read_bytes() and
    (sources/'Montage-preview-invalid.mp4').read_bytes()==b'Invalid video fixture\n')
workspace=root/'Library/Application Support/ProjectMontage/workspace.json'
media=workspace.parent/'media'
def listing(folder):
    return [{'name':p.name,'is_file':p.is_file(),'is_symlink':p.is_symlink(),'bytes':p.lstat().st_size,'links':p.lstat().st_nlink} for p in folder.glob('*')]
remaining=listing(media)
aliases=listing(root/'tmp/ProjectMontagePreview')
retained=json.loads(workspace.read_text())['videos']
result={'source_preserved':source_preserved,'managed_cleanup_empty':not remaining,'preview_cache_cleanup_empty':not aliases,
    'source_location':'Files/On My iPhone/Montage smoke fixtures','sources_outside_app_container':not sources.is_relative_to(root),
    'managed_remaining':remaining,'preview_cache_remaining':aliases,'retained_video_count':len(retained),
    'fixture_sha256':hashlib.sha256(fixture.read_bytes()).hexdigest(),'preview_fixture_sha256':hashlib.sha256(preview.read_bytes()).hexdigest()}
Path('build/ui-smoke/ios/storage-result.json').write_text(json.dumps(result,indent=2)+'\n')
assert source_preserved, 'Import/removal changed an original test video'
assert result['sources_outside_app_container']
assert not retained
assert not remaining, f'Managed media was not removed after successful save: {remaining}'
assert not aliases, f'Preview aliases were not released: {aliases}'
PY
test "$xcode_status" -eq 0
python3 - <<'PY'
import json
p=json.load(open('build/ui-smoke/ios/result.json'))
assert p['passedTests'] == p['totalTestCount'] == 3 and p['failedTests'] == 0 and p['skippedTests'] == 0, p
print('iOS UI smoke passed: draft/import recovery, real video metadata/thumbnail/play/pause/seek/restart and corrupt-file handling')
PY
