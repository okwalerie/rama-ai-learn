# Implementation Validation

<!-- Phase 4 artifact for hld-metrics-pipeline (build phase). Reviewed source:
test-resources/hld_metrics_pipeline/module.clj. Authority: README.md + protocol
docstrings + PLAN.md. Default verdict was major-fail until each check was traced. -->

Line numbers refer to `test-resources/hld_metrics_pipeline/module.clj` at the time
of review.

## Redundant conditionals
- `<<subsource` (module, ETL body): two cases do different work (clock advance + expiry
  vs. admission + folds). Not redundant. PASS.
- `<<cond` in ingest: branches bind `*outcome` to different keywords and only the
  default branch does the duplicate lookup and the writes — required by rule order
  (rule 2 must fire before the point lookup). Not collapsible. PASS.
- `<<if (< *lo *hi)` in `query-raw` and the `(>= *lo *hi)` case in `query-rollup`:
  the branches differ (one skips the second read entirely). PASS.
- `query-rollup` width branches: different PState fields (`:buckets-60` range scan vs
  `:buckets-3600` whole small map + in-memory filter) — dictated by the plan's schema.
  PASS.

## Consecutive keypath
- Every path uses a single multi-arity `keypath`: `(keypath *series :clock)`,
  `(keypath *series :raw *ts)`, `(keypath *series :buckets-60 *s)`,
  `(keypath *series :buckets-3600)`, `(keypath *series *outcome)`. No consecutive
  keypath forms. PASS.

## Select-compute-transform
- Counters: `[(keypath *series *outcome) (nil->val 0) (term inc)]` — no prior select.
  PASS.
- 60-bucket fold: `(+compound $$series {*series {:buckets-60 {*b60 (+fold-bucket *value)}}})`
  — aggregator, one RMW of one subindexed entry. PASS.
- 3600-bucket fold: `local-select>` of the whole ≤3-entry map, `fold-into-map`, then
  write-only `termval`. This is select-compute-transform by shape, but PLAN.md
  ("`ingest-sample!` processing", "PState Design") mandates exactly this because the
  map is one small stored value: an aggregator on a nested key of a non-subindexed map
  would read+write the same whole value, so I/O is identical (1 read + 1 write). PASS
  (plan-mandated, same I/O).
- 3600-bucket expiry: same read + `termval` of the pruned map, plan-mandated. PASS.
- Raw store: `[(keypath *series :raw *timestamp) (termval *value)]` — write-only, after
  the duplicate lookup that the spec requires anyway. PASS.

## Unnecessary nil->val
- `(nil->val 0)` before comparisons of `*clock`/`*cur` (`>`/`<=` cannot take nil): needed.
- `(nil->val 0)` before `(term inc)`: needed.
- No `nil->val` on `:raw`, `:buckets-60` (range navigators treat nil as empty), nor on
  `:buckets-3600` (`prune-buckets`/`fold-into-map`/`bucket-rows-in` handle nil: `into {}`
  on `(filter ... nil)` → `{}`; `(or m {})`; `filter` over nil → empty). PASS.

## :allow-yield?
- `query-raw` range read: up to 300 entries → `{:allow-yield? true}` present. PASS.
- `query-rollup` 60 range read: up to ~120 entries → `{:allow-yield? true}` present. PASS.
- ETL expiry reads (`sorted-map-range-to ... (subselect MAP-KEYS)`): up to 300 / 121
  entries. Deliberately NOT yielding: yielding gives up ordering, and per-series order
  between `advance-clock!` and the next `ingest-sample!` is a spec requirement (PLAN.md
  "No yielding anywhere: bounded work ... and per-series ordering must be preserved").
  The work is bounded by the windows (≤ 300 + 121 entries), i.e. ~1 seek + a few hundred
  iterator steps ≈ 2–3 ms worst case, not a repeated large scan. PASS (justified).
- Point reads (`:clock`, `:raw *timestamp`, `:buckets-3600`): single values. PASS.

## Non-subindexed collections without size limits
- `:buckets-3600` (plain `map-schema`): bounded to ≤ 3 live entries by the application:
  admission only accepts `ts > clock - 300` (so a new bucket start satisfies
  `start > clock - 3900`), and every `advance-clock!` prunes `start < clock - 10799` in
  the same event (`prune-buckets` cutoff `(- *clock 3600 (dec ROLLUP-RETENTION))`). Live
  starts therefore lie in `(clock - 10800, clock]`, at most 3 multiples of 3600. Traced:
  clock 99000 in the efficiency test: starts 90000, 93600, 97200 present (3), 86400 pruned
  when the clock passed 97200. PASS.
- `:raw` and `:buckets-60` are subindexed (`{:subindex-options {:track-size? false}}`).
  PASS.
- The per-series fixed-keys record itself is not a collection. PASS.

## Stream topology idempotency
- No stream topology. All writes happen in the microbatch topology `metrics`; retries
  reset and reapply the attempt exactly once. Counters (`term inc`), folds and deletes
  are therefore exactly-once. PASS.

## Partial failure in stream topologies
- No stream topology; the ETL has no partitioner after the source, so every record's
  writes are one atomic segment on one task within one microbatch attempt. PASS.

## Single depot append per client operation
- `advance-clock!` → one `foreign-append!` (`append!` helper); `ingest-sample!` → one.
  Reads do no appends. PASS.

## Application-state caches survive restart
- No TaskGlobals, no caches. The only client-side memory is `append-counts`, a
  synchronization counter never consulted by any read (README explicitly allows
  "transient synchronization counters"). Verified at runtime: after
  `rtest/update-module!` (simulated restart) all reads returned the same values and the
  processed-count wait still worked (functional test "durable state survives a simulated
  worker restart"). PASS.

## No reimplementation of built-in operations
- `bucket-start`, `raw-window`, `rollup-window`, `fold-value-into-bucket`,
  `prune-buckets`, `raw-rows`, `bucket-rows`, `bucket-rows-in`: domain arithmetic and
  row shaping — no Rama built-in provides these. `ops/explode` is used for iteration
  rather than a hand-rolled loop. `+fold-bucket` uses `accumulator` (a single custom
  4-field fold; `aggs/+count`/`+sum`/`+min`/`+max` as four separate compound leaves
  would be four navigations into the same entry instead of one). PASS.

## Plan conformance
Compared field by field with PLAN.md:
- Series identity: `SeriesKey` record with sorted label map — matches.
- Depot `*series-events` `(hash-by :series)`, records `AdvanceClock`/`IngestSample` —
  matches.
- PState `$$series` schema: identical to the plan's schema (`:raw`, `:buckets-60`
  subindexed with size tracking off; `:buckets-3600` plain map; scalar counters) —
  matches.
- ETL: `<<subsource` dispatch; advance = clock read, `<<if`, `termval` clock, three
  `<<branch`es (raw range delete, 60 range delete, 3600 prune) — matches the plan's
  sketch exactly (anchor `<advanced>`). Ingest = clock read, `<<cond` in rule order,
  point lookup for duplicates, write-only raw store, `+compound` 60 fold, read+`termval`
  3600 fold, single counter increment — matches.
- Query topologies: leading `(|hash *series)`, clock read, window arithmetic in plain
  defns, conditional second read, `(|origin)` — matches.
- Client: `foreign-select-one` + `submap` for info; query topologies for the two range
  reads; `:append-ack` appends; shared counter registry keyed by IPC, created per
  `create-module` result (see FULL_SPEC_REVIEW.md) — matches.
- One deviation from the plan's *sketch* (not its design): `get-series-info` merges
  with `reduce-kv` skipping nil values instead of `merge`. Reason: correctness —
  `submap` over a fixed-keys record returns explicit `nil` for fields never written, and
  plain `merge` would override the zero defaults with `nil` (observed at the REPL:
  `{:rejected-expired nil ...}` for a series with only accepted/future outcomes). The
  plan's stated intent ("fields never written ... default to 0 by the same merge") is
  preserved. PASS.

## Additional runtime traces (REPL / suite)
- README worked example steps 1–15 reproduced exactly (see TEST_VALIDATION.md).
- Suite: `clojure -X:test-private-harness` → `Ran 4 tests containing 306 assertions.
  0 failures, 0 errors.`
- Lint: `clj-kondo --lint test-private` → 0 errors, 0 warnings. `clj-kondo` on the
  module file reports only "unresolved symbol" noise for Rama macros (`defmodule`,
  `<<sources`, `local-select>`, dataflow vars) because the repo's `.clj-kondo/config.edn`
  has no Rama hooks; the namespace loads cleanly (`(require 'hld-metrics-pipeline.module)`).

## Verdict

`pass` — every check traced above holds; the only divergence from the plan text is a
correctness fix that preserves the plan's intent.

PHASE_VALIDATION:pass
