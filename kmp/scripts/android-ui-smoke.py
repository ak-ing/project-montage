#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Drive the installed Android app through adb; choose coordinates from UI XML."""
import argparse
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


def adb(*command, binary=False):
    return subprocess.check_output(['adb', '-s', args.serial, *command], text=not binary, timeout=45)


def tree():
    global sequence
    sequence += 1
    adb('shell', 'uiautomator', 'dump', '/sdcard/montage-ui.xml')
    xml = adb('exec-out', 'cat', '/sdcard/montage-ui.xml')
    (out / f'ui-{sequence:03}.xml').write_text(xml)
    return ET.fromstring(xml)


def find(tag, text=None):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        root = tree()
        nodes = [n for n in root.iter('node') if
                 (n.get('resource-id', '').split('/')[-1] == tag if tag else n.get('text') == text)]
        if nodes:
            return nodes[0]
        time.sleep(1)
    raise AssertionError(f'Missing UI node tag={tag!r}, text={text!r}')


def tap(tag):
    node = find(tag)
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
    tap('reset-sample')
    find(None, '2 scenes · 10s visible · 1s transition buffer')
    assert 'My first Montage' in find('project-title').get('text', '')
    assert find('add-scene').get('enabled') == 'false'
    screenshot('reset')
    assert adb('shell', 'pidof', 'dev.montage.app').strip()
    crashes = adb('logcat', '-d', '-b', 'crash')
    assert 'dev.montage.app' not in crashes, crashes
    (out / 'result.json').write_text(json.dumps({'status': 'passed', 'flow': 'initial, edit title, append scene, reset', 'serial': args.serial}, indent=2))
    print('Android UI smoke passed: launch, edit, append, reset; no app crash')
finally:
    (out / 'logcat.txt').write_text(adb('logcat', '-d'))
