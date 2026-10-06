#!/usr/bin/env python3
"""Fetch, import and summarize run bundles recovered from Amp orbs.

  python3 scripts/recovered_runs.py fetch [--tag recovered]   # download + import into runs/
  python3 scripts/recovered_runs.py table [--json]            # one row per recovered attempt

Bundles come from scripts/recover_orb_artifacts.py (release `recovered` of the
private runs repository). Each attempt's score is its runner record in
reports/results.edn: the last record for the bundle's challenge (and agent,
when the provider is known). Thread metadata is joined from
scripts/amp_threads.py's index when present.
"""
import argparse
import collections
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
RUNS = ROOT / "runs"
RUNS_REPO = "okwalerie/rama-ai-learn-runs"
INDEX = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "rama-ai-learn" / "amp-threads" / "index.jsonl"
EDN_TO_JSON = ("(require '[cheshire.core :as j]) "
               "(doseq [l (line-seq (java.io.BufferedReader. *in*)) :when (seq (clojure.string/trim l))] "
               "(println (try (j/generate-string (clojure.edn/read-string l)) (catch Exception _ \"null\"))))")


def gh(*args):
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(f"gh {' '.join(args[:2])} failed: {r.stderr.strip()[:300]}")
    return r.stdout


def cmd_fetch(args):
    sys.path.insert(0, str(ROOT / "scripts"))
    from import_run_bundle import BundleError, import_bundle
    RUNS.mkdir(exist_ok=True)
    assets = json.loads(gh("release", "view", args.tag, "--repo", RUNS_REPO, "--json", "assets"))["assets"]
    todo = [a["name"] for a in assets if a["name"].endswith(".legacy.tar.gz")
            and not (RUNS / a["name"][: -len(".tar.gz")]).exists()]
    print(f"{len(assets)} assets in {args.tag}; {len(todo)} to import", file=sys.stderr)
    with tempfile.TemporaryDirectory() as tmp:
        for i in range(0, len(todo), 20):
            batch = todo[i:i + 20]
            gh("release", "download", args.tag, "--repo", RUNS_REPO, "--dir", tmp,
               *[x for n in batch for x in ("--pattern", n)])
            for name in batch:
                try:
                    import_bundle(Path(tmp) / name, RUNS)
                except BundleError as e:
                    print(f"  rejected {name}: {e}", file=sys.stderr)


def edn_records(path):
    r = subprocess.run(["bb", "-e", EDN_TO_JSON], input=path.read_text(errors="replace"),
                       capture_output=True, text=True)
    return [json.loads(l) for l in r.stdout.splitlines() if l and l != "null"]


def run_row(run, threads):
    manifest = json.loads((run / "BUNDLE.json").read_text())
    challenge, provider = manifest.get("challenge"), manifest.get("provider")
    files = list(manifest.get("files", {}))
    records = []
    for edn in run.rglob("results.edn"):
        records += [r for r in edn_records(edn) if r.get("challenge") == challenge]
    if provider in ("claude", "opencode", "codex", "pi"):
        records = [r for r in records if r.get("agent") == provider] or records
    records.sort(key=lambda r: r.get("timestamp") or "")
    last = records[-1] if records else {}
    thread = threads.get(manifest.get("thread")) or {}
    phases = sorted({m.group(1) for f in files if f.endswith(".jsonl")
                     for m in [re.search(r"-phase([A-Za-z0-9-]+)\.jsonl$", f)] if m})
    return {
        "run": run.name, "thread": manifest.get("thread"), "title": thread.get("title"),
        "wave": thread.get("wave"), "created": (thread.get("created") or "")[:10],
        "challenge": challenge, "provider": provider,
        "impl_files": sum("/implementations/" in f"/{f}" for f in files),
        "transcripts": sum(f.endswith(".jsonl") for f in files),
        "phases": phases,
        "ended": last.get("timestamp"),
        "runner_records": len(records),
        "model": last.get("model"), "reasoning": last.get("reasoning"),
        "status": last.get("status"), "outcome": last.get("outcome"),
        "private": last.get("private-status"), "private_counts": last.get("private-counts"),
        "score": last.get("challenge-score"), "duration_s": last.get("duration-s"),
        "tokens_in": last.get("input-tokens"), "tokens_out": last.get("output-tokens"),
        "cost": last.get("cost"), "builds": last.get("builds"), "retries": last.get("retries"),
    }


def cmd_table(args):
    threads = {}
    if INDEX.exists():
        threads = {r["id"]: r for r in map(json.loads, INDEX.open())}
    rows = [run_row(run, threads) for run in sorted(RUNS.glob("recovered-*")) if (run / "BUNDLE.json").exists()]
    if args.json:
        print(json.dumps(rows, indent=1))
        return
    hdr = f"{'created':10} {'challenge':34} {'prov':8} {'model':22} {'status':6} {'private':8} {'score':>5} {'min':>5} {'impl':>4} {'tx':>3} {'ended':5} phases"
    print(hdr)
    for r in sorted(rows, key=lambda r: (r["challenge"] or "", r["provider"] or "", r["created"])):
        dur = f"{r['duration_s'] / 60:.0f}" if isinstance(r["duration_s"], (int, float)) else "-"
        print(f"{r['created']:10} {(r['challenge'] or '-')[:34]:34} {(r['provider'] or '-'):8} "
              f"{str(r['model'] or '-')[:22]:22} {str(r['status'] or '-'):6} {str(r['private'] or '-'):8} "
              f"{str(r['score'] if r['score'] is not None else '-'):>5} {dur:>5} {r['impl_files']:>4} {r['transcripts']:>3} {(r['ended'] or '')[11:16]:5} {','.join(r['phases'])}")
    by = collections.Counter((r["provider"], r["private"]) for r in rows)
    print(f"\n{len(rows)} recovered runs; private status by provider: "
          + ", ".join(f"{p}/{s}={n}" for (p, s), n in sorted(by.items(), key=str)))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("fetch")
    s.add_argument("--tag", default="recovered")
    s.set_defaults(fn=cmd_fetch)
    s = sub.add_parser("table")
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_table)
    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
