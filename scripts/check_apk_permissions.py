#!/usr/bin/env python3
"""Ensure every assembled APK retains Heimdall's offline-only permission policy.

Read the *merged* binary AndroidManifest.xml with Android SDK's aapt. Grepping
source manifests misses transitively injected permissions and XML line wraps.
This check intentionally rejects unexpected permissions until explicitly reviewed.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REQUIRED = frozenset({
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.SYSTEM_ALERT_WINDOW",
})
# AndroidX Core adds a package-scoped signature permission when using
# ContextCompat-registered, non-exported dynamic receivers. This does not grant
# external networking or access to another application. It is version-dependent,
# so allow but do not require this exact generated name.
ALLOWED = REQUIRED | frozenset({
    "com.locutus5382.heimdall.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
})
PERMISSION = re.compile(r"uses-permission(?:-sdk-\d+)?:\s*name='([^']+)'")

def main() -> int:
    if len(sys.argv) < 3:
        print("Usage: check_apk_permissions.py <aapt> <apk> [<apk> ...]", file=sys.stderr)
        return 2
    aapt = Path(sys.argv[1])
    if not aapt.is_file():
        print(f"Android SDK aapt not found: {aapt}", file=sys.stderr)
        return 2
    failed = False
    for name in sys.argv[2:]:
        apk = Path(name)
        if not apk.is_file():
            print(f"Missing APK: {apk}", file=sys.stderr)
            failed = True
            continue
        result = subprocess.run(
            [str(aapt), "dump", "permissions", str(apk)],
            capture_output=True, text=True, check=False
        )
        if result.returncode:
            print(f"aapt failed for {apk}: {result.stderr}", file=sys.stderr)
            failed = True
            continue
        found = frozenset(PERMISSION.findall(result.stdout))
        # No permissions at all indicates a broken or unexpectedly changed parser.
        missing = REQUIRED - found
        unexpected = found - ALLOWED
        print(f"{apk}: {len(found)} merged permissions")
        for permission in sorted(found):
            print(f"  {permission}")
        if missing or unexpected:
            print(f"ERROR: {apk}", file=sys.stderr)
            if missing:
                print(f"  Missing declared permissions: {sorted(missing)}", file=sys.stderr)
            if unexpected:
                print(f"  Unexpected merged permissions: {sorted(unexpected)}", file=sys.stderr)
            failed = True
    return 1 if failed else 0

if __name__ == "__main__":
    raise SystemExit(main())
