#!/usr/bin/env python3
"""Pack a collected orb-run output directory into two zstd run archives and
optionally upload them to a private GitHub release.

  <challenge>-<provider>-<run_id>.results.tar.zst
      implementation/, report.md, manifest.json, run-bundle.tar.gz,
      launch.json, private.log (the evaluator-only log)
  <challenge>-<provider>-<run_id>.transcripts.tar.zst
      transcripts/ (verified phase transcripts), logs/ (runner logs)

Each archive holds a top-level BUNDLE.json with the run identity, requested
and observed model/effort, pin, times, verdicts, sizes and a sha256 for every
file; both parts carry the same identity. Sources are re-verified against the
run manifest before packing, and archives are read back after writing.

  python3 scripts/run_archives.py write OUTPUT_DIR [--dest DIR]
  python3 scripts/run_archives.py upload --wave N OUTPUT_DIR [--dest DIR]

`upload` reuses archives already written for OUTPUT_DIR, refuses unless the
runs repository is PRIVATE, creates release wave-<N> when absent, never
replaces a different asset of the same name, and verifies the uploaded sizes.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tarfile
import tempfile

from import_run_bundle import (ARCHIVE_KIND, IMPORT_ERRORS, PARTS, BundleError, check_same_run,
                               identity, read_archive, run_name, safe_member_path)

RUNS_REPO = "okwalerie/rama-ai-learn-runs"
ZSTD_LEVEL = "-19"
ARCHIVE_MODE = 0o600
LOG_NAME = re.compile(r"[A-Za-z0-9._-]+\.log")
MODEL_KEYS = {"model", "modelID"}
EFFORT_KEYS = {"effort", "reasoning_effort", "reasoningEffort"}


class ArchiveError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise ArchiveError(message)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def visible_files(root):
    """Non-hidden files under root, as the runner's tree-sha256 sees them."""
    files = []
    for path in sorted(Path(root).rglob("*")):
        rel = path.relative_to(root)
        if any(part.startswith(".") for part in rel.parts):
            continue
        require(not path.is_symlink(), f"Symlink in implementation: {rel}")
        if path.is_file():
            files.append(path)
    return files


def tree_sha256(root):
    """Python twin of run_challenges.bb tree-sha256; None when root is missing."""
    if not Path(root).is_dir():
        return None
    entries = [f"{p.relative_to(root).as_posix()}\0{sha256(p)}" for p in visible_files(root)]
    return hashlib.sha256("\n".join(entries).encode()).hexdigest()


def observed(transcripts):
    """Distinct model and effort strings the provider reported in transcripts.
    Empty when the CLI does not report them; never filled from the request."""
    models, efforts = set(), set()
    def walk(value, depth):
        if depth > 4:
            return
        if isinstance(value, dict):
            for key, item in value.items():
                if key in MODEL_KEYS and isinstance(item, str) and item:
                    provider = value.get("providerID")
                    models.add(f"{provider}/{item}" if key == "modelID" and isinstance(provider, str) else item)
                elif key in EFFORT_KEYS and isinstance(item, str) and item:
                    efforts.add(item)
                else:
                    walk(item, depth + 1)
        elif isinstance(value, list):
            for item in value:
                walk(item, depth + 1)
    for path in transcripts:
        for line in path.read_text(errors="replace").splitlines():
            try:
                walk(json.loads(line), 0)
            except ValueError:
                continue
    return sorted(models), sorted(efforts)


def collect(output):
    """Verify a collected output directory against its run manifest. Returns
    (metadata, {"results": {arcname: path}, "transcripts": {arcname: path}})."""
    output = Path(output)
    manifests = sorted(output.glob("*.manifest.json"))
    require(len(manifests) == 1, f"Expected exactly one run manifest in {output}")
    source = manifests[0]
    base = source.name.removesuffix(".manifest.json")
    manifest = json.loads(source.read_text())
    launch_path = output / "LAUNCH.json"
    require(launch_path.is_file(), "LAUNCH.json is missing; not a completed orb-run output")
    launch = json.loads(launch_path.read_text())
    challenges = manifest.get("challenges") or []
    require(len(challenges) == 1, "Manifest does not describe exactly one challenge")
    challenge = challenges[0]
    requested = manifest.get("requested") or {}
    pin = (manifest.get("repo") or {}).get("head-sha")
    require(challenge.get("name") == launch.get("challenge"), "Manifest challenge differs from LAUNCH.json")
    require(requested.get("agent") == launch.get("agent"), "Manifest agent differs from LAUNCH.json")
    require(pin == launch.get("pin"), "Manifest repo SHA differs from the launch pin")

    log_ref = manifest.get("evaluator-log") or {}
    require(isinstance(log_ref.get("path"), str)
            and re.fullmatch(r"[A-Za-z0-9._-]+\.private\.log", log_ref["path"]),
            "Evaluator log reference must be a local private log")
    private = output / log_ref["path"]
    require(private.is_file() and private.stat().st_size == log_ref.get("bytes")
            and sha256(private) == log_ref.get("sha256"), "Private evaluator log is missing or corrupt")
    report, run_bundle = output / (base + ".md"), output / (base + ".bundle.tar.gz")
    require(report.is_file() and run_bundle.is_file(), "Report or run bundle is missing")
    with tarfile.open(run_bundle, "r:gz") as tar:
        members = [m for m in tar.getmembers() if m.name.endswith("/BUNDLE.json")]
        require(len(members) == 1, "Run bundle has no unique BUNDLE.json")
        require(json.load(tar.extractfile(members[0])).get("manifest-sha256") == sha256(source),
                "Run bundle manifest digest differs from the manifest file")

    implementation = output / "implementation"
    require(tree_sha256(implementation) == challenge.get("implementation-sha256"),
            "Collected implementation differs from the manifest implementation-sha256")

    expected = {Path(p["transcript-path"]).name: p.get("transcript-sha256")
                for p in challenge.get("phases", []) if p.get("transcript-path")}
    folder = output / "transcripts"
    present = {p.name: p for p in folder.iterdir()} if folder.is_dir() else {}
    require(set(present) == set(expected), "Collected transcripts differ from the manifest phases")
    for name, path in present.items():
        require(path.is_file() and not path.is_symlink() and sha256(path) == expected[name],
                f"Phase transcript is corrupt: {name}")

    results = {"report.md": report, "manifest.json": source, "run-bundle.tar.gz": run_bundle,
               "launch.json": launch_path, "private.log": private}
    for path in visible_files(implementation) if implementation.is_dir() else []:
        results["implementation/" + path.relative_to(implementation).as_posix()] = path
    transcripts = {"transcripts/" + name: path for name, path in sorted(present.items())}
    for path in sorted(output.glob("*.log")):
        if path != private:
            require(LOG_NAME.fullmatch(path.name) and path.is_file() and not path.is_symlink(),
                    f"Unexpected runner log: {path.name}")
            transcripts["logs/" + path.name] = path

    actual_models, actual_efforts = observed(present.values())
    meta = {"schema_version": 1, "kind": ARCHIVE_KIND,
            "challenge": challenge.get("name"), "provider": requested.get("agent"),
            "run_id": manifest.get("run-id"), "pin": pin,
            "requested_model": {"fast": requested.get("fast-model"), "slow": requested.get("slow-model")},
            "requested_effort": {"fast": requested.get("fast-effort"), "slow": requested.get("slow-effort")},
            "actual_model": actual_models, "actual_effort": actual_efforts,
            "started_at": manifest.get("started-at"), "finished_at": manifest.get("finished-at"),
            "verdicts": {"phase": [{"phase": p.get("phase-id"), "attempt": p.get("attempt"),
                                    "subsystem": p.get("subsystem"), "verdict": p.get("verdict")}
                                   for p in challenge.get("phases", [])],
                         "private": challenge.get("private-status"),
                         "overall": challenge.get("outcome")},
            "manifest_sha256": sha256(source)}
    for key in ("challenge", "provider", "run_id"):
        require(isinstance(meta[key], str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", meta[key]),
                f"Run {key} is not a safe archive name component: {meta[key]!r}")
    for files in (results, transcripts):
        for arcname in files:
            try:
                safe_member_path(arcname)
            except BundleError as error:
                raise ArchiveError(f"Cannot archive {arcname}: {error}") from error
    return meta, {"results": results, "transcripts": transcripts}


def archive_paths(meta, dest):
    return {part: Path(dest) / f"{run_name(meta)}.{part}.tar.zst" for part in PARTS}


def write_part(path, meta, part, files):
    contents = {arcname: Path(src).read_bytes() for arcname, src in sorted(files.items())}
    bundle = {**meta, "part": part,
              "sizes": {"total_bytes": sum(map(len, contents.values())), "file_count": len(contents),
                        "files": {k: len(v) for k, v in contents.items()}},
              "files": {k: hashlib.sha256(v).hexdigest() for k, v in contents.items()}}
    members = {"BUNDLE.json": (json.dumps(bundle, indent=2, sort_keys=True) + "\n").encode(), **contents}
    # Both parts carry evaluator-only logs: stage privately, publish 0600.
    with tempfile.TemporaryDirectory(dir=path.parent, prefix=".archive-") as tmp:
        os.chmod(tmp, 0o700)
        tar_path, zst_path = Path(tmp) / "a.tar", Path(tmp) / "a.tar.zst"
        with os.fdopen(os.open(tar_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as raw, \
                tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as tar:
            for name, data in members.items():
                info = tarfile.TarInfo(name)
                info.size, info.mode, info.mtime = len(data), 0o644, 0
                tar.addfile(info, io.BytesIO(data))
        result = subprocess.run(["zstd", ZSTD_LEVEL, "-q", "-T0", "-o", str(zst_path), str(tar_path)],
                                capture_output=True, text=True)
        require(result.returncode == 0, f"zstd failed for {path.name}: {result.stderr.strip()}")
        os.chmod(zst_path, ARCHIVE_MODE)
        os.link(zst_path, path)  # fails instead of replacing an existing archive


def verify_pair(paths, meta):
    """Read both archives back; their identity must equal the run's."""
    read = {}
    for part, path in paths.items():
        try:
            read[part] = read_archive(path)
        except IMPORT_ERRORS as error:
            raise ArchiveError(f"{path.name} failed verification: {error}") from error
    check_same_run(read["results"][0], read["transcripts"][0])
    expected = {k: v for k, v in meta.items() if k != "created_at"}
    actual = {k: v for k, v in identity(read["results"][0]).items() if k != "created_at"}
    require(actual == expected, f"{paths['results'].name} does not describe this output directory")
    return [{"part": part, "path": path, "bytes": path.stat().st_size,
             "unpacked_bytes": read[part][0]["sizes"]["total_bytes"],
             "files": read[part][0]["sizes"]["file_count"]} for part, path in paths.items()]


def write_archives(output, dest=None, created_at=None):
    """Write (or verify and reuse) both archives; returns one size row per part."""
    output = Path(output)
    dest = Path(dest) if dest else output / "archives"
    meta, parts = collect(output)
    paths = archive_paths(meta, dest)
    existing = [path for path in paths.values() if path.exists()]
    if existing:
        require(len(existing) == len(paths),
                f"Partial archive set in {dest}; remove {existing[0].name} to rewrite it")
        for path in existing:
            if stat.S_IMODE(path.stat().st_mode) != ARCHIVE_MODE:
                os.chmod(path, ARCHIVE_MODE)  # tighten archives written before 0600
        return verify_pair(paths, meta)
    dest.mkdir(parents=True, exist_ok=True)
    meta["created_at"] = created_at or datetime.now(timezone.utc).isoformat(timespec="seconds")
    written = []
    try:
        for part, files in parts.items():
            write_part(paths[part], meta, part, files)
            written.append(paths[part])
        return verify_pair(paths, meta)
    except BaseException:
        for path in written:
            path.unlink(missing_ok=True)
        raise


def human(n):
    for unit in ("B", "KiB", "MiB", "GiB"):
        if n < 1024 or unit == "GiB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


def size_report(rows):
    lines = []
    for row in rows:
        ratio = row["unpacked_bytes"] / row["bytes"] if row["bytes"] else 0
        lines.append(f"{row['part']:<12} {row['path'].name}  {human(row['bytes'])} "
                     f"(unpacked {human(row['unpacked_bytes'])}, {row['files']} files, {ratio:.1f}x)")
    return "\n".join(lines)


# --- GitHub release transport ------------------------------------------------

def gh(args, timeout=900):
    """Run gh. RUN_ARCHIVES_GH overrides the executable (tests use a stub)."""
    exe = os.environ.get("RUN_ARCHIVES_GH", "gh")
    try:
        return subprocess.run([exe, *map(str, args)], capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ArchiveError(f"gh {' '.join(map(str, args[:2]))} failed: {error}") from error


def wave_tag(wave):
    require(isinstance(wave, int) and wave >= 1, "--wave must be a positive integer")
    return f"wave-{wave}"


def require_private(repo):
    result = gh(["repo", "view", repo, "--json", "isPrivate,visibility"])
    require(result.returncode == 0, f"Cannot read visibility of {repo}: {result.stderr.strip()}")
    info = json.loads(result.stdout)
    require(info.get("isPrivate") is True and info.get("visibility") == "PRIVATE",
            f"Refusing to upload: {repo} is not PRIVATE (visibility {info.get('visibility')!r})")


def release_assets(repo, tag):
    """{name: asset} for the release, or None when the release does not exist."""
    result = gh(["release", "view", tag, "--repo", repo, "--json", "assets"])
    if result.returncode == 0:
        return {a["name"]: a for a in json.loads(result.stdout).get("assets", [])}
    if "release not found" in result.stderr.lower():
        return None
    raise ArchiveError(f"Cannot read release {tag} of {repo}: {result.stderr.strip()}")


def download_assets(repo, tag, names, folder):
    if not names:
        return
    patterns = [arg for name in names for arg in ("--pattern", name)]
    result = gh(["release", "download", tag, "--repo", repo, "--dir", folder, *patterns])
    require(result.returncode == 0, f"Download from {tag} failed: {result.stderr.strip()}")
    missing = [name for name in names if not (Path(folder) / name).is_file()]
    require(not missing, f"Download from {tag} lacks: {', '.join(missing)}")


def upload_archives(paths, wave, repo=RUNS_REPO):
    tag = wave_tag(wave)
    paths = [Path(p) for p in paths]
    require_private(repo)
    assets = release_assets(repo, tag)
    if assets is None:
        created = gh(["release", "create", tag, "--repo", repo, "--title", tag,
                      "--notes", f"Run archives for {tag}."])
        # A concurrent worker may have created it first; only absence is fatal.
        assets = release_assets(repo, tag)
        require(assets is not None, f"Could not create release {tag}: {created.stderr.strip()}")
    pending = []
    for path in paths:
        if path.name not in assets:
            pending.append(path)
            continue
        with tempfile.TemporaryDirectory() as tmp:
            download_assets(repo, tag, [path.name], tmp)
            require(sha256(Path(tmp) / path.name) == sha256(path),
                    f"Release {tag} already has a different {path.name}")
        print(f"already uploaded: {path.name}")
    if pending:
        result = gh(["release", "upload", tag, "--repo", repo, *pending])
        require(result.returncode == 0, f"Upload to {tag} failed: {result.stderr.strip()}")
    assets = release_assets(repo, tag) or {}
    for path in paths:
        asset = assets.get(path.name)
        require(asset is not None and asset.get("size") == path.stat().st_size
                and asset.get("state", "uploaded") == "uploaded",
                f"Release {tag} does not hold a complete {path.name} after upload")
    return tag


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("write", "upload"):
        cmd = sub.add_parser(name)
        cmd.add_argument("output", type=Path, help="orb-run output directory")
        cmd.add_argument("--dest", type=Path, help="archive directory (default OUTPUT/archives)")
        if name == "upload":
            cmd.add_argument("--wave", type=int, required=True)
            cmd.add_argument("--repo", default=RUNS_REPO)
    opts = parser.parse_args(argv)
    try:
        rows = write_archives(opts.output, opts.dest)
        print(size_report(rows))
        if opts.command == "upload":
            tag = upload_archives([row["path"] for row in rows], opts.wave, opts.repo)
            print(f"uploaded to {opts.repo} release {tag}")
    except (ArchiveError, OSError, ValueError, KeyError, tarfile.TarError) as error:
        print(f"run archives FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
