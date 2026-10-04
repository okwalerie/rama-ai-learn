#!/usr/bin/env python3
"""Pack one challenge's run artifacts from inside an Amp orb into a legacy
bundle that scripts/import_run_bundle.py accepts, and optionally upload it.

Runs with the standard library only, so an orb checked out at an old pin can
fetch and run the current copy:

  git fetch -q origin master && git show origin/master:scripts/recover_orb_artifacts.py > /tmp/recover.py
  python3 /tmp/recover.py --challenge NAME --provider claude --name BUNDLE [--upload]

Collected from the repository and its parent workspace (paths kept relative
to the workspace): implementations/<challenge>/, transcripts/ and
latest-transcripts/ files named for the challenge, logs/ and reports/ paths
named for the challenge, runner reports (reports/*.md) that mention it, and
reports/results.edn. Private tests, encrypted files, credentials, build
caches and files over the size cap are skipped. BUNDLE.json maps every file
to its sha256; RECOVERY.json records provenance and what was skipped.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
from datetime import datetime, timezone

RUNS_REPO = "okwalerie/rama-ai-learn-runs"
REPO = Path(__file__).resolve().parents[1] if (Path(__file__).resolve().parents[1] / ".git").exists() \
    else Path("/home/user/workspace/repo")
FORBIDDEN_PARTS = {"test-private", "test-resources", ".claude", ".ssh", ".aws", ".config", ".git"}
FORBIDDEN_NAMES = {".credentials.json", "auth.json", ".env", ".netrc", "id_rsa", "id_ed25519"}
SKIP_PARTS = {"target", ".cpcache", "node_modules", ".clj-kondo", ".lsp", "__pycache__", ".nrepl-port"}
MAX_FILE = 64 << 20
MAX_TOTAL = 1 << 30


def allowed(rel):
    parts = rel.parts
    if FORBIDDEN_PARTS.intersection(parts) or SKIP_PARTS.intersection(parts):
        return False
    return parts[-1] not in FORBIDDEN_NAMES and not parts[-1].endswith(".enc")


def walk(path):
    if path.is_file():
        yield path
    elif path.is_dir():
        for root, dirs, files in os.walk(path):
            dirs[:] = [d for d in dirs if d not in SKIP_PARTS and d not in FORBIDDEN_PARTS]
            for f in files:
                yield Path(root) / f


def named_for(path, keys):
    text = str(path).lower()
    return any(k in text for k in keys)


def collect(challenge, roots):
    keys = {challenge.lower(), challenge.lower().replace("-", "_")}
    found = {}
    for base in roots:
        for sub in ("implementations",):
            for key in keys:
                for f in walk(base / sub / key):
                    found.setdefault(f.resolve(), f)
        for sub in ("transcripts", "latest-transcripts", "logs", "reports", "runs"):
            for f in walk(base / sub):
                if named_for(f.relative_to(base / sub), keys):
                    found.setdefault(f.resolve(), f)
        reports = base / "reports"
        if reports.is_dir():
            for f in reports.glob("*.md"):
                try:
                    if any(k in f.read_text(errors="replace")[:200_000].lower() for k in keys):
                        found.setdefault(f.resolve(), f)
                except OSError:
                    pass
            if (reports / "results.edn").is_file():
                found.setdefault((reports / "results.edn").resolve(), reports / "results.edn")
    return list(found.values())


def git(*args):
    r = subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True)
    return r.stdout.strip()


def build(opts):
    workspace = REPO.parent
    files, skipped, total = {}, [], 0
    for f in sorted(collect(opts.challenge, [REPO, workspace]), key=str):
        rel = f.relative_to(workspace)
        if not allowed(rel) or f.is_symlink():
            skipped.append([str(rel), "excluded"])
            continue
        size = f.stat().st_size
        if size > MAX_FILE or total + size > MAX_TOTAL:
            skipped.append([str(rel), f"size {size}"])
            continue
        files[str(rel)] = f.read_bytes()
        total += size
    recovery = {
        "challenge": opts.challenge, "provider": opts.provider, "name": opts.name,
        "thread": os.environ.get("AMP_THREAD_ID"), "recovered_at": datetime.now(timezone.utc).isoformat(),
        "repo_head": git("rev-parse", "HEAD"), "repo_status": git("status", "--short")[:20000],
        "skipped": skipped,
    }
    files["RECOVERY.json"] = json.dumps(recovery, indent=2).encode()
    manifest = {
        "kind": "legacy-recovered", "challenge": opts.challenge, "provider": opts.provider,
        "thread": recovery["thread"], "recovered_at": recovery["recovered_at"],
        "files": {p: hashlib.sha256(d).hexdigest() for p, d in sorted(files.items())},
        "sizes": {p: len(d) for p, d in sorted(files.items())},
    }
    out = Path(opts.dest) / f"{opts.name}.legacy.tar.gz"
    out.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with tarfile.open(out, "w:gz") as tar:
        for path, data in [("BUNDLE.json", json.dumps(manifest, indent=2).encode()), *sorted(files.items())]:
            info = tarfile.TarInfo(path)
            info.size, info.mode, info.mtime = len(data), 0o644, 0
            tar.addfile(info, io.BytesIO(data))
    # Read back and verify every hash before anything leaves the orb.
    with tarfile.open(out, "r:gz") as tar:
        back = {m.name: tar.extractfile(m).read() for m in tar.getmembers() if m.isfile()}
    listed = json.loads(back.pop("BUNDLE.json"))["files"]
    if set(listed) != set(back) or any(hashlib.sha256(d).hexdigest() != listed[p] for p, d in back.items()):
        raise SystemExit("bundle read-back verification failed")
    impl = sum(1 for p in files if "/implementations/" in f"/{p}")
    return out, len(files), total, impl, len(skipped)


def gh(*args):
    return subprocess.run(["gh", *args], capture_output=True, text=True, timeout=900)


def upload(path, tag):
    r = gh("api", f"repos/{RUNS_REPO}", "--jq", ".private")
    if r.stdout.strip() != "true":
        raise SystemExit(f"refusing upload: {RUNS_REPO} is not confirmed private ({r.stderr.strip()[:200]})")
    r = gh("release", "view", tag, "--repo", RUNS_REPO, "--json", "assets")
    if r.returncode:
        raise SystemExit(f"release {tag} not found; it must be created before recovery: {r.stderr.strip()[:200]}")
    if any(a["name"] == path.name for a in json.loads(r.stdout)["assets"]):
        return "already-uploaded"
    r = gh("release", "upload", tag, str(path), "--repo", RUNS_REPO)
    if r.returncode:
        raise SystemExit(f"upload failed: {r.stderr.strip()[:300]}")
    return "uploaded"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--challenge", required=True)
    p.add_argument("--provider", default="unknown")
    p.add_argument("--name", required=True, help="bundle name (no extension)")
    p.add_argument("--dest", default="/tmp/recovered")
    p.add_argument("--tag", default="recovered")
    p.add_argument("--upload", action="store_true")
    opts = p.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", opts.name):
        raise SystemExit("--name must be a safe file name")
    out, n, total, impl, skipped = build(opts)
    status = upload(out, opts.tag) if opts.upload else "not-uploaded"
    print(f"RECOVERY {status} {out.name} files={n} implementation_files={impl} "
          f"bytes={total} skipped={skipped}")


if __name__ == "__main__":
    main()
