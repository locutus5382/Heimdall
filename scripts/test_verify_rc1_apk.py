"""Offline regression tests for exact-byte RC1 APK integrity validation."""
import hashlib
import tempfile
import unittest
from pathlib import Path

from scripts.verify_rc1_apk import verify

COMMIT = "a" * 40


class VerifyApkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / "app-debug.apk"
        self.record = Path(self.temp.name) / "heimdall-apk-sha256.txt"
        self.apk.write_bytes(b"test-apk-bytes")
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.write_record()

    def write_record(self, **changes):
        values = dict(source_commit=COMMIT, apk_filename=self.apk.name, apk_sha256=self.digest)
        values.update(changes)
        self.record.write_text("".join(f"{k}={v}\n" for k, v in values.items()))

    def test_matching_bytes_and_commit(self):
        self.assertEqual(verify(self.apk, self.record, COMMIT), self.digest)

    def test_modified_apk_rejected(self):
        self.apk.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "mismatch"):
            verify(self.apk, self.record, COMMIT)

    def test_wrong_commit_rejected(self):
        with self.assertRaisesRegex(ValueError, "Source commit"):
            verify(self.apk, self.record, "b" * 40)

    def test_wrong_filename_rejected(self):
        self.write_record(apk_filename="other.apk")
        with self.assertRaisesRegex(ValueError, "filename"):
            verify(self.apk, self.record)

    def test_duplicate_field_rejected(self):
        with self.record.open("a") as output:
            output.write(f"apk_sha256={self.digest}\n")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            verify(self.apk, self.record)

    def test_missing_field_rejected(self):
        self.record.write_text(f"source_commit={COMMIT}\n")
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            verify(self.apk, self.record)


if __name__ == "__main__":
    unittest.main()
