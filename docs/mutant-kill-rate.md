# Mutant kill rate — baseline

`bb mutant-kill-rate` runs each challenge's private grader (`test-private/`)
against the reference module (must pass) and against each mutant (should
fail). A mutant is a copy of the reference with one wrong design, usually an
NFR violation. A mutant that survives points to a grader gap.

## Status: inventory only, NOT EXECUTED

The kill rate has **not** been measured yet. In the session that wrote this
file (2026-09-29, branch `wip/mutant-kill-rate`), the permission mode blocked
every `bb` and `clojure` invocation, so:

- no reference was run against its private suite;
- no mutant was compiled or run (compile status and functional preservation
  are unverified for every mutant);
- `bb scripts/mutant_kill_rate_test.bb` (unit tests for the task) was not run
  after this session's edits.

Every killed/survived figure below is therefore **TBD**. Do not quote a kill
rate from this file until the "Results" section is filled from a real run.

## Partial run, 2026-09-29 (commit 78b293a; scripts unchanged since except for reference output-tail capture)

Four `bb mutant-kill-rate` processes ran concurrently on a 4-CPU/7 GB host
with `--timeout-s 360`. The session was killed before any process finished,
so no JSON report was written. From the logs:

| Run | Outcome | Valid? |
|---|---|---|
| collaborative-document-editor reference | **pass** (96s) | Yes. Confirms the `test-resources/upstream` classpath fix; the run before the fix crashed in 36s. |
| top-users-module reference | **fail** (60s), twice (runs at 06:47 and 06:53) | Yes, but cause unknown. Output tail not captured (the process was killed before the JSON was written). **Investigate before trusting any top-users result.** |
| chat-app reference | timeout (518s) | No: CPU contention (4 concurrent JVMs) |
| hld-stock-exchange reference | timeout (536s) | No: CPU contention |
| collaborative-document-editor/non-subindexed-history | timeout (518s) | No: contention. Would count as killed; do not trust. |
| top-users-module/global-user-totals | timeout (505s) | No: contention, and the reference fails anyway |

Rerun with at most 1–2 concurrent processes and `--timeout-s 900`.

## How to run (and rerun after `wip/nfr-tests` merges)

```bash
bb scripts/mutant_kill_rate_test.bb            # task unit tests
bb mutant-kill-rate --jobs 2 --timeout-s 900   # all challenges with mutants
bb mutant-kill-rate -c hld-payment-system      # one challenge
```

Output: a Markdown summary and per-mutant table on stdout, plus
`docs/mutant-kill-rate.json`. To measure the effect of the NFR tests, run once
on this branch, then once on a scratch merge
(`git switch -c scratch/mkr-nfr && git merge origin/wip/nfr-tests`), and diff
the two JSON files. Mutants and graders are independent, so the merge needs no
changes to the mutants.

Classification rules (in `scripts/mutant_kill_rate.bb`):

- `pass` means the grader accepted the mutant, so it **survived**.
- `fail` (some test FAIL/ERROR) or `timeout` counts as **killed**.
- `crash` (JVM/compile died and no test reported) counts as **broken**. It is
  excluded from the rate, so compile errors are never counted as kills.
- Invalid mutants are skipped and listed separately, never counted. Invalid
  means a missing or invalid `manifest.edn`, or source byte-identical to the
  reference.
- If a reference fails its own suite, the task exits 2, because kill rates for
  that challenge would be meaningless.

## Solver protection (verified by code reading, not by a run)

Mutants live only under `challenges/<name>/test-private/mutants/<id>/`.

- Isolated runs: `scripts/isolate_solver.py` `snapshot` copies an allowlist.
  From the challenge it copies only `README.md`, `deps.edn`, `src`, and
  `.clj-kondo`. `test-private/` (and so every mutant) is never copied.
- Non-isolated runs: `scripts/encrypt_challenges.bb` `sensitive-dirs`
  includes `test-private`. `source-files` globs `**` under it, so manifests
  and mutant sources are encrypted with the rest of the private suite.
- Grading: `-X:test-private` puts `test-private` on the classpath as a root.
  Mutant namespaces then sit at `mutants/<id>/src/...`, which cannot resolve
  as `<ns>.module`. The test-runner only loads `*-test` namespaces, so
  mutants never shadow a solver's module during grading.
- `run_challenges.bb` `find-functional-test-files` globs `test-private/**`
  but keeps only filenames containing `functional`, so no mutant file matches.
- Residual risk: **this file (`docs/`) is not protected.** It names the wrong
  designs, and eventually which of them survive. Isolated snapshots exclude
  `docs/`, but a non-isolated solver could read it.

## Inventory (31 mutant directories in WIP commit 935c229)

### Placeholders: byte-identical to the reference in 935c229 (7). Invalid, not counted.

Update (78b293a): real mutations were written for three of them:
`time-series-module-hard/stream-ingest` (positive control; the audit documents a
`#{:microbatch}` check), `time-series-module-hard/no-thirty-day-rollup`
(`:expected :survives`, an audit-predicted grader gap), and
`chat-app/per-member-post-writes` (positive control; gold-standard grader asserts
fewer than 40 writes per post to a 300-member room). None has been run yet.
The remaining four are still placeholders.

These were committed as mutants but contain no mutation. The task now rejects
them with `no mutation: source identical to reference`. Each one still needs
a real mutation written.

| Challenge | Mutant dir | Intended wrong design (from dir name) |
|---|---|---|
| chat-app | durable-heartbeats | Presence heartbeats written to a durable depot/PState instead of ephemeral state |
| chat-app | per-member-post-writes | Each post written once per channel member instead of once per channel |
| hld-file-sync | version-history-vector | Version history kept as one growing vector value instead of subindexed |
| hld-hotel-reservation | cancel-scans-bookings | Cancel scans all bookings instead of a keyed lookup |
| hld-rate-limiter | stream-client-dedup | Client dedup done in a stream topology instead of microbatch |
| time-series-module-hard | no-thirty-day-rollup | 30-day window aggregated from raw points instead of a rollup |
| time-series-module-hard | stream-ingest | Ingest in a stream topology instead of microbatch |

### Real mutations (24)

"Manifest" says whether `manifest.edn` was present in 935c229 or written in
this session from a diff review. For every row, compile status, functional
preservation, and kill status are TBD.

| Challenge | Mutant | Manifest | Wrong design (one line) | Result |
|---|---|---|---|---|
| bank-transfer-module | stream-topology | 935c229 | Deposits/transfers processed in a stream topology instead of microbatch | TBD |
| bank-transfer-module | unsubindexed-history | 935c229 | Transfer histories are plain nested maps (no `:subindex?`), loaded whole on read | TBD |
| hld-payment-system | journal-sorted-map-blob | 935c229 | Tenant journal stored as one non-subindexed sorted-map, read and rewritten whole on every command | TBD |
| hld-stock-exchange | book-as-one-value | 935c229 | Each book side stored as one non-subindexed map per symbol, rewritten whole on every submit/cancel | TBD |
| top-users-module | global-user-totals | 935c229 | Per-user totals in a single `:global?` PState, so every purchase routes to task 0 | TBD |
| top-users-module | query-time-top-n | 935c229 | No top-500 maintained; reads scan and sort every user total | TBD |
| collaborative-document-editor | non-subindexed-history | this session | `$$edits` log is a plain vector, loaded and rewritten whole per edit/read | TBD |
| collaborative-document-editor | recompute-doc-on-read | this session | No `$$docs` PState; every doc+version read replays the whole edit log | TBD |
| hld-ad-click-aggregation | global-placement | this session | `$$campaigns` is `:global?`, so all campaigns sit on one task | TBD |
| hld-ad-click-aggregation | read-time-aggregation | this session | Raw rows kept per window and summed at query time instead of pre-aggregated | TBD |
| hld-feature-flag-service | env-scan | this session | Partitioned by (tenant, env); a flag read scans every flag in the env | TBD |
| hld-feature-flag-service | global-pstate | this session | `$$flags` is `:global?`, so all flags sit on one task | TBD |
| hld-metrics-pipeline | lazy-retention | this session | Clock advance never expires raw/60s/3600s data; queries rely on range filtering | TBD |
| hld-metrics-pipeline | series-blob | this session | 60s bucket map not subindexed, loaded and rewritten whole | TBD |
| hld-search-autocomplete | full-prefix-scan | this session | Suggest reads all candidates for the prefix, then sorts and takes k | TBD |
| hld-search-autocomplete | locale-placement | this session | Partition key is `[locale]` only, so each locale is one hot partition | TBD |
| hld-ticketing-system | hold-scans-event-holds | this session | HoldSeats scans every hold on the event instead of per-seat records (**may change results**) | TBD |
| hld-url-shortener | stream-client-dedup | this session | Stream topology with client-side click dedup (**not functional-preserving**: concurrent duplicates double-count; possible runtime failure) | TBD |

The bank-transfer-module manifests carry `:notes` claiming KILLED results from
an earlier run. That run is not reproduced here, so treat the notes as
unverified.

## Not yet done

- Run the task: reference pass counts, mutant compile/functional checks, and
  kill results.
- Write real mutations for the 7 placeholders.
- Add mutants for the audited challenges that have none (see
  `origin/wip/atlas-onepagers:docs/nfr-audit.md`).
- Add 2–3 gold-standard positive-control mutants on graders known to be
  strong. Each should be a mutant whose kill is certain, e.g. a deliberately
  wrong functional result, to prove the harness can observe a kill end-to-end.
- Scratch-merge `origin/wip/nfr-tests` and rerun.
