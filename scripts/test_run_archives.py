"""Run archives: write -> upload -> fetch -> import round trip through a stub gh."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import textwrap
import unittest
from unittest.mock import patch

import fetch_runs
import import_run_bundle
import run_archives
from run_archives import ArchiveError

SCRIPTS = Path(__file__).resolve().parent
PIN = "c" * 40
RUN_ID = "2026-10-03-demo-r1"
RUN = f"demo-claude-{RUN_ID}"

# A local stand-in for gh: releases are directories under $FAKE_GH_STORE.
FAKE_GH = textwrap.dedent('''\
    #!/usr/bin/env python3
    import fnmatch, json, os, shutil, sys
    from pathlib import Path
    store = Path(os.environ["FAKE_GH_STORE"])
    args = sys.argv[1:]
    with open(store / "calls.log", "a") as log:
        log.write(" ".join(args[:2]) + "\\n")
    def opt(name):
        return args[args.index(name) + 1]
    def fail(message):
        print(message, file=sys.stderr)
        sys.exit(1)
    if args[:2] == ["repo", "view"]:
        visibility = os.environ.get("FAKE_GH_VISIBILITY", "PRIVATE")
        print(json.dumps({"isPrivate": visibility == "PRIVATE", "visibility": visibility}))
        sys.exit(0)
    release = store / opt("--repo").replace("/", "__") / args[2]
    if args[:2] == ["release", "view"]:
        if not release.is_dir():
            fail("release not found")
        print(json.dumps({"assets": [{"name": p.name, "size": p.stat().st_size, "state": "uploaded"}
                                     for p in sorted(release.iterdir())]}))
    elif args[:2] == ["release", "create"]:
        if os.environ.get("FAKE_GH_FAIL") == "create":
            fail("HTTP 403: create forbidden")
        release.mkdir(parents=True)
    elif args[:2] == ["release", "upload"]:
        if os.environ.get("FAKE_GH_FAIL") == "upload":
            fail("HTTP 502: upload failed")
        files = [Path(a) for a in args[3:] if a.endswith(".tar.zst")]
        if any((release / f.name).exists() for f in files):
            fail("asset already exists")
        for f in files:
            shutil.copy(f, release / f.name)
    elif args[:2] == ["release", "download"]:
        if not release.is_dir():
            fail("release not found")
        patterns = [args[i + 1] for i, a in enumerate(args) if a == "--pattern"]
        for p in release.iterdir():
            if any(fnmatch.fnmatch(p.name, pat) for pat in patterns):
                shutil.copy(p, Path(opt("--dir")) / p.name)
    else:
        fail("unsupported: " + " ".join(args))
''')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def tree_sha(files):
    entries = [f"{name}\0{sha(data)}" for name, data in sorted(files.items())]
    return sha("\n".join(entries).encode())


def make_output(output, implementation=None, transcript=None):
    """A completed orb-run output directory as collect_artifacts leaves it."""
    implementation = implementation or {"src/demo/module.clj": b"(ns demo.module)"}
    transcript = transcript or (json.dumps({"type": "system", "subtype": "init",
                                            "model": "claude-opus-5-5"}) + "\n").encode()
    output.mkdir(parents=True)
    private = b"evaluator only\n"
    (output / "run.private.log").write_bytes(private)
    (output / "transcripts").mkdir()
    (output / "transcripts/phase0.jsonl").write_bytes(transcript)
    for name, data in implementation.items():
        (output / "implementation" / name).parent.mkdir(parents=True, exist_ok=True)
        (output / "implementation" / name).write_bytes(data)
    (output / "implementation/.clj-kondo").mkdir()
    (output / "implementation/.clj-kondo/cache").write_bytes(b"ignored like the runner hash")
    manifest = {"run-id": RUN_ID, "started-at": "2026-10-03T10:00:00Z",
                "finished-at": "2026-10-03T11:00:00Z", "repo": {"head-sha": PIN},
                "requested": {"agent": "claude", "fast-model": "claude-sonnet-5-5", "fast-effort": "high",
                              "slow-model": "claude-opus-5-5", "slow-effort": "medium"},
                "evaluator-log": {"path": "run.private.log", "bytes": len(private), "sha256": sha(private)},
                "challenges": [{"name": "demo", "outcome": "pass", "private-status": "pass",
                                "implementation-sha256": tree_sha(implementation),
                                "phases": [{"phase-id": "0", "attempt": 1, "subsystem": None,
                                            "verdict": "pass",
                                            "transcript-path": "/w/transcripts/phase0.jsonl",
                                            "transcript-sha256": sha(transcript)}]}]}
    source = output / "run.manifest.json"
    source.write_text(json.dumps(manifest, indent=2))
    (output / "run.md").write_text("# report\n")
    payload = json.dumps({"manifest-sha256": sha(source.read_bytes())}).encode()
    with tarfile.open(output / "run.bundle.tar.gz", "w:gz") as tar:
        info = tarfile.TarInfo("run/BUNDLE.json")
        info.size = len(payload)
        tar.addfile(info, io.BytesIO(payload))
    (output / "LAUNCH.json").write_text(json.dumps({"pin": PIN, "challenge": "demo", "agent": "claude"}))
    for log in ("setup.log", "run.log", "reference.private.log"):
        (output / log).write_text(f"{log}\n")
    return manifest


class FakeGh:
    def __init__(self, root):
        self.store = root / "gh-store"
        self.store.mkdir()
        exe = root / "fake-gh"
        exe.write_text(FAKE_GH)
        exe.chmod(exe.stat().st_mode | stat.S_IEXEC)
        self.env = {"RUN_ARCHIVES_GH": str(exe), "FAKE_GH_STORE": str(self.store)}

    def calls(self):
        log = self.store / "calls.log"
        return log.read_text().splitlines() if log.exists() else []

    def release(self, tag="wave-3"):
        return self.store / run_archives.RUNS_REPO.replace("/", "__") / tag


class ArchiveTestCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.output = self.root / "orb-demo"
        self.manifest = make_output(self.output)
        self.gh = FakeGh(self.root)
        env = patch.dict(os.environ, self.gh.env)
        env.start()
        self.addCleanup(env.stop)
        self.runs = self.root / "runs"

    def quiet(self, fn, *args, **kwargs):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            result = fn(*args, **kwargs)
        return result, out.getvalue(), err.getvalue()


class WriteTests(ArchiveTestCase):
    def test_two_named_archives_with_consistent_bundles_and_sizes(self):
        rows = run_archives.write_archives(self.output, created_at="2026-10-03T11:05:00+00:00")
        self.assertEqual([r["path"].name for r in rows],
                         [f"{RUN}.results.tar.zst", f"{RUN}.transcripts.tar.zst"])
        results, rfiles = import_run_bundle.read_archive(rows[0]["path"])
        transcripts, tfiles = import_run_bundle.read_archive(rows[1]["path"])
        self.assertEqual(sorted(rfiles), ["implementation/src/demo/module.clj", "launch.json",
                                          "manifest.json", "private.log", "report.md", "run-bundle.tar.gz"])
        self.assertEqual(sorted(tfiles), ["logs/reference.private.log", "logs/run.log", "logs/setup.log",
                                          "transcripts/phase0.jsonl"])
        self.assertEqual(rfiles["private.log"], b"evaluator only\n")
        import_run_bundle.check_same_run(results, transcripts)
        self.assertEqual((results["challenge"], results["provider"], results["run_id"], results["pin"]),
                         ("demo", "claude", RUN_ID, PIN))
        self.assertEqual(results["requested_model"], {"fast": "claude-sonnet-5-5", "slow": "claude-opus-5-5"})
        self.assertEqual(results["requested_effort"], {"fast": "high", "slow": "medium"})
        self.assertEqual(results["actual_model"], ["claude-opus-5-5"])
        self.assertEqual(results["actual_effort"], [])
        self.assertEqual(results["verdicts"], {"private": "pass", "overall": "pass", "phase": [
            {"phase": "0", "attempt": 1, "subsystem": None, "verdict": "pass"}]})
        self.assertEqual((results["started_at"], results["finished_at"], results["created_at"]),
                         ("2026-10-03T10:00:00Z", "2026-10-03T11:00:00Z", "2026-10-03T11:05:00+00:00"))
        self.assertEqual(results["manifest_sha256"], sha((self.output / "run.manifest.json").read_bytes()))
        self.assertEqual(results["files"]["report.md"], sha(b"# report\n"))

    def test_size_reporting(self):
        rows = run_archives.write_archives(self.output)
        for row in rows:
            bundle, files = import_run_bundle.read_archive(row["path"])
            self.assertEqual(row["bytes"], row["path"].stat().st_size)
            self.assertEqual(row["unpacked_bytes"], sum(map(len, files.values())))
            self.assertEqual(row["files"], len(files))
            self.assertEqual(bundle["sizes"]["files"], {k: len(v) for k, v in files.items()})
            self.assertEqual(bundle["sizes"]["total_bytes"], row["unpacked_bytes"])
        report = run_archives.size_report(rows)
        self.assertIn(f"results      {RUN}.results.tar.zst  ", report)
        transcripts = rows[1]
        self.assertIn(f"(unpacked {transcripts['unpacked_bytes']} B, 4 files, "
                      f"{transcripts['unpacked_bytes'] / transcripts['bytes']:.1f}x)", report.splitlines()[1])
        self.assertEqual(run_archives.human(2048), "2.0 KiB")
        _, out, _ = self.quiet(run_archives.main, ["write", str(self.output)])
        self.assertIn("unpacked", out)

    def test_existing_archives_are_verified_and_reused_never_rewritten(self):
        first = run_archives.write_archives(self.output)
        mtimes = [r["path"].stat().st_mtime_ns for r in first]
        again = run_archives.write_archives(self.output)
        self.assertEqual([r["path"].stat().st_mtime_ns for r in again], mtimes)
        first[1]["path"].unlink()
        with self.assertRaisesRegex(ArchiveError, "Partial archive set"):
            run_archives.write_archives(self.output)

    def test_archives_are_private_wherever_written_and_reuse_tightens_them(self):
        staged = []
        real_run = subprocess.run
        def spy(cmd, *args, **kwargs):
            tar = Path(cmd[-1])
            staged.append((stat.S_IMODE(tar.parent.stat().st_mode), stat.S_IMODE(tar.stat().st_mode)))
            return real_run(cmd, *args, **kwargs)
        old_umask = os.umask(0o022)
        try:
            for dest in (None, self.root / "elsewhere"):
                with self.subTest(dest=dest), patch.object(run_archives.subprocess, "run", spy):
                    rows = run_archives.write_archives(self.output, dest)
                    self.assertEqual([stat.S_IMODE(r["path"].stat().st_mode) for r in rows], [0o600, 0o600])
            self.assertEqual(staged, [(0o700, 0o600)] * 4)
            rows[0]["path"].chmod(0o644)
            mtimes = [r["path"].stat().st_mtime_ns for r in rows]
            again = run_archives.write_archives(self.output, self.root / "elsewhere")
            self.assertEqual([stat.S_IMODE(r["path"].stat().st_mode) for r in again], [0o600, 0o600])
            self.assertEqual([r["path"].stat().st_mtime_ns for r in again], mtimes)
        finally:
            os.umask(old_umask)

    def test_sources_that_differ_from_the_manifest_are_rejected(self):
        cases = {
            "implementation-sha256": lambda o: (o / "implementation/src/demo/module.clj").write_text("edited"),
            "transcripts differ": lambda o: (o / "transcripts/extra.jsonl").write_text("{}"),
            "Phase transcript is corrupt": lambda o: (o / "transcripts/phase0.jsonl").write_text("x"),
            "Private evaluator log": lambda o: (o / "run.private.log").write_text("other"),
            "launch pin": lambda o: (o / "LAUNCH.json").write_text(json.dumps(
                {"pin": "d" * 40, "challenge": "demo", "agent": "claude"})),
            "Cannot archive": lambda o: (o / "implementation/src/demo/key.enc").write_text("x"),
        }
        for i, (message, tamper) in enumerate(cases.items()):
            with self.subTest(message=message):
                output = self.root / f"case-{i}"
                make_output(output)
                tamper(output)
                if message == "Cannot archive":  # keep the hash consistent so the path rule is what fails
                    files = {p.relative_to(output / "implementation").as_posix(): p.read_bytes()
                             for p in run_archives.visible_files(output / "implementation")}
                    manifest = json.loads((output / "run.manifest.json").read_text())
                    manifest["challenges"][0]["implementation-sha256"] = tree_sha(files)
                    (output / "run.manifest.json").write_text(json.dumps(manifest))
                    (output / "run.bundle.tar.gz").unlink()
                    payload = json.dumps({"manifest-sha256": sha((output / "run.manifest.json").read_bytes())}).encode()
                    with tarfile.open(output / "run.bundle.tar.gz", "w:gz") as tar:
                        info = tarfile.TarInfo("run/BUNDLE.json")
                        info.size = len(payload)
                        tar.addfile(info, io.BytesIO(payload))
                with self.assertRaisesRegex(ArchiveError, message):
                    run_archives.write_archives(output)
                self.assertFalse((output / "archives").exists() and any((output / "archives").iterdir()))


class UploadTests(ArchiveTestCase):
    def setUp(self):
        super().setUp()
        self.paths = [r["path"] for r in run_archives.write_archives(self.output)]

    def test_public_or_unreadable_repo_blocks_every_write(self):
        for visibility in ("PUBLIC", "INTERNAL"):
            with self.subTest(visibility=visibility), patch.dict(os.environ, {"FAKE_GH_VISIBILITY": visibility}):
                with self.assertRaisesRegex(ArchiveError, "not PRIVATE"):
                    run_archives.upload_archives(self.paths, 3)
        self.assertEqual(set(self.gh.calls()), {"repo view"})
        with patch.dict(os.environ, {"RUN_ARCHIVES_GH": str(self.root / "missing-gh")}):
            with self.assertRaisesRegex(ArchiveError, "failed"):
                run_archives.upload_archives(self.paths, 3)

    def test_creates_absent_release_uploads_and_verifies(self):
        tag, out, _ = self.quiet(run_archives.upload_archives, self.paths, 3)
        self.assertEqual(tag, "wave-3")
        self.assertEqual(self.gh.calls()[:3], ["repo view", "release view", "release create"])
        self.assertEqual(sorted(p.name for p in self.gh.release().iterdir()), sorted(p.name for p in self.paths))
        # Retrying is idempotent only for byte-identical assets.
        self.quiet(run_archives.upload_archives, self.paths, 3)
        self.assertEqual(self.gh.calls().count("release create"), 1)
        (self.gh.release() / self.paths[0].name).write_bytes(b"different")
        with self.assertRaisesRegex(ArchiveError, "already has a different"):
            run_archives.upload_archives(self.paths, 3)

    def test_failures_are_loud(self):
        with patch.dict(os.environ, {"FAKE_GH_FAIL": "create"}):
            with self.assertRaisesRegex(ArchiveError, "Could not create release wave-3: HTTP 403"):
                run_archives.upload_archives(self.paths, 3)
        with patch.dict(os.environ, {"FAKE_GH_FAIL": "upload"}):
            with self.assertRaisesRegex(ArchiveError, "Upload to wave-4 failed: HTTP 502"):
                run_archives.upload_archives(self.paths, 4)
        with self.assertRaisesRegex(ArchiveError, "positive"):
            run_archives.upload_archives(self.paths, 0)
        _, _, err = self.quiet(run_archives.main, ["upload", "--wave", "5", str(self.output)])
        self.assertNotIn("FAILED", err)
        with patch.dict(os.environ, {"FAKE_GH_FAIL": "upload"}):
            code, _, err = self.quiet(run_archives.main, ["upload", "--wave", "6", str(self.output)])
        self.assertEqual(code, 1)
        self.assertIn("run archives FAILED: Upload to wave-6 failed", err)


class RoundTripTests(ArchiveTestCase):
    def test_write_upload_fetch_import_round_trip(self):
        paths = [r["path"] for r in run_archives.write_archives(self.output)]
        self.quiet(run_archives.upload_archives, paths, 3)

        code, out, _ = self.quiet(fetch_runs.main, ["--wave", "3", "--list", "--dest", str(self.runs)])
        self.assertEqual(code, 0)
        self.assertIn(RUN, out)
        self.assertNotIn("release download", self.gh.calls())

        code, out, err = self.quiet(fetch_runs.main, ["--wave", "3", "--dest", str(self.runs)])
        self.assertEqual((code, err), (0, ""))
        run = self.runs / RUN
        self.assertEqual((run / "implementation/src/demo/module.clj").read_bytes(), b"(ns demo.module)")
        self.assertEqual((run / "manifest.json").read_bytes(), (self.output / "run.manifest.json").read_bytes())
        self.assertFalse((run / "transcripts").exists())
        self.assertFalse((run / "logs").exists())

        code, out, err = self.quiet(fetch_runs.main, ["--wave", "3", "--with-transcripts", RUN,
                                                      "--dest", str(self.runs)])
        self.assertEqual((code, err), (0, ""))
        self.assertIn(f"already imported {RUN}", out)
        self.assertEqual((run / "transcripts/phase0.jsonl").read_bytes(),
                         (self.output / "transcripts/phase0.jsonl").read_bytes())
        self.assertEqual((run / "logs/setup.log").read_text(), "setup.log\n")
        self.assertEqual(json.loads((run / "BUNDLE.json").read_text())["part"], "results")
        self.assertEqual(json.loads((run / "BUNDLE.transcripts.json").read_text())["part"], "transcripts")

        downloads = self.gh.calls().count("release download")
        code, out, _ = self.quiet(fetch_runs.main, ["--wave", "3", "--with-transcripts", RUN,
                                                    "--dest", str(self.runs)])
        self.assertEqual(code, 0)
        self.assertIn("transcripts already merged", out)
        self.assertEqual(self.gh.calls().count("release download"), downloads)
        rows, out, _ = self.quiet(import_run_bundle.list_runs, self.runs)
        self.assertEqual(rows[0][-1], "yes")

    def test_fetched_run_and_private_log_are_owner_only_under_umask_022(self):
        old_umask = os.umask(0o022)
        try:
            paths = [r["path"] for r in run_archives.write_archives(self.output)]
            self.quiet(run_archives.upload_archives, paths, 3)
            code, _, err = self.quiet(fetch_runs.main, ["--wave", "3", "--with-transcripts", RUN,
                                                        "--dest", str(self.runs)])
            self.assertEqual((code, err), (0, ""))
        finally:
            os.umask(old_umask)
        run = self.runs / RUN
        self.assertEqual((run / "private.log").read_text(), "evaluator only\n")
        self.assertEqual(stat.S_IMODE(run.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE((run / "private.log").stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE((run / "report.md").stat().st_mode), 0o644)

    def test_fetch_rejects_unknown_runs_and_missing_waves(self):
        paths = [r["path"] for r in run_archives.write_archives(self.output)]
        self.quiet(run_archives.upload_archives, paths, 3)
        code, _, err = self.quiet(fetch_runs.main, ["--wave", "3", "--with-transcripts", "nope",
                                                    "--dest", str(self.runs)])
        self.assertEqual(code, 1)
        self.assertIn("no transcripts archive for: nope", err)
        self.assertFalse(self.runs.exists())
        code, _, err = self.quiet(fetch_runs.main, ["--wave", "9", "--dest", str(self.runs)])
        self.assertEqual(code, 1)
        self.assertIn("has no release wave-9", err)

    def test_legacy_only_release_is_listed_and_fetch_fails_loudly(self):
        release = self.gh.release("wave-2")
        release.mkdir(parents=True)
        legacy = ["a-claude-r1.legacy.tar.gz", "b-codex-r2.legacy.tar.gz"]
        for name in legacy:
            (release / name).write_bytes(b"legacy")
        code, out, _ = self.quiet(fetch_runs.main, ["--wave", "2", "--list", "--dest", str(self.runs)])
        self.assertEqual(code, 0)
        self.assertIn("2 asset(s) not importable by fetch-runs", out)
        for name in legacy:
            self.assertIn(name, out)
        code, out, err = self.quiet(fetch_runs.main, ["--wave", "2", "--dest", str(self.runs)])
        self.assertEqual(code, 1)
        self.assertIn("wave-2 has no results archives", err)
        self.assertIn("legacy", err)
        self.assertIn("manual inspection", err)
        self.assertIn("individual bundle", err)
        self.assertNotIn("DIR/*.legacy.tar.gz", err)
        for name in legacy:
            self.assertIn(name, err)
        self.assertNotIn("release download", self.gh.calls())
        self.assertFalse(self.runs.exists())

    def test_fetch_reports_a_rejected_archive(self):
        paths = [r["path"] for r in run_archives.write_archives(self.output)]
        self.quiet(run_archives.upload_archives, paths, 3)
        (self.gh.release() / paths[0].name).write_bytes(b"not zstd")
        code, _, err = self.quiet(fetch_runs.main, ["--wave", "3", "--dest", str(self.runs)])
        self.assertEqual(code, 1)
        self.assertIn(f"REJECTED {paths[0].name}", err)
        self.assertFalse((self.runs / RUN).exists())

    def test_fetch_rejects_results_without_manifest_json(self):
        paths = [r["path"] for r in run_archives.write_archives(self.output)]
        with import_run_bundle.open_tar(paths[0]) as tar:
            files = {m.name: tar.extractfile(m).read() for m in tar.getmembers()}
        bundle = json.loads(files.pop("BUNDLE.json"))
        del files["manifest.json"]
        bundle["files"] = {k: sha(v) for k, v in files.items()}
        plain = self.root / "plain.tar"
        with tarfile.open(plain, "w") as tar:
            for name, data in [("BUNDLE.json", json.dumps(bundle).encode()), *files.items()]:
                info = tarfile.TarInfo(name)
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        paths[0].unlink()
        subprocess.run(["zstd", "-q", "-o", str(paths[0]), str(plain)], check=True)
        self.quiet(run_archives.upload_archives, paths, 3)
        code, _, err = self.quiet(fetch_runs.main, ["--wave", "3", "--dest", str(self.runs)])
        self.assertEqual(code, 1)
        self.assertIn(f"REJECTED {paths[0].name}: results archive has no manifest.json", err)
        self.assertFalse((self.runs / RUN).exists())

    @unittest.skipUnless(shutil.which("bb"), "bb not installed")
    def test_bb_tasks_reach_the_scripts(self):
        paths = [r["path"] for r in run_archives.write_archives(self.output)]
        self.quiet(run_archives.upload_archives, paths, 3)
        env = {**os.environ, **self.gh.env}
        listing = subprocess.run(["bb", "fetch-runs", "--wave", "3", "--list", "--dest", str(self.runs)],
                                 cwd=SCRIPTS.parent, env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(listing.returncode, 0, listing.stderr)
        self.assertIn(RUN, listing.stdout)
        write = subprocess.run(["bb", "run-archives", "write", str(self.output)],
                               cwd=SCRIPTS.parent, env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(write.returncode, 0, write.stderr)
        self.assertIn("transcripts.tar.zst", write.stdout)


if __name__ == "__main__":
    unittest.main()
