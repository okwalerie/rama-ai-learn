import tempfile
import unittest
from pathlib import Path

from index_run_artifacts import inventory


class InventoryTests(unittest.TestCase):
    def test_scopes_run_and_omits_private_and_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            prefix = '2026-09-24-123456'
            impl = root / 'repo/implementations/sample'
            impl.mkdir(parents=True)
            (impl / 'REASONING.md').write_text('decision')
            (impl / 'src/sample').mkdir(parents=True)
            (impl / 'src/sample/module.clj').write_text('module')
            (impl / 'src/sample/solution.clj').symlink_to('/etc/passwd')
            (root / 'repo/challenges/sample/test-private').mkdir(parents=True)
            (root / 'repo/challenges/sample/test-private/test.clj').write_text('secret')
            (root / 'transcripts').mkdir()
            (root / 'transcripts' / f'{prefix}-claude-sonnet-sample-phase3-attempt2-retry1.jsonl').write_text('{}')
            (root / 'transcripts' / f'{prefix}-claude-opus-sample-phasedecompose.jsonl').write_text('{}')
            (root / 'transcripts' / f'{prefix}-claude-sonnet-other-phase3.jsonl').write_text('{}')
            (root / 'reports').mkdir()
            (root / 'reports' / f'{prefix}.md').write_text('report')
            result = inventory(root, 'sample', prefix)
            self.assertEqual({e['kind'] for e in result['entries']},
                             {'native-phase-jsonl', 'runner-report-candidate',
                              'solver-artifact', 'implementation'})
            self.assertEqual(len(result['entries']), 5)
            self.assertNotIn('decision', str(result))
            self.assertNotIn('secret', str(result))

    def test_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                inventory(tmp, '../other', '2026-09-24-123456')
            with self.assertRaises(ValueError):
                inventory(tmp, 'sample', '2026-09-24-123456', ['../private.log'])


if __name__ == '__main__':
    unittest.main()
