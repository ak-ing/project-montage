#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Drive the installed Android app through adb; choose coordinates from UI XML."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', default='emulator-5554')
args = parser.parse_args()
out = Path('build/ui-smoke/android')
out.mkdir(parents=True, exist_ok=True)
sequence = 0
launcher_recoveries = 0


def adb(*command, binary=False):
    return subprocess.check_output(['adb', '-s', args.serial, *command], text=not binary, timeout=45)


def tree():
    global sequence
    sequence += 1
    adb('shell', 'uiautomator', 'dump', '/sdcard/montage-ui.xml')
    xml = adb('exec-out', 'cat', '/sdcard/montage-ui.xml')
    (out / f'ui-{sequence:03}.xml').write_text(xml)
    return ET.fromstring(xml)


def find(tag=None, text=None, description=None):
    global launcher_recoveries
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        root = tree()
        if any(n.get('package') == 'android' and n.get('text') == "Pixel Launcher isn't responding" for n in root.iter('node')):
            assert launcher_recoveries == 0, 'Repeated system launcher ANR; emulator is unstable'
            close = next(n for n in root.iter('node') if n.get('resource-id') == 'android:id/aerr_close')
            screenshot('launcher-anr')
            tap_node(close)
            launcher_recoveries += 1
            continue
        nodes = [n for n in root.iter('node') if
                 (n.get('resource-id', '').split('/')[-1] == tag if tag else
                  n.get('content-desc') == description if description else n.get('text') == text)]
        if nodes:
            return nodes[0]
        time.sleep(1)
    raise AssertionError(f'Missing UI node tag={tag!r}, text={text!r}, description={description!r}')


def tap(tag=None, text=None, description=None):
    tap_node(find(tag, text, description))


def tap_node(node):
    assert node.get('enabled') == 'true', node.attrib
    x1, y1, x2, y2 = map(int, re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds')).groups())
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def screenshot(stage):
    (out / f'{stage}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


try:
    adb('logcat', '-c')
    adb('shell', 'am', 'force-stop', 'dev.montage.app')
    resolved = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', 'dev.montage.app').strip().splitlines()[-1]
    assert '/' in resolved, resolved
    adb('shell', 'am', 'start', '-W', '-n', resolved)
    find(None, 'No unsaved changes')
    find(None, '2 scenes · 10s visible · 1s transition buffer')
    assert len([n for n in tree().iter('node') if n.get('text') == 'Project Montage']) == 1, 'Duplicate native/Compose title bars'
    assert find('add-scene').get('enabled') == 'false'
    screenshot('initial')
    tap('project-title')
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    adb('shell', 'input', 'keyevent', *(['KEYCODE_DEL'] * 30))
    adb('shell', 'input', 'text', 'UI%ssmoke%stitle')
    assert 'UI smoke title' in find('project-title').get('text', '')
    tap('scene-description')
    adb('shell', 'input', 'text', 'Smoke%sscene')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    tap('add-scene')
    find(None, '3 scenes · 14s visible · 2s transition buffer')
    assert find('add-scene').get('enabled') == 'false'
    screenshot('added')
    tap('save-draft')
    find(None, 'No unsaved changes')
    fixture = Path('test-data/import-smoke.mp4')
    remote_fixture = '/sdcard/Download/Montage-import-smoke.mp4'
    adb('push', str(fixture), remote_fixture)
    adb('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file://' + remote_fixture)
    tap('import-video')
    find(description='Show roots')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    find(text='Import cancelled')
    assert find('save-draft').get('enabled') == 'false'
    tap('import-video')
    tap(description='Show roots')
    tap(text='Downloads')
    tap(text='Montage-import-smoke.mp4')
    find(text='Available on this device')
    find(text='Montage-import-smoke.mp4')
    tap('save-draft')
    find(text='No unsaved changes')
    screenshot('videos')
    # The managed copy must survive deletion of the selected source, and process death.
    adb('shell', 'rm', remote_fixture)
    adb('shell', 'am', 'force-stop', 'dev.montage.app')
    adb('shell', 'am', 'start', '-W', '-n', resolved)
    find(text='No unsaved changes')
    tap('video-tab')
    find(text='Available on this device')
    find(text='Montage-import-smoke.mp4')
    screenshot('videos-restored')
    document = json.loads(adb('exec-out', 'run-as', 'dev.montage.app', 'cat', 'files/workspace.json'))
    asset = document['videos'][0]
    imported = adb('exec-out', 'run-as', 'dev.montage.app', 'cat', f"files/media/{asset['id']}.media", binary=True)
    assert imported == fixture.read_bytes(), 'Imported video bytes changed'
    tap('remove-video-' + asset['id'])
    tap('save-draft')
    find(text='No unsaved changes')
    find(text='No videos imported')
    assert not json.loads(adb('exec-out', 'run-as', 'dev.montage.app', 'cat', 'files/workspace.json'))['videos']
    assert not adb('exec-out', 'run-as', 'dev.montage.app', 'ls', 'files/media').strip()
    screenshot('videos-removed')
    preview_fixture = Path('test-data/preview-smoke.mp4')
    preview_source = '/sdcard/Download/Montage-preview-smoke.mp4'
    adb('push', str(preview_fixture), preview_source)
    adb('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file://' + preview_source)
    tap('import-video')
    tap(description='Show roots')
    tap(text='Downloads')
    tap(text='Montage-preview-smoke.mp4')
    find(text='Available on this device')
    preview_button = next(n for n in tree().iter('node') if n.get('resource-id', '').split('/')[-1].startswith('preview-video-'))
    tap_node(preview_button)
    metadata = find('video-metadata').get('text', '')
    assert '8000 ms' in metadata and '160 × 90' in metadata and '0°' in metadata and 'Audio' in metadata, metadata
    find('video-thumbnail')
    find(text='Paused')
    screenshot('preview-ready')
    # Use the observed button bounds while video is running, so UI-tree idle waits do not delay Pause.
    pause_button = find('pause-video')
    tap('play-video')
    time.sleep(1)
    tap_node(pause_button)
    find(text='Paused')
    paused_position = int(find('video-position').get('text').split('/')[0].strip())
    assert paused_position >= 500, paused_position
    slider = find('video-seek')
    x1, y1, x2, y2 = map(int, re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', slider.get('bounds')).groups())
    adb('shell', 'input', 'swipe', str(x1 + (x2 - x1) // 4), str((y1 + y2) // 2), str(x1 + (x2 - x1) * 3 // 4), str((y1 + y2) // 2), '400')
    seek_position = int(find('video-position').get('text').split('/')[0].strip())
    assert 4500 <= seek_position <= 7500, seek_position
    screenshot('preview-seek')
    tap('preview-back')
    find(text='Unsaved changes')
    find(text='Montage-preview-smoke.mp4')
    tap('save-draft')
    find(text='No unsaved changes')
    adb('shell', 'rm', preview_source)
    adb('shell', 'am', 'force-stop', 'dev.montage.app')
    adb('shell', 'am', 'start', '-W', '-n', resolved)
    find(text='No unsaved changes')
    tap('video-tab')
    find(text='Available on this device')
    preview_document = json.loads(adb('exec-out', 'run-as', 'dev.montage.app', 'cat', 'files/workspace.json'))
    preview_asset = preview_document['videos'][0]
    tap('preview-video-' + preview_asset['id'])
    assert '8000 ms' in find('video-metadata').get('text', '')
    find(text='Paused')
    screenshot('preview-restored')
    tap('preview-back')
    # A same-size damaged file must fail decoding; replacing it permits retry without changing the project.
    managed_preview = f"files/media/{preview_asset['id']}.media"
    adb('shell', 'run-as', 'dev.montage.app', 'dd', 'if=/dev/zero', 'of=' + managed_preview, 'bs=' + str(preview_fixture.stat().st_size), 'count=1')
    tap('preview-video-' + preview_asset['id'])
    find('preview-error')
    screenshot('preview-error')
    adb('push', str(preview_fixture), '/data/local/tmp/montage-preview-fixture.mp4')
    adb('shell', 'run-as', 'dev.montage.app', 'cp', '/data/local/tmp/montage-preview-fixture.mp4', managed_preview)
    tap('preview-retry')
    assert '8000 ms' in find('video-metadata').get('text', '')
    find(text='Paused')
    tap('preview-back')
    restored_copy = adb('exec-out', 'run-as', 'dev.montage.app', 'cat', managed_preview, binary=True)
    assert restored_copy == preview_fixture.read_bytes()
    assert json.loads(adb('exec-out', 'run-as', 'dev.montage.app', 'cat', 'files/workspace.json')) == preview_document
    tap('remove-video-' + preview_asset['id'])
    tap('save-draft')
    find(text='No unsaved changes')
    find(text='No videos imported')
    assert not adb('exec-out', 'run-as', 'dev.montage.app', 'ls', 'files/media').strip()
    adb('shell', 'am', 'force-stop', 'dev.montage.app')
    adb('shell', 'am', 'start', '-W', '-n', resolved)
    find(None, 'No unsaved changes')
    find(None, '3 scenes · 14s visible · 2s transition buffer')
    assert 'UI smoke title' in find('project-title').get('text', '')
    screenshot('restored')
    tap('reset-sample')
    find(None, '2 scenes · 10s visible · 1s transition buffer')
    assert 'My first Montage' in find('project-title').get('text', '')
    assert find('add-scene').get('enabled') == 'false'
    screenshot('reset')
    tap('save-draft')
    find(None, 'No unsaved changes')
    assert adb('shell', 'pidof', 'dev.montage.app').strip()
    crashes = adb('logcat', '-d', '-b', 'crash')
    assert 'dev.montage.app' not in crashes, crashes
    application_anrs = re.findall(r'ANR in dev\.montage\.app\b.*', adb('logcat', '-d'))
    assert not application_anrs, application_anrs
    (out / 'result.json').write_text(json.dumps({'status': 'passed', 'flow': 'draft recovery; native picker cancel/import; independent copy; metadata/thumbnail/play/pause/seek; preview restart; corruption/retry; remove/cleanup', 'serial': args.serial, 'fixture_sha256': hashlib.sha256(imported).hexdigest(), 'imported_bytes': len(imported), 'managed_cleanup_empty': True, 'system_launcher_recoveries': launcher_recoveries, 'preview': {'fixture_sha256': hashlib.sha256(restored_copy).hexdigest(), 'metadata': metadata, 'paused_position_ms': paused_position, 'seek_position_ms': seek_position, 'restart_recovery': True, 'corruption_and_retry': True}}, indent=2))
    print('Android UI smoke passed: draft recovery; native picker cancel/import, independent copy, restart and removal')
finally:
    (out / 'logcat.txt').write_text(adb('logcat', '-d'))
