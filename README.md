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
library source. It does not see the project's `.git`, encrypted files, private test/reference
directories, other challenges, sibling repository mounts, host `/proc`, host
transcripts, user settings/MCP servers, or `CHALLENGE_KEY`. Public input symlinks
are rejected. Only the current `implementations/<name>` is persisted; other
filesystem changes and CLI session state are discarded after each invocation.
Native stdout/stderr, timing, requested model/effort, and isolation mode are
preserved in the runner transcript, including failures and retries.

System tools and explicit read-only Maven/Git dependency caches remain available
(including the two generic `clojure-mcp-light` and Cognitect `test-runner` Git
object caches required by Clojure; never challenge or sibling repository history).
Prefetch dependencies on the host first (`.agents/setup` does this). Provider
environment credentials and selected CLI auth files are allowlisted; unrelated
secrets, Git credentials, histories, and full home directories are not mounted.
This supports the standard installed CLI locations used by orb setup/Docker,
not arbitrary custom installations or provider endpoints.

**`--isolate` boundary limits:** network is shared for provider access, not filtered. A solver
could fetch public reference repositories or reach an unsafe host-local REPL or
file server. Use fresh disposable orbs with no such services; a strict
no-external-reference evaluation needs the additional controls and trust decisions
described below. Do not claim this is an adversarial-code security
sandbox: provider credentials are readable by the CLI and its tools, installed
tools/caches/public inputs are trusted, and private scoring executes generated
code on the host after solving. Do not run hostile submissions or concurrent
untrusted host processes. Hidden cluster-setup services/custom networking are
not certified by this filesystem-only boundary. Docker hosts must permit
unprivileged namespaces; Docker isolation was not validated by the orb tests.

### CONNECT-restricted networking is an additional opt-in boundary

Use **`--isolate-network`** for evaluation solvers. It implies `--isolate` and
keeps bubblewrap's separate network namespace (no external interface, host
loopback, or direct DNS route). The only external connection path is a mounted
Unix socket to a host-side CONNECT proxy. A Python loopback bridge inside the
namespace supplies `HTTP_PROXY`/`HTTPS_PROXY` to the CLI. Bypassing those variables
does not restore a route. Proxy and solver terminate with the launcher; denied
and allowed destinations are recorded in stderr and therefore the native runner
transcript (without request headers, tokens, paths, or response bodies).

The fixed allowlist permits only CONNECT on **443**, with all resolved addresses
public and connection made to the checked numeric address (no second DNS lookup):

| Purpose | Exact CONNECT hosts |
|---|---|
| Claude first-party | `api.anthropic.com` |
| OpenCode with OpenRouter | `openrouter.ai` |
| Official Rama documentation | `redplanetlabs.com` |
| Dependency repositories | `nexus.redplanetlabs.com`, `repo.maven.apache.org`, `repo.clojars.org` |

GitHub, raw GitHub, other public origins, private/link-local/loopback destinations,
non-443 ports, plaintext HTTP proxying, and unlisted providers are denied. Each
agent gets only its own provider host plus docs/dependency hosts. Redirects to
unlisted hosts fail. OAuth refresh/custom providers/Bedrock/Vertex are not
supported; provision a currently valid first-party token or OpenRouter API key
before the run. This mode currently rejects Codex and Pi rather than broadening
the allowlist. Cluster services outside the solver namespace are unavailable.

Run `.agents/setup` first: it preseeds OpenCode's public models catalog, installs
ripgrep, and warms Maven/Git/nREPL dependencies. Strict OpenCode loads only the
preseeded catalog and disables catalog refresh, auto-update, LSP downloads, and
external/default plugins. Full user CLI configuration is not mounted. New Git
dependencies cannot be fetched; read-only dependency caches must already contain
everything needed. Missing prerequisites fail rather than enabling shared
network access. Claude telemetry and OpenCode npm requests may appear as denied
destinations; both tested CLIs still completed the logistics smoke requests.

### OpenRouter credentials in project orbs

Follow the installed `agent-skills:fetching-project-secrets` skill for safe
Bitwarden use. Orb setup installs `bws`; the existing **personal-scope** masked
Amp `BWS_API_KEY` must be injected, with read access to the Bitwarden secret named
`OPENROUTER_API_KEY` (do not create a project-scope duplicate). To run a trusted
command with that key only in the consumer's environment:

```bash
scripts/with-openrouter-key.bb bb run-challenges --agent opencode --batch 1
```

The wrapper requires one exact-name match. Optional non-secret
`OPENROUTER_BWS_PROJECT_ID` scopes discovery; `OPENROUTER_BWS_SECRET_ID` skips
discovery when the UUID is known. Never log the consumer's environment.

**Residual guarantees:** this is CONNECT-authority and public TCP-endpoint
enforcement, **not exact HTTP-authority or response-content enforcement**. TLS
stays end-to-end: the proxy does not inspect SNI, HTTP Host, or HTTP/2 authority.
Shared CDN virtual hosts/domain fronting or a relay on an allowed service could
permit indirect reference retrieval. A tested cross-host CDN request returned
403, but that is not a general proof. Evaluations must explicitly accept this
trust assumption or use a maintained TLS-terminating HTTP-aware proxy that
authorizes every request. Even that cannot certify content provenance from
allowed services. Host private scoring, credentials, trusted caches, and
non-hostile-submission limitations above still apply.

The evaluation owner accepted this CONNECT/public-destination boundary for the
non-hostile challenge program, not adversarial reference containment. Keep native
transcripts and proxy audit logs. **Any observed access to excluded reference
material invalidates the attempt:** fix the logistics and rerun; do not score it
as a model failure.

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

The authorized evaluation configurations (select the challenge with `-f` or batch
from `CHALLENGE_ORDER.md`) are:

```bash
# A: Claude CLI
CHALLENGE_KEY=<passphrase> bb run-challenges --isolate-network -f <challenge> --agent claude \
  --slow-model claude-fable-5-1 --slow-effort medium \
  --fast-model claude-opus-5-5 --fast-effort high
# B: OpenCode CLI; explicitly authorized GLM substitution
CHALLENGE_KEY=<passphrase> bb run-challenges --isolate-network -f <challenge> --agent opencode \
  --slow-model openrouter/z-ai/glm-5.3 --slow-effort high \
  --fast-model openrouter/meta/muse-spark-1.3-contributor --fast-effort high
```


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

For the shared Amp/Claude/OpenCode/Pi instruction and skill layout, and a
metadata-only inventory workflow for stopped runs, see
[agent layout and run review](docs/agent-layout-and-run-review.md). Do not
publish raw native histories or private-test output as solver review bundles.

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
python3 -m unittest discover -s scripts -p 'test_solver_proxy.py' -v
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
