#!/usr/bin/env python3
"""Inspect bounded private tar archives and export metadata, never raw contents.

Outputs are a staging area for review, not automatic publication. No archive
path is extracted to disk. Only native transcript bytes enter a private
temporary file for the repository's supported safe-review command.
"""

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tarfile
import tempfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

from review_index import report_metrics

ROOT = Path(__file__).resolve().parents[1]
MAX_FILE = 16 * 1024 * 1024
MAX_TOTAL = 128 * 1024 * 1024
MAX_MEMBERS = 2000
SAFE_PATH = re.compile(r"[A-Za-z0-9._/-]+")
SECRET_MARKERS = re.compile(
    rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
    rb"(?:sk-[A-Za-z0-9_-]{20,}|AKIA[A-Z0-9]{16}|ghp_[A-Za-z0-9]{30,})"
)


def inspect_archive(path):
    """Fail before processing content if any member is unsafe or over budget."""
    if path.stat().st_size > MAX_TOTAL:
        raise ValueError("Compressed archive exceeds limit")
    records = []
    seen = set()
    total = 0
    with tarfile.open(path) as archive:
        for member in archive:
            if len(records) >= MAX_MEMBERS:
                raise ValueError("Too many archive members")
            name = member.name
            parts = PurePosixPath(name).parts
            if (
                not SAFE_PATH.fullmatch(name)
                or name.startswith("/")
                or ".." in parts
                or not parts
                or not (member.isfile() or member.isdir())
            ):
                raise ValueError("Unsafe archive member path or type")
            canonical = str(PurePosixPath(name))
            if canonical in seen:
                raise ValueError("Duplicate archive path")
            seen.add(canonical)
            total += member.size
            if member.size < 0 or member.size > MAX_FILE or total > MAX_TOTAL:
                raise ValueError("Archive size limit exceeded")
            records.append(member)
    return records


def export_archives(paths, output):
    # Validate every archive before reading content or creating output files.
    inspected = [(path, inspect_archive(path)) for path in paths]
    if output.exists() and any(output.iterdir()):
        raise ValueError("Output directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    index = {"schema": 1, "archives": [], "files": []}
    unique = {}
    for path, members in inspected:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        summary = {
            "name": path.name,
            "sha256": digest,
            "compressed_bytes": path.stat().st_size,
            "member_count": len(members),
            "uncompressed_bytes": sum(m.size for m in members),
            "secret_marker_files": 0,
            "policy": "No links, special files, traversal, duplicate paths or oversized members. Secret scan is heuristic; free text is withheld regardless.",
        }
        index["archives"].append(summary)
        with tarfile.open(path) as archive:
            for member in members:
                if not member.isfile():
                    continue
                raw = archive.extractfile(member).read()
                sha = hashlib.sha256(raw).hexdigest()
                secret = bool(SECRET_MARKERS.search(raw))
                summary["secret_marker_files"] += int(secret)
                origin = {
                    "archive": digest,
                    "path": member.name,
                    "mtime_utc": datetime.fromtimestamp(
                        member.mtime, timezone.utc
                    ).isoformat(),
                }
                if sha in unique:
                    unique[sha]["origins"].append(origin)
                    continue
                record = {
                    "sha256": sha,
                    "bytes": len(raw),
                    "origins": [origin],
                    "secret_marker_detected": secret,
                    "disposition": "withheld document",
                }
                unique[sha] = record
                index["files"].append(record)
                if member.name.endswith(".jsonl"):
                    # Unknown formats never use the shared adapter's fallback.
                    types = Counter()
                    malformed = 0
                    for line in raw.splitlines():
                        if not line.strip():
                            continue
                        try:
                            obj = json.loads(line)
                        except (ValueError, UnicodeDecodeError):
                            malformed += 1
                            continue
                        if isinstance(obj, dict) and isinstance(obj.get("type"), str):
                            types[obj["type"]] += 1
                    # This import is bounded to Codex exec lifecycle captures,
                    # the format supported by the shared adapter.
                    if not types["thread.started"]:
                        record["disposition"] = "unsupported transcript format"
                        continue
                    with tempfile.TemporaryDirectory() as temp:
                        native = Path(temp) / "native.jsonl"
                        native.write_bytes(raw)
                        result = subprocess.run(
                            [
                                sys.executable,
                                "scripts/analyze-latest-transcript.py",
                                "--file",
                                str(native),
                                "safe-review",
                            ],
                            cwd=ROOT,
                            capture_output=True,
                            timeout=60,
                        )
                    if result.returncode:
                        raise ValueError(
                            "Safe transcript exporter failed; raw diagnostics withheld"
                        )
                    safe = json.loads(result.stdout)
                    export = f"archive-{sha[:16]}-events.json"
                    (output / export).write_text(json.dumps(safe, indent=2) + "\n")
                    record.update(
                        {
                            "disposition": "metadata-only transcript",
                            "export": export,
                            "format": "codex",
                            "malformed_lines": malformed,
                            "ends_with_newline": raw.endswith(b"\n"),
                            "event_count": len(safe["events"]),
                            "normalized_event_count": safe["normalized_event_count"],
                            "first_time": next(
                                (e["time"] for e in safe["events"] if e.get("time")),
                                None,
                            ),
                            "last_time": next(
                                (
                                    e["time"]
                                    for e in reversed(safe["events"])
                                    if e.get("time")
                                ),
                                None,
                            ),
                        }
                    )
                elif member.name.startswith("reports/") and member.name.endswith(".md"):
                    safe = {
                        "schema": 1,
                        "sha256": sha,
                        "bytes": len(raw),
                        "rows": report_metrics(raw.decode()),
                    }
                    export = f"archive-{sha[:16]}-metrics.json"
                    (output / export).write_text(json.dumps(safe, indent=2) + "\n")
                    record.update({"disposition": "typed report", "export": export})
    index["counts"] = {
        "file_occurrences": sum(len(f["origins"]) for f in index["files"]),
        "unique_files": len(index["files"]),
        "duplicate_occurrences": sum(len(f["origins"]) - 1 for f in index["files"]),
    }
    (output / "archive-index.json").write_text(json.dumps(index, indent=2) + "\n")
    return index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", action="append", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    index = export_archives(args.archive, args.output)
    print(json.dumps(index["counts"]))


if __name__ == "__main__":
    main()
