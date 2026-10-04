#!/usr/bin/env python3
"""Collect Amp threads for this repository into a local cache and index.

Uses the logged-in `amp` CLI (no External API token needed; the External
API is workspace-scoped and personal threads have no workspace).
Threads are only pointers; run data itself is shipped via `bb run-archives`
and fetched with `bb fetch-runs`.

    python3 scripts/amp_threads.py sync [--since 21d] [--jobs 6] [--refresh]
    python3 scripts/amp_threads.py index
    python3 scripts/amp_threads.py summary [--by challenge|harness|wave|day|kind|parent] [--kind attempt]
    python3 scripts/amp_threads.py show <thread-id-prefix>
    python3 scripts/amp_threads.py pointers [--kind attempt] [--json]

Threads are pointers: the run data (implementations/, reports/ bundles,
latest-transcripts/, logs/, test verdicts) lives in each orb's filesystem.
`pointers` extracts those paths, release uploads, pushed branches and verdicts
from each thread's tool calls and joins them with live orbs and release assets.

Cache (outside the repo): ${XDG_CACHE_HOME:-~/.cache}/rama-ai-learn/amp-threads/
  threads.json          raw `amp threads list --json` (archived included)
  export/<id>.json.gz   full `amp threads export` payload
  pointers.jsonl        orb artifact paths, verdicts, release assets per thread
  index.jsonl           one derived record per thread (rebuilt by `index`)
"""
import argparse
import collections
import concurrent.futures
import gzip
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone

REPO_MARKER = "rama-ai-learn"
RUNS_REPO = "okwalerie/rama-ai-learn-runs"
CACHE = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "rama-ai-learn" / "amp-threads"


def amp(*args, timeout=300):
    result = subprocess.run(["amp", *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"amp {' '.join(args[:2])} failed: {result.stderr.strip()[:300]}")
    return result.stdout


def list_threads():
    threads, offset = [], 0
    while True:
        page = json.loads(amp("threads", "list", "--json", "--include-archived",
                              "--limit", "500", "--offset", str(offset)))
        threads += page
        offset += 500
        if len(page) < 500:
            return threads


def parse_since(text):
    m = re.fullmatch(r"(\d+)([dw])", text)
    if m:
        days = int(m[1]) * (7 if m[2] == "w" else 1)
        return datetime.now(timezone.utc) - timedelta(days=days)
    return datetime.fromisoformat(text).replace(tzinfo=timezone.utc)


def export_path(tid):
    return CACHE / "export" / f"{tid}.json.gz"


def load_export(tid):
    with gzip.open(export_path(tid), "rt") as f:
        return json.load(f)


def is_repo_thread(export):
    trees = (export.get("env") or {}).get("initial", {}).get("trees") or []
    return any(REPO_MARKER in ((t.get("repository") or {}).get("url") or t.get("uri") or "")
               for t in trees)


def fetch(thread, refresh):
    """Fetch the export for one thread unless cached at the same update time."""
    tid = thread["id"]
    stamp = CACHE / "export" / f"{tid}.updated"
    if not refresh and stamp.exists() and stamp.read_text() == thread["updated"] \
            and export_path(tid).exists():
        return tid, "cached"
    if (CACHE / "other-repo" / tid).exists():
        return tid, "other-repo"
    payload = amp("threads", "export", tid)
    export = json.loads(payload)
    if not is_repo_thread(export):
        (CACHE / "other-repo" / tid).touch()
        return tid, "other-repo"
    with gzip.open(export_path(tid), "wt") as f:
        f.write(payload)
    stamp.write_text(thread["updated"])
    return tid, "fetched"


def cmd_sync(args):
    for sub in ("export", "other-repo"):
        (CACHE / sub).mkdir(parents=True, exist_ok=True)
    threads = list_threads()
    (CACHE / "threads.json").write_text(json.dumps(threads))
    since = parse_since(args.since)
    # Orb threads run at /home/user/workspace/repo; local ones carry the checkout path.
    candidates = [t for t in threads
                  if datetime.fromisoformat(t["updated"].replace("Z", "+00:00")) >= since
                  and (t.get("tree") is None or "workspace/repo" in t["tree"] or REPO_MARKER in t["tree"])]
    print(f"{len(threads)} threads listed; {len(candidates)} candidates since {since:%Y-%m-%d}",
          file=sys.stderr)
    counts = collections.Counter()
    with concurrent.futures.ThreadPoolExecutor(args.jobs) as pool:
        futures = {pool.submit(fetch, t, args.refresh): t for t in candidates}
        for i, fut in enumerate(concurrent.futures.as_completed(futures), 1):
            try:
                _, status = fut.result()
            except Exception as e:  # keep going; report at the end
                status = "error"
                print(f"  {futures[fut]['id']}: {e}", file=sys.stderr)
            counts[status] += 1
            if i % 20 == 0:
                print(f"  {i}/{len(candidates)} {dict(counts)}", file=sys.stderr)
    print(dict(counts), file=sys.stderr)
    cmd_index(args)


# --- thread record ------------------------------------------------------------

CHALLENGES = sorted((p.name for p in (Path(__file__).resolve().parents[1] / "challenges").iterdir()
                     if p.is_dir()), key=len, reverse=True)
GENERIC = {"hld", "module", "the", "of", "and", "to", "a", "service", "system", "find", "number", "with"}
HARNESSES = [("opencode", r"\bopencode\b|\bgo\b"), ("claude", r"\bclaude\b"), ("codex", r"\bcodex\b"),
             ("pi", r"\bpi\b"), ("amp", r"\bamp\b")]


def match_challenge(text, fuzzy=True):
    norm = re.sub(r"[^a-z0-9]+", "-", (text or "").lower())
    for name in CHALLENGES:  # longest exact name first
        if f"-{name}-" in f"-{norm}-":
            return name
    if not fuzzy:
        return None
    words = [w for w in norm.split("-") if len(w) >= 3]
    best, best_score = None, 0.0
    for name in CHALLENGES:
        keys = [k for k in name.split("-") if k not in GENERIC and len(k) >= 3]
        hits = sum(any(min(len(w), len(k)) >= 4 and (w.startswith(k) or k.startswith(w)) for w in words)
                   for k in keys)
        score = hits / len(keys) if keys else 0
        strong = score == 1 or hits >= 2 or (name.startswith("hld-") and "hld" in words)
        if score > best_score and hits and score >= 0.5 and strong and not name.startswith("qa-"):
            best, best_score = name, score
    return best


def classify(title, prompt):
    """Derive challenge, harness, and kind from a free-form title (prompt as fallback)."""
    t = (title or "").lower()
    challenge = match_challenge(title) or match_challenge((prompt or "")[:300], fuzzy=False)
    harness = next((h for h, rx in HARNESSES if re.search(rx, t)), None) \
        or next((h for h, rx in HARNESSES[:4] if re.search(rx, (prompt or "")[:300].lower())), None)
    m = re.match(r"(wave \d+(?: retry)?)\s*([a-z])?\b", t)
    if t.startswith("private suite"):
        kind = "private-suite"
    elif "idiom review" in t or "review" in t.split(":")[0]:
        kind = "review"
    elif challenge and re.search(r"\b(implement|finish|complete|independent|validat\w*|preflight|"
                                 r"baseline|frozen|requirements)\b", t):
        kind = "authoring"
    elif challenge and (harness or m or re.search(r"\b(attempt|a\d|retry)\b", t)):
        kind = "attempt"
    else:
        kind = "infra"
    return {"challenge": challenge, "harness": harness, "kind": kind,
            "wave": (m[1] + (f" {m[2].upper()}" if m[2] else "")) if m else None}


def message_usage(messages):
    """Per-model token totals from assistant message `usage` (no rate limit, unlike the CLI)."""
    models = collections.defaultdict(lambda: collections.Counter())
    for m in messages:
        u = m.get("usage") if m.get("role") == "assistant" else None
        if not u:
            continue
        c = models[u.get("model") or "?"]
        c["requests"] += 1
        c["input"] += u.get("totalInputTokens") or u.get("inputTokens") or 0
        c["output"] += u.get("outputTokens") or 0
        c["cache_read"] += u.get("cacheReadInputTokens") or 0
        c["cache_write"] += u.get("cacheCreationInputTokens") or 0
        c["peak_context"] = max(c["peak_context"], u.get("totalInputTokens") or 0)
    return {k: dict(v) for k, v in models.items()}


def active_minutes(messages, idle_cap_s=600):
    """Sum inter-message gaps, capping idle gaps, so resumed threads don't count wall-clock waits."""
    times = sorted(datetime.fromisoformat(m["createdAt"].replace("Z", "+00:00"))
                   for m in messages if m.get("createdAt"))
    return round(sum(min((b - a).total_seconds(), idle_cap_s) for a, b in zip(times, times[1:])) / 60, 1)


def thread_record(tid):
    export = load_export(tid)
    meta = export.get("meta") or {}
    messages = export.get("messages") or []
    env = (export.get("env") or {}).get("initial") or {}
    repo = next(((t.get("repository") or {}) for t in env.get("trees") or []), {})
    roles = collections.Counter(m.get("role") for m in messages)
    tool_uses = collections.Counter(
        c.get("name") for m in messages if m.get("role") == "assistant"
        for c in m.get("content") or [] if isinstance(c, dict) and c.get("type") == "tool_use")
    parent = next((m.get("meta", {}).get("fromExecutorThreadID") for m in messages
                   if (m.get("meta") or {}).get("fromExecutorThreadID")), None)
    first_user = next((c.get("text") for m in messages if m.get("role") == "user"
                       for c in m.get("content") or [] if isinstance(c, dict) and c.get("type") == "text"), "")
    created = export.get("created")
    rec = {
        "id": tid,
        "url": f"https://ampcode.com/threads/{tid}",
        "title": export.get("title"),
        "created": datetime.fromtimestamp(created / 1000, timezone.utc).isoformat() if created else None,
        "updated": export.get("updatedAt"),
        "executor": meta.get("executorType"),
        "agent_mode": export.get("agentMode") or meta.get("agentMode"),
        "project_id": meta.get("projectID"),
        "repo_sha": repo.get("sha"),
        "client": (env.get("platform") or {}).get("client"),
        "arch": (env.get("platform") or {}).get("cpuArchitecture"),
        "parent": parent,
        "state": (meta.get("lastKnownAgentState") or {}).get("state"),
        "recap": ((meta.get("recap") or {}).get("recap") or {}).get("text"),
        "skills": [s.get("name") for s in export.get("activatedSkills") or []],
        "messages": len(messages),
        "roles": dict(roles),
        "tool_uses": dict(tool_uses.most_common()),
        "first_prompt": (first_user or "")[:500],
        "active_min": active_minutes(messages),
        "tokens": message_usage(messages),
    }
    rec.update(classify(rec["title"], first_user))
    return rec


def cmd_index(args):
    ids = sorted(p.name[:-len(".json.gz")] for p in (CACHE / "export").glob("*.json.gz"))
    records = []
    for tid in ids:
        try:
            records.append(thread_record(tid))
        except Exception as e:
            print(f"  index {tid}: {e}", file=sys.stderr)
    records.sort(key=lambda r: r.get("created") or "")
    children = collections.Counter(r["parent"] for r in records if r.get("parent"))
    for r in records:
        r["children"] = children.get(r["id"], 0)
    with (CACHE / "index.jsonl").open("w") as f:
        for r in records:
            f.write(json.dumps(r) + "\n")
    print(f"indexed {len(records)} threads -> {CACHE / 'index.jsonl'}", file=sys.stderr)


def load_index():
    return [json.loads(l) for l in (CACHE / "index.jsonl").open()]


def cmd_summary(args):
    recs = [r for r in load_index() if not args.kind or r.get("kind") == args.kind]
    groups = collections.defaultdict(list)
    for r in recs:
        key = {"day": (r.get("created") or "")[:10]}.get(args.by) or r.get(args.by) or "(none)"
        groups[key].append(r)
    print(f"{'group':40} {'n':>4} {'msgs':>6} {'Mtok_in':>8} {'Ktok_out':>8} {'act_h':>6}")
    for key in sorted(groups):
        rs = groups[key]
        tok = lambda k: sum(m.get(k, 0) for r in rs for m in (r.get("tokens") or {}).values())
        print(f"{str(key)[:40]:40} {len(rs):>4} {sum(r['messages'] for r in rs):>6} {tok('input') / 1e6:>8.1f} "
              f"{tok('output') / 1e3:>8.0f} {sum(r.get('active_min') or 0 for r in rs) / 60:>6.1f}")
    print("tokens are the Amp orchestrating agent's, not the inner solver's")


# --- pointers into orb filesystems ----------------------------------------------

ARTIFACT_RX = re.compile(
    r"(?:/home/user/workspace/)?(?:repo/)?"
    r"((?:implementations|reports|latest-transcripts|logs|runs|transcripts)/[\w.@+=-]+(?:/[\w.@+=-]+)*)")
ARCHIVE_RX = re.compile(r"[\w.@+=-]+\.(?:bundle\.tar\.gz|legacy\.tar\.gz|tar\.zst|tar\.gz)\b")
VERDICT_RX = re.compile(r"\b(phase|private|overall|runner)[\s:=]+(PASS|FAIL|UNAVAILABLE|ERROR|SKIP)\b", re.I)
UPLOAD_RX = re.compile(r"(gh release upload\s+\S+[^\n;&|]*|run[-_]archives(?:\.py)? upload[^\n;&|]*)")
PUSH_RX = re.compile(r"git push\b[^\n;&|]*")


def artifact_root(path):
    """Collapse a path to the directory that holds one run's artifacts."""
    parts = path.rstrip(".,:)").split("/")
    depth = {"implementations": 2, "reports": 2, "runs": 2}.get(parts[0], 2)
    return "/".join(parts[:depth])


def thread_pointers(tid):
    export = load_export(tid)
    roots, archives, uploads, pushes, verdicts = collections.Counter(), set(), [], [], []
    for m in export.get("messages") or []:
        for c in m.get("content") or []:
            if not isinstance(c, dict):
                continue
            if c.get("type") == "tool_use":
                text = json.dumps(c.get("input") or {})
                uploads += UPLOAD_RX.findall(text)
                pushes += PUSH_RX.findall(text)
            elif c.get("type") == "tool_result":
                text = json.dumps(c.get("run") or c.get("content") or "")
                verdicts += [(k.lower(), v.upper()) for k, v in VERDICT_RX.findall(text)]
            else:
                continue
            text = text.replace("\\n", " ")
            for path in ARTIFACT_RX.findall(text):
                roots[artifact_root(path)] += 1
            archives.update(ARCHIVE_RX.findall(text))
    last = {}
    for kind, verdict in verdicts:  # the final reported verdict of each kind wins
        last[kind] = verdict
    return {"roots": [r for r, _ in roots.most_common(25)], "archives": sorted(archives),
            "uploads": sorted(set(u.strip() for u in uploads))[:10],
            "pushes": sorted(set(p.strip() for p in pushes))[:10], "verdicts": last}


def release_assets():
    try:
        tags = json.loads(subprocess.run(
            ["gh", "release", "list", "--repo", RUNS_REPO, "--json", "tagName"],
            capture_output=True, text=True, timeout=60).stdout or "[]")
        assets = {}
        for t in tags:
            out = subprocess.run(["gh", "release", "view", t["tagName"], "--repo", RUNS_REPO,
                                  "--json", "assets"], capture_output=True, text=True, timeout=60).stdout
            for a in json.loads(out)["assets"]:
                assets[a["name"]] = t["tagName"]
        return assets
    except Exception as e:
        print(f"  release listing failed: {e}", file=sys.stderr)
        return {}


def match_assets(rec, assets):
    """Release assets named for this thread's wave, challenge and harness."""
    ch, h, wave = rec.get("challenge"), rec.get("harness"), rec.get("wave")
    if not ch or not wave or "retry" in wave:
        return []
    m = re.match(r"wave (\d+) ([a-z])", wave.lower())
    if not m:
        return []
    prefix = f"wave{m[1]}-{m[2]}-"
    stem = ch.removeprefix("hld-").replace("collaborative-document-editor", "collaborative-editor")
    return sorted(n for n in assets if n.startswith(prefix + stem) and (not h or f"-{h}" in n))


def cmd_pointers(args):
    recs = [r for r in load_index() if not args.kind or r.get("kind") == args.kind]
    assets = release_assets()
    out = []
    for r in recs:
        ptr = thread_pointers(r["id"])
        ptr.update({k: r.get(k) for k in ("id", "title", "kind", "challenge", "harness", "wave",
                                          "created", "recap")})
        ptr["release_assets"] = match_assets(r, assets)
        out.append(ptr)
    (CACHE / "pointers.jsonl").write_text("".join(json.dumps(p) + "\n" for p in out))
    if args.json:
        print(json.dumps(out, indent=1))
        return
    for p in out:
        v = " ".join(f"{k}={v}" for k, v in sorted(p["verdicts"].items())) or "-"
        print(f"{p['created'][:10]} {p['id'][:13]} {(p['challenge'] or '-')[:28]:28} "
              f"{(p['harness'] or '-'):8} {(p['wave'] or '-'):13} {v}")
        print(f"    release: {', '.join(p['release_assets']) or '-'}")
        print(f"    orb paths: {', '.join(p['roots'][:6]) or '-'}")
    print(f"wrote {CACHE / 'pointers.jsonl'}", file=sys.stderr)


def cmd_show(args):
    for r in load_index():
        if r["id"].startswith(args.id) or r["id"][2:].startswith(args.id):
            print(json.dumps(r, indent=1))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("sync")
    s.add_argument("--since", default="21d", help="Nd, Nw, or ISO date (default 21d)")
    s.add_argument("--jobs", type=int, default=6)
    s.add_argument("--refresh", action="store_true", help="refetch even if unchanged")
    s.set_defaults(fn=cmd_sync)
    sub.add_parser("index").set_defaults(fn=cmd_index)
    s = sub.add_parser("summary")
    s.add_argument("--by", default="challenge",
                   choices=["challenge", "harness", "wave", "day", "kind", "agent_mode", "state", "parent"])
    s.add_argument("--kind", choices=["attempt", "authoring", "private-suite", "review", "infra"])
    s.set_defaults(fn=cmd_summary)
    s = sub.add_parser("pointers")
    s.add_argument("--kind", choices=["attempt", "authoring", "private-suite", "review", "infra"])
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_pointers)
    s = sub.add_parser("show")
    s.add_argument("id")
    s.set_defaults(fn=cmd_show)
    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
