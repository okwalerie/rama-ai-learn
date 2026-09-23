"""Real attempted reads, not assertions about a model's compliance."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from isolate_solver import isolated_command, snapshot


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
                    "plugins/rama-skill/skills/rama/SKILL.md"):
            path = self.repo / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture")
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        subprocess.run(["git", "-C", str(self.repo), "add", "private-key"], check=True)
        subprocess.run(["git", "-C", str(self.repo), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "--no-gpg-sign",
                        "-qm", "fixture"],
                       check=True)
        self.assertEqual(subprocess.check_output(
            ["git", "-C", str(self.repo), "show", "HEAD:private-key"], text=True), "fixture")

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

    @unittest.skipUnless(shutil.which("bwrap"), "Linux bubblewrap required")
    def test_actual_process_cannot_read_private_host_state(self):
        # Host process has a secret; the child must not recover it through env
        # or /proc, even though both processes use the same host UID.
        probe = r'''
import os
from pathlib import Path
import subprocess
root = Path.cwd()
assert (root / 'challenges/demo/README.md').read_text() == 'fixture'
assert (root / '.agents/skills/rama/SKILL.md').read_text() == 'fixture'
blocked = ['.git/config', 'private-key', 'challenges/other/README.md',
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


if __name__ == "__main__":
    unittest.main()
