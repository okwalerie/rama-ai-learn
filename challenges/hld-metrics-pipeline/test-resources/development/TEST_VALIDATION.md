# Test Validation

<!-- Phase 6 artifact for hld-metrics-pipeline (build phase). Reviewed:
test-private/hld_metrics_pipeline/{functional,efficiency}_test_support.clj and the two
*_challenge_test.clj drivers. Authority: README.md + protocol docstrings; IMPLICIT_SPEC.md
used as the edge-case checklist. Default verdict was major-fail until each check was
walked through. -->

## Minimize IPC launches
- Four launches total, each justified by the spec's requirement that "The module runs
  with both 2 and 4 tasks in private validation": `functional-2-tasks-challenge-test`,
  `functional-4-tasks-challenge-test`, `efficiency-2-tasks-challenge-test`,
  `efficiency-4-tasks-challenge-test`. Each deploys explicitly with
  `{:tasks n :threads 2}` (no random task count).
- Functional and efficiency are separate launches because the efficiency suite's
  measurements depend on a controlled amount of state in the cluster (the "small" vs
  "grown" comparison); the functional suite's ~20 series of writes would be uncontrolled
  background growth. Within each suite all scenarios use disjoint series and share one
  cluster. PASS.
- Wall clock of the whole suite: ~2.5 min (`clojure -X:test-private-harness`).

## Implicit spec coverage
Each IMPLICIT_SPEC.md edge case / matrix row → the `testing` block in
`functional_test_support.clj` (F) or `efficiency_test_support.clj` (E) that exercises it.

Derived facts / boundaries
- `ts = C` accepted, `C + 1` future, `C - 299` accepted, `C - 300` expired → F "admission
  boundaries at clock C" (700/701/1000/1001/699) and F "README worked example".
- Raw gone exactly at `ts + 300 == clock` → F worked example step 11 (advance 1001 drops
  701) with the distinctive message "ts 701 must be gone from query-raw once 701 + 300 <=
  clock"; F "one huge advance" (`BIG-299` survives at `BIG`, gone at `BIG+60`).
- Bucket complete exactly at `start + width == clock` → F worked example (1019 vs 1020);
  F "fresh series at clock 0" (59 vs 60).
- Bucket gone exactly at `start + width + 7200 == clock` → F worked example (7919 vs
  7920 for the 60-bucket; 10799 vs 10800 for the 3600-bucket).
- Query ranges half-open, `start == end` → `[]` → F "rejection never reserves"
  (`raw 500 500`, `raw 501 600`), F "bucket membership" (`rollup 3600 3600`, `3600 3660`).
- Values negative / zero / > 32-bit; first sample sets min=max=value → F "negative, zero,
  and > 32-bit values".
- Large clocks/timestamps (10^12) and huge ranges → F "one huge advance"; every `[0 BIG)`
  query; E "advance-clock! work is proportional to what it expires" (jump to 10^12).
- Rule order: expired re-offer of a previously accepted ts counts as expired, not
  duplicate → F "admission boundaries" (701 re-offered at 1001); counters sum to number
  of ingest calls → same block (`= 6 (+ ...)`).

Global requirements
- Durable state / no client atoms → F "durable state survives a simulated worker restart"
  (`rtest/update-module!` then reads + further writes); F "a wrapper created after the
  writes sees the same state" (fresh `wrap-client` reads 300 samples it never wrote).
- Multiple clients share one truth → every F block alternates: writes via `ca`, sync `ca`,
  reads via `cb`, then the reverse.
- `wait-for-processing!` on a second wrapper must cover its own writes even when the
  cluster already processed more records → F "second-wrapper write barrier" (A writes
  301 records and syncs; new wrapper C writes 201 records, syncs, reads its own 200
  samples; then C's advance expires A's data and A/B observe it).
- Per-series total order across write kinds within one sync phase → F "same-series
  writes take effect in client order" (advance→ingest accepted; ingest→advance future
  and unreserved; stale advance in the middle is a no-op).
- Task-count independence → both suites run at 2 and at 4 tasks with identical
  expectations.
- Reclamation / bounded footprint → not directly observable through the protocol;
  covered indirectly by E (cost of reads does not grow with expired history) — see
  Limitations.
- Never lose data reads depend on: raw expiry never alters buckets → F worked example
  steps 11/13, F "tenant, metric ... isolate series"; bucket expiry never alters
  counters → F worked example (10800), F "one huge advance", E huge advance.

Operation edge cases
- `advance-clock!`: fresh series `0` no-op (F "fresh series at clock 0"); fresh series
  `1000` sets clock only (F worked example first assertion); stale/equal advance no-op
  (F worked example step 15, F "same-series writes"); advance to `ts+300` vs `ts+299`
  (F worked example); one advance open→expired (F "one huge advance"); everything expires,
  info unchanged except clock (F "one huge advance", F worked example 10800); must not
  touch other series (F "tenant ... isolate series": advancing A leaves B, C intact; E
  huge advance leaves `t6/latency` intact).
- `ingest-sample!`: fresh series ts 0 accepted / ts 1 future, rejections on a fresh
  series still show counters with clock 0 (F "fresh series at clock 0", F "tenant ..."
  series D); late sample into a complete bucket (F "late samples land in an
  already-complete bucket": count 1→3, min/max/sum updated, returned immediately);
  re-offer of expired ts → expired (F "admission boundaries"); duplicate with a
  different value keeps the first (F "conflicting duplicate"); bucket membership at
  `start`, `start+width-1`, 3599/3600 (F "bucket membership at width boundaries").
- `get-series-info`: unknown series all zeros with all five keys (F "unknown series");
  only-rejections series (F "tenant ..." D, F "rejection never reserves" T before retry);
  all-expired series keeps counters (F worked example 10800, F "one huge advance");
  advance-only series (F worked example first assertion).
- `query-raw`: `start == end`; `end` just above / equal to a timestamp (F "rejection never
  reserves"); range straddling the retention boundary (F worked example step 11, F
  "second-wrapper write barrier" 702 first after advance 1001); future range (F "bucket
  membership" `3701 BIG`); only-rejected timestamps (F "rejection never reserves" before
  retry; F "tenant ..." D); unknown series (F "unknown series").
- `query-rollup`: `start == end`; bucket end == clock complete, end == clock+1 not (F
  worked example 1019/1020); `start + width + 7200 == clock` gone (F worked example 7920,
  10800); 3600-bucket outlives its 60-buckets (F worked example: at 7920 the 60-bucket
  660 is gone while 3600-bucket 0 remains until 10800); bucket populated only by expired
  raw samples (F worked example step 13); single-sample bucket (many); range beginning
  before the window and ending after the clock (F "bucket membership" `0 BIG`, E rollup
  ranges); empty slots never returned (F "one huge advance" `999999999720 BIG`);
  incomplete non-empty bucket never returned (F "late samples" 3600-bucket 0 at 1100).
- `wait-for-processing!`: no prior writes (F unknown-series block reads through `cb`
  before `cb` ever wrote — `cb` did sync in the worked example, and `cc` in the barrier
  block reads before syncing); second wrapper after another wrote and waited (barrier
  block). Synchronizable is asserted with `satisfies?`.

Entity × write matrix: every row of the Series / Raw sample / Rollup bucket matrices
maps to at least one assertion above (never-written × each write → F "unknown series",
"fresh series at clock 0", "tenant ..." D; written × advance ≤/> clock → worked
example; written × ingest future/expired/duplicate/accepted → "admission boundaries",
"conflicting duplicate"; raw sample retained/expired transitions → worked example 11,
"admission boundaries"; bucket empty/open/complete/expired transitions → worked example
12–14, "late samples", "one huge advance").

Efficiency contract (README) → E:
- `query-raw` / `query-rollup` (both widths) / `get-series-info` cost compared between a
  small state and a grown state where only irrelevant data was added: 106 out-of-range
  retained raw samples on the same series, 100 out-of-range complete buckets on the
  same series (built before the baseline by walking the clock), and 23 other series
  across 6 tenants × 2 metrics × 2 label sets with 51 samples each. Relevant results are
  asserted equal before and after growth (outside the captures), and expectations are
  computed by spec-derived helpers (`expected-raw`, `expected-rollup`) independent of
  the module.
- Bound: `after <= 2 * before + 20` RocksDB read-side ops (reads + iterator seeks +
  iterator steps) — generous for any layout, but a scan of the series' whole raw map
  (+106), whole bucket map (+100), or other series (+1000s) fails it. No exact counts,
  no topology type or PState naming is assumed; only the event kinds are counted.
- `query-rollup` (60): additionally `< 50` ops with 100 out-of-range buckets present on
  the series (a whole-map scan costs ≥ 100 iterator steps).
- `ingest-sample!` and no-expiry `advance-clock!`: same growth comparison.
- `advance-clock!` that expires ~120 samples and ~105 buckets after a 10^12 jump:
  `< 8 × (data expired + 110)` ops — proportional to data, independent of the numeric
  distance.
- Actual reference numbers (4 tasks): raw 14→14, rollup60 7→8, rollup3600 2→2, info
  1→1, ingest 6→6, advance 7→7; huge advance 237 ops.

## Synchronization
Walked every write→read sequence in both files: each `advance*`/`ingest*` group is
followed by `(sync! <the writing wrapper>)` before any `info*`/`raw*`/`rollup*`. The only
reads without an immediately preceding sync are reads of state already synced by
another wrapper (e.g. `cc` reading X right after A synced; `cb` reading in the
"unknown series" block), which is exactly the multi-client guarantee under test. In E,
`(sync! c)` follows every write group, including inside the ingest/advance captures.
PASS.

## Test namespaces compile
- `(require 'hld-metrics-pipeline.functional-test-support 'hld-metrics-pipeline.efficiency-test-support 'hld-metrics-pipeline.functional-challenge-test 'hld-metrics-pipeline.efficiency-challenge-test)` → `nil` at the REPL.
- `clj-kondo --lint test-private` → `errors: 0, warnings: 0`.
- Tests depend only on the public protocol namespace, the harness, and
  `com.rpl.rama.test`; the implementation is resolved by `requiring-resolve` of
  `hld-metrics-pipeline.module/create-module`, so the same tests run against agent
  implementations (`:test-private`) and the reference (`:test-private-harness`). PASS.

## Mutation check (deliberately wrong variant)
Variant: replaced `(dec RAW-RETENTION)` with `RAW-RETENTION` in both the physical
expiry cutoff and the query clamp (models the consistent misreading "retained while
`ts + 300 >= clock`"). Suite result:

```
FAIL in (functional-4-tasks-challenge-test) (functional_test_support.clj:86)
README worked example, verbatim (4 tasks)
ts 701 must be gone from query-raw once 701 + 300 <= clock
expected: (= [(row 1000 5)] (raw* ca S 600 1100))
  actual: (not (= [{:timestamp 1000, :value 5}] [{:timestamp 701, :value 3} {:timestamp 1000, :value 5}]))
...
Ran 4 tests containing 306 assertions.
10 failures, 0 errors.
```

(5 distinct assertions × 2 task counts.) A one-sided variant (clamp only) did NOT fail:
the reference deletes expired entries eagerly in the same event, so the clamp bug is
masked. Original restored (`diff` against the saved copy: identical) and the suite
re-run green.

## Actual suite output (reference, restored original)

```
Testing hld-metrics-pipeline.efficiency-challenge-test
Testing hld-metrics-pipeline.functional-challenge-test
Ran 4 tests containing 306 assertions.
0 failures, 0 errors.
```

## Limitations
- RocksDB events carry no payload sizes, so a design that stores a series (or a tenant)
  as one non-subindexed blob and filters in memory shows a constant read count and is
  not caught by the efficiency suite; only scan growth visible as reads / iterator
  steps is caught.
- Physical reclamation (bounded storage) is not observable through the protocol or the
  events; lazy designs that clamp queries correctly pass.
- Microbatch retry / exactly-once cannot be forced from tests; retry safety is argued in
  IMPLEMENTATION_VALIDATION.md.
- Cross-client concurrent interleavings are not tested (the README requires none).

## Verdict

`pass` — every protocol method, every IMPLICIT_SPEC edge case and matrix row, the
efficiency contract, multi-client visibility, explicit 2- and 4-task deployment, and
the restart scenario are exercised with independently derived expectations.

PHASE_VALIDATION:pass
