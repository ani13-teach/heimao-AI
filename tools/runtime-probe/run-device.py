#!/usr/bin/env python3
"""Install/run only the independent probe on exactly one authorized ADB device."""
import argparse
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent
PACKAGE = 'me.rerere.heimao.runtimeprobe'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='C:/Android/Sdk/platform-tools/adb.exe')
    args = parser.parse_args()
    devices = subprocess.check_output([args.adb, 'devices'], text=True)
    entries = [line.split() for line in devices.splitlines()[1:] if line.strip()]
    if len(entries) != 1 or entries[0][1] != 'device':
        raise SystemExit('Connect exactly one USB-debugging-authorized device before running this probe.')
    serial = entries[0][0]
    adb = [args.adb, '-s', serial]
    apk = ROOT / 'build/Heimao-Runtime-Probe.apk'
    if not apk.is_file():
        raise SystemExit('Build the probe first: python build.py')
    subprocess.run(adb + ['install', '-r', str(apk)], check=True)
    subprocess.run(adb + ['shell', 'am', 'force-stop', PACKAGE], check=True)
    # Clear only the old probe result to avoid reporting a previous run as current.
    subprocess.run(adb + ['shell', 'run-as', PACKAGE, 'rm', '-f', 'files/probe-result.txt'], check=True)
    subprocess.run(adb + ['shell', 'am', 'start', '-W', '-n',
                          PACKAGE + '/.ProbeActivity'], check=True)
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        result = subprocess.run(adb + ['exec-out', 'run-as', PACKAGE, 'cat',
                                      'files/probe-result.txt'], capture_output=True)
        if (result.returncode == 0
                and b'=== runtimeprobe report ===' in result.stdout
                and b'=== end of report ===' in result.stdout):
            output = ROOT / 'build/device-result.txt'
            output.write_bytes(result.stdout)
            print(result.stdout.decode('utf-8', errors='replace'))
            print(f'Saved: {output}')
            return
        time.sleep(0.5)
    raise SystemExit('No report within 20 seconds. Inspect the probe UI; no result claimed.')


if __name__ == '__main__':
    main()
