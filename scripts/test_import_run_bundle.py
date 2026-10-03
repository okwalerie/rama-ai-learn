"""Bundle import rejects tampered, incomplete, or unsafe bundles."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tarfile
import tempfile
import unittest

from import_run_bundle import BundleError, import_bundle, main, read_archive, read_bundle, open_tar
import run_archives
from test_run_archives import PIN, RUN, make_output


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


def raw_tar(path, members, mode="w:gz"):
    """Tar arbitrary (name, bytes) members, duplicates included."""
    with tarfile.open(path, mode) as tar:
        for name, data in members:
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return path


def zst(path, members):
    raw_tar(path.with_suffix(".plain"), members, "w")
    subprocess.run(["zstd", "-q", "-f", "--rm", "-o", str(path), str(path.with_suffix(".plain"))], check=True)
    return path


def rewrite(src, dest, mutate):
    """Re-pack an archive after mutate(bundle, files) edits it in place."""
    with open_tar(src) as tar:
        files = {m.name: tar.extractfile(m).read() for m in tar.getmembers()}
    bundle = json.loads(files.pop("BUNDLE.json"))
    mutate(bundle, files)
    return zst(dest, [("BUNDLE.json", json.dumps(bundle).encode()), *files.items()])


def garbage(path):
    path.write_bytes(b"not zstd")
    return path


def relist(bundle, files):
    bundle["files"] = {k: hashlib.sha256(v).hexdigest() for k, v in files.items()}


class HardeningTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def legacy(self, members, listed):
        manifest = json.dumps({"files": listed}).encode()
        return raw_tar(self.root / "b.tar.gz", [("BUNDLE.json", manifest), *members])

    def test_duplicate_colliding_and_malformed_paths_are_rejected(self):
        h = hashlib.sha256(b"x").hexdigest()
        cases = {
            "duplicate path": ([("a.txt", b"x"), ("./a.txt", b"x")], {"a.txt": h}),
            "duplicate listed": ([("a.txt", b"x")], {"a.txt": h, "./a.txt": h}),
            "colliding paths": ([("A.txt", b"x"), ("a.txt", b"x")], {"A.txt": h, "a.txt": h}),
            "both file and directory": ([("x", b"x"), ("x/y", b"x")], {"x": h, "x/y": h}),
            "64 lowercase hex": ([("a.txt", b"x")], {"a.txt": h.upper()}),
            "unsafe path": ([("a\\..\\b", b"x")], {"a\\..\\b": h}),
            "invalid listed path": ([("a.txt", b"x")], {"a.txt": h, "BUNDLE.json": h}),
        }
        for message, (members, listed) in cases.items():
            with self.subTest(message=message), self.assertRaisesRegex(BundleError, message):
                read_bundle(self.legacy(members, listed))

    def test_legacy_bundle_listing_files_as_a_list_is_rejected(self):
        bundle = self.legacy([("a.txt", b"x")], ["a.txt"])
        with self.assertRaisesRegex(BundleError, "must map file paths to sha256"):
            read_bundle(bundle)


class ArchiveImportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        make_output(self.root / "out")
        rows = run_archives.write_archives(self.root / "out")
        self.results, self.transcripts = (r["path"] for r in rows)
        self.dest = self.root / "runs"
        self.dest.mkdir()
        self.bad = self.root / "bad"
        self.bad.mkdir()

    def snapshot(self):
        return {p.relative_to(self.dest): p.read_bytes() for p in sorted(self.dest.rglob("*")) if p.is_file()}

    def test_archive_identity_must_match_name_part_and_layout(self):
        def stray(b, f):
            f["transcripts/phase0.jsonl"] = b"smuggled"
            relist(b, f)
        def edited_manifest(b, f):
            f["manifest.json"] = b"{}"
            relist(b, f)
        cases = [
            ("does not match its BUNDLE.json identity",
             lambda: rewrite(self.results, self.bad / f"demo-claude-other.results.tar.zst", lambda b, f: None)),
            ("does not describe a transcripts run archive",
             lambda: rewrite(self.results, self.bad / f"{RUN}.transcripts.tar.zst", lambda b, f: None)),
            ("paths outside the results part",
             lambda: rewrite(self.results, self.bad / f"{RUN}.results.tar.zst", stray)),
            ("no valid run_id",
             lambda: rewrite(self.results, self.bad / "demo-claude-...results.tar.zst",
                             lambda b, f: b.update(run_id=".."))),
            ("zstd could not decompress", lambda: garbage(self.bad / f"{RUN}.results.tar.zst")),
        ]
        for message, build in cases:
            with self.subTest(message=message):
                path = build()
                with self.assertRaisesRegex(BundleError, message):
                    read_archive(path)
                path.unlink()
        path = rewrite(self.results, self.bad / f"{RUN}.results.tar.zst", edited_manifest)
        with self.assertRaisesRegex(BundleError, "differs from manifest_sha256"):
            import_bundle(path, self.dest)
        self.assertEqual(list(self.dest.iterdir()), [])

    def test_results_manifest_json_missing_or_mismatched_is_rejected(self):
        def dropped(b, f):
            del f["manifest.json"]
            relist(b, f)
        def edited(b, f):
            f["manifest.json"] = b"{}"
            relist(b, f)
        for message, mutate in [("results archive has no manifest.json", dropped),
                                ("manifest.json differs from manifest_sha256", edited)]:
            with self.subTest(message=message):
                path = rewrite(self.results, self.bad / f"{RUN}.results.tar.zst", mutate)
                with self.assertRaisesRegex(BundleError, message):
                    read_archive(path)
                with self.assertRaisesRegex(BundleError, message):
                    import_bundle(path, self.dest)
                err = io.StringIO()
                with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(main([str(path), "--dest", str(self.dest)]), 1)
                self.assertIn(f"REJECTED {path}: {message}", err.getvalue())
                self.assertEqual(list(self.dest.iterdir()), [])
                path.unlink()

    def test_transcripts_need_the_matching_imported_run(self):
        with self.assertRaisesRegex(BundleError, "import the results archive first"):
            import_bundle(self.transcripts, self.dest)
        import_bundle(self.results, self.dest)
        before = self.snapshot()
        def other_pin(b, f):
            b["pin"] = "d" * 40
        def unrecorded(b, f):
            f["transcripts/other.jsonl"] = b"{}"
            relist(b, f)
        def missing(b, f):
            del f["transcripts/phase0.jsonl"]
            relist(b, f)
        for message, mutate in [("identity differs from the run: pin", other_pin),
                                ("not a phase transcript recorded", unrecorded),
                                ("phase transcripts absent: phase0.jsonl", missing)]:
            with self.subTest(message=message):
                path = rewrite(self.transcripts, self.bad / f"{RUN}.transcripts.tar.zst", mutate)
                with self.assertRaisesRegex(BundleError, message):
                    import_bundle(path, self.dest)
                self.assertEqual(self.snapshot(), before)

    def test_merge_never_clobbers_and_is_idempotent_only_when_identical(self):
        target, _ = import_bundle(self.results, self.dest)
        (target / "logs").mkdir()
        (target / "logs/mine.txt").write_text("keep")
        before = self.snapshot()
        with self.assertRaisesRegex(BundleError, "would overwrite: logs"):
            import_bundle(self.transcripts, self.dest)
        self.assertEqual(self.snapshot(), before)
        (target / "logs/mine.txt").unlink()
        (target / "logs").rmdir()

        import_bundle(self.transcripts, self.dest)
        merged = self.snapshot()
        self.assertEqual(merged[Path(RUN, "transcripts/phase0.jsonl")],
                         (self.root / "out/transcripts/phase0.jsonl").read_bytes())
        import_bundle(self.transcripts, self.dest)
        self.assertEqual(self.snapshot(), merged)
        def relogged(b, f):
            f["logs/run.log"] = b"other\n"
            relist(b, f)
        path = rewrite(self.transcripts, self.bad / f"{RUN}.transcripts.tar.zst", relogged)
        with self.assertRaisesRegex(BundleError, "already holds different transcripts"):
            import_bundle(path, self.dest)
        self.assertEqual(self.snapshot(), merged)
        with self.assertRaisesRegex(BundleError, "already exists"):
            import_bundle(self.results, self.dest)

    def test_run_dir_and_private_log_are_owner_only_under_umask_022(self):
        def modes(run):
            return {rel: stat.S_IMODE((run / rel).stat().st_mode)
                    for rel in (".", "private.log", "manifest.json")}
        private = {".": 0o700, "private.log": 0o600, "manifest.json": 0o644}
        old_umask = os.umask(0o022)
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(main([str(self.results), "--dest", str(self.dest)]), 0)
            run = self.dest / RUN
            self.assertEqual(modes(run), private)
            # A run imported before this hardening is tightened by --force.
            run.chmod(0o755)
            (run / "private.log").chmod(0o644)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(main([str(self.results), "--dest", str(self.dest), "--force"]), 0)
            self.assertEqual(modes(run), private)
            import_bundle(self.transcripts, self.dest)
            self.assertEqual(modes(run), private)
            self.assertEqual([p.name for p in self.dest.iterdir()], [RUN])
        finally:
            os.umask(old_umask)

    def test_cli_imports_both_parts_in_dependency_order(self):
        self.assertEqual(main([str(self.transcripts), str(self.results), "--dest", str(self.dest)]), 0)
        bundle = json.loads((self.dest / RUN / "BUNDLE.json").read_text())
        self.assertEqual((bundle["pin"], bundle["part"]), (PIN, "results"))
        self.assertTrue((self.dest / RUN / "BUNDLE.transcripts.json").is_file())


if __name__ == "__main__":
    unittest.main()
