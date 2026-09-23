#!/usr/bin/env python3
"""Linux solver filesystem boundary. No models are called by this launcher.

The host orchestrator retains private data. Only an allowlisted public snapshot
and this challenge's writable implementation directory enter bubblewrap.
Network defaults to shared; --network strict uses an exact-host CONNECT proxy.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading

from solver_proxy import ProxyServer, allowed_hosts


def copy_public(source, dest, *, source_tree=False):
    """Never follow repository symlinks or copy encrypted/private test data."""
    if source.is_symlink() or source.resolve() != source.absolute():
        raise ValueError(f"Symlink in public input: {source}")
    if not source.exists():
        return
    if source.name == ".git" or source.name.endswith(".enc"):
        return
    if source_tree and "test" in source.name and source.is_file():
        return
    if source.is_dir():
        dest.mkdir(parents=True, exist_ok=True)
        for item in source.iterdir():
            copy_public(item, dest / item.name, source_tree=source_tree)
    else:
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, dest)


def snapshot(repo, target, challenge):
    for rel in ("deps.edn", "lib/rama-deps", "lib/harness/deps.edn",
                "lib/harness/src", "plugins/rama-skill/skills/rama",
                ".agents/skills/challenge-phase", ".claude/commands/challenge-phase.md",
                "scripts/import-kondo-configs.sh"):
        copy_public(repo / rel, target / rel)
    base = Path("challenges") / challenge
    for rel in ("README.md", "deps.edn", "src", ".clj-kondo"):
        copy_public(repo / base / rel, target / base / rel, source_tree=rel == "src")
    for kind in (".agents", ".claude", ".codex"):
        link = target / kind / "skills/rama"
        link.parent.mkdir(parents=True, exist_ok=True)
        link.symlink_to("../../plugins/rama-skill/skills/rama")


def isolated_command(repo, challenge, agent, command, staging, *, strict=False):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", challenge):
        raise ValueError("Invalid challenge name")
    if not shutil.which("bwrap"):
        raise RuntimeError("bubblewrap is required; refusing an unisolated fallback")
    home = Path.home()
    public = staging / "public"
    public.mkdir()
    snapshot(repo, public, challenge)
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
    for path in ("/usr", "/bin", "/sbin", "/lib", "/lib64", "/opt/java/openjdk"):
        if Path(path).exists():
            args += ["--ro-bind", path, path]
    for path in ("/etc/ssl", "/etc/alternatives", "/etc/java-17-openjdk",
                 "/etc/resolv.conf", "/etc/hosts", "/etc/nsswitch.conf",
                 "/etc/passwd", "/etc/group", "/etc/ld.so.cache"):
        if Path(path).exists():
            args += ["--ro-bind", path, path]
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
    for rel in credentials.get(agent, []):
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
    for key in keys.get(agent, ()):
        if key in os.environ:
            env[key] = os.environ[key]
    if strict:
        allowed_hosts(agent)  # Reject unsupported providers, never broaden policy.
        args += ["--ro-bind", str(staging / "provider.sock"), "/run/provider.sock",
                 "--ro-bind", str(Path(__file__).with_name("solver_proxy.py")), "/run/solver_proxy.py"]
        command = ["python3", "/run/solver_proxy.py", "--bridge", "/run/provider.sock", *command]
        if agent == "opencode":
            catalog = home / ".cache/opencode/models.json"
            if not catalog.is_file():
                raise RuntimeError("Preseed OpenCode models.json on the host before strict runs")
            args += ["--ro-bind", str(catalog), "/run/opencode-models.json"]
            env.update(OPENCODE_MODELS_PATH="/run/opencode-models.json",
                       OPENCODE_DISABLE_MODELS_FETCH="true", OPENCODE_DISABLE_AUTOUPDATE="true",
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
    parser.add_argument("--challenge", required=True)
    parser.add_argument("--agent", choices=("claude", "opencode", "codex", "pi"), required=True)
    parser.add_argument("--network", choices=("shared", "strict"), default="shared")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    opts = parser.parse_args()
    command = opts.command
    if command[:1] == ["--"]:
        command = command[1:]
    if not command:
        parser.error("a command is required after --")
    with tempfile.TemporaryDirectory(prefix="rama-solver-") as tmp:
        strict = opts.network == "strict"
        args, env = isolated_command(opts.repo.resolve(), opts.challenge, opts.agent,
                                     command, Path(tmp), strict=strict)
        if not strict:
            return subprocess.call(args, env=env, close_fds=True)
        with ProxyServer(Path(tmp) / "provider.sock", allowed_hosts(opts.agent)) as proxy:
            thread = threading.Thread(target=proxy.serve_forever, daemon=True)
            thread.start()
            try:
                return subprocess.call(args, env=env, close_fds=True)
            finally:
                proxy.shutdown()


if __name__ == "__main__":
    raise SystemExit(main())
