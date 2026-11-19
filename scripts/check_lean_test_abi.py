#!/usr/bin/env python3
"""Fail before the emulator if R8 removed binary names needed by AndroidX Test.

The optimized application and instrumentation APK share a class loader. The
test APK links to original Kotlin coroutine API names; R8 must preserve those
names in the target app. Check class *definitions*, not just string references.
Only Python's standard library is required.
"""
import struct
import sys
import zipfile
from pathlib import Path


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def string_at(data: bytes, start: int) -> str:
    # DEX strings begin with a ULEB128 UTF-16 length.
    while data[start] & 0x80:
        start += 1
    start += 1
    end = data.index(b"\x00", start)
    return data[start:end].decode("utf-8", errors="replace")


def class_definitions(data: bytes) -> set[str]:
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a DEX image")
    nstrings, strings_off = u32(data, 56), u32(data, 60)
    ntypes, types_off = u32(data, 64), u32(data, 68)
    nclasses, classes_off = u32(data, 96), u32(data, 100)
    strings = [
        string_at(data, u32(data, strings_off + i * 4))
        for i in range(nstrings)
    ]
    types = [strings[u32(data, types_off + i * 4)] for i in range(ntypes)]
    return {
        types[u32(data, classes_off + i * 32)]
        for i in range(nclasses)
    }


def main() -> int:
    if len(sys.argv) < 3:
        print("Usage: check_lean_test_abi.py <app.apk> <fully.qualified.Class>...")
        return 2
    apk = Path(sys.argv[1])
    with zipfile.ZipFile(apk) as archive:
        dex_files = [
            name for name in archive.namelist()
            if name.startswith("classes") and name.endswith(".dex")
        ]
        if not dex_files:
            print(f"ERROR: No DEX classes found in {apk}")
            return 1
        classes: set[str] = set()
        for name in dex_files:
            classes.update(class_definitions(archive.read(name)))
    required = {"L" + name.replace(".", "/") + ";" for name in sys.argv[2:]}
    missing = sorted(required - classes)
    for desc in sorted(required):
        print(("MISSING" if desc in missing else "OK") + " " + desc)
    if missing:
        print("ERROR: optimized QA app's ABI is incompatible with test APK.")
        return 1
    print(f"Optimized QA class preflight passed ({len(required)} types; {len(classes)} classes).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
