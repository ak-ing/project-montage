#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate a native-only XCTest project reusing the exact existing UIKit control."""
from pathlib import Path
import json
import hashlib
import os
import plistlib
import sys
import xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[2]
root = Path(sys.argv[1]).resolve()
project = root / 'probe.xcodeproj'
project.mkdir(parents=True, exist_ok=True)
objects = {}

def add(isa, **values):
    identifier = f'{len(objects) + 1:024X}'
    objects[identifier] = dict(isa=isa, **values)
    return identifier

def config(name, **settings):
    return add('XCBuildConfiguration', name=name, buildSettings=dict(
        SDKROOT='iphonesimulator', IPHONEOS_DEPLOYMENT_TARGET='15.0', SWIFT_VERSION='5.0',
        TARGETED_DEVICE_FAMILY='1', CODE_SIGN_STYLE='Automatic', **settings))

def configs(**settings):
    return add('XCConfigurationList', buildConfigurations=[config(n, **settings) for n in ['Debug', 'Release']],
               defaultConfigurationIsVisible=0, defaultConfigurationName='Debug')

def phase(kind, files=()):
    return add(kind, buildActionMask=2147483647, files=list(files), runOnlyForDeploymentPostprocessing=0)

def source(path):
    ref = add('PBXFileReference', lastKnownFileType='sourcecode.swift', path=str(path), sourceTree='<absolute>')
    return ref, add('PBXBuildFile', fileRef=ref)

reader, reader_build = source(repo / 'kmp/ios-app/ui-tests/fixture-reader/FixtureReader.swift')
host, host_build = source(repo / 'diagnostics/apple/ProbeHost.swift')
test_source = repo / 'kmp/ios-app/ui-tests/DraftUiSmokeTests.swift'
test_text = test_source.read_text()
# Investigate remote Files nodes missing from queries but present in complete
# snapshots. Compare the public full-query path without changing test actions,
# assertions or fixtures; the optimization is not yet established as the cause.
query_test = root / 'DraftUiSmokeTests.swift'
query_text = test_text
for identifier in ('Cancel', 'Open', 'BackButton'):
    query_text = query_text.replace(f'app.buttons["{identifier}"].firstMatch',
        f'app.buttons.matching(identifier: "{identifier}").element(boundBy: 0)')
query_text = query_text.replace('.firstMatch', '.element(boundBy: 0)')
old_wait = '''            let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                folderTitle.exists || folder.exists || location.exists || parent.exists || browse.exists
            }, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed, app.debugDescription)'''
new_wait = '''            let ready = app.descendants(matching: .any).matching(NSPredicate(
                format: "identifier IN %@ OR label BEGINSWITH %@ OR label == %@ OR label == %@",
                ["Montage smoke fixtures, Actions Menu", "DOC.sidebar.item.On My iPhone", "BackButton"],
                "Montage smoke fixtures", "Browse", "On My iPhone"
            )).element(boundBy: 0)
            XCTAssertTrue(ready.waitForExistence(timeout: 60), app.debugDescription)'''
assert query_text.count(old_wait) == 1
query_text = query_text.replace(old_wait, new_wait)
query_text = query_text.replace('            if folderTitle.exists { return }', '''            if folderTitle.exists { return }
            let phoneRoot = app.navigationBars.staticTexts.matching(identifier: "On My iPhone").element(boundBy: 0)
            if phoneRoot.exists {
                XCTAssertTrue(folder.waitForExistence(timeout: 60), app.debugDescription)
                folder.tap()
                continue
            }''')
query_test.write_text(query_text)
test, test_build = source(query_test)
(root / 'query-mode.json').write_text(json.dumps(dict(mode='full-query', source=str(test_source),
    replacement='.firstMatch -> .element(boundBy: 0)',
    navigation_wait='one native OR query, 60s; wait for source folder at On My iPhone instead of navigating back during enumeration',
    source_sha256=hashlib.sha256(test_text.encode()).hexdigest(),
    generated_sha256=hashlib.sha256(query_text.encode()).hexdigest(),
    cancellation_callbacks_and_hash_assertions_changed=False,
    navigation_waits_changed=True, fixture_bytes_changed=False), indent=2) + '\n')

fixture_source = repo / 'kmp/ios-app/ui-tests/fixture-source/FixtureSource.swift'
fixture_text = fixture_source.read_text()
track = os.environ.get('MONTAGE_PROBE_TRACK_IDENTITIES', '0')
assert track in ('0', '1'), track
fixture_generated = fixture_text
if track == '1':
    assert fixture_text.count('                saved.append(name)') == 1
    fixture_generated = fixture_generated.replace('    private var saved: [String] = []',
        '    private var saved: [String] = []\n    private var documentIDs: [String: NSNumber] = [:]')
    fixture_generated = fixture_generated.replace('                saved.append(name)',
        '                documentIDs[name] = try prepareFixtureIdentity(destination)\n                saved.append(name)')
    fixture_generated = fixture_generated.replace('            "saved_documents": saved,',
        '            "saved_documents": saved,\n            "identity_preparation": "fchflags(UF_TRACKED) + fsync",\n            "document_ids": documentIDs,')
(root / 'FixtureSource.swift').write_text(fixture_generated)
(root / 'source-identity-mode.json').write_text(json.dumps(dict(
    tracked_and_synced=track == '1', original_sha256=hashlib.sha256(fixture_text.encode()).hexdigest(),
    generated_sha256=hashlib.sha256(fixture_generated.encode()).hexdigest(),
    helper='diagnostics/apple/PrepareFixtureIdentity.swift', payload_bytes_changed=False,
    reference='https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/stat.h'), indent=2) + '\n')
app_product = add('PBXFileReference', explicitFileType='wrapper.application', path='MontageFixtureReader.app', sourceTree='BUILT_PRODUCTS_DIR')
host_product = add('PBXFileReference', explicitFileType='wrapper.application', path='MontageProbeHost.app', sourceTree='BUILT_PRODUCTS_DIR')
test_product = add('PBXFileReference', explicitFileType='wrapper.cfbundle', path='MontageUITests.xctest', sourceTree='BUILT_PRODUCTS_DIR')
products = add('PBXGroup', children=[app_product, host_product, test_product], name='Products', sourceTree='<group>')
main = add('PBXGroup', children=[reader, host, test, products], sourceTree='<group>')
app = add('PBXNativeTarget', name='MontageFixtureReader', productName='MontageFixtureReader',
          productType='com.apple.product-type.application', productReference=app_product,
          buildConfigurationList=configs(PRODUCT_NAME='MontageFixtureReader', PRODUCT_BUNDLE_IDENTIFIER='dev.montage.fixture-reader',
                                        INFOPLIST_FILE=str(root / 'ReaderInfo.plist'), GENERATE_INFOPLIST_FILE='NO'),
          buildPhases=[phase('PBXSourcesBuildPhase', [reader_build]), phase('PBXFrameworksBuildPhase'), phase('PBXResourcesBuildPhase')],
          buildRules=[], dependencies=[])
host_app = add('PBXNativeTarget', name='MontageProbeHost', productName='MontageProbeHost',
          productType='com.apple.product-type.application', productReference=host_product,
          buildConfigurationList=configs(PRODUCT_NAME='MontageProbeHost', PRODUCT_BUNDLE_IDENTIFIER='dev.montage.probe-host',
                                        INFOPLIST_FILE=str(root / 'HostInfo.plist'), GENERATE_INFOPLIST_FILE='NO'),
          buildPhases=[phase('PBXSourcesBuildPhase', [host_build]), phase('PBXFrameworksBuildPhase'), phase('PBXResourcesBuildPhase')],
          buildRules=[], dependencies=[])
proxy = add('PBXContainerItemProxy', containerPortal='PENDING', proxyType=1, remoteGlobalIDString=host_app, remoteInfo='MontageProbeHost')
dependency = add('PBXTargetDependency', target=host_app, targetProxy=proxy)
reader_proxy = add('PBXContainerItemProxy', containerPortal='PENDING', proxyType=1, remoteGlobalIDString=app, remoteInfo='MontageFixtureReader')
reader_dependency = add('PBXTargetDependency', target=app, targetProxy=reader_proxy)
tests = add('PBXNativeTarget', name='MontageUITests', productName='MontageUITests',
            productType='com.apple.product-type.bundle.ui-testing', productReference=test_product,
            buildConfigurationList=configs(PRODUCT_NAME='MontageUITests', PRODUCT_BUNDLE_IDENTIFIER='dev.montage.fixture-reader.uitests',
                                          GENERATE_INFOPLIST_FILE='YES', TEST_TARGET_NAME='MontageProbeHost'),
            buildPhases=[phase('PBXSourcesBuildPhase', [test_build]), phase('PBXFrameworksBuildPhase'), phase('PBXResourcesBuildPhase')],
            buildRules=[], dependencies=[dependency, reader_dependency])
project_id = add('PBXProject', buildConfigurationList=configs(), compatibilityVersion='Xcode 14.0', developmentRegion='en',
                 knownRegions=['en', 'Base'], mainGroup=main, productRefGroup=products, projectDirPath='', projectRoot='', targets=[app, host_app, tests],
                 attributes={'LastUpgradeCheck': '2600', 'TargetAttributes': {tests: {'TestTargetID': host_app}}})
objects[proxy]['containerPortal'] = project_id
objects[reader_proxy]['containerPortal'] = project_id

def render(value):
    if isinstance(value, dict):
        return '{\n' + '\n'.join(json.dumps(k) + ' = ' + render(v) + ';' for k, v in value.items()) + '\n}'
    if isinstance(value, list):
        return '(' + ''.join(render(v) + ',' for v in value) + ')'
    return str(value) if isinstance(value, int) else json.dumps(value)

(project / 'project.pbxproj').write_text('// !$*UTF8*$!\n' + render(dict(archiveVersion=1, classes={}, objectVersion=56, objects=objects, rootObject=project_id)) + '\n')
with (repo / 'kmp/ios-app/ui-tests/fixture-source/Info.plist').open('rb') as f:
    info = plistlib.load(f)
info.update(CFBundleIdentifier='dev.montage.fixture-reader', CFBundleExecutable='MontageFixtureReader', CFBundleName='MontageFixtureReader',
            CFBundleDisplayName='Montage Files probe', UIFileSharingEnabled=False, LSSupportsOpeningDocumentsInPlace=False)
with (root / 'ReaderInfo.plist').open('wb') as f:
    plistlib.dump(info, f)
info.update(CFBundleIdentifier='dev.montage.probe-host', CFBundleExecutable='MontageProbeHost', CFBundleName='MontageProbeHost',
            CFBundleDisplayName='Montage probe host')
with (root / 'HostInfo.plist').open('wb') as f:
    plistlib.dump(info, f)
scheme = ET.parse(repo / 'kmp/ios-app/module.xcodeproj/xcshareddata/xcschemes/app.xcscheme')
for ref in scheme.iter('BuildableReference'):
    is_test = ref.get('BlueprintName') == 'MontageUITests'
    ref.set('BlueprintIdentifier', tests if is_test else host_app)
    ref.set('BlueprintName', 'MontageUITests' if is_test else 'MontageProbeHost')
    ref.set('BuildableName', 'MontageUITests.xctest' if is_test else 'MontageProbeHost.app')
    ref.set('ReferencedContainer', 'container:probe.xcodeproj')
directory = project / 'xcshareddata/xcschemes'
directory.mkdir(parents=True, exist_ok=True)
scheme.write(directory / 'probe.xcscheme', encoding='utf-8', xml_declaration=True)
print(project)
