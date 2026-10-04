"""Real attempted reads, not assertions about a model's compliance."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from isolate_solver import (CHALLENGE_ALLOWLIST, SHARED_ALLOWLIST, audit_snapshot,
                            allowed_hosts_for_command, isolated_command, preflight,
                            selected_model, snapshot)

REPO = Path(__file__).resolve().parent.parent
# The runner writes <report>.private.log next to its reports, in ../reports.
EVALUATOR_LOG_SENTINEL = "FAIL in (withdraw-test)\nexpected: (= 4711 (withdraw! c 99999))"


def bind_sources(args):
    """Host paths bubblewrap exposes to the solver."""
    return [Path(args[i + 1]) for i, a in enumerate(args) if a in ("--bind", "--ro-bind")]


def within(path, root):
    return path == root or root in path.parents


class IsolationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        for rel in ("challenges/demo/README.md", "challenges/demo/src/protocol.clj",
                    "challenges/demo/src/test_support.clj", "challenges/demo/test-private/secret",
                    "challenges/demo/test-resources/secret", "challenges/demo/test/secret",
                    "challenges/demo/test-harness/secret", "challenges/demo/src/secret.enc",
                    "challenges/other/README.md", "private-key",
                    "docs/atlas/data/reference-decisions.json", "review/answer.clj",
                    "plugins/rama-skill/skills/rama/SKILL.md"):
            path = self.repo / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"fixture {rel}")
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        subprocess.run(["git", "-C", str(self.repo), "add", "private-key"], check=True)
        subprocess.run(["git", "-C", str(self.repo), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "--no-gpg-sign",
                        "-qm", "fixture"],
                       check=True)
        self.assertEqual(subprocess.check_output(
            ["git", "-C", str(self.repo), "show", "HEAD:private-key"], text=True), "fixture private-key")

    def test_host_tool_prefix_is_read_only_and_on_path(self):
        prefix = self.root / "brew"
        (prefix / "bin").mkdir(parents=True)
        with patch("isolate_solver.TOOL_PREFIXES", (str(prefix),)):
            args, env = isolated_command(self.repo, "demo", "codex", ["true"], self.root)
        triples = [args[j:j + 3] for j in range(len(args) - 2)]
        self.assertIn(["--ro-bind", str(prefix.resolve()), str(prefix)], triples)
        self.assertNotIn(["--bind", str(prefix.resolve()), str(prefix)], triples)
        self.assertIn(f"{prefix}/bin:", env["PATH"])
        (self.root / "x").mkdir()
        with patch("isolate_solver.TOOL_PREFIXES", (str(self.root / "absent"),)):
            args, env = isolated_command(self.repo, "demo", "codex", ["true"], self.root / "x")
        self.assertNotIn(str(self.root / "absent"), " ".join(args) + env["PATH"])

    def test_authoring_references_are_not_in_solver_snapshot(self):
        public = self.root / "public"
        snapshot(self.repo, public, "demo")
        for rel in ("docs/atlas/data/reference-decisions.json", "review/answer.clj"):
            self.assertEqual((self.repo / rel).read_text(), f"fixture {rel}")
            with self.assertRaises(FileNotFoundError):
                (public / rel).read_text()

    def test_snapshot_rejects_symlink_parent(self):
        (self.repo / "challenges/alias").symlink_to(self.repo / "challenges/demo")
        with self.assertRaisesRegex(ValueError, "Symlink"):
            snapshot(self.repo, self.root / "public", "alias")

    def test_snapshot_rejects_symlink(self):
        (self.repo / "challenges/demo/src/link").symlink_to(self.repo / "private-key")
        with self.assertRaisesRegex(ValueError, "Symlink"):
            snapshot(self.repo, self.root / "public", "demo")

    def test_missing_bwrap_fails_closed(self):
        with patch("shutil.which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "refusing"):
                isolated_command(self.repo, "demo", "claude", ["true"], self.root)

    def test_selected_model_ignores_prompt_arguments(self):
        self.assertEqual("opencode-go/gpt-6-luna", selected_model(
            ["opencode", "run", "--model", "opencode-go/gpt-6-luna", "--", "prompt"]))
        self.assertEqual("openrouter/z-ai/glm-5.3", selected_model(
            ["opencode", "run", "--model=openrouter/z-ai/glm-5.3", "prompt"]))
        self.assertIsNone(selected_model(["opencode", "run", "--", "--model=opencode-go/gpt-6-luna"]))

    def test_proxy_server_hosts_follow_the_selected_model(self):
        go_hosts = allowed_hosts_for_command("opencode", [
            "opencode", "run", "--model", "opencode-go/gpt-6-luna"])
        router_hosts = allowed_hosts_for_command("opencode", [
            "opencode", "run", "--model=openrouter/z-ai/glm-5.3"])
        self.assertIn("opencode.ai", go_hosts)
        self.assertNotIn("openrouter.ai", go_hosts)
        self.assertIn("openrouter.ai", router_hosts)
        self.assertNotIn("opencode.ai", router_hosts)

    def test_opencode_go_gets_only_its_environment_key_and_no_auth_file(self):
        home = self.root / "home"
        catalog = home / ".cache/opencode/models.json"
        auth = home / ".local/share/opencode/auth.json"
        catalog.parent.mkdir(parents=True)
        auth.parent.mkdir(parents=True)
        catalog.write_text("{}")
        auth.write_text('{"must_not_be_mounted":true}')
        command = ["opencode", "run", "--model", "opencode-go/gpt-6-luna", "--variant", "xhigh"]
        secrets = {"OPENCODE_API_KEY": "go-only-sentinel",
                   "OPENROUTER_API_KEY": "router-sentinel",
                   "OPENAI_API_KEY": "openai-sentinel",
                   "ANTHROPIC_API_KEY": "anthropic-sentinel"}
        staging = self.root / "go-staging"
        staging.mkdir()
        with patch("isolate_solver.Path.home", return_value=home), patch.dict(os.environ, secrets):
            args, env = isolated_command(self.repo, "demo", "opencode", command,
                                         staging, strict=True)
        self.assertEqual("go-only-sentinel", env["OPENCODE_API_KEY"])
        provider = json.loads(env["OPENCODE_CONFIG_CONTENT"])["provider"]["opencode-go"]
        self.assertEqual("@ai-sdk/openai-compatible", provider["npm"])
        self.assertEqual("https://opencode.ai/zen/go/v1", provider["options"]["baseURL"])
        self.assertEqual("{env:OPENCODE_API_KEY}", provider["options"]["apiKey"])
        self.assertNotIn("go-only-sentinel", env["OPENCODE_CONFIG_CONTENT"])
        for key in ("OPENROUTER_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY",
                    "BWS_API_KEY", "BWS_ACCESS_TOKEN"):
            self.assertNotIn(key, env)
        self.assertEqual("1", env["OPENCODE_DISABLE_MODELS_FETCH"])
        self.assertEqual("1", env["OPENCODE_DISABLE_AUTOUPDATE"])
        self.assertEqual("/run/opencode-models.json", env["OPENCODE_MODELS_PATH"])
        self.assertNotIn(str(auth), args)
        self.assertNotIn("go-only-sentinel", args)
        public = staging / "public"
        self.assertTrue(public.is_dir())
        self.assertFalse(any(b"go-only-sentinel" in p.read_bytes()
                             for p in public.rglob("*") if p.is_file()))

    def test_opencode_go_fails_closed_without_its_key(self):
        with patch.dict(os.environ, {"OPENCODE_API_KEY": ""}):
            with self.assertRaisesRegex(RuntimeError, "required for OpenCode Go"):
                isolated_command(self.repo, "demo", "opencode",
                                 ["opencode", "run", "--model=opencode-go/glm-5.3-flash"],
                                 self.root / "go-staging", strict=True)

    def test_non_go_opencode_keeps_existing_key_and_auth_file_behavior(self):
        home = self.root / "home"
        catalog = home / ".cache/opencode/models.json"
        auth = home / ".local/share/opencode/auth.json"
        catalog.parent.mkdir(parents=True)
        auth.parent.mkdir(parents=True)
        catalog.write_text("{}")
        auth.write_text('{"existing_opencode_auth":true}')
        staging = self.root / "router-staging"
        staging.mkdir()
        with patch("isolate_solver.Path.home", return_value=home), patch.dict(
                os.environ, {"OPENCODE_API_KEY": "must-not-forward",
                             "OPENROUTER_API_KEY": "router-sentinel"}):
            args, env = isolated_command(self.repo, "demo", "opencode",
                ["opencode", "run", "--model", "openrouter/z-ai/glm-5.3"],
                staging, strict=True)
        self.assertEqual("router-sentinel", env["OPENROUTER_API_KEY"])
        self.assertNotIn("OPENCODE_API_KEY", env)
        self.assertNotIn("OPENCODE_CONFIG_CONTENT", env)
        self.assertIn(str(auth), args)

    def test_snapshot_audit_is_clean_and_reports_no_contents(self):
        public = self.root / "public"
        snapshot(self.repo, public, "demo")
        report = audit_snapshot(self.repo, public, "demo", environ={})
        self.assertEqual(report["violations"], [])
        self.assertEqual(report["categories"], {"challenges/demo/README.md": 1,
                                                "challenges/demo/src": 1,
                                                "plugins/rama-skill/skills/rama": 1})
        self.assertEqual(report["symlinks"], [".agents/skills/rama", ".claude/skills/rama",
                                              ".codex/skills/rama"])

    def test_snapshot_skips_secret_and_protected_names_in_public_trees(self):
        for rel in ("plugins/rama-skill/skills/rama/.env", "plugins/rama-skill/skills/rama/id_rsa",
                    "plugins/rama-skill/skills/rama/test-private/answer.clj",
                    "lib/harness/src/atlas/notes.md", "challenges/demo/src/server.pem"):
            path = self.repo / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"fixture {rel}")
        public = self.root / "public"
        snapshot(self.repo, public, "demo")
        copied = {p.relative_to(public).as_posix() for p in public.rglob("*") if p.is_file()}
        self.assertEqual(copied, {"challenges/demo/README.md", "challenges/demo/src/protocol.clj",
                                  "plugins/rama-skill/skills/rama/SKILL.md"})

    def test_audit_flags_leaks_without_printing_values(self):
        public = self.root / "public"
        snapshot(self.repo, public, "demo")
        secret = "sk-ant-" + "x" * 40
        (public / "challenges/demo/src/copied.clj").write_text(
            (self.repo / "challenges/demo/test-private/secret").read_text())
        (public / "plugins/rama-skill/skills/rama/leak.md").write_text("value=" + "k" * 12 + "\n" + secret)
        (public / "docs").mkdir()
        (public / "docs/gaps.md").write_text("authoring notes")
        report = audit_snapshot(self.repo, public, "demo", environ={"CHALLENGE_KEY": "k" * 12})
        rules = {(v["path"], v["rule"]) for v in report["violations"]}
        self.assertEqual(rules, {("challenges/demo/src/copied.clj", "protected-content"),
                                 ("plugins/rama-skill/skills/rama/leak.md", "secret-pattern"),
                                 ("plugins/rama-skill/skills/rama/leak.md", "secret-env-value"),
                                 ("docs/gaps.md", "outside-allowlist")})
        rendered = repr(report)
        self.assertNotIn(secret, rendered)
        self.assertNotIn("k" * 12, rendered)
        self.assertIn("CHALLENGE_KEY", rendered)

    def test_isolated_command_refuses_dirty_snapshot(self):
        with patch("isolate_solver.audit_snapshot",
                   return_value={"violations": [{"path": "x", "rule": "protected-content"}]}):
            with self.assertRaisesRegex(ValueError, "Snapshot audit failed: x \\(protected-content\\)"):
                isolated_command(self.repo, "demo", "claude", ["true"], self.root)

    def test_preflight_fails_closed_without_bwrap(self):
        with patch("shutil.which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "refusing"):
                preflight(self.repo, ["demo"])

    @unittest.skipUnless(shutil.which("bwrap"), "Linux bubblewrap required")
    def test_preflight_cli_records_isolation(self):
        result = subprocess.run(
            ["python3", str(Path(__file__).with_name("isolate_solver.py")), "--repo", str(self.repo),
             "--agent", "claude", "--network", "strict", "--preflight", "--audit-challenge", "demo"],
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        record = json.loads(result.stdout)
        self.assertEqual(record["filesystem"], "bubblewrap")
        self.assertEqual(record["probe"], "passed")
        self.assertEqual(record["network"], "strict")
        self.assertEqual(record["snapshot"]["kind"], "allowlisted-public-copy")
        self.assertEqual(record["snapshot"]["audits"]["demo"]["violations"], 0)
        bad = subprocess.run(
            ["python3", str(Path(__file__).with_name("isolate_solver.py")), "--repo", str(self.repo),
             "--agent", "claude", "--preflight", "--audit-challenge", "../demo"],
            capture_output=True, text=True)
        self.assertEqual(bad.returncode, 1)
        self.assertIn("isolation preflight failed", bad.stderr)

    @unittest.skipUnless(shutil.which("bwrap"), "Linux bubblewrap required")
    def test_actual_process_cannot_read_private_host_state(self):
        # Host process has a secret; the child must not recover it through env
        # or /proc, even though both processes use the same host UID.
        probe = r'''
import json
import os
from pathlib import Path
import subprocess
root = Path.cwd()
assert (root / 'challenges/demo/README.md').read_text() == 'fixture challenges/demo/README.md'
assert (root / '.agents/skills/rama/SKILL.md').read_text() == 'fixture plugins/rama-skill/skills/rama/SKILL.md'
blocked = ['.git/config', 'private-key', 'challenges/other/README.md',
 'docs/atlas/data/reference-decisions.json', 'review/answer.clj',
 'challenges/demo/src/test_support.clj', 'challenges/demo/src/secret.enc',
 'challenges/demo/test-private/secret', 'challenges/demo/test-resources/secret',
 'challenges/demo/test/secret', 'challenges/demo/test-harness/secret']
blocked += ['/home/user/workspace/repos/rama-demo-gallery/README.md',
 '/home/user/workspace/repos/rama-helpers/README.md',
 '/home/user/workspace/repos/next-level-backends-with-rama-clj/README.md',
 str(Path.home() / '.claude/projects'), str(Path.home() / '.git-credentials'),
 '/proc/1/root' + str(root / 'private-key')]
for rel in blocked:
    try:
        (root / rel).read_bytes()
    except OSError:
        pass
    else:
        raise AssertionError('read succeeded: ' + rel)
assert 'CHALLENGE_KEY' not in os.environ
assert 'AMP_API_KEY' not in os.environ
for proc in Path('/proc').glob('[0-9]*/environ'):
    try:
        data = proc.read_bytes()
    except OSError:
        continue
    assert b'CHALLENGE_KEY=' not in data
    assert b'AMP_API_KEY=' not in data
assert subprocess.run(['git', 'show', 'HEAD:private-key'], capture_output=True).returncode != 0
out = root / 'implementations/demo'
(out / 'escape').symlink_to(root / 'private-key')
try:
    (out / 'escape').read_text()
except OSError:
    pass
else:
    raise AssertionError('symlink escaped')
(out / 'result').write_text('persisted')
print('blocked private files, Git, sibling mounts, key, host /proc; public skill and output work')
'''
        with patch.dict(os.environ, {"CHALLENGE_KEY": "sentinel-never-in-child",
                                     "AMP_API_KEY": "unrelated-secret"}):
            args, env = isolated_command(self.repo, "demo", "claude",
                                         ["python3", "-c", probe], self.root)
            result = subprocess.run(args, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("blocked private files", result.stdout)
        self.assertEqual((self.repo / "implementations/demo/result").read_text(), "persisted")
        # The solver's blocked reads must not destroy or encrypt authoring data.
        for rel in ("docs/atlas/data/reference-decisions.json", "review/answer.clj"):
            self.assertEqual((self.repo / rel).read_text(), f"fixture {rel}")

    @unittest.skipUnless(shutil.which("bwrap"), "Linux bubblewrap required")
    def test_solver_cannot_read_evaluator_logs(self):
        logs = [self.root / "reports/run.private.log", self.repo / "reports/run.private.log"]
        for log in logs:
            log.parent.mkdir(parents=True, exist_ok=True)
            log.write_text(EVALUATOR_LOG_SENTINEL)
        probe = ("import sys\nfrom pathlib import Path\nfor p in sys.argv[1:]:\n"
                 "    try:\n        Path(p).read_bytes()\n    except OSError:\n        continue\n"
                 "    raise AssertionError('read succeeded: ' + p)\nprint('evaluator logs unreadable')\n")
        args, env = isolated_command(self.repo, "demo", "claude",
                                     ["python3", "-c", probe, *map(str, logs)], self.root)
        for log in logs:
            self.assertFalse(any(within(log, src) for src in bind_sources(args)), log)
        result = subprocess.run(args, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("evaluator logs unreadable", result.stdout)
        self.assertNotIn("withdraw-test", result.stdout + result.stderr)
        public = self.root / "snapshot"
        snapshot(self.repo, public, "demo")
        for path in public.rglob("*"):
            self.assertFalse(path.name.endswith(".private.log"), path)
            if path.is_file():
                self.assertNotIn(b"withdraw-test", path.read_bytes())


class RepositoryEvaluatorLogTests(unittest.TestCase):
    """Every real challenge's snapshot excludes the runner's evaluator logs."""

    def test_evaluator_log_dir_is_outside_every_challenge_snapshot(self):
        reports = (REPO / ".." / "reports").resolve()
        in_repo_reports = REPO / "reports"
        self.assertFalse(within(reports, REPO))
        allowed = [*SHARED_ALLOWLIST, *("challenges/{c}/" + r for r in CHALLENGE_ALLOWLIST)]
        self.assertFalse(any(r.split("/")[0] == "reports" for r in allowed))
        challenges = sorted(p.name for p in (REPO / "challenges").iterdir()
                            if p.is_dir() and not p.is_symlink())
        self.assertTrue(challenges)
        for challenge in challenges:
            with self.subTest(challenge=challenge), tempfile.TemporaryDirectory() as tmp:
                public = Path(tmp)
                snapshot(REPO, public, challenge)
                for path in public.rglob("*"):
                    rel = path.relative_to(public)
                    self.assertFalse(path.name.endswith(".private.log"), rel)
                    if path.is_symlink():
                        continue
                    source = (REPO / rel).resolve()
                    self.assertTrue(within(source, REPO), rel)
                    self.assertFalse(within(source, reports) or within(source, in_repo_reports), rel)
                # Paths only: violations name files and rules, never contents.
                self.assertEqual(audit_snapshot(REPO, public, challenge, environ={})["violations"], [])


if __name__ == "__main__":
    unittest.main()
