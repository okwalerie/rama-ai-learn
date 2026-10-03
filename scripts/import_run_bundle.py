#!/usr/bin/env python3
"""Import solver-run bundles produced in remote workers (e.g. Amp orbs).

Solver output (implementations/, reports/, logs/, latest-transcripts/) is
gitignored on purpose, so it cannot travel on a branch. A worker packs one
run into archives with a top-level BUNDLE.json that lists a sha256 for every
file. This script verifies an archive and unpacks it under runs/<run>/
without touching the working implementations/ tree.

Run archives (scripts/run_archives.py) come in two parts per run:
`<challenge>-<provider>-<run_id>.results.tar.zst` imports as runs/<run>/;
the matching `.transcripts.tar.zst` merges into that existing run only when
its identity agrees and none of its paths already exist. A legacy
`<name>.tar.gz` bundle imports as runs/<name>/ only if its BUNDLE.json maps
"files" to sha256s; legacy formats vary (many list "files"), and
nonconforming bundles are rejected.

  python3 scripts/import_run_bundle.py ARCHIVE [...] [--dest runs] [--force]
  python3 scripts/import_run_bundle.py --list [--dest runs]
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

MANIFEST = "BUNDLE.json"
TRANSCRIPTS_MANIFEST = "BUNDLE.transcripts.json"
FORBIDDEN_PARTS = {"test-private", "test-resources", ".claude", ".ssh", ".aws", ".config"}
FORBIDDEN_NAMES = {".credentials.json", "auth.json", ".env", ".netrc", "id_rsa", "id_ed25519"}
ARCHIVE_KIND = "rama-ai-learn-run-archive"
PARTS = ("results", "transcripts")
# Top-level entries each archive part may hold. Disjoint, so a transcript
# merge can never replace a results file.
PART_ROOTS = {"results": {"implementation", "report.md", "manifest.json", "run-bundle.tar.gz",
                          "launch.json", "private.log"},
              "transcripts": {"transcripts", "logs"}}
# Everything except these must agree between the two parts of one run.
PART_LOCAL_KEYS = {"part", "files", "sizes"}
MAX_UNPACKED_BYTES = 2 << 30
SHA256 = re.compile(r"[0-9a-f]{64}")
SAFE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
# Imported runs hold the evaluator-only log: owner-only regardless of umask.
RUN_DIR_MODE = 0o700
PRIVATE_LOG = "private.log"
PRIVATE_LOG_MODE = 0o600


class BundleError(Exception):
    pass


def archive_part(path):
    name = Path(path).name
    for part in PARTS:
        if name.endswith(f".{part}.tar.zst"):
            return part
    return None


def bundle_name(path):
    name = Path(path).name
    for suffix in (*(f".{part}.tar.zst" for part in PARTS), ".tar.gz", ".tgz", ".tar"):
        if name.endswith(suffix):
            return name[: -len(suffix)]
    return name


def safe_member_path(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        raise BundleError(f"unsafe path in bundle: {name}")
    parts = [p for p in path.parts if p != "."]
    if not parts:
        return None
    if FORBIDDEN_PARTS.intersection(parts) or parts[-1] in FORBIDDEN_NAMES or parts[-1].endswith(".enc"):
        raise BundleError(f"forbidden path in bundle: {name}")
    return PurePosixPath(*parts)


def check_path_set(paths):
    """Reject case-insensitive duplicates and a file that is also a directory."""
    folded = {}
    for p in paths:
        other = folded.setdefault(p.casefold(), p)
        if other != p:
            raise BundleError(f"colliding paths in bundle: {other}, {p}")
    dirs = {str(parent) for p in paths for parent in PurePosixPath(p).parents}
    clash = sorted(dirs.intersection(paths))
    if clash:
        raise BundleError(f"path is both file and directory: {', '.join(clash)}")


def open_tar(bundle_path):
    if archive_part(bundle_path) is None:
        return tarfile.open(bundle_path, "r:*")
    with tempfile.TemporaryFile() as err_file, \
            subprocess.Popen(["zstd", "-dcq", "--", str(bundle_path)],
                             stdout=subprocess.PIPE, stderr=err_file) as proc:
        chunks, total = [], 0
        while chunk := proc.stdout.read(1 << 20):
            total += len(chunk)
            if total > MAX_UNPACKED_BYTES:
                proc.kill()
                raise BundleError(f"archive unpacks to more than {MAX_UNPACKED_BYTES} bytes")
            chunks.append(chunk)
        if proc.wait() != 0:
            err_file.seek(0)
            raise BundleError("zstd could not decompress archive: "
                              + err_file.read().decode(errors="replace").strip())
    return tarfile.open(fileobj=io.BytesIO(b"".join(chunks)), mode="r:")


def read_bundle(bundle_path):
    """Return (manifest, {relpath: bytes}) after verifying every file hash."""
    files = {}
    with open_tar(bundle_path) as tar:
        for member in tar.getmembers():
            rel = safe_member_path(member.name)
            if rel is None or member.isdir():
                continue
            if not member.isfile():
                raise BundleError(f"non-regular file in bundle: {member.name}")
            if str(rel) in files:
                raise BundleError(f"duplicate path in bundle: {rel}")
            files[str(rel)] = tar.extractfile(member).read()
    if MANIFEST not in files:
        raise BundleError(f"missing {MANIFEST}")
    manifest = json.loads(files.pop(MANIFEST))
    if not isinstance(manifest, dict):
        raise BundleError(f"{MANIFEST} must be a JSON object")
    listed_raw = manifest.get("files")
    if not isinstance(listed_raw, dict):
        raise BundleError(f"{MANIFEST} must map file paths to sha256 under \"files\"")
    listed = {}
    for k, v in listed_raw.items():
        rel = safe_member_path(k)
        if rel is None or str(rel) == MANIFEST:
            raise BundleError(f"invalid listed path: {k}")
        if str(rel) in listed:
            raise BundleError(f"duplicate listed path: {rel}")
        if not (isinstance(v, str) and SHA256.fullmatch(v)):
            raise BundleError(f"listed sha256 is not 64 lowercase hex digits: {k}")
        listed[str(rel)] = v
    missing = sorted(set(listed) - set(files))
    unlisted = sorted(set(files) - set(listed))
    if missing:
        raise BundleError(f"listed but absent: {', '.join(missing)}")
    if unlisted:
        raise BundleError(f"present but unlisted: {', '.join(unlisted)}")
    check_path_set(list(files))
    bad = sorted(p for p, data in files.items() if hashlib.sha256(data).hexdigest() != listed[p])
    if bad:
        raise BundleError(f"sha256 mismatch: {', '.join(bad)}")
    return manifest, files


def run_name(manifest):
    return f"{manifest.get('challenge')}-{manifest.get('provider')}-{manifest.get('run_id')}"


def read_archive(path):
    """read_bundle plus the identity rules of a two-part run archive."""
    part = archive_part(path)
    manifest, files = read_bundle(path)
    if part is None:
        return manifest, files
    if manifest.get("kind") != ARCHIVE_KIND or manifest.get("part") != part:
        raise BundleError(f"{MANIFEST} does not describe a {part} run archive")
    for key in ("challenge", "provider", "run_id"):
        if not (isinstance(manifest.get(key), str) and SAFE_NAME.fullmatch(manifest[key])):
            raise BundleError(f"{MANIFEST} has no valid {key}")
    if run_name(manifest) != bundle_name(path):
        raise BundleError(f"archive name {Path(path).name} does not match its {MANIFEST} "
                          f"identity {run_name(manifest)}")
    if not SHA256.fullmatch(str(manifest.get("manifest_sha256"))):
        raise BundleError(f"{MANIFEST} has no manifest_sha256")
    stray = sorted(p for p in files if PurePosixPath(p).parts[0] not in PART_ROOTS[part])
    if stray:
        raise BundleError(f"paths outside the {part} part: {', '.join(stray)}")
    if part == "results":
        if "manifest.json" not in files:
            raise BundleError("results archive has no manifest.json")
        if hashlib.sha256(files["manifest.json"]).hexdigest() != manifest["manifest_sha256"]:
            raise BundleError("manifest.json differs from manifest_sha256")
    return manifest, files


def identity(manifest):
    return {k: v for k, v in manifest.items() if k not in PART_LOCAL_KEYS}


def check_same_run(results, transcripts):
    a, b = identity(results), identity(transcripts)
    differ = sorted(k for k in a.keys() | b.keys() if a.get(k) != b.get(k))
    if differ:
        raise BundleError(f"transcripts archive identity differs from the run: {', '.join(differ)}")


def write_tree(root, files):
    for rel, data in files.items():
        out = root / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(data)


def import_bundle(bundle_path, dest, force=False):
    """Import a results archive or a legacy bundle as dest/<name>/; merge a
    transcripts archive into its existing run."""
    if archive_part(bundle_path) == "transcripts":
        return merge_transcripts(bundle_path, dest)
    manifest, files = read_archive(bundle_path)
    target = Path(dest) / bundle_name(bundle_path)
    if target.exists() and not force:
        raise BundleError(f"{target} already exists (use --force to replace)")
    with tempfile.TemporaryDirectory(dir=dest, prefix=".staging-") as staging:
        root = Path(staging) / "run"
        root.mkdir()
        os.chmod(root, RUN_DIR_MODE)
        write_tree(root, files)
        if PRIVATE_LOG in files:
            os.chmod(root / PRIVATE_LOG, PRIVATE_LOG_MODE)
        (root / MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        old = None
        if target.exists():
            # Replace only after the new copy is fully staged.
            old = Path(staging) / "old"
            target.rename(old)
        try:
            root.rename(target)
        except OSError:
            if old is not None:
                old.rename(target)
            raise
    return target, manifest


def merge_transcripts(bundle_path, dest):
    manifest, files = read_archive(bundle_path)
    target = Path(dest) / bundle_name(bundle_path)
    results_path = target / MANIFEST
    if not results_path.is_file():
        raise BundleError(f"{target} has no imported results; import the results archive first")
    results = json.loads(results_path.read_text())
    if results.get("kind") != ARCHIVE_KIND or results.get("part") != "results":
        raise BundleError(f"{target} was not imported from a results archive")
    check_same_run(results, manifest)
    run_manifest = target / "manifest.json"
    if not run_manifest.is_file() or \
            hashlib.sha256(run_manifest.read_bytes()).hexdigest() != results["manifest_sha256"]:
        raise BundleError(f"{run_manifest} is missing or modified")
    expected = {Path(p["transcript-path"]).name: p.get("transcript-sha256")
                for c in json.loads(run_manifest.read_text()).get("challenges", [])
                for p in c.get("phases", []) if p.get("transcript-path")}
    present = set()
    for rel, data in files.items():
        parts = PurePosixPath(rel).parts
        if parts[0] == "transcripts":
            if len(parts) != 2 or expected.get(parts[1]) != hashlib.sha256(data).hexdigest():
                raise BundleError(f"{rel} is not a phase transcript recorded in the run manifest")
            present.add(parts[1])
    if present != set(expected):
        raise BundleError(f"phase transcripts absent: {', '.join(sorted(set(expected) - present))}")
    recorded = target / TRANSCRIPTS_MANIFEST
    if recorded.exists():
        if json.loads(recorded.read_text()) == manifest:
            return target, manifest
        raise BundleError(f"{target} already holds different transcripts")
    tops = sorted({PurePosixPath(rel).parts[0] for rel in files})
    clash = [top for top in tops if (target / top).exists() or (target / top).is_symlink()]
    if clash:
        raise BundleError(f"transcript merge would overwrite: {', '.join(clash)}")
    with tempfile.TemporaryDirectory(dir=dest, prefix=".staging-") as staging:
        root = Path(staging)
        write_tree(root, files)
        moved = []
        try:
            for top in tops:
                (root / top).rename(target / top)
                moved.append(top)
            (root / TRANSCRIPTS_MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
            (root / TRANSCRIPTS_MANIFEST).rename(recorded)
        except OSError:
            for top in moved:
                (target / top).rename(root / top)
            raise
    return target, manifest


def verdict(manifest, key):
    v = (manifest.get("verdicts") or {}).get(key)
    return "-" if v is None else str(v)


def model_label(manifest):
    model = manifest.get("actual_model") or manifest.get("requested_model") or "?"
    if isinstance(model, dict):
        model = sorted(set(filter(None, model.values())))
    return ",".join(model) if isinstance(model, list) else str(model)


def list_runs(dest):
    rows = []
    for m in sorted(Path(dest).glob(f"*/{MANIFEST}")):
        man = json.loads(m.read_text())
        rows.append((m.parent.name, man.get("challenge", "?"), man.get("provider", "?"),
                     model_label(man), verdict(man, "private"), verdict(man, "overall"),
                     "yes" if (m.parent / TRANSCRIPTS_MANIFEST).is_file() else "-"))
    header = ("run", "challenge", "provider", "model", "private", "overall", "transcripts")
    widths = [max(len(r[i]) for r in rows + [header]) for i in range(len(header))]
    for r in [header] + rows:
        print("  ".join(c.ljust(w) for c, w in zip(r, widths)).rstrip())
    return rows


IMPORT_ERRORS = (BundleError, tarfile.TarError, json.JSONDecodeError, UnicodeDecodeError, OSError)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("bundles", nargs="*")
    parser.add_argument("--dest", default="runs")
    parser.add_argument("--force", action="store_true",
                        help="replace an existing run imported from a results/legacy archive")
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args(argv)
    Path(args.dest).mkdir(parents=True, exist_ok=True)
    failed = False
    # Results before transcripts, so one invocation can import both parts.
    for bundle in sorted(args.bundles, key=lambda b: archive_part(b) == "transcripts"):
        try:
            target, _ = import_bundle(bundle, args.dest, args.force)
            print(f"imported {bundle} -> {target}")
        except IMPORT_ERRORS as e:
            failed = True
            print(f"REJECTED {bundle}: {e}", file=sys.stderr)
    if args.list or not args.bundles:
        list_runs(args.dest)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
