#!/usr/bin/env python3
"""Versioned, fail-closed entrypoint for one scored challenge in an Amp Orb.

Run from the repository with `bb orb-run --pin SHA --challenge NAME ...`.
The launcher re-executes the copy at the detached pin before doing any work.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def command(args, *, cwd=None, log=None, timeout=None):
    cwd = ROOT if cwd is None else cwd
    if log is None:
        result = subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=timeout)
        require(result.returncode == 0, f"Command failed ({args[0]}); exit {result.returncode}")
        return result.stdout
    with log.open("w") as output:
        result = subprocess.run(args, cwd=cwd, stdout=output, stderr=subprocess.STDOUT,
                                timeout=timeout)
    require(result.returncode == 0, f"Command failed ({args[0]}); see {log}")
    return ""


def git(*args):
    return command(["git", *args]).strip()


def pin_checkout(pin):
    require(re.fullmatch(r"[0-9a-f]{40}", pin), "--pin must be a full 40-character commit SHA")
    require(git("rev-parse", "--show-toplevel") == str(ROOT), "Run from the repository checkout")
    require(not git("status", "--porcelain", "--untracked-files=no"),
            "Tracked worktree changes would be lost by checkout")
    require(git("cat-file", "-t", pin) == "commit", "Assigned pin is not a local commit")
    command(["git", "checkout", "--detach", pin])
    require(git("rev-parse", "HEAD") == pin, "Detached HEAD does not match assigned pin")
    require(subprocess.run(["git", "symbolic-ref", "-q", "HEAD"], cwd=ROOT,
                           stdout=subprocess.DEVNULL).returncode == 1, "Checkout is not detached")


def verify_completion(output, agent):
    """Reject an empty, failed, or non-terminal CLI response (not merely exit 0)."""
    events = [json.loads(line) for line in output.splitlines() if line.startswith("{")]
    if agent == "claude":
        require(len(events) == 1, "Claude did not return one JSON result")
        event = events[0]
        require(event.get("type") == "result" and not event.get("is_error")
                and event.get("subtype") == "success" and event.get("result"),
                "Claude did not return a successful completion")
    else:
        require(any(e.get("type") == "text" and e.get("part", {}).get("text") for e in events),
                "OpenCode did not return completion text")
        require(not any(e.get("type") == "error" for e in events), "OpenCode returned an error")


def preflight(opts, output):
    require(os.environ.get("AMP_ORB") == "1" and os.environ.get("AMP_ORB_PROVIDER") == "e2b",
            "Execution host must be an Amp Orb")
    require(bool(os.environ.get("CHALLENGE_KEY")), "CHALLENGE_KEY is required")
    # One resource authority shared with normal runs: no launcher-side override.
    command(["bb", "run-challenges", "--resource-preflight"],
            log=output / "resources.log", timeout=60)
    require((ROOT / "challenges" / opts.challenge / "README.md").is_file(),
            "Challenge does not exist")
    require(opts.agent in ("claude", "opencode"), "Strict proxy supports only Claude or OpenCode")
    pairs = [(opts.fast_model, opts.fast_effort), (opts.slow_model, opts.slow_effort)]
    # Metadata check is not a provider entitlement check; prove each distinct model
    # with a real completion through the exact strict proxy used by solver phases.
    command(["python3", "scripts/check_solver_models.py", "--agent", opts.agent,
             *[arg for pair in pairs for arg in ("--pair", *pair)]],
            log=output / "models.log", timeout=180)
    isolation = json.loads(command(["python3", "scripts/isolate_solver.py", "--repo", str(ROOT),
                                    "--agent", opts.agent, "--network", "strict", "--preflight",
                                    "--audit-challenge", opts.challenge], timeout=180))
    require(isolation.get("probe") == "passed" and isolation.get("network") == "strict"
            and isolation.get("snapshot", {}).get("audits", {}).get(opts.challenge, {}).get("violations") == 0,
            "Snapshot audit did not pass")
    for model, effort in dict.fromkeys(pairs):
        if opts.agent == "opencode":
            cli = ["opencode", "run", "--format", "json", "--model", model,
                   "--variant", effort, "--", "Reply with OK."]
        else:
            cli = ["claude", "--print", "--output-format", "json", "--model", model,
                   "--effort", effort, "-p", "Reply with OK."]
        response = command(["python3", "scripts/isolate_solver.py", "--repo", str(ROOT),
                            "--challenge", opts.challenge, "--agent", opts.agent,
                            "--network", "strict", "--", *cli], timeout=180)
        verify_completion(response, opts.agent)
    # The reference acceptance suite is deliberately serial and must finish before
    # any solver is launched. Capture potentially private output outside the repo.
    command(["clojure", "-X:test-harness"], cwd=ROOT / "challenges" / opts.challenge,
            log=output / "reference.private.log", timeout=1800)
    return isolation


def collect_artifacts(opts, output, before):
    reports = ROOT.parent / "reports"
    manifests = set(reports.glob("*.manifest.json")) - before
    require(len(manifests) == 1, "Expected exactly one new run manifest")
    source = manifests.pop()
    manifest = json.loads(source.read_text())
    require(manifest.get("repo", {}).get("head-sha") == opts.pin, "Manifest repo SHA differs from pin")
    require(len(manifest.get("challenges", [])) == 1
            and manifest["challenges"][0].get("name") == opts.challenge,
            "Manifest does not describe exactly the assigned challenge")
    require(manifest.get("isolation", {}).get("network") == "strict", "Manifest lacks strict isolation")
    base = source.name.removesuffix(".manifest.json")
    private_ref = manifest["evaluator-log"]
    require(re.fullmatch(r"[A-Za-z0-9._-]+\.private\.log", private_ref["path"]),
            "Evaluator log reference must be a local private log")
    private = reports / private_ref["path"]
    require(private.is_file() and private.stat().st_size == private_ref["bytes"]
            and hashlib.sha256(private.read_bytes()).hexdigest() == private_ref["sha256"],
            "Private evaluator log is missing or corrupt")
    paths = [source, reports / (base + ".md"), reports / (base + ".bundle.tar.gz"), private]
    require(all(path.is_file() and path.stat().st_size for path in paths),
            "Incomplete run artifact bundle")
    with tarfile.open(paths[2], "r:gz") as bundle:
        members = [member for member in bundle.getmembers() if member.name.endswith("/BUNDLE.json")]
        require(len(members) == 1, "Run bundle has no unique BUNDLE.json")
        content = json.load(bundle.extractfile(members[0]))
        require(content.get("manifest-sha256") == hashlib.sha256(source.read_bytes()).hexdigest(),
                "Bundle manifest digest differs from the manifest file")
    for path in paths:
        shutil.copy2(path, output / path.name)
    (output / private.name).chmod(0o600)
    transcripts = output / "transcripts"
    transcripts.mkdir()
    for phase in manifest["challenges"][0].get("phases", []):
        if path_string := phase.get("transcript-path"):
            path = Path(path_string).resolve()
            require(path.parent == (ROOT.parent / "transcripts").resolve()
                    and path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == phase["transcript-sha256"],
                    "Phase transcript is missing or corrupt")
            shutil.copy2(path, transcripts / path.name)
    return [path.name for path in paths]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pin", required=True)
    parser.add_argument("--challenge", required=True)
    parser.add_argument("--agent", choices=("claude", "opencode"), required=True)
    for tier in ("fast", "slow"):
        parser.add_argument(f"--{tier}-model", required=True)
        parser.add_argument(f"--{tier}-effort", required=True)
    parser.add_argument("--output", type=Path, help="Bundle directory, outside the repository")
    parser.add_argument("--checked-out", action="store_true", help=argparse.SUPPRESS)
    opts = parser.parse_args(argv)
    require(re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]*", opts.challenge), "Invalid challenge name")
    if not opts.checked_out:
        pin_checkout(opts.pin)
        # The invoked launcher, not just the runner, must come from the pinned tree.
        os.execv(sys.executable, [sys.executable, str(ROOT / "scripts/orb_run.py"),
                                   *(sys.argv[1:] if argv is None else argv), "--checked-out"])
    require(git("rev-parse", "HEAD") == opts.pin
            and subprocess.run(["git", "symbolic-ref", "-q", "HEAD"], cwd=ROOT,
                               stdout=subprocess.DEVNULL).returncode == 1,
            "Assigned pin is no longer detached HEAD")
    output = (opts.output or ROOT.parent / "reports" / ("orb-" + opts.challenge + "-" +
              datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))).resolve()
    require(not output.is_relative_to(ROOT), "Artifacts must be outside the repository")
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    command(["bash", ".agents/setup"], log=output / "setup.log")
    isolation = preflight(opts, output)
    reports = ROOT.parent / "reports"
    before = set(reports.glob("*.manifest.json"))
    command(["bb", "run-challenges", "--filter", opts.challenge, "--agent", opts.agent,
             "--isolate-network", "--fast-model", opts.fast_model, "--fast-effort", opts.fast_effort,
             "--slow-model", opts.slow_model, "--slow-effort", opts.slow_effort],
            log=output / "run.log")
    files = collect_artifacts(opts, output, before)
    (output / "LAUNCH.json").write_text(json.dumps({"schema-version": 1, "pin": opts.pin,
        "challenge": opts.challenge, "agent": opts.agent, "models": {
            "fast": [opts.fast_model, opts.fast_effort], "slow": [opts.slow_model, opts.slow_effort]},
        "isolation": isolation, "files": files}, indent=2) + "\n")
    print(f"Complete run artifact bundle: {output}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as error:
        sys.exit(f"Orb launch failed closed: {error}")
