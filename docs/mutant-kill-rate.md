# Mutant kill rate — before and after PR #11

`bb mutant-kill-rate` runs each challenge's private grader (`test-private/`)
against the reference module (must pass) and against each mutant (should
fail). A mutant is a copy of the reference with one wrong design, usually an
NFR violation. A mutant that survives points to a grader gap.

## Comparison (same 25 mutants)

| Graders | References | Mutants killed | Kill rate | Survived | Broken |
|---|---:|---:|---:|---:|---:|
| Before: `4bed04d` (2026-09-30) | 16/16 | 6/25 | 24% | 19 | 0 |
| After: `78b9872` (`origin/master`, includes PR #11 at `600158f`; 2026-10-03) | 16/16 | 13/25 | 52% | 12 | 0 |

The after run used `bb mutant-kill-rate --jobs 1 --timeout-s 900` across all
16 challenges with mutants, without skipping any references. The mutant
sources are unchanged from `0396370`. Seven previous survivors flipped to
killed; none flipped the other way. Both full run receipts and the joined rows
are in `docs/mutant-kill-rate.json` (`before-run`, `after-run`, `mutants`).
"PR #11 NFR test?" means **at least one of the after run's failing test names**
was introduced in PR #11; it does not claim that test was the only killer.
In particular, `full-prefix-scan` was already killed before PR #11 but also
fails the new ranking test. "Killing test" lists the after run's failing test
names; `—` means no test failed.

| Challenge | Mutant | Before | After | Killing test (after) | PR #11 NFR test? |
|---|---|---|---|---|---|
| bank-transfer-module | stream-topology | killed | killed | performance-challenge-test | No |
| bank-transfer-module | unsubindexed-history | killed | killed | performance-challenge-test | No |
| chat-app | durable-heartbeats | killed | killed | fault-tolerance-challenge-test, write-volume-challenge-test | No |
| chat-app | per-member-post-writes | killed | killed | write-volume-challenge-test | No |
| collaborative-document-editor | non-subindexed-history | survived | survived | — | No |
| collaborative-document-editor | recompute-doc-on-read | survived | survived | — | No |
| hld-ad-click-aggregation | global-placement | survived | survived | — | No |
| hld-ad-click-aggregation | read-time-aggregation | survived | killed | window-work-independent-of-clicks-in-window | Yes |
| hld-feature-flag-service | env-scan | survived | survived | — | No |
| hld-feature-flag-service | global-pstate | survived | survived | — | No |
| hld-file-sync | version-history-vector | survived | survived | — | No |
| hld-hotel-reservation | cancel-scans-bookings | survived | killed | reserve-and-cancel-bounded-by-stay-not-history | Yes |
| hld-metrics-pipeline | lazy-retention | survived | survived | — | No |
| hld-metrics-pipeline | series-blob | survived | survived | — | No |
| hld-payment-system | journal-sorted-map-blob | survived | killed | journal-page-reads-each-returned-row | Yes |
| hld-rate-limiter | stream-client-dedup | survived | survived | — | No |
| hld-search-autocomplete | full-prefix-scan | killed | killed | independent-four-tasks, independent-two-tasks, suggest-ranks-by-score-not-lexical-order | Yes |
| hld-search-autocomplete | locale-placement | survived | survived | — | No |
| hld-stock-exchange | book-as-one-value | survived | killed | trade-pages-and-depth-read-each-returned-entry | Yes |
| hld-ticketing-system | hold-scans-event-holds | survived | killed | mutating-commands-bounded-by-own-history | Yes |
| hld-url-shortener | stream-client-dedup | survived | killed | forced-stream-retry-counts-each-click-once | Yes |
| time-series-module-hard | no-thirty-day-rollup | survived | killed | multi-year-range-reads-coarse-buckets | Yes |
| time-series-module-hard | stream-ingest | killed | killed | performance-challenge-test | No |
| top-users-module | global-user-totals | survived | survived | — | No |
| top-users-module | query-time-top-n | survived | survived | — | No |

The seven newly killed mutants fail tests added in PR #11: ad-click window
work, hotel reserve/cancel work, payment journal paging, stock-exchange depth
and trade paging, ticketing command work, URL-shortener retry, and time-series
multi-year range reads. The remaining 12 survivors still identify uncovered
designs; a surviving mutant is not evidence that its design is desirable.

## Baseline (2026-09-30, graders as on `origin/master` 4bed04d)

**References: 16/16 pass. Mutants: 6/25 killed (24%), 19 survived, 0 broken
(no compile or JVM crashes).** Baseline receipt: `before-run` in the JSON.

Every survivor passed the challenge's complete private suite, functional tests
included. So each survivor compiles and keeps the reference's
functionally-tested behaviour. Only the design differs, which makes it a clean
NFR gap. Killed mutants were not separately checked for functional
preservation. `chat-app/durable-heartbeats` is not functional-preserving by
design (see its manifest).

| Challenge | Mutant | Result | Killed by / wrong design (one line) |
|---|---|---|---|
| bank-transfer-module | stream-topology | **killed** | performance test (topology type, read bounds). Transfers run in a stream topology instead of microbatch. |
| bank-transfer-module | unsubindexed-history | **killed** | performance test. Transfer histories are plain maps, loaded whole per read. |
| chat-app | durable-heartbeats | **killed** | "heartbeats perform no durable writes" and fault tolerance. Heartbeats go to a depot and a durable last-beat PState. |
| chat-app | per-member-post-writes | **killed** | "a post to a 300-member room writes a bounded number of records". Every post is fanned out into a per-member inbox. |
| hld-search-autocomplete | full-prefix-scan | **killed** | "suggest work grew with unrelated state". Suggest reads every candidate of the prefix, then sorts and takes k. |
| time-series-module-hard | stream-ingest | **killed** | "microbatch topology required". Ingest runs in a stream topology. |
| collaborative-document-editor | non-subindexed-history | survived | The `$$edits` log is a plain vector, loaded and rewritten whole. |
| collaborative-document-editor | recompute-doc-on-read | survived | No `$$docs`; every read replays the whole edit log. |
| hld-ad-click-aggregation | global-placement | survived | `$$campaigns` is `:global?`, so all campaigns sit on one task. |
| hld-ad-click-aggregation | read-time-aggregation | survived | Raw rows per window, summed at query time. |
| hld-feature-flag-service | env-scan | survived | A flag read scans every flag in its (tenant, env). |
| hld-feature-flag-service | global-pstate | survived | `$$flags` is `:global?`, so all flags sit on one task. |
| hld-file-sync | version-history-vector | survived | A file's version history is one non-subindexed value. |
| hld-hotel-reservation | cancel-scans-bookings | survived | `cancel-booking!` scans all bookings to recompute availability. |
| hld-metrics-pipeline | lazy-retention | survived | Nothing is ever expired; queries filter by clock. |
| hld-metrics-pipeline | series-blob | survived | The 60s bucket map is non-subindexed, rewritten whole per sample. |
| hld-payment-system | journal-sorted-map-blob | survived | The tenant journal is one non-subindexed sorted-map. |
| hld-rate-limiter | stream-client-dedup | survived | Stream topology, so a retry can double-debit. |
| hld-search-autocomplete | locale-placement | survived | Partitioned by locale only (a hot partition per locale). |
| hld-stock-exchange | book-as-one-value | survived | Each book side is one non-subindexed value per symbol. |
| hld-ticketing-system | hold-scans-event-holds | survived | HoldSeats scans every hold of the event. |
| hld-url-shortener | stream-client-dedup | survived | Stream topology with client-side click dedup. |
| time-series-module-hard | no-thirty-day-rollup | survived | No 30-day rollup, so multi-year ranges read per-day buckets. |
| top-users-module | global-user-totals | survived | User totals in one `:global?` PState. |
| top-users-module | query-time-top-n | survived | No maintained top-500; every read scans and sorts all users. |

At the baseline, every survivor matched a gap named in the NFR audit
(`origin/wip/atlas-onepagers:docs/nfr-audit.md`). The audit either names the
design as passing, or says the relevant cost, balance, or retry behaviour is
untested (top-users: no NFR tests at all). **Caveat:** the manifests'
`:expected :survives` values were set *after* the run, by checking each
survivor against the audit. They are not blind predictions. The exceptions are
`time-series/no-thirty-day-rollup`, `hotel/cancel-scans-bookings`,
`file-sync/version-history-vector` and `rate-limiter/stream-client-dedup`,
which were marked `:survives` from the audit before they ran. The six killed
mutants were all `:expected :killed` beforehand. The manifests retain these
baseline expectations, so `as-expected?` is false for the seven newly killed
mutants in the after-run receipt; this is not a grader failure.

### Positive controls (gold-standard graders)

- `chat-app/per-member-post-writes` and `chat-app/durable-heartbeats`, both
  killed by the assertions written for exactly those designs. The first
  version of `per-member-post-writes` fanned out with
  `(local-select> [(keypath *room-id) ALL] $$room-members :> *member)`
  followed by `|hash`. A probe showed it wrote to only **1 of 300** members,
  so it survived for a reason unrelated to the grader. Fixed to
  `sorted-set-range-from-start` + `ops/explode`: the probe then showed 300/300
  inbox entries and 603 counted writes, and the grader killed it.
  **Follow-up:** the reference uses the same `ALL` + `|hash` pattern for
  thread-participant fanout. Check whether it also under-emits for large
  subindexed sets.
- `time-series-module-hard/stream-ingest` and
  `bank-transfer-module/stream-topology`, both killed by topology-type checks.

### Provenance and how the runs were made

All runs were sequential, one grader JVM at a time (`--jobs 1`, one process).
The after comparison is one full run on the rebased branch, from approximately
22:53–23:56 UTC on 2026-10-03. All 16 references passed before the mutants
for their respective challenges were graded. The baseline merges three runs:

1. Full run, 03:33–04:25 UTC. All 16 challenges; the script as at 8286be6
   (before the lock). `hotel/cancel-scans-bookings`,
   `file-sync/version-history-vector` and `rate-limiter/stream-client-dedup`
   were written while this run was in progress, before it reached those
   challenges. The report's `git-rev` (6bf5e71) therefore predates them; they
   are committed together with this doc.
2. chat-app rerun, 04:32: reference pass (129s), `durable-heartbeats` killed.
3. `per-member-post-writes` regraded after the fanout fix, 04:45:
   `--skip-reference`, killed.

An earlier attempt (2026-09-29) ran 4 grader processes concurrently on a
4-CPU host. It produced timeouts and a spurious top-users-module reference
failure, which did not reproduce in isolation: the direct grader gave exit 0,
1 test, 14 assertions, 0 failures, and the task reported a pass (54s). The
task now takes a machine-wide lock (`$TMPDIR/mutant-kill-rate.lock`; a second
run exits 3) and warns on `--jobs > 1`.

## How to run

```bash
bb scripts/mutant_kill_rate_test.bb                # task unit tests
bb mutant-kill-rate --jobs 1 --timeout-s 900       # all challenges; overwrites JSON with a single-run receipt
bb mutant-kill-rate -c hld-payment-system          # one challenge
```

The runner writes only a single-run receipt. This document's joined JSON
preserves the committed baseline and after run; save it before rerunning.

Classification rules (in `scripts/mutant_kill_rate.bb`):

- `pass` means the grader accepted the mutant, so it **survived**.
- `fail` (some test FAIL/ERROR) or `timeout` counts as **killed**. A timeout
  is only meaningful with `--jobs 1` on an idle host.
- `crash` (JVM/compile died and no test reported) counts as **broken**. It is
  excluded from the rate, so compile errors are never counted as kills.
- Invalid mutants are skipped and listed, never counted. Invalid means a
  missing or invalid manifest, or source byte-identical to the reference.
- If a reference fails, the task exits 2. A failing reference's output tail is
  kept in the JSON.
- Source-backed challenges (`test-resources/upstream`) get their resource
  roots from the `:test-private-harness` alias. Before this fix, the
  collaborative-document-editor reference crashed.

## Solver protection (verified by code reading)

Mutants live only under `challenges/<name>/test-private/mutants/<id>/`.

- Isolated runs: `scripts/isolate_solver.py` `snapshot` copies an allowlist.
  From the challenge it copies only `README.md`, `deps.edn`, `src`, and
  `.clj-kondo`, so `test-private/` never reaches the solver.
- Non-isolated runs: `scripts/encrypt_challenges.bb` `sensitive-dirs`
  includes `test-private`, and `source-files` globs `**` under it, so
  manifests and mutant sources are encrypted with the private suite.
- Grading: `-X:test-private` puts `test-private` on the classpath as a root.
  Mutant namespaces then sit at `mutants/<id>/src/...` and cannot resolve as
  `<ns>.module`, so they never shadow a solver's module.
- `run_challenges.bb` `find-functional-test-files` keeps only filenames
  containing `functional`, so no mutant file matches.
- Residual risk: `docs/` (this file and the JSON) is not protected. It names
  the grader gaps. Isolated snapshots exclude `docs/`, but a non-isolated
  solver could read it.

## Remaining gaps

- Audited challenges with no mutants: auction-module, fanout,
  social-graph-and-fanout, who-to-follow, timed-notifications,
  unbalanced-social-graph, content-moderation, family-tree,
  music-catalog-migration, profile-module, rest-api-integration-module,
  hld-job-scheduler, hld-notification-system, hld-web-crawler,
  hld-enterprise-rag.
- Killed mutants were not checked separately for functional preservation (a
  kill could partly come from a functional test). At baseline, all six were
  killed by performance/write-volume tests. Additional
  fault-tolerance failures were also recorded for `durable-heartbeats`.
- `hld-url-shortener/stream-client-dedup` and
  `hld-ticketing-system/hold-scans-event-holds` were flagged in review as
  possibly not functional-preserving. Both passed the baseline functional
  suite, so any drift was untested rather than absent at baseline.
- The positive control mutants run on a single random task count (2 or 4)
  per run, as the harness chooses.

## Session ledger

| When (UTC) | Model (evidence) | Permission mode (evidence) | Notes |
|---|---|---|---|
| 2026-09-29 06:25–07:15 | claude-opus-5-5 (transcript `"model":"claude-opus-5-5"`; process `claude --model claude-opus-5-5`) | default, then bypassPermissions (by the operator), then acceptEdits | bb/clojure/git were blocked at first; the partial concurrent run was invalid |
| 2026-09-30 03:30– | claude-opus-5-5 (process `claude --model claude-opus-5-5 --permission-mode auto`; debug log `[auto-mode] verifyAutoModeGateAccess: enabledState=enabled ... model=claude-opus-5-5 modelSupported=true`; transcript `"permissionMode":"auto"`) | auto (`~/.claude/settings.json` = `{"permissions":{"defaultMode":"auto"}}`) | Baseline runs; `git push` worked under auto |
| 2026-10-03 22:53–23:56 | No Claude CLI/model call; `bb mutant-kill-rate --jobs 1 --timeout-s 900` invoked graders directly from this Amp thread | Not applicable to grader JVMs | After run on `78b9872`; 16/16 references passed |

One manifest-drafting subagent ran on 2026-09-29 (alias `opus`; its transcript
records `claude-opus-5-5`). Its 12 manifests were reviewed and the outcomes
re-derived from baseline runs in that session.
