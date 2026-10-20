#!/usr/bin/env python3
"""Provision a user-owned key over adb stdin; never put the key in argv, logs, or APKs."""
import argparse
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--key-file", required=True, type=Path)
args = parser.parse_args()
key = args.key_file.read_bytes().strip()
if not key.startswith(b"rbk_") or b"\n" in key:
    raise SystemExit("Expected one Rebyte API key.")
adb = ["adb", "-s", args.serial]
subprocess.run(adb + ["shell", "run-as", "ai.rebyte.mobile", "mkdir", "-p", "no_backup"], check=True, capture_output=True)
result = subprocess.run(
    adb + ["shell", "run-as ai.rebyte.mobile sh -c 'umask 077; cat > no_backup/debug-api-key'"],
    input=key, capture_output=True,
)
if result.returncode:
    raise SystemExit("Provisioning failed. This requires an installed debug APK.")
subprocess.run(adb + ["shell", "am", "force-stop", "ai.rebyte.mobile"], check=True, capture_output=True)
subprocess.run(adb + ["shell", "am", "start", "-n", "ai.rebyte.mobile/.MainActivity"], check=True, capture_output=True)
print("Key delivered privately. The app encrypts and deletes the import file on launch.")
