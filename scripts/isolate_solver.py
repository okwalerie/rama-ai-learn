#!/usr/bin/env python3
"""Linux solver filesystem boundary. No models are called by this launcher.

The host orchestrator retains private data. Only an allowlisted public snapshot
and this challenge's writable implementation directory enter bubblewrap.
Network defaults to shared; --network strict uses an exact-host CONNECT proxy.
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
import tempfile
import threading

from solver_proxy import ProxyServer, allowed_hosts


# The only repository paths a solver may read. Everything else, including
# docs/ (atlas, judge criteria), review/, HLD notes and Git, stays on the host.
SHARED_ALLOWLIST = ("deps.edn", "lib/rama-deps", "lib/harness/deps.edn",
                    "lib/harness/src", "plugins/rama-skill/skills/rama",
                    ".agents/skills/challenge-phase", ".claude/commands/challenge-phase.md",
                    "scripts/import-kondo-configs.sh")
CHALLENGE_ALLOWLIST = ("README.md", "deps.edn", "src", ".clj-kondo")
SKILL_LINKS = tuple(f"{kind}/skills/rama" for kind in (".agents", ".claude", ".codex"))
# Runner-protected and authoring-only directories, refused at any depth.
PROTECTED_DIRS = frozenset({".git", "test-private", "test-resources", "test",
                            "test-harness", "review", "atlas"})
SECRET_FILE_RE = re.compile(
    r"(?i)^(\.env(\..*)?|\.netrc|\.git-credentials|\.npmrc|\.pypirc|"
    r".*\.(pem|key|p12|pfx|jks|keystore)|id_(rsa|dsa|ecdsa|ed25519)(\.pub)?|"
    r"\.?credentials(\.json)?|auth\.json)$")
SECRET_CONTENT_RE = re.compile(
    rb"-----BEGIN [A-Z ]*PRIVATE KEY-----|sk-ant-[A-Za-z0-9_-]{16,}|"
    rb"sk-(?:proj-|or-v1-)?[A-Za-z0-9_-]{32,}|gh[pousr]_[A-Za-z0-9]{30,}|"
    rb"github_pat_[A-Za-z0-9_]{30,}|(?:AKIA|ASIA)[0-9A-Z]{16}|xox[abposr]-[A-Za-z0-9-]{10,}")
SECRET_ENV_RE = re.compile(r"(?i)KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH")


def denied(source, *, source_tree=False):
    """Reason a path may not enter the snapshot, or None."""
    if source.name in PROTECTED_DIRS or source.name.endswith(".enc"):
        return "protected"
    if SECRET_FILE_RE.match(source.name):
        return "secret-file"
    if source_tree and "test" in source.name and source.is_file():
        return "test-source"
    return None


def copy_public(source, dest, *, source_tree=False):
    """Never follow repository symlinks or copy encrypted/private test data."""
    if source.is_symlink() or source.resolve() != source.absolute():
        raise ValueError(f"Symlink in public input: {source}")
    if not source.exists() or denied(source, source_tree=source_tree):
        return
    if source.is_dir():
        dest.mkdir(parents=True, exist_ok=True)
        for item in source.iterdir():
            copy_public(item, dest / item.name, source_tree=source_tree)
    else:
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, dest)


def snapshot(repo, target, challenge):
    for rel in SHARED_ALLOWLIST:
        copy_public(repo / rel, target / rel)
    base = Path("challenges") / challenge
    for rel in CHALLENGE_ALLOWLIST:
        copy_public(repo / base / rel, target / base / rel, source_tree=rel == "src")
    for rel in SKILL_LINKS:
        link = target / rel
        link.parent.mkdir(parents=True, exist_ok=True)
        link.symlink_to("../../plugins/rama-skill/skills/rama")


def protected_files(repo):
    """Files the solver must never see: runner-protected challenge data
    (plain or encrypted) and authoring-only reference surfaces."""
    roots = [*repo.glob("challenges/*/test-private"), *repo.glob("challenges/*/test-resources"),
             repo / "docs", repo / "review", repo / ".amp"]
    for root in roots:
        if root.is_dir() and not root.is_symlink():
            yield from (p for p in root.rglob("*") if p.is_file() and not p.is_symlink())


def secret_env_values(environ):
    return {k: v.encode() for k, v in environ.items()
            if SECRET_ENV_RE.search(k) and len(v) >= 8}


def audit_snapshot(repo, public, challenge, environ=None):
    """Check a built snapshot. Returns {"files", "categories", "violations"};
    violations name paths, rules and env-var names, never file contents or
    secret values."""
    environ = os.environ if environ is None else environ
    allowed = [*SHARED_ALLOWLIST, *(f"challenges/{challenge}/{r}" for r in CHALLENGE_ALLOWLIST)]
    secrets = secret_env_values(environ)
    files, links, violations, categories = [], [], [], {}

    def violate(rel, rule, detail=None):
        violations.append({"path": rel, "rule": rule, **({"detail": detail} if detail else {})})

    for path in sorted(public.rglob("*")):
        rel = path.relative_to(public).as_posix()
        if path.is_symlink():
            links.append(rel)
            if rel not in SKILL_LINKS:
                violate(rel, "unexpected-symlink")
            continue
        if not path.is_file():
            continue
        files.append(path)
        root = next((a for a in allowed if rel == a or rel.startswith(a + "/")), None)
        categories[root or "outside-allowlist"] = categories.get(root or "outside-allowlist", 0) + 1
        if root is None:
            violate(rel, "outside-allowlist")
        for part in Path(rel).parts:
            reason = denied(Path(part))
            if reason:
                violate(rel, reason)
                break
        data = path.read_bytes()
        if SECRET_CONTENT_RE.search(data):
            violate(rel, "secret-pattern")
        for name, value in secrets.items():
            if value in data:
                violate(rel, "secret-env-value", name)
    # Byte-identical copies of protected files, compared by size first.
    by_size = {}
    for path in files:
        size = path.stat().st_size
        if size:
            by_size.setdefault(size, []).append(path)
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    protected = {}
    for path in protected_files(repo):
        size = path.stat().st_size
        if size in by_size:
            protected.setdefault(digest(path), path.relative_to(repo).as_posix())
    for group in by_size.values():
        for path in group:
            if digest(path) in protected:
                violate(path.relative_to(public).as_posix(), "protected-content")
    return {"files": len(files), "symlinks": sorted(links),
            "categories": dict(sorted(categories.items())), "violations": violations}


def require_clean_snapshot(repo, public, challenge):
    violations = audit_snapshot(repo, public, challenge)["violations"]
    if violations:
        raise ValueError("Snapshot audit failed: " + "; ".join(
            f"{v['path']} ({v['rule']})" for v in violations))


def selected_model(command):
    for index, arg in enumerate(command):
        if arg == "--":
            break
        if arg == "--model" and index + 1 < len(command):
            return command[index + 1]
        if arg.startswith("--model="):
            return arg.partition("=")[2]
    return None


def allowed_hosts_for_command(agent, command):
    model = selected_model(command) if agent == "opencode" else None
    return allowed_hosts(agent, model=model)


def opencode_go_config():
    return json.dumps({
        "$schema": "https://opencode.ai/config.json",
        "provider": {
            "opencode-go": {
                "npm": "@ai-sdk/openai-compatible",
                "name": "OpenCode Go",
                "options": {
                    "baseURL": "https://opencode.ai/zen/go/v1",
                    "apiKey": "{env:OPENCODE_API_KEY}",
                },
            },
        },
    })


def system_binds():
    args = []
    for path in ("/usr", "/bin", "/sbin", "/lib", "/lib64", "/opt/java/openjdk"):
        if Path(path).exists():
            args += ["--ro-bind", path, path]
    for path in ("/etc/ssl", "/etc/alternatives", "/etc/java-17-openjdk",
                 "/etc/resolv.conf", "/etc/hosts", "/etc/nsswitch.conf",
                 "/etc/passwd", "/etc/group", "/etc/ld.so.cache"):
        if Path(path).exists():
            args += ["--ro-bind", path, path]
    return args


def preflight(repo, challenges, strict=False):
    """Prove bubblewrap launches here and every selected challenge's snapshot
    is clean. Returns a JSON-ready isolation record; raises on any failure."""
    bwrap = shutil.which("bwrap")
    if not bwrap:
        raise RuntimeError("bubblewrap is required; refusing an unisolated fallback")
    version = subprocess.run([bwrap, "--version"], capture_output=True, text=True,
                             timeout=30).stdout.strip()
    probe = subprocess.run([bwrap, "--die-with-parent", "--new-session", "--unshare-all",
                            "--cap-drop", "ALL", *system_binds(), "--proc", "/proc",
                            "--dev", "/dev", "--tmpfs", "/tmp", "--", "true"],
                           capture_output=True, text=True, timeout=60)
    if probe.returncode != 0:
        raise RuntimeError(f"bubblewrap probe exited {probe.returncode}: {probe.stderr.strip()}")
    audits = {}
    for challenge in challenges:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", challenge):
            raise ValueError("Invalid challenge name")
        with tempfile.TemporaryDirectory(prefix="rama-snapshot-audit-") as tmp:
            snapshot(repo, Path(tmp), challenge)
            report = audit_snapshot(repo, Path(tmp), challenge)
        if report["violations"]:
            raise ValueError(f"Snapshot audit failed for {challenge}: " + "; ".join(
                f"{v['path']} ({v['rule']})" for v in report["violations"]))
        audits[challenge] = {"files": report["files"], "categories": report["categories"],
                             "violations": 0}
    launcher = Path(__file__).resolve()
    return {"filesystem": "bubblewrap", "bwrap": {"path": bwrap, "version": version},
            "probe": "passed", "network": "strict" if strict else "shared",
            "snapshot": {"kind": "allowlisted-public-copy",
                         "shared-allowlist": list(SHARED_ALLOWLIST),
                         "challenge-allowlist": list(CHALLENGE_ALLOWLIST),
                         "protected-dirs": sorted(PROTECTED_DIRS),
                         "audits": audits},
            "launcher-sha256": hashlib.sha256(launcher.read_bytes()).hexdigest()}


def isolated_command(repo, challenge, agent, command, staging, *, strict=False):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", challenge):
        raise ValueError("Invalid challenge name")
    if not shutil.which("bwrap"):
        raise RuntimeError("bubblewrap is required; refusing an unisolated fallback")
    model = selected_model(command) if agent == "opencode" else None
    opencode_go = bool(model and model.startswith("opencode-go/"))
    if opencode_go and not os.environ.get("OPENCODE_API_KEY"):
        raise RuntimeError("OPENCODE_API_KEY is required for OpenCode Go models")
    home = Path.home()
    public = staging / "public"
    public.mkdir()
    snapshot(repo, public, challenge)
    require_clean_snapshot(repo, public, challenge)
    impl = repo / "implementations" / challenge
    if impl.is_symlink() or impl.parent.is_symlink():
        raise ValueError("Implementation root must not be a symlink")
    impl.mkdir(parents=True, exist_ok=True)
    # Keep the original absolute project path so CLI output and recorded paths
    # continue to match the host's telemetry and implementation artifacts.
    args = ["bwrap", "--die-with-parent", "--new-session", "--unshare-all",
            "--cap-drop", "ALL"]
    if not strict:
        args += ["--share-net"]
    args += system_binds()
    args += ["--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",
             "--dir", str(home), "--bind", str(public), str(repo),
             "--bind", str(impl), str(impl)]
    # Explicit dependency/tool paths, never all of HOME, .cache, or .config.
    for rel in (".m2/repository", ".gitlibs/libs", ".local/bin/bbin",
                ".gitlibs/_repos/https/github.com/bhauman/clojure-mcp-light",
                ".gitlibs/_repos/https/github.com/cognitect-labs/test-runner",
                ".local/bin/clj-nrepl-eval", ".local/bin/clj-paren-repair-claude-hook"):
        path = home / rel
        if path.exists():
            args += ["--ro-bind", str(path), str(path)]
    # Credentials only, not histories, plugins, user settings, or MCP servers.
    credentials = {"claude": [".claude/.credentials.json"],
                   "opencode": [".local/share/opencode/auth.json"],
                   "codex": [".codex/auth.json"], "pi": [".pi/agent/auth.json"]}
    credential_files = [] if opencode_go else credentials.get(agent, [])
    for rel in credential_files:
        path = home / rel
        if path.exists():
            args += ["--ro-bind", str(path), str(path)]
    env = {"HOME": str(home), "USER": home.name, "LANG": "C.UTF-8",
           "PATH": f"{home}/.local/bin:/usr/local/bin:/usr/bin:/bin",
           "TMPDIR": "/tmp", "GIT_CONFIG_NOSYSTEM": "1"}
    if Path("/opt/java/openjdk").exists():
        env["JAVA_HOME"] = "/opt/java/openjdk"
        env["PATH"] = "/opt/java/openjdk/bin:" + env["PATH"]
    keys = {"claude": ("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY"),
            "opencode": ("OPENROUTER_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY"),
            "codex": ("OPENAI_API_KEY",),
            "pi": ("OPENROUTER_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY")}
    if opencode_go:
        env["OPENCODE_API_KEY"] = os.environ["OPENCODE_API_KEY"]
        env["OPENCODE_CONFIG_CONTENT"] = opencode_go_config()
    else:
        for key in keys.get(agent, ()):
            if key in os.environ:
                env[key] = os.environ[key]
    if strict:
        allowed_hosts_for_command(agent, command)  # Reject unsupported providers, never broaden policy.
        args += ["--ro-bind", str(staging / "provider.sock"), "/run/provider.sock",
                 "--ro-bind", str(Path(__file__).with_name("solver_proxy.py")), "/run/solver_proxy.py"]
        command = ["python3", "/run/solver_proxy.py", "--bridge", "/run/provider.sock", *command]
        if agent == "opencode":
            catalog = home / ".cache/opencode/models.json"
            if not catalog.is_file():
                raise RuntimeError("Preseed OpenCode models.json on the host before strict runs")
            args += ["--ro-bind", str(catalog), "/run/opencode-models.json"]
            env.update(OPENCODE_MODELS_PATH="/run/opencode-models.json",
                       OPENCODE_DISABLE_MODELS_FETCH="1", OPENCODE_DISABLE_AUTOUPDATE="1",
                       OPENCODE_DISABLE_LSP_DOWNLOAD="true", OPENCODE_PURE="true",
                       OPENCODE_DISABLE_DEFAULT_PLUGINS="true")
            for key in ("ANTHROPIC_API_KEY", "OPENAI_API_KEY"):
                env.pop(key, None)
        else:
            env["DISABLE_AUTOUPDATER"] = "1"
    # Clearing before spawning bwrap also removes secrets from /proc/1/environ,
    # rather than merely removing them from the final CLI's environment.
    args += ["--chdir", str(repo), "--", *command]
    return args, env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--challenge")
    parser.add_argument("--agent", choices=("claude", "opencode", "codex", "pi"), required=True)
    parser.add_argument("--network", choices=("shared", "strict"), default="shared")
    parser.add_argument("--preflight", action="store_true",
                        help="probe bubblewrap, audit snapshots, print an isolation record")
    parser.add_argument("--audit-challenge", action="append", default=[],
                        help="challenge whose snapshot --preflight audits (repeatable)")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    opts = parser.parse_args()
    if opts.preflight:
        try:
            record = preflight(opts.repo.resolve(), opts.audit_challenge,
                               strict=opts.network == "strict")
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as e:
            print(f"isolation preflight failed: {e}", file=sys.stderr)
            return 1
        print(json.dumps(record))
        return 0
    command = opts.command
    if command[:1] == ["--"]:
        command = command[1:]
    if not command:
        parser.error("a command is required after --")
    if not opts.challenge:
        parser.error("--challenge is required to launch a solver")
    with tempfile.TemporaryDirectory(prefix="rama-solver-") as tmp:
        strict = opts.network == "strict"
        args, env = isolated_command(opts.repo.resolve(), opts.challenge, opts.agent,
                                     command, Path(tmp), strict=strict)
        if not strict:
            return subprocess.call(args, env=env, close_fds=True)
        with ProxyServer(Path(tmp) / "provider.sock",
                         allowed_hosts_for_command(opts.agent, command)) as proxy:
            thread = threading.Thread(target=proxy.serve_forever, daemon=True)
            thread.start()
            try:
                return subprocess.call(args, env=env, close_fds=True)
            finally:
                proxy.shutdown()


if __name__ == "__main__":
    raise SystemExit(main())
