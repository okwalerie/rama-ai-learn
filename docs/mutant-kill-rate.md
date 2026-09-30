# Mutant kill rate — baseline

`bb mutant-kill-rate` runs each challenge's private grader (`test-private/`)
against the reference module (must pass) and against each mutant (should
fail). A mutant is a copy of the reference with one wrong design, usually an
NFR violation. A mutant that survives points to a grader gap.

## Baseline (2026-09-30, graders as on `origin/master` 4bed04d)

**References: 16/16 pass. Mutants: 6/25 killed (24%), 19 survived, 0 broken
(no compile or JVM crashes).** Machine-readable: `docs/mutant-kill-rate.json`.

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

Every survivor matches a gap named in the NFR audit
(`origin/wip/atlas-onepagers:docs/nfr-audit.md`). The audit either names the
design as passing, or says the relevant cost, balance, or retry behaviour is
untested (top-users: no NFR tests at all). **Caveat:** the manifests'
`:expected :survives` values were set *after* the run, by checking each
survivor against the audit. They are not blind predictions. The exceptions are
`time-series/no-thirty-day-rollup`, `hotel/cancel-scans-bookings`,
`file-sync/version-history-vector` and `rate-limiter/stream-client-dedup`,
which were marked `:survives` from the audit before they ran. The six killed
mutants were all `:expected :killed` beforehand.

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
The baseline merges three runs:

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

## How to run (and rerun after `wip/nfr-tests` merges)

```bash
bb scripts/mutant_kill_rate_test.bb                # task unit tests
bb mutant-kill-rate --jobs 1 --timeout-s 900       # all challenges, ~55 min
bb mutant-kill-rate -c hld-payment-system          # one challenge
```

To measure the NFR tests, make a scratch merge
(`git switch -c scratch/mkr-nfr && git merge origin/wip/nfr-tests`), rerun,
and diff the resulting JSON against `docs/mutant-kill-rate.json`. Mutants are
independent of the graders, so the merge needs no mutant changes. The
survivors above are the audit's gaps, so each one the NFR tests close should
flip to killed; update its manifest `:expected` to `:killed`. Not done yet.

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

- The scratch merge with `wip/nfr-tests` and a rerun have not been done.
- Audited challenges with no mutants: auction-module, fanout,
  social-graph-and-fanout, who-to-follow, timed-notifications,
  unbalanced-social-graph, content-moderation, family-tree,
  music-catalog-migration, profile-module, rest-api-integration-module,
  hld-job-scheduler, hld-notification-system, hld-web-crawler,
  hld-enterprise-rag.
- Killed mutants were not checked separately for functional preservation (a
  kill could partly come from a functional test). Per the killing-test labels,
  all six were killed by performance/write-volume tests. Additional
  fault-tolerance failures were also recorded for `durable-heartbeats`.
- `hld-url-shortener/stream-client-dedup` and
  `hld-ticketing-system/hold-scans-event-holds` were flagged in review as
  possibly not functional-preserving. Both passed the full functional suite,
  so any drift is untested rather than absent.
- The positive control mutants run on a single random task count (2 or 4)
  per run, as the harness chooses.

## Session ledger

| When (UTC) | Model (evidence) | Permission mode (evidence) | Notes |
|---|---|---|---|
| 2026-09-29 06:25–07:15 | claude-opus-5-5 (transcript `"model":"claude-opus-5-5"`; process `claude --model claude-opus-5-5`) | default, then bypassPermissions (by the operator), then acceptEdits | bb/clojure/git were blocked at first; the partial concurrent run was invalid |
| 2026-09-30 03:30– | claude-opus-5-5 (process `claude --model claude-opus-5-5 --permission-mode auto`; debug log `[auto-mode] verifyAutoModeGateAccess: enabledState=enabled ... model=claude-opus-5-5 modelSupported=true`; transcript `"permissionMode":"auto"`) | auto (`~/.claude/settings.json` = `{"permissions":{"defaultMode":"auto"}}`) | All runs in this doc; `git push` worked under auto |

One manifest-drafting subagent ran on 2026-09-29 (alias `opus`; its transcript
records `claude-opus-5-5`). Its 12 manifests were reviewed and the outcomes
re-derived from runs in this session. No other model was used.
