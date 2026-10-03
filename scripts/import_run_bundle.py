#!/usr/bin/env python3
"""Import solver-run bundles produced in remote workers (e.g. Amp orbs).

Solver output (implementations/, reports/, logs/, latest-transcripts/) is
gitignored on purpose, so it cannot travel on a branch. A worker packs one
run into `<challenge>-<provider>-<runid>.tar.gz` with a top-level
BUNDLE.json that lists a sha256 for every file. This script verifies the
bundle and unpacks it under runs/<bundle-name>/ without touching the
working implementations/ tree.

  python3 scripts/import_run_bundle.py BUNDLE.tar.gz [...] [--dest runs] [--force]
  python3 scripts/import_run_bundle.py --list [--dest runs]
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import sys
import tarfile
import tempfile

MANIFEST = "BUNDLE.json"
FORBIDDEN_PARTS = {"test-private", "test-resources", ".claude", ".ssh", ".aws", ".config"}
FORBIDDEN_NAMES = {".credentials.json", "auth.json", ".env", ".netrc", "id_rsa", "id_ed25519"}


class BundleError(Exception):
    pass


def bundle_name(path):
    name = Path(path).name
    for suffix in (".tar.gz", ".tgz", ".tar"):
        if name.endswith(suffix):
            return name[: -len(suffix)]
    return name


def safe_member_path(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        raise BundleError(f"unsafe path in bundle: {name}")
    parts = [p for p in path.parts if p != "."]
    if not parts:
        return None
    if FORBIDDEN_PARTS.intersection(parts) or parts[-1] in FORBIDDEN_NAMES or parts[-1].endswith(".enc"):
        raise BundleError(f"forbidden path in bundle: {name}")
    return PurePosixPath(*parts)


def read_bundle(bundle_path):
    """Return (manifest, {relpath: bytes}) after verifying every file hash."""
    files = {}
    with tarfile.open(bundle_path, "r:*") as tar:
        for member in tar.getmembers():
            rel = safe_member_path(member.name)
            if rel is None or member.isdir():
                continue
            if not member.isfile():
                raise BundleError(f"non-regular file in bundle: {member.name}")
            files[str(rel)] = tar.extractfile(member).read()
    if MANIFEST not in files:
        raise BundleError(f"missing {MANIFEST}")
    manifest = json.loads(files.pop(MANIFEST))
    listed = manifest.get("files")
    if not isinstance(listed, dict):
        raise BundleError(f"{MANIFEST} must map file paths to sha256 under \"files\"")
    listed = {str(safe_member_path(k)): v for k, v in listed.items()}
    missing = sorted(set(listed) - set(files))
    unlisted = sorted(set(files) - set(listed))
    if missing:
        raise BundleError(f"listed but absent: {', '.join(missing)}")
    if unlisted:
        raise BundleError(f"present but unlisted: {', '.join(unlisted)}")
    bad = sorted(p for p, data in files.items() if hashlib.sha256(data).hexdigest() != listed[p])
    if bad:
        raise BundleError(f"sha256 mismatch: {', '.join(bad)}")
    return manifest, files


def import_bundle(bundle_path, dest, force=False):
    manifest, files = read_bundle(bundle_path)
    target = Path(dest) / bundle_name(bundle_path)
    if target.exists():
        if not force:
            raise BundleError(f"{target} already exists (use --force to replace)")
        shutil.rmtree(target)
    with tempfile.TemporaryDirectory(dir=dest) as staging:
        root = Path(staging) / "run"
        for rel, data in files.items():
            out = root / rel
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(data)
        (root / MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        root.rename(target)
    return target, manifest


def verdict(manifest, key):
    v = (manifest.get("verdicts") or {}).get(key)
    return "-" if v is None else str(v)


def list_runs(dest):
    rows = []
    for m in sorted(Path(dest).glob(f"*/{MANIFEST}")):
        man = json.loads(m.read_text())
        rows.append((m.parent.name, man.get("challenge", "?"), man.get("provider", "?"),
                     man.get("actual_model") or man.get("requested_model") or "?",
                     verdict(man, "private"), verdict(man, "overall")))
    header = ("run", "challenge", "provider", "model", "private", "overall")
    widths = [max(len(r[i]) for r in rows + [header]) for i in range(len(header))]
    for r in [header] + rows:
        print("  ".join(c.ljust(w) for c, w in zip(r, widths)).rstrip())
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("bundles", nargs="*")
    parser.add_argument("--dest", default="runs")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--list", action="store_true")
    args = parser.parse_args(argv)
    Path(args.dest).mkdir(parents=True, exist_ok=True)
    failed = False
    for bundle in args.bundles:
        try:
            target, _ = import_bundle(bundle, args.dest, args.force)
            print(f"imported {bundle} -> {target}")
        except (BundleError, tarfile.TarError, json.JSONDecodeError) as e:
            failed = True
            print(f"REJECTED {bundle}: {e}", file=sys.stderr)
    if args.list or not args.bundles:
        list_runs(args.dest)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
