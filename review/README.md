# Rama evaluation review

Evaluator-only review interface for a bounded, real artifact corpus. No solver or
judge invocation occurs when building, serving or testing this interface.

## Run

From the repository root:

```sh
amp orb service start rama-review \
  --command 'PYTHONDONTWRITEBYTECODE=1 python3 scripts/review_server.py --port 8766' \
  --port 8766 --portal
```

The command returns the authenticated Portal URL. Keep the supervised service
running for review. The server binds loopback and serves an exact allowlist, not
the repository root. It has no independent authentication layer; do not expose
the port without the authenticated Portal. The service can be persisted in
`.amp/services.yaml` in a follow-up if wanted.

## Evidence coverage

`catalog.json` and `local-runs.json` record 13 selected attempts:

- 5 completed runs from 16 September: auction, attack, social graph and fanout,
  bank transfer and time series
- 4 user-stopped attempts from 23 September: A/B URL shortener and A/B rate limiter
- 4 local runner-failed records from 15 September: bank transfer and time series

Searches for 17 to 19 September found no matching threads, including a date-only
query. The completed runs are 8 days before this audit, outside the requested
5 to 7-day window. The user identified bank transfer and time series after the
initial search. Discovery is incomplete; other runs may exist.

Both added Opus reports and implementations were authenticated-transferred after
the owner thread was restored with approval. Each record includes original hashes.
The owner reported that `latest-transcripts` was missing; no transcript is
invented. Auction was explicitly not run in that owner thread.

Eleven original files were authenticated-transferred from seven owner orbs: four
reports, one native phase capture, three agent implementations, three compact
start/configuration files. Originals were inspected outside the served root.
Only reviewed source, compact metadata and safe derivatives are retained here.
Raw originals containing private/reference justifications or native reasoning
are not included. Each record carries original path, byte count, SHA-256,
transfer status and a separate digest of the retained export.

Two private local archives contain 62 file occurrences, 41 unique files and 21
duplicate occurrences. Safe exports retain 4 reports and 25 unique Codex captures
(805 metadata events). Twelve documents are withheld. `evidence/archive-index.json`
records archive hashes, member hashes and duplicate origins. The dataset adds
export hashes to each run's evidence records.
The September 16 archive overlaps the September 15 archive; it does not recover
the missing Claude transcripts. Native events lack timestamps. Filename stamps
have no timezone; archive modification times are separate metadata, not run times.
Report-to-capture associations are provisional, and no local source was supplied.

Most phase inventories and the attack run's outcome metrics are owner-thread
reports, not locally reconstructed execution proof. A-rate has no transferred
artifact. No baseline implementation was recovered, so the source viewer does
not pretend to be a diff. Missing costs, verdicts and timings are not zeros.
The four stopped attempts are not observed provider rate/quota failures.

## Safe extraction

Typed report fields, excluding all justification prose:

```sh
python3 scripts/review_index.py /path/to/authorized-report.md > /tmp/metrics.json
```

Native transcript inspection uses the existing supported analyzer entry point:

```sh
python3 scripts/analyze-latest-transcript.py \
  --file /path/to/authorized-native.jsonl safe-review > /tmp/safe-events.json
```

The safe exporter projects numeric/enumerated metadata, validated timestamps,
allowlisted tool names and exact claimed phase-verdict lines. It never exports
message prose, tool arguments/results, thinking, `source_event`, model strings,
stderr, credentials or private test content. Event indices refer to normalized
events, not original JSONL lines. This preserves structure, not a complete
transcript. A claimed verdict is not an independently executed test result.

Shared adapters cover Claude, Codex, OpenCode and Pi with offline fixtures.
OpenClaw is unsupported. Unknown formats must be investigated before indexing;
the existing shared adapter's Claude fallback does not establish compatibility.

Add only explicitly reviewed exports to `evidence/`, register them in the
catalog, and rerun checks. Never copy a whole owner transcript archive into the
served directory. Do not mount `review/` into solver workspaces. The existing
isolated snapshot excludes it; unisolated solver mode is not a safety boundary.

## Preparing local transcripts for import

`scripts/review_import_archives.py` imports approved archives offline. It checks
paths, types, file counts and expanded sizes before streaming members. It rejects
links and special files, deduplicates by content hash and exports typed reports
and recognised Codex metadata only. Raw files never enter the served directory.
The format is deliberately narrow, not a general archive upload endpoint.

No access to your local machine or server is configured. Prepare exports locally
and transfer only reviewed files through an approved attachment or file-transfer
channel. The review server has no transcript upload endpoint.

1. Keep the original native JSONL on your machine. Identify its CLI and version
   before extraction. Claude, Codex, OpenCode and Pi have offline-tested adapters;
   other formats need explicit support. Do not rely on the Claude fallback.
2. Run the `safe-review` command above on each phase file. Keep one JSON export
   per phase. This exports event structure, not readable conversations.
3. Prepare a JSON manifest with schema version, run and phase IDs, challenge,
   CLI version, both model and effort settings, source revision and dirty status,
   start and end times with timezone, exit status, and completion classification.
   Record private verdicts separately with their evidence source. Use null for
   unknown values. Record whether a file is complete or truncated.
4. Include the original and exported SHA-256 hashes, original byte count,
   relative filename, exporter revision and approved export filename. Omit
   personal paths, environment variables, credentials and raw diagnostics.
5. Review the exports before transfer. Send optional report metrics through
   `review_index.py`, never raw grading explanations. Review implementation source
   separately for private-test or reference material. Keep curated public
   excerpts separate from event records and identify their source.

The proposed manifest is a handoff format, not an implemented automatic importer.
We must validate file hashes, allowed fields, CLI compatibility and references
before adding it to `catalog.json`. Unsupported formats, missing files and
partial captures must stay explicit. Server access needs separate configuration.

## Review data

Pass/Fail/Defer labels answer whether evidence substantiates the reported private
verdict. They are not new benchmark scores or expert-calibrated ground truth.
Notes autosave to SQLite at `review/.state/annotations.sqlite3`, keyed by random
browser-local reviewer ID, dataset hash and attempt ID. Browser localStorage is
a backup. Pending writes are marked and can be retried with Save. Exports include
the criterion and dataset hash. Export reviews before moving to another orb.
There is no multi-user identity resolution or aggregate expert calibration.

## Audit method

`findings.json` contains 11 prioritized, source-cited findings with validation
proposals and usage notes for all eight mounted `okwalerie/evals-skills` skills.
The mounted revision and inspected harness revision are in the catalog. No
skill was edited or installed. Human annotation, failure-mode saturation, judge
calibration and new model comparisons remain future work, not completed claims.
QA claim scoring has no inspected retrieval stage; RAG retrieval metrics are
not applicable. Synthetic fixtures target export failure modes only.

Cross-CLI skills/log portability lives in a separate checkout/thread. Its
changes are not included here. Isolation fixes also remain outside this task.

## Current sweep

The `#sweep` route reads `/api/sweep`, a manually curated `sweep.json` snapshot.
It is separate from the historical dataset and does not change its annotation
hash. Snapshot freshness and attempt-checkpoint freshness are separate: a newly
verified shipment does not verify current worker activity. Reference tests are
not solver scores. Ledger paths in other orbs are retrieval pointers, not files
verified here. Only approved metadata receipts belong in `attempts`.

Attempt fields are challenge, provider, model, effort, attempt_number,
retry_reason, worker_receipt, shipped_run_sha, started_at, finished_at,
implementation_reached, outcome, private_verdict, tests, assertions,
evidence_basis, estimated_spend_usd and billed_spend_usd. Use null for unknown
values. Retain partial, infrastructure-blocked, quota-blocked and unknown outcomes
separately. Never infer quota from a stop, or a private PASS from a phase PASS.
Verify actual nonzero tests and their assertions before accepting a private PASS.
No invoice means billed spend is unknown. Do not import raw logs or private data.

`native_private_verdict` preserves the reported runner result separately from
`private_verdict`, the substantiated result/disposition. `private_execution`
explains the evidence boundary. A native FAIL may reflect real tests failing to
load an absent implementation; it is not automatically a solver-quality failure
or a synthetic sentinel. Preserve unknown original counts and distinguish host
reruns. A private PASS can coexist with an overall quota-interrupted attempt.
Grouped lane receipts may confirm active challenges before exact worker URLs or
start times arrive; display them separately rather than fabricate individual
receipts or double-count them. `retryOf` links a confirmed subsequent attempt.

`stage_rollup` is a separately timestamped owner census. It counts attempts,
not unique challenge cells. Current stage at stop is mutually exclusive;
cumulative reached stages overlap and must not be summed. Blockers classify
the same attempts separately. Null cohort cells mean unknown, not zero; do not
fill them by extrapolating dispatch links or partial worker receipts. Preserve
the source time even when a newer manual snapshot imports unrelated updates.

## Verify

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/review_test.py -v
PYTHONDONTWRITEBYTECODE=1 python3 scripts/review_import_test.py -v
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py' -v
bb scripts/run_challenges_test.bb
PYTHONDONTWRITEBYTECODE=1 python3 scripts/review_browser_check.py \
  --artifacts /absolute/path/to/review-captures
```

Browser checks use installed `agent-browser`, a temporary server and disposable
annotation database. They exercise labeling, note persistence, injection-safe
rendering, keyboard controls, safe evidence/source dialogs, missing/empty
states, comparison, findings, and all four routes at 390px. Screenshots need
visual inspection after the command; capturing alone is not a visual test.
Chromium narrow-width checks are not touch, physical-device or Safari tests.
