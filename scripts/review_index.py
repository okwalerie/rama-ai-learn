#!/usr/bin/env python3
"""Allowlisted review exports. Never publish native logs or reference justifications.

Report input is a runner Markdown report; transcript input comes through the
supported analyze-latest-transcript.py safe-review command. Free text is omitted,
not heuristically redacted. Curated excerpts require a separate human review.
"""

import argparse
import hashlib
import json
import math
import re
from pathlib import Path

from transcript_events import timestamp

TOOLS = {
    "Read",
    "Write",
    "Edit",
    "Bash",
    "Glob",
    "Grep",
    "Skill",
    "TodoWrite",
    "FileChange",
    "Task",
    "WebFetch",
    "WebSearch",
}
USAGE = (
    "input_tokens",
    "output_tokens",
    "cache_read_input_tokens",
    "cache_creation_input_tokens",
)


def number(value):
    return (
        value
        if type(value) in (int, float) and math.isfinite(value) and value >= 0
        else None
    )


def safe_events(events):
    """Export observable event shape only, dropping all free-form source content.

    Indices refer to the normalized stream, not native JSONL line numbers.
    An assistant verdict is a claimed phase outcome, never a private-test result.
    """
    rows = []
    for index, event in enumerate(events):
        kind = event.get("type")
        row = {"index": index, "time": timestamp(event.get("timestamp"))}
        if kind in ("assistant", "user"):
            message = event.get("message") or {}
            content = message.get("content") or []
            if isinstance(content, str):
                content = [{"type": "text", "text": content}]
            for block in content:
                if not isinstance(block, dict):
                    continue
                btype = block.get("type")
                if btype == "tool_use":
                    name = block.get("name")
                    rows.append(
                        {
                            **row,
                            "kind": "tool_call",
                            "tool": name if name in TOOLS else "Other tool",
                            "detail": "Arguments withheld",
                        }
                    )
                elif btype == "tool_result":
                    rows.append(
                        {
                            **row,
                            "kind": "tool_result",
                            "error": block.get("is_error") is True,
                            "detail": "Result content withheld",
                        }
                    )
                elif btype == "text" and kind == "assistant":
                    # Only a complete, narrowly defined verdict line may cross.
                    verdicts = re.findall(
                        r"^\s*PHASE_VERDICT:\s*(PASS|FAIL|MAJOR_FAIL|MINOR_FAIL)\s*$",
                        block.get("text", ""),
                        re.MULTILINE,
                    )
                    for verdict in verdicts:
                        rows.append(
                            {**row, "kind": "claimed_verdict", "verdict": verdict}
                        )
        elif kind in ("usage", "result"):
            usage = event.get("usage") or {}
            status = event.get("status")
            rows.append(
                {
                    **row,
                    "kind": kind,
                    "status": status
                    if status in ("completed", "incomplete", "error", "timeout")
                    else None,
                    "is_error": event.get("is_error")
                    if type(event.get("is_error")) is bool
                    else None,
                    "duration_ms": number(event.get("duration_ms")),
                    "cost_usd": number(event.get("total_cost_usd")),
                    "usage": {key: number(usage.get(key)) for key in USAGE},
                }
            )
        elif kind == "run_metadata":
            isolation = event.get("isolation")
            rows.append(
                {
                    **row,
                    "kind": kind,
                    "exit": event.get("exit")
                    if type(event.get("exit")) is int
                    else None,
                    "isolation": isolation
                    if isolation
                    in ("none", "bubblewrap-public-only", "bubblewrap-provider-network")
                    else None,
                    "duration_s": number(event.get("duration_s")),
                    "timed_out": event.get("timed_out")
                    if type(event.get("timed_out")) is bool
                    else None,
                }
            )
    return {
        "schema": 1,
        "policy": "metadata-only; no thinking, message text, arguments, results, stderr or source_event",
        "normalized_event_count": len(events),
        "events": rows,
    }


def report_metrics(text):
    """Extract only typed fields from the runner table, never grading prose."""
    lines = [line for line in text.splitlines() if line.startswith("|")]
    if len(lines) < 3:
        raise ValueError("Runner report table missing")
    header = [cell.strip() for cell in lines[0].strip("|").split("|")]
    rows = []
    for line in lines[2:]:
        cells = [cell.strip() for cell in line.strip("|").split("|")]
        if len(cells) != len(header):
            raise ValueError("Malformed report row")
        source = dict(zip(header, cells))
        challenge = source.get("Challenge", "")
        if not re.fullmatch(r"[a-z0-9-]+", challenge):
            raise ValueError("Invalid challenge identifier")
        row = {"challenge": challenge}
        for key in ("Status", "Private"):
            value = source.get(key)
            if value not in ("PASS", "FAIL", "TIMEOUT", "-", "SKIP"):
                raise ValueError("Invalid report status")
            row[key] = value
        for key in (
            "Score",
            "Iterations",
            "Duration",
            "In Tokens",
            "Out Tokens",
            "Cache Create",
            "Cache Read",
            "Tool Uses",
            "Cost",
            "Align",
            "TestAlign",
        ):
            value = source.get(key, "-")
            if value in ("-", "N/A"):
                row[key] = None
            else:
                value = value.removeprefix("$").removesuffix("s")
                if not re.fullmatch(r"\d+(?:\.\d+)?", value):
                    raise ValueError(f"Invalid numeric field: {key}")
                row[key] = number(float(value))
                if row[key] is None:
                    raise ValueError("Non-finite metric")
        rows.append(row)
    if not rows:
        raise ValueError("Empty report")
    return rows


def receipt(path):
    data = Path(path).read_bytes()
    return {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    print(
        json.dumps(
            {
                "schema": 1,
                **receipt(args.report),
                "rows": report_metrics(args.report.read_text()),
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
