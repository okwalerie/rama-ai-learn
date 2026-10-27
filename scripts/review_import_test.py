"""Offline archive safety tests. No real secrets, model calls or private suites."""

import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path

from review_import_archives import MAX_FILE, export_archives, inspect_archive


def archive(path, files):
    with tarfile.open(path, "w:gz") as target:
        for name, content in files:
            member = tarfile.TarInfo(name)
            member.size = len(content)
            target.addfile(member, io.BytesIO(content))


class ArchiveTests(unittest.TestCase):
    def test_unsafe_paths_and_duplicate_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "input.tar.gz"
            for names in (("../escape",), ("/absolute",), ("ok", "./ok"), ("x\\y",)):
                with self.subTest(names=names):
                    archive(path, [(name, b"fixture") for name in names])
                    with self.assertRaises(ValueError):
                        inspect_archive(path)

    def test_links_and_special_files_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "input.tar.gz"
            for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE):
                with self.subTest(kind=kind):
                    with tarfile.open(path, "w:gz") as target:
                        member = tarfile.TarInfo("unsafe")
                        member.type = kind
                        member.linkname = "../outside"
                        target.addfile(member)
                    with self.assertRaises(ValueError):
                        inspect_archive(path)

    def test_size_rejected_before_content_read(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "large.tar"
            member = tarfile.TarInfo("large")
            member.size = MAX_FILE + 1
            path.write_bytes(member.tobuf())
            with self.assertRaisesRegex(ValueError, "size limit"):
                inspect_archive(path)

    def test_hash_deduplication_not_filename_deduplication(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive(root / "a.tgz", [("same.md", b"one"), ("alias.md", b"one")])
            archive(root / "b.tgz", [("same.md", b"two")])
            index = export_archives([root / "a.tgz", root / "b.tgz"], root / "out")
            self.assertEqual(2, index["counts"]["unique_files"])
            self.assertEqual(1, index["counts"]["duplicate_occurrences"])
            self.assertEqual(2, len(index["files"][0]["origins"]))
            self.assertEqual(
                ["archive-index.json"], [p.name for p in (root / "out").iterdir()]
            )

    def test_sensitive_content_withheld_and_unknown_format_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            canary = "SYNTHETIC_PRIVATE_CANARY"
            events = [
                {"type": "thread.started", "thread_id": "synthetic"},
                {
                    "type": "item.completed",
                    "item": {"id": "a", "type": "reasoning", "text": canary},
                },
                {
                    "type": "item.completed",
                    "item": {
                        "id": "b",
                        "type": "agent_message",
                        "text": "PHASE_VERDICT: PASS",
                    },
                },
                {
                    "type": "turn.completed",
                    "usage": {"input_tokens": 17, "output_tokens": 23},
                },
            ]
            archive(
                root / "a.tgz",
                [
                    (
                        "capture.jsonl",
                        ("\n".join(json.dumps(e) for e in events) + "\n").encode(),
                    ),
                    (
                        "unknown.jsonl",
                        b'{"type":"unknown","text":"SYNTHETIC_PRIVATE_CANARY"}\n',
                    ),
                    ("REASONING.md", canary.encode()),
                ],
            )
            index = export_archives([root / "a.tgz"], root / "out")
            self.assertEqual(
                "unsupported transcript format", index["files"][1]["disposition"]
            )
            outputs = "".join(p.read_text() for p in (root / "out").iterdir())
            self.assertNotIn(canary, outputs)
            safe = json.loads((root / "out" / index["files"][0]["export"]).read_text())
            self.assertEqual(
                "PASS",
                next(
                    e["verdict"]
                    for e in safe["events"]
                    if e["kind"] == "claimed_verdict"
                ),
            )
            self.assertEqual(
                17,
                next(
                    e["usage"]["input_tokens"]
                    for e in safe["events"]
                    if e["kind"] == "usage"
                ),
            )


if __name__ == "__main__":
    unittest.main()
