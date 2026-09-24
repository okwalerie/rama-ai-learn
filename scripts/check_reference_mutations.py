#!/usr/bin/env python3
"""Run unchanged private acceptance suites against real reference mutations.

Each baseline and mutant uses a disposable package copy, never implementations/
or the author's reference files. A killed mutant must complete the suite with
assertion failures and no runtime errors; compilation failures are not proof.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MUTATIONS = {
    "who-to-follow": ("nlb/who_to_follow.clj", "(not *already-follows?)", "true"),
    "timed-notifications": ("nlb/timed_notifications.clj", "(termval *post)", '(termval "mutated")'),
    "top-users-module": ("rama/gallery/top_users_module.clj", "(def TOP-AMOUNT 500)", "(def TOP-AMOUNT 499)"),
    "profile-module": ("rama/gallery/profile_module.clj", "(termval *pwd-hash)", '(termval "mutated")'),
    "rest-api-integration-module": ("rama/gallery/rest_api_integration_module.clj", "(.getResponseBody response)", '"mutated"'),
    "music-catalog-migration": ("rama/gallery/migrations_music_catalog_modules.clj", "(->Song name features)", "(->Song name [])"),
}


def run(package, label, output):
    env = {**os.environ, "JDK_JAVA_OPTIONS": "-Xmx1g -XX:ActiveProcessorCount=2"}
    result = subprocess.run(["clojure", "-X:test-private-harness"], cwd=package,
                            env=env, capture_output=True, text=True, timeout=240)
    log = result.stdout + result.stderr
    (output / f"{package.name}-{label}.log").write_text(log)
    counts = re.search(r"Ran (\d+) tests containing (\d+) assertions\.\s+(\d+) failures, (\d+) errors\.", log)
    if not counts:
        raise RuntimeError(f"{package.name}/{label}: no completed test summary; see log")
    tests, assertions, failures, errors = map(int, counts.groups())
    if errors or (label == "baseline" and (result.returncode or failures)):
        raise RuntimeError(f"{package.name}/{label}: baseline or runtime error; see log")
    if label == "mutant" and (failures == 0 or result.returncode == 0):
        raise RuntimeError(f"{package.name}: mutation survived")
    return dict(tests=tests, assertions=assertions, failures=failures,
                errors=errors, exit=result.returncode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("packages", nargs="+", choices=list(MUTATIONS))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    receipts = []
    for slug in args.packages:
        relative, old, new = MUTATIONS[slug]
        with tempfile.TemporaryDirectory(prefix="rama-mutation-") as temp:
            scratch = Path(temp)
            (scratch / "lib").symlink_to(ROOT / "lib", target_is_directory=True)
            package = scratch / "challenges" / slug
            shutil.copytree(ROOT / "challenges" / slug, package,
                            ignore=shutil.ignore_patterns(".cpcache", "target", ".clj-kondo"))
            file = package / "test-resources" / relative
            original = file.read_text()
            if original.count(old) != 1:
                raise RuntimeError(f"{slug}: mutation anchor is not unique")
            baseline = run(package, "baseline", args.output)
            file.write_text(original.replace(old, new, 1))
            mutant = run(package, "mutant", args.output)
            receipt = dict(package=slug, file=relative,
                           source_sha256=hashlib.sha256(original.encode()).hexdigest(),
                           before=old, after=new, baseline=baseline, mutant=mutant)
            receipts.append(receipt)
            (args.output / "receipts.json").write_text(json.dumps(receipts, indent=2) + "\n")
            print(json.dumps(receipt), flush=True)


if __name__ == "__main__":
    main()
