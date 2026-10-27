#!/usr/bin/env python3
"""Evaluator-only review server: exact file allowlist, local annotation SQLite.

Never serve the repository root with SimpleHTTPRequestHandler. The Portal adds
authentication; this loopback service itself is not a multi-tenant auth system.
"""

import argparse
import hashlib
import json
import sqlite3
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / "review"
SOURCES = {
    "scripts/run_challenges.bb",
    "scripts/save-attempt.sh",
    "scripts/isolate_solver.py",
    "scripts/encrypt_challenges.bb",
    "scripts/qa_score.bb",
    "scripts/qa_extract.bb",
    "scripts/qa_report.bb",
    "scripts/transcript_events.py",
    "scripts/analyze-latest-transcript.py",
    "SCORING_RUBRIC.md",
    "lib/harness/src/rama_challenges/harness.clj",
    "lib/harness/src/rama_challenges/event_assertions.clj",
    "challenges/auction-module/deps.edn",
    "challenges/auction-module/src/auction_module/protocol.clj",
}


def dataset():
    data = json.loads((REVIEW / "catalog.json").read_text())
    data.update(json.loads((REVIEW / "findings.json").read_text()))
    archive = json.loads((REVIEW / "evidence/archive-index.json").read_text())
    data["archive_import"] = archive
    for spec in json.loads((REVIEW / "local-runs.json").read_text()):
        report = json.loads((REVIEW / "evidence" / spec["report"]).read_text())
        row = report["rows"][0]
        if row["challenge"] != spec["challenge"]:
            raise ValueError("Archive report challenge mismatch")
        captures = [
            f
            for f in archive["files"]
            if f["disposition"] == "metadata-only transcript"
            and spec["capture_stamp"]
            and any(
                Path(o["path"]).name.startswith(spec["capture_stamp"])
                and f"-{spec['challenge']}-phase" in o["path"]
                for o in f["origins"]
            )
        ]
        files = [
            f for f in archive["files"] if f.get("export") == spec["report"]
        ] + captures
        transcripts = [
            {
                "file": f["export"],
                "label": Path(f["origins"][0]["path"]).stem.split("-phase", 1)[1],
            }
            for f in captures
        ]
        data["runs"].append(
            {
                **spec,
                "date": "2026-09-15",
                "group": "Local archive import",
                "harness": "Codex (version unavailable)",
                "thread": None,
                "classification": "failed",
                "status": row["Status"],
                "private": row["Private"],
                "commit": None,
                "metrics_file": spec["report"],
                "metric_basis": "Verified report bytes from the private September 15 archive. Phase grouping is provisional, based on challenge/model names and adjacent filename stamps; no immutable run ID was supplied. Costs are report estimates, not invoices.",
                "retry_note": "Capture suffixes retain plan/validation attempts. They are not independent runs. The report's Iterations field may disagree with the supplied phase inventory.",
                "isolation": "No verified local operating-system or network isolation record. Source revision and prompt changes are unknown.",
                "missing": "No local implementation source supplied. Planning and reasoning documents are withheld. No billing or quota records. Native events have no timestamps; filename timezone is unknown. Missing later captures do not prove those phases never ran.",
                "retrieval": "User-supplied rama-sept15.tar.gz (reports/transcripts) and rama-sept16.tar.gz (latest-transcripts/planning documents). Original archive hashes and all member origins are retained below. No local-machine or server access is configured.",
                "phase_basis": "Capture list in archive order, not verified execution timing. Filenames are dated 15 September; the September 16 archive label is not a run timestamp. Durations are unavailable.",
                "phases": [[t["label"], None] for t in transcripts],
                "transcripts": transcripts,
                "decisions": [
                    spec["summary"],
                    "Identical captures are shown once, with every archive origin retained. Local Codex captures do not fill the missing Claude transcripts.",
                ],
                "evidence": [
                    {
                        "label": "Report metrics"
                        if f.get("export") == spec["report"]
                        else "Phase "
                        + Path(f["origins"][0]["path"]).stem.split("-phase", 1)[1],
                        "file": f["export"],
                        "origin": f["origins"][0]["path"],
                        "origins": f["origins"],
                        "sha256": f["sha256"],
                        "bytes": f["bytes"],
                        "kind": "archive transfer + safe export",
                    }
                    for f in files
                ],
            }
        )
    for run in data["runs"]:
        if run.get("metrics_file"):
            report = json.loads((REVIEW / "evidence" / run["metrics_file"]).read_text())
            row = next(
                row for row in report["rows"] if row["challenge"] == run["challenge"]
            )
            run["metrics"] = row
            if row["Private"] != run["private"] or row["Status"] != run["status"]:
                raise ValueError("Catalog disagrees with report")
        for item in run["evidence"]:
            path = REVIEW / "evidence" / item["file"]
            item["export_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    # Binds labels to this exact curated corpus, not a mutable run name alone.
    data["dataset_hash"] = hashlib.sha256(
        json.dumps(data, sort_keys=True).encode()
    ).hexdigest()
    return data


def make_server(port=8766, database=None):
    data = dataset()
    ids = {run["id"] for run in data["runs"]}
    evidence = {item["file"] for run in data["runs"] for item in run["evidence"]} | {
        "archive-index.json"
    }
    database = Path(database or REVIEW / ".state" / "annotations.sqlite3")
    database.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(database) as db:
        db.execute(
            "CREATE TABLE IF NOT EXISTS annotations (reviewer TEXT, dataset TEXT, run TEXT, label TEXT, note TEXT, updated TEXT, PRIMARY KEY(reviewer,dataset,run))"
        )

    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, body, content_type="application/json; charset=utf-8"):
            raw = body if isinstance(body, bytes) else json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.end_headers()
            self.wfile.write(raw)

        def reviewer(self, query):
            try:
                value = query["reviewer"][0]
                return str(uuid.UUID(value))
            except (KeyError, ValueError, IndexError):
                return None

        def do_GET(self):
            url = urlsplit(self.path)
            query = parse_qs(url.query)
            if url.path == "/api/data":
                return self.reply(200, data)
            if url.path == "/api/sweep":
                # Separate snapshot: sweep updates must not re-key historical reviews.
                snapshot = json.loads((REVIEW / "sweep.json").read_text())
                snapshot["private_verdict_count"] = sum(
                    a.get("private_verdict") in ("PASS", "FAIL")
                    for a in snapshot["attempts"]
                )
                return self.reply(200, snapshot)
            if url.path == "/api/annotations":
                reviewer = self.reviewer(query)
                if not reviewer:
                    return self.reply(400, {"error": "Reviewer ID required"})
                with sqlite3.connect(database) as db:
                    rows = db.execute(
                        "SELECT run,label,note,updated FROM annotations WHERE reviewer=? AND dataset=?",
                        (reviewer, data["dataset_hash"]),
                    ).fetchall()
                return self.reply(
                    200,
                    {
                        r[0]: {"label": r[1], "note": r[2], "updated": r[3]}
                        for r in rows
                    },
                )
            if url.path == "/api/source":
                name = query.get("name", [""])[0]
                if name not in SOURCES:
                    return self.reply(404, {"error": "Source not allowlisted"})
                return self.reply(
                    200, {"name": name, "text": (ROOT / name).read_text()}
                )
            if (
                url.path.startswith("/evidence/")
                and url.path[len("/evidence/") :] in evidence
            ):
                path = REVIEW / url.path.lstrip("/")
                return self.reply(200, path.read_bytes(), "text/plain; charset=utf-8")
            static = {
                "/": ("index.html", "text/html"),
                "/app.js": ("app.js", "text/javascript"),
                "/style.css": ("style.css", "text/css"),
            }
            if url.path in static:
                name, mime = static[url.path]
                return self.reply(
                    200, (REVIEW / name).read_bytes(), mime + "; charset=utf-8"
                )
            self.reply(404, {"error": "Not found"})

        def do_POST(self):
            if self.path != "/api/annotations":
                return self.reply(404, {"error": "Not found"})
            # Cross-origin pages cannot send this custom header without CORS preflight.
            if (
                self.headers.get("X-Review-Request") != "1"
                or self.headers.get("Content-Type") != "application/json"
            ):
                return self.reply(403, {"error": "Same-origin JSON request required"})
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 20000:
                    raise ValueError()
                body = json.loads(self.rfile.read(size))
                reviewer = str(uuid.UUID(body["reviewer"]))
                if body["run"] not in ids or body["dataset"] != data["dataset_hash"]:
                    raise ValueError()
                if body["label"] not in ("Pass", "Fail", "Defer", ""):
                    raise ValueError()
                if not isinstance(body["note"], str) or len(body["note"]) > 10000:
                    raise ValueError()
            except (ValueError, KeyError, TypeError, AttributeError):
                return self.reply(400, {"error": "Invalid annotation"})
            updated = datetime.now(timezone.utc).isoformat()
            with sqlite3.connect(database) as db:
                db.execute(
                    "INSERT OR REPLACE INTO annotations VALUES (?,?,?,?,?,?)",
                    (
                        reviewer,
                        data["dataset_hash"],
                        body["run"],
                        body["label"],
                        body["note"],
                        updated,
                    ),
                )
            self.reply(200, {"saved": True, "updated": updated})

        def log_message(self, format, *args):
            # Do not log query strings or annotation text.
            pass

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--database", type=Path)
    args = parser.parse_args()
    server = make_server(args.port, args.database)
    print(f"Review server ready on port {server.server_port}", flush=True)
    server.serve_forever()
