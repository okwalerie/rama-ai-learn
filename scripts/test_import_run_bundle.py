"""Bundle import rejects tampered, incomplete, or unsafe bundles."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

from import_run_bundle import BundleError, import_bundle, read_bundle


def make_bundle(path, files, manifest_files=None, extra=None):
    manifest = {"challenge": "demo", "provider": "claude", "actual_model": "claude-opus-5-5",
                "verdicts": {"private": "PASS", "overall": "PASS"},
                "files": manifest_files if manifest_files is not None else
                {k: hashlib.sha256(v).hexdigest() for k, v in files.items()}}
    with tarfile.open(path, "w:gz") as tar:
        for name, data in {**files, "BUNDLE.json": json.dumps(manifest).encode(), **(extra or {})}.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return path


class ImportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.dest = self.root / "runs"
        self.dest.mkdir()
        self.files = {"implementations/demo/src/demo/module.clj": b"(ns demo.module)",
                      "reports/demo.md": b"Private: PASS"}

    def test_valid_bundle_is_unpacked_under_its_own_name(self):
        bundle = make_bundle(self.root / "demo-claude-r1.tar.gz", self.files)
        target, manifest = import_bundle(bundle, self.dest)
        self.assertEqual(target, self.dest / "demo-claude-r1")
        self.assertEqual((target / "reports/demo.md").read_bytes(), b"Private: PASS")
        self.assertEqual(manifest["actual_model"], "claude-opus-5-5")

    def test_hash_mismatch_is_rejected(self):
        listed = {k: hashlib.sha256(b"other").hexdigest() for k in self.files}
        bundle = make_bundle(self.root / "b.tar.gz", self.files, listed)
        with self.assertRaisesRegex(BundleError, "sha256 mismatch"):
            read_bundle(bundle)

    def test_unlisted_and_missing_files_are_rejected(self):
        bundle = make_bundle(self.root / "b.tar.gz", self.files, extra={"stray.txt": b"x"})
        with self.assertRaisesRegex(BundleError, "unlisted"):
            read_bundle(bundle)
        listed = {k: hashlib.sha256(v).hexdigest() for k, v in self.files.items()}
        listed["logs/gone.log"] = "0" * 64
        with self.assertRaisesRegex(BundleError, "absent"):
            read_bundle(make_bundle(self.root / "c.tar.gz", self.files, listed))

    def test_private_suites_credentials_and_traversal_are_rejected(self):
        for name in ("challenges/demo/test-private/x.clj", ".claude/.credentials.json",
                     "../escape.txt", "/abs.txt", "notes.enc"):
            with self.subTest(name=name):
                files = {**self.files, name: b"secret"}
                with self.assertRaises(BundleError):
                    read_bundle(make_bundle(self.root / "bad.tar.gz", files))

    def test_existing_import_is_not_overwritten_without_force(self):
        bundle = make_bundle(self.root / "demo-claude-r1.tar.gz", self.files)
        import_bundle(bundle, self.dest)
        with self.assertRaisesRegex(BundleError, "already exists"):
            import_bundle(bundle, self.dest)
        import_bundle(bundle, self.dest, force=True)


if __name__ == "__main__":
    unittest.main()
