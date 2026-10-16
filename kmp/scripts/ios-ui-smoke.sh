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
xcode_status=0
xcodebuild test-without-building -project ios-app/module.xcodeproj -scheme app \
  -destination "platform=iOS Simulator,id=$simulator_id" \
  -destination-timeout 120 -test-timeouts-enabled YES \
  -default-test-execution-time-allowance 120 -maximum-test-execution-time-allowance 180 \
  -parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 \
  -resultBundlePath build/ui-smoke/ios/DraftUiSmoke.xcresult \
  -derivedDataPath build/ui-smoke/ios/DerivedData \
  CODE_SIGNING_ALLOWED=NO 2>&1 | tee -a build/ui-smoke/ios/xcodebuild.log || xcode_status=$?
xcrun xcresulttool get test-results summary --path build/ui-smoke/ios/DraftUiSmoke.xcresult > build/ui-smoke/ios/result.json
xcrun xcresulttool export attachments --path build/ui-smoke/ios/DraftUiSmoke.xcresult --output-path build/ui-smoke/ios/attachments
test "$xcode_status" -eq 0
python3 - <<'PY'
import json
p=json.load(open('build/ui-smoke/ios/result.json'))
assert p['passedTests'] == p['totalTestCount'] == 2 and p['failedTests'] == 0 and p['skippedTests'] == 0, p
print('iOS UI smoke passed: draft recovery and native video import/cancel/recovery/removal')
PY
