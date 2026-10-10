#!/usr/bin/env python3
"""Counts tests from the Surefire and Failsafe XML reports of the last Maven run.

    python3 scripts/metrics/conta_test.py        ->  test=228 falliti=0 errori=0 saltati=1
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")


def main():
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    files = glob.glob(os.path.join(ROOT, "target", "surefire-reports", "TEST-*.xml"))
    files += glob.glob(os.path.join(ROOT, "target", "failsafe-reports", "TEST-*.xml"))
    if not files:
        print("nessun report di test: lancia prima ./mvnw verify", file=sys.stderr)
        return 1
    for path in files:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0) or 0)
    print(f"test={totals['tests']} falliti={totals['failures']} errori={totals['errors']} saltati={totals['skipped']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
