# Repository agent layout and stopped-run review

`AGENTS.md` is the shared instruction source. Root `CLAUDE.md` and the old
`.claude/CLAUDE.md` path link to it. `.agents/skills/` owns repository skills;
the Rama skill itself remains in `plugins/rama-skill/skills/rama/` (published
from there). `.claude/skills/` has targeted links so Claude's existing command
aliases and local settings, plugin configuration and `.claude/agents/` remain
intact. Do **not** replace all of `.claude` with a link to `.agents`.

OpenCode can discover `.agents/skills/` without an `.opencode/skills` mirror.
Its custom agents use `.opencode/agents/`, not Claude's `.claude/agents/`;
Claude agent frontmatter, models and tool lists are not drop-in OpenCode
configuration. [Pi's skill documentation](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/docs/skills.md)
lists project `.agents/skills/` as a discovery root, after project trust.
Pi is not installed in this orb, so actual discovery on a particular Pi build
remains unverified. If that build differs, inspect its discovery before adding
a targeted compatibility link; its documented native project path is
`.pi/skills/`, not `.pi/agent/skills/` (which is a global path).
Project skills take precedence only according to each CLI's own discovery
order; avoid adding duplicate names under multiple native roots with different
contents. Claude plugin skills may also introduce namespaced aliases; its
settings explicitly deny two plugin Rama skill aliases, so keep those settings.

## Run inventory (no model calls)

On the orb holding a run, use the workspace parent of `repo/`,
`transcripts/`, and `reports/`. Get the shared timestamp from native phase
filenames; model and effort segments can differ between phases:

```bash
python3 scripts/index_run_artifacts.py /home/user/workspace \
  hld-url-shortener 2026-09-24-123456 \
  --proxy-log transcripts/network-proxy.log > /tmp/url-inventory.json
```

The index emits **only** relative paths, byte sizes, and kinds; it skips
symlinks and private suites. It does not copy files, parse JSONL, or claim that
a matching report/implementation belongs to this run. Reports have a separate
timestamp; implementation files can be overwritten by subsequent attempts.
Each retry has its own `-retryN` JSONL; phase retries carry `-attemptN`.
Compare the inventory with the worker's attempt ID, start/end time, base commit,
phase/stop classification, and runner report. Mark missing files as missing,
not zero-cost or successful. The `REASONING.md` sentinel alone does not prove
a phase completed or that substantive entries were written.

For a review bundle, copy *only after human inspection*: the specific run's
implementation source and authored `REASONING.md` decision/basis/outcome
entries, plus a curated phase/status/usage summary. Keep raw native JSONL,
proxy logs, report diagnostics and private-test stdout in a restricted evidence
store. `scripts/analyze-latest-transcript.py --file <phase.jsonl> summary`
helps inspect telemetry locally, but `thinking`/`events` and tool results can
contain model reasoning, secrets, private output, and unrelated reads; never
bulk-export these or infer an inner chain of thought from them. Extract only
agent-authored explicit decisions and observable actions, redact credentials,
paths and any reference/private suite content, then review again before sharing.
Private-suite verdict/counts may be reported separately after the solver is
done, but not the tests/expected answers or scorer context in any solver
prompt. The test-improver agent may access reference code in a separate
authorized authoring context; it must not be used as a solver-phase prompt.

This orb has no `implementations/`, `transcripts/` or `reports/` for the four
stopped solver attempts. Their owning threads report partial implementation
trees and completed *earlier* phase JSONLs, but no finalized JSONL for the
phase active at the stop, private test verdict, final report or separate durable
proxy log. Operator logs exist in their own `.amp/` locations. These are
worker reports, not a file-level audit from this orb: use authenticated transfer
or inventory in each owning orb before exporting any review bundle:

| Attempt | Owning thread | Completed phase captures reported | Interrupted phase |
|---|---|---:|---|
| A-url-01 | [thread](https://ampcode.com/threads/T-01a0cf74-e16b-7249-8d7c-18e913eb459f) | 7 | click-counting build |
| B-url-01 | [thread](https://ampcode.com/threads/T-01a0cf74-efcd-77da-912a-250c831c0132) | 6 | after lifecycle-resolve phase 1; active phase unverified |
| A-rate-01 | [thread](https://ampcode.com/threads/T-01a0cf74-ff4d-7308-8b43-30b6090ed90f) | 7 | decisions build |
| B-rate-01 | [thread](https://ampcode.com/threads/T-01a0cf75-0ed9-718b-b83b-de443c4d0c47) | 7 | decisions build |

All four were user/logistics stops (exit 143), not observed provider rate or
quota failures. `.amp/hld-program/STATE.md` also describes earlier *authoring*
checkpoints; do not conflate those with solver attempt histories. No old
artifacts need to be modified or shipped.
