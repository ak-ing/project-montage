#!/usr/bin/env python3
"""Reject empty, skipped, or failed test runs; support JVM XML and native events."""
# SPDX-License-Identifier: Apache-2.0
import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--xml-dir', type=Path)
    source.add_argument('--teamcity-log', type=Path)
    parser.add_argument('--min-tests', type=int, default=5)
    args = parser.parse_args()
    if args.min_tests < 1:
        parser.error('--min-tests must be positive')

    if args.xml_dir:
        reports = list(args.xml_dir.glob('TEST-*.xml'))
        if not reports:
            raise ValueError(f'No test reports in {args.xml_dir}')
        tests = failures = skipped = 0
        for report in reports:
            root = ET.parse(report).getroot()
            suites = [root] if root.tag == 'testsuite' else root.findall('.//testsuite')
            for suite in suites:
                tests += int(suite.attrib.get('tests', '0'))
                failures += int(suite.attrib.get('failures', '0')) + int(suite.attrib.get('errors', '0'))
                skipped += int(suite.attrib.get('skipped', '0'))
        finished = tests
    else:
        text = args.teamcity_log.read_text(encoding='utf-8')
        events = re.findall(r'##teamcity\[(testStarted|testFinished|testFailed|testIgnored)(?:\s|\])', text)
        tests = events.count('testStarted')
        finished = events.count('testFinished')
        failures = events.count('testFailed')
        skipped = events.count('testIgnored')

    if tests < args.min_tests or finished != tests or failures or skipped:
        raise ValueError(f'Invalid test run: started={tests}, finished={finished}, failed={failures}, skipped={skipped}; minimum={args.min_tests}')
    print(f'Verified {tests} tests, 0 failures, 0 skipped')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, ET.ParseError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
