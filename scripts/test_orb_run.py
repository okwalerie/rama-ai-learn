import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import orb_run


class OrbRunTests(unittest.TestCase):
    def test_detaches_exact_pin_without_consulting_branch_tip(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True).strip()
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            (root / "file").write_text("one")
            subprocess.run(["git", "add", "file"], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "one"], cwd=root, check=True)
            pin = git("rev-parse", "HEAD")
            (root / "file").write_text("two")
            subprocess.run(["git", "commit", "-qam", "two"], cwd=root, check=True)
            newer = git("rev-parse", "HEAD")
            subprocess.run(["git", "update-ref", "refs/remotes/origin/master", newer], cwd=root, check=True)
            with patch.object(orb_run, "ROOT", root):
                orb_run.pin_checkout(pin)
            self.assertEqual(git("rev-parse", "HEAD"), pin)
            self.assertEqual(git("rev-parse", "refs/remotes/origin/master"), newer)
            self.assertNotEqual(subprocess.run(["git", "symbolic-ref", "-q", "HEAD"],
                                               cwd=root, capture_output=True).returncode, 0)

    def test_missing_key_blocks_all_commands(self):
        opts = SimpleNamespace(challenge="attack", agent="opencode")
        with tempfile.TemporaryDirectory() as tmp, patch.dict("os.environ", {"AMP_ORB": "1",
                    "AMP_ORB_PROVIDER": "e2b"}, clear=True), patch.object(orb_run, "command") as command:
            with self.assertRaisesRegex(RuntimeError, "CHALLENGE_KEY"):
                orb_run.preflight(opts, Path(tmp))
            command.assert_not_called()

    def test_resources_fail_before_models_or_solver(self):
        opts = SimpleNamespace(challenge="attack", agent="opencode")
        with tempfile.TemporaryDirectory() as tmp, patch.dict("os.environ", {"AMP_ORB": "1",
                    "AMP_ORB_PROVIDER": "e2b", "CHALLENGE_KEY": "test"}, clear=True), \
                patch.object(orb_run, "command", side_effect=RuntimeError("resource gate")) as command:
            with self.assertRaisesRegex(RuntimeError, "resource gate"):
                orb_run.preflight(opts, Path(tmp))
            self.assertEqual(command.call_args.args[0], ["bb", "run-challenges", "--resource-preflight"])
            self.assertEqual(command.call_count, 1)

    def test_strict_preflight_and_one_completion_per_distinct_pair(self):
        opts = SimpleNamespace(challenge="attack", agent="opencode", fast_model="openrouter/fast",
                               fast_effort="high", slow_model="openrouter/slow", slow_effort="low")
        commands = []
        def fake_command(args, **kwargs):
            commands.append(args)
            if "--preflight" in args:
                return json.dumps({"probe": "passed", "network": "strict",
                                   "snapshot": {"audits": {"attack": {"violations": 0}}}})
            if "--challenge" in args:
                return (json.dumps({"type": "text", "part": {"text": "OK"}}) + "\n" +
                        json.dumps({"type": "step_finish", "part": {"reason": "stop"}}))
            return ""
        with tempfile.TemporaryDirectory() as tmp, patch.dict("os.environ", {"AMP_ORB": "1",
                    "AMP_ORB_PROVIDER": "e2b", "CHALLENGE_KEY": "test"}, clear=True), \
                patch.object(orb_run, "command", side_effect=fake_command):
            orb_run.preflight(opts, Path(tmp))
            self.assertEqual(commands[0], ["bb", "run-challenges", "--resource-preflight"])
            self.assertEqual(len([c for c in commands if "--network" in c and "--preflight" not in c]), 2)
            self.assertEqual(commands[-1], ["clojure", "-X:test-harness"])
            opts.slow_model, opts.slow_effort = opts.fast_model, opts.fast_effort
            commands.clear()
            orb_run.preflight(opts, Path(tmp))
            self.assertEqual(len([c for c in commands if "--network" in c and "--preflight" not in c]), 1)

    def test_empty_or_failed_completion_is_not_entitlement(self):
        for payload in ('', '{"type":"error"}', '{"type":"text","part":{"text":""}}',
                        '{"type":"text","part":{"text":"partial"}}'):
            with self.assertRaises(RuntimeError):
                orb_run.verify_completion(payload, "opencode")
        with self.assertRaises(RuntimeError):
            orb_run.verify_completion('{"type":"result","is_error":true,"result":"OK"}', "claude")

    def test_bundle_requires_matching_pin_private_log_and_transcript(self):
        with tempfile.TemporaryDirectory() as tmp:
            parent = Path(tmp)
            root, reports, transcripts, output = (parent / name for name in ("repo", "reports", "transcripts", "out"))
            for folder in (root, reports, transcripts, output):
                folder.mkdir()
            pin = "a" * 40
            private = b"private"
            (reports / "run.private.log").write_bytes(private)
            transcript = transcripts / "phase.jsonl"
            transcript.write_text("phase")
            manifest = {"repo": {"head-sha": pin}, "isolation": {"network": "strict"},
                        "challenges": [{"name": "attack", "phases": [{"transcript-path": str(transcript),
                           "transcript-sha256": hashlib.sha256(transcript.read_bytes()).hexdigest()}]}],
                        "evaluator-log": {"path": "run.private.log", "bytes": len(private),
                          "sha256": hashlib.sha256(private).hexdigest()}}
            source = reports / "run.manifest.json"
            source.write_text(json.dumps(manifest))
            (reports / "run.md").write_text("report")
            def bundle():
                payload = json.dumps({"manifest-sha256": hashlib.sha256(source.read_bytes()).hexdigest()}).encode()
                with tarfile.open(reports / "run.bundle.tar.gz", "w:gz") as tar:
                    info = tarfile.TarInfo("run/BUNDLE.json")
                    info.size = len(payload)
                    tar.addfile(info, io.BytesIO(payload))
            bundle()
            with patch.object(orb_run, "ROOT", root):
                manifest["repo"]["head-sha"] = "b" * 40
                source.write_text(json.dumps(manifest))
                with self.assertRaisesRegex(RuntimeError, "SHA differs"):
                    orb_run.collect_artifacts(SimpleNamespace(pin=pin, challenge="attack"), output, set())
                manifest["repo"]["head-sha"] = pin
                source.write_text(json.dumps(manifest))
                bundle()
                self.assertEqual(len(orb_run.collect_artifacts(
                    SimpleNamespace(pin=pin, challenge="attack"), output, set())), 4)
                self.assertEqual((output / "transcripts/phase.jsonl").read_text(), "phase")


if __name__ == "__main__":
    unittest.main()
