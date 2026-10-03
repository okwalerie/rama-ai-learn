#!/usr/bin/env python3
"""Fetch a wave's run archives from the private runs release and import them.

  bb fetch-runs --wave N                        # import every run's results
  bb fetch-runs --wave N --with-transcripts RUN # also merge RUN's transcripts
  bb fetch-runs --wave N --list                 # show assets, download nothing

Results archives import as runs/<run>/; a transcripts archive merges into the
same runs/<run>/ (see scripts/import_run_bundle.py). Runs already imported are
skipped without downloading.
"""
import argparse
from pathlib import Path
import sys
import tempfile

from import_run_bundle import (IMPORT_ERRORS, MANIFEST, TRANSCRIPTS_MANIFEST, archive_part,
                               bundle_name, import_bundle)
from run_archives import RUNS_REPO, ArchiveError, download_assets, human, release_assets, wave_tag

RUNS_DIR = Path(__file__).resolve().parents[1] / "runs"


def wave_runs(assets):
    runs = {}
    for name, asset in assets.items():
        if part := archive_part(name):
            runs.setdefault(bundle_name(name), {})[part] = asset
    return runs


def print_listing(runs, dest):
    header = ("run", "results", "transcripts", "imported")
    rows = []
    for run, parts in sorted(runs.items()):
        imported = ("results+transcripts" if (dest / run / TRANSCRIPTS_MANIFEST).is_file() else
                    "results" if (dest / run / MANIFEST).is_file() else "-")
        rows.append((run, *(human(parts[p]["size"]) if p in parts else "-"
                            for p in ("results", "transcripts")), imported))
    widths = [max(len(r[i]) for r in rows + [header]) for i in range(len(header))]
    for r in [header] + rows:
        print("  ".join(c.ljust(w) for c, w in zip(r, widths)).rstrip())


def print_unimportable(names):
    print(f"{len(names)} asset(s) not importable by fetch-runs (legacy or unrecognized):")
    for name in names:
        print(f"  {name}")


def no_results_error(tag, repo, unimportable):
    message = f"{tag} has no results archives (*.results.tar.zst) to import"
    if not unimportable:
        return ArchiveError(message)
    return ArchiveError(
        f"{message}; its {len(unimportable)} other asset(s) are legacy or unrecognized and "
        f"not importable by fetch-runs: {', '.join(unimportable)}. Legacy bundles vary in "
        f"format (many list \"files\" without sha256s) and most are not importable; download "
        f"them for manual inspection (gh release download {tag} --repo {repo} --pattern "
        f"'*.legacy.tar.gz' --dir DIR) and only import an individual bundle with "
        f"scripts/import_run_bundle.py after confirming its BUNDLE.json maps \"files\" to sha256s")


def fetch(wave, repo=RUNS_REPO, dest=RUNS_DIR, with_transcripts=(), listing=False):
    tag = wave_tag(wave)
    assets = release_assets(repo, tag)
    if assets is None:
        raise ArchiveError(f"{repo} has no release {tag}")
    runs = wave_runs(assets)
    unimportable = sorted(name for name in assets if not archive_part(name))
    dest = Path(dest)
    if listing:
        print_listing(runs, dest)
        if unimportable:
            print_unimportable(unimportable)
        return 0
    if assets and not any("results" in parts for parts in runs.values()):
        raise no_results_error(tag, repo, unimportable)
    unknown = [run for run in with_transcripts if "transcripts" not in runs.get(run, {})]
    if unknown:
        raise ArchiveError(f"{tag} has no transcripts archive for: {', '.join(unknown)}")
    wanted = []
    for run, parts in sorted(runs.items()):
        if "results" not in parts:
            print(f"WARNING {run}: release has no results archive", file=sys.stderr)
        elif (dest / run / MANIFEST).exists():
            print(f"already imported {run}")
        else:
            wanted.append(f"{run}.results.tar.zst")
    for run in dict.fromkeys(with_transcripts):
        if (dest / run / TRANSCRIPTS_MANIFEST).exists():
            print(f"transcripts already merged {run}")
        else:
            wanted.append(f"{run}.transcripts.tar.zst")
    dest.mkdir(parents=True, exist_ok=True)
    failed = False
    with tempfile.TemporaryDirectory() as tmp:
        download_assets(repo, tag, wanted, tmp)
        # Results first: transcripts merge only into an imported run.
        for name in sorted(wanted, key=lambda n: archive_part(n) == "transcripts"):
            try:
                target, _ = import_bundle(Path(tmp) / name, dest)
                print(f"imported {name} -> {target}")
            except IMPORT_ERRORS as error:
                failed = True
                print(f"REJECTED {name}: {error}", file=sys.stderr)
    return 1 if failed else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--wave", type=int, required=True)
    parser.add_argument("--with-transcripts", action="append", default=[], metavar="RUN",
                        help="also fetch and merge this run's transcripts (repeatable)")
    parser.add_argument("--list", action="store_true", help="list the wave's archives only")
    parser.add_argument("--dest", type=Path, default=RUNS_DIR)
    parser.add_argument("--repo", default=RUNS_REPO)
    opts = parser.parse_args(argv)
    try:
        return fetch(opts.wave, opts.repo, opts.dest, opts.with_transcripts, opts.list)
    except ArchiveError as error:
        print(f"fetch-runs FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
