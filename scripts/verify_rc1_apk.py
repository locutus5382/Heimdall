#!/usr/bin/env python3
"""Verify an RC1 APK against its exact-byte CI checksum record.

Usage: python3 scripts/verify_rc1_apk.py path/to/app.apk path/to/heimdall-apk-sha256.txt --commit FULL_SHA
"""
import argparse
import hashlib
import re
from pathlib import Path

SHA = re.compile(r"[0-9a-f]{64}\Z")
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
EXPECTED_KEYS = {"source_commit", "apk_filename", "apk_sha256"}


def verify(apk: Path, manifest: Path, expected_commit: str | None = None) -> str:
    if not apk.is_file() or not manifest.is_file():
        raise ValueError("APK or checksum record does not exist")
    entries = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if "=" not in line:
            raise ValueError("Invalid checksum record line")
        key, value = line.split("=", 1)
        if key in entries or key not in EXPECTED_KEYS or not value:
            raise ValueError("Duplicate, unknown or empty checksum record field")
        entries[key] = value
    if entries.keys() != EXPECTED_KEYS:
        raise ValueError("Incomplete checksum record")
    if not COMMIT.fullmatch(entries["source_commit"]):
        raise ValueError("Invalid source commit")
    if not SHA.fullmatch(entries["apk_sha256"]):
        raise ValueError("Invalid SHA-256")
    if apk.name != entries["apk_filename"]:
        raise ValueError("APK filename does not match checksum record")
    if expected_commit is not None and entries["source_commit"] != expected_commit:
        raise ValueError("Source commit does not match requested build")
    digest = hashlib.file_digest(apk.open("rb"), "sha256").hexdigest()
    if digest != entries["apk_sha256"]:
        raise ValueError("APK SHA-256 mismatch: do not install")
    return digest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("checksum_record", type=Path)
    parser.add_argument("--commit", help="Expected full 40-character source commit")
    args = parser.parse_args()
    try:
        digest = verify(args.apk, args.checksum_record, args.commit)
    except (OSError, ValueError) as exc:
        parser.exit(1, f"FAIL: {exc}\n")
    print(f"PASS: {args.apk.name} SHA-256 {digest}")


if __name__ == "__main__":
    main()
