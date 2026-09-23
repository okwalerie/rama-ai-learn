# rama-ai-learn

A harness for developing skill files for [Rama](https://redplanetlabs.com/), with the goal of one-shotting complex, scalable, and fault-tolerant backends. The harness runs an LLM through a series of implementation challenges, captures every transcript in full, and provides tooling for analyzing transcripts to inform edits to skill files. A challenge succeeds when the agent's implementation passes private tests covering correctness, performance, and fault-tolerance.

We're currently targeting the Clojure API, but a Java equivalent is planned.

For the project's motivation, design, and progress updates, see [the blog post series](https://blog.redplanetlabs.com/2026/05/28/teaching-llms-to-one-shot-complex-backends-at-scale-report-1/).

## What's in the repo

- `challenges/` — implementation challenges, each one a Rama module the agent has to build from a protocol contract.
- `plugins/rama-skill/skills/rama/` — the Rama skill content (an [agentskills.io](http://agentskills.io/)-format skill).
- `scripts/run_challenges.bb` — the orchestration runner that drives an agent through a challenge.
- `scripts/docker-*.sh` — Docker harness for running challenges in an isolated container.
- `scripts/analyze-latest-transcript.py` — tooling for inspecting transcripts after a run.

## Challenge structure

Each challenge is self-contained under `challenges/<name>/`. The agent gets a README and a protocol contract, while everything else (private tests, reference implementation) is encrypted while the run is in progress so the agent cannot read it.

A typical challenge looks like:

```
challenges/auction-module/
├── README.md                                # problem statement + constraints
├── src/
│   └── auction_module/
│       └── protocol.clj                     # the contract the agent must satisfy
├── test-resources/auction_module/module.clj # reference implementation (encrypted at run time)
├── test-private/                            # private functional + performance tests (encrypted)
└── deps.edn
```


## Running a challenge

The `CHALLENGE_KEY` environment variable encrypts working-tree reference solutions
and private tests during a run. It does **not** hide copies in Git history or
elsewhere on disk. For uncontaminated challenge results, use a disposable checkout
whose Git history excludes private material and isolate it from other checkouts,
backups, and the encryption key. Do not count a run that accessed hidden references.

For Linux evaluation runs, pass **`--isolate`**. This is opt-in for compatibility
with existing non-Linux workflows; encryption without it is **not isolation**.
The runner launches each solver phase through `scripts/isolate_solver.py` using
bubblewrap user, mount, PID, IPC, and UTS namespaces. Missing bubblewrap or denied
namespace creation fails closed; there is no unisolated fallback.

The solver sees only the selected README, public source (excluding test-support
files), deps, kondo config, generic Rama skill, phase instructions, and shared
library source. It does not see `.git`, encrypted files, private test/reference
directories, other challenges, sibling repository mounts, host `/proc`, host
transcripts, user settings/MCP servers, or `CHALLENGE_KEY`. Public input symlinks
are rejected. Only the current `implementations/<name>` is persisted; other
filesystem changes and CLI session state are discarded after each invocation.
Native stdout/stderr, timing, requested model/effort, and isolation mode are
preserved in the runner transcript, including failures and retries.

System tools and explicit read-only Maven/Git dependency caches remain available.
Prefetch dependencies on the host first (`.agents/setup` does this). Provider
environment credentials and selected CLI auth files are allowlisted; unrelated
secrets, Git credentials, histories, and full home directories are not mounted.
This supports the standard installed CLI locations used by orb setup/Docker,
not arbitrary custom installations or provider endpoints.

**Boundary limits:** network is shared for provider access, not filtered. A solver
could fetch public reference repositories or reach an unsafe host-local REPL or
file server. Use fresh disposable orbs with no such services; a strict
no-external-reference evaluation additionally requires a provider-only network
policy outside this launcher. Do not claim this is an adversarial-code security
sandbox: provider credentials are readable by the CLI and its tools, installed
tools/caches/public inputs are trusted, and private scoring executes generated
code on the host after solving. Do not run hostile submissions or concurrent
untrusted host processes. Hidden cluster-setup services/custom networking are
not certified by this filesystem-only boundary. Docker hosts must permit
unprivileged namespaces; Docker isolation was not validated by the orb tests.

```bash
CHALLENGE_KEY=<passphrase> bb run-challenges --isolate -f auction-module --agent claude \
  --fast-model claude-sonnet-4-6 --fast-effort medium \
  --slow-model claude-opus-4-6 --slow-effort high -p
```

Each invocation (including retries) produces a transcript under `../transcripts/`.
The Docker copy script collects the latest run into `latest-transcripts/`.

The phase runner supports `--agent claude`, `codex`, `opencode`, and `pi`.
Install and authenticate the selected CLI first; Python 3 is also required for
shared transcript normalization. OpenCode and Pi use `provider/model` model
names (the `/` is sanitized only in output filenames). For example:

```bash
CHALLENGE_KEY=<passphrase> bb run-challenges --isolate -f auction-module --agent opencode \
  --fast-model anthropic/claude-sonnet-4-6 --fast-effort medium \
  --slow-model anthropic/claude-opus-4-6 --slow-effort high
# Use --agent pi with model IDs supported by your Pi installation.
```

OpenCode maps effort to `--variant`; Pi maps it to `--thinking`. Valid values
depend on the model/CLI. Each phase starts a fresh invocation and explicitly
instructs OpenCode/Pi to read the existing phase instructions; no harness-specific
slash-command installation is required. Alignment scoring uses the selected
harness, rather than requiring Claude for an OpenCode/Pi/Codex run.

**Run only in an isolated environment:** OpenCode uses `--auto` to approve
permissions unless explicitly denied by its configuration; Pi's built-in tools
execute without interactive approval. Configure project trust/skills as required
by your installed CLI. Orb setup and Docker install OpenCode 1.18.32 but do not
provision credentials or install Pi. This change does not extend `run-qa`.

### Model and effort preflight makes no completion request

Isolated Claude/OpenCode runs first validate both configured model/effort pairs against
installed CLI metadata, failing rather than silently substituting. You can run
that check independently without `CHALLENGE_KEY`:

```bash
python3 scripts/check_solver_models.py --agent claude \
  --pair claude-fable-5-1 medium --pair claude-opus-5-5 high
python3 scripts/check_solver_models.py --agent opencode \
  --pair openrouter/z-ai/glm-5.3 high --pair openrouter/meta/muse-spark-1.3-contributor high
```

On 2026-09-23, Claude 2.1.280 SDK initialize metadata advertises Fable 5.1 as
`claude-fable-5-1` and Opus 5.5 as `claude-opus-5-5`, both supporting medium/high.
OpenCode 1.18.32 advertises Muse Spark 1.3 Contributor at the exact ID above with
high effort. It advertises `openrouter/z-ai/glm-5.3` as **GLM-5.3**, with only
low/high/max variants. The evaluation owner explicitly authorized **GLM-5.3/high
as the slow tier**, replacing the unavailable GLM 5.3 Pro/medium; Muse Contributor
high remains the fast tier. Config A uses Fable 5.1/medium slow and Opus 5.5/high
fast. This is an authorized configuration change, not an automatic fallback.
Metadata establishes CLI support, not account
entitlement or proof the provider applies the requested effort. No inference is
performed by the preflight. Pi/Codex model metadata validation is not implemented.


## Docker workflow

Runs are usually executed inside a Docker container so the LLM has a clean, isolated environment with the tooling it needs (Clojure CLI, clj-kondo, Babashka, nREPL helpers, Claude Code CLI).

```bash
# Build the image once
bash scripts/docker-build.sh

# Start a long-running container (requires CLAUDE_CODE_OAUTH_TOKEN in the env)
bash scripts/docker-start.sh

# Copy the current repo into the running container
bash scripts/docker-copy-in.sh

# (From inside the container) run challenges
docker exec -it rama bash
CHALLENGE_KEY=<passphrase> bb run-challenges -f <challenge-name> --agent claude \
  --fast-model claude-sonnet-4-6 --fast-effort medium \
  --slow-model claude-opus-4-6 --slow-effort high -p

# After the run, copy transcripts back to the host
bash scripts/docker-copy-transcript.sh
```

The container mounts `~/.m2` and a named gitlibs volume so dependency caches persist across runs.

Claude challenge phases append concise `Decision` / `Basis` / `Outcome` records to
`implementations/<challenge>/REASONING.md`, including technical dead ends and
resolved blockers, without requesting private chain-of-thought. The Amp
`challenge-phase` skill and Codex harness retain their existing logging prompts.

## Inspecting transcripts

After a run, transcripts can be analyzed via:

```bash
python3 scripts/analyze-latest-transcript.py run-overview
python3 scripts/analyze-latest-transcript.py --phase 3 module
python3 scripts/analyze-latest-transcript.py --phase 4 impl-validation
python3 scripts/analyze-latest-transcript.py thinking <keyword>
python3 scripts/analyze-latest-transcript.py --file /path/to/native-history.jsonl summary
python3 scripts/analyze-latest-transcript.py --file /path/to/native-history.jsonl events
```

Run `python3 scripts/analyze-latest-transcript.py` with no arguments for the full command list.

The analyzer auto-detects Claude stream-json, Codex `exec --json`, OpenCode
`run --format json`, and Pi `--mode json` or session-message JSONL. Existing
Claude-shaped normalized histories remain readable. `--file` is an alternative
to the existing default/phase/attempt/subsystem selectors.

`scripts/transcript_events.py` is the single native-event normalization boundary:
it produces the assistant/user content blocks already understood by all analyzer
commands, plus usage, turn, error, and result events. The runner uses the same
normalizer for telemetry. Scheduling, retries, encryption, and subprocess timing
remain in the orchestrator. Saved files retain native output, with runner timing,
exit, and timeout metadata around it; normalization does not rewrite old files.
Source events (including unknown events and unfinished streaming deltas) remain
available through `events`; `events <index>` inspects one event by its timeline
line index. Displayed line indexes refer to normalized events.

Completed snapshots supply content and usage once: Pi message/turn/agent lifecycle
echoes do not multiply searches, tools, or costs. OpenCode steps and Pi assistant
messages count as turns. Summaries show provider-reported cost, token/cache totals,
observed status, stop reason, and last completed response. Failures are separate
from response text. Missing cost/timing/turn data is `N/A`, not fabricated zero;
runner timing takes precedence when available. Standalone native Codex histories
usually lack timestamps and billed cost. Token fields preserve provider semantics
(notably Codex input includes cached input; Pi/OpenCode separate cache counters).

Transcript status `completed` means the harness invocation completed, not that the
challenge passed. Check the runner report's separate `Private` verdict and score;
its phase-level `Status` can be `PASS` while private tests fail.

File reconstruction replays writes/edits and skips explicitly failed tools; it
cannot reconstruct shell writes or Codex path-only file-change records, and is
best-effort if tool results are missing. OpenCode edits support an unambiguous
line-whitespace fallback; its other fuzzy matches are not replayed. Pi session
files are analyzed in recorded order, not as a selected branch of an interactive
session tree. Partial deltas are retained for inspection but are not promoted to
completed text or tool calls.

`test-runs` recognizes Clojure CLI test aliases and direct `run-tests` calls
(including `clojure.test/run-tests` under `-e` and a `timeout` prefix), and matches
their tool results by call ID. This is command recognition, not a general shell
parser or a substitute for the runner's private-test verdict.

Validate the adapters without credentials or model calls:

```bash
python3 -m unittest discover -s scripts -p 'test_transcript_events.py' -v
python3 -m unittest discover -s scripts -p 'test_isolate_solver.py' -v
python3 -m unittest discover -s scripts -p 'test_check_solver_models.py' -v
bb scripts/run_challenges_test.bb
python3 scripts/analyze-latest-transcript.py --file scripts/fixtures/transcripts/pi.jsonl summary
python3 scripts/analyze-latest-transcript.py --file scripts/fixtures/transcripts/opencode.jsonl module
```

Fixtures are synthetic, based on the native schemas documented by
[OpenCode's run command](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/cli/cmd/run.ts)
and [Pi's JSON mode](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/docs/json.md).
Tests cover shared commands, multi-turn totals, lifecycle deduplication, failures,
file reconstruction, timestamps, and real subprocess/save/scoring boundaries with
fixture output. They do not certify provider authentication or a full live challenge.
