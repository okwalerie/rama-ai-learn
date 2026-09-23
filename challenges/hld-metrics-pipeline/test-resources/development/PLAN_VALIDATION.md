# Plan Validation

<!-- Phase 2 artifact for hld-metrics-pipeline, subsystem `metrics-pipeline`.
Authority: README.md + protocol docstrings + harness Synchronizable docstring.
IMPLICIT_SPEC.md used for derived facts only. Every check below was traced with
concrete values; the default verdict was FAIL until a citation showed otherwise.
Reference claims were checked against the skill references (microbatch.md,
paths.md, pstate-schema.md, aggregators.md, dataflow.md, testing.md). -->

## Query topology: `query-raw`
- Input examples present: yes (PLAN.md "Query Topologies", 4 examples)
- Example 1 (clock 1000, 200 samples, `[600,1100)`): N=2, M=2. N == M? yes — clock read is
  required to clamp; the range read returns exactly the result rows.
- Example 2 (`[0,500)`, window empty): N=1, M=1. N == M? yes — second read skipped by `<<if`.
- Example 3 (unknown series, `[0,10^12)`): N=2, M=2. yes — window `[0,1)` is non-empty at
  clock 0 and cannot be known empty without reading (`sorted-map-range` on nil → `{}`,
  paths.md "nil is treated as an empty collection").
- Example 4 (unknown series, `[5,10^12)`): N=1, M=1. yes.
- M values: 2, 1, 2, 1. All same? no.
- Marked variable with dynamic approach? yes — `(<<if (< *lo *hi) ...)` both branches bind
  `*result`. PASS.

## Query topology: `query-rollup`
- Input examples present: yes (3 examples).
- Example 1 (clock 1020, w=60, `[600,1020)` → window `[600,961)`): N=2, M=2. yes.
- Example 2 (clock 1000, w=3600, `[0,3600)` → `hi=-2599`): N=1, M=1. yes.
- Example 3 (clock 10^6, w=60, `[0,10^12)` → `[992801, 999941)`): N=2, M=2; ≤119 iterated
  entries regardless of the 10^12 bound. yes.
- M values: 2, 1, 2. Variable; handled by the same `<<if`. PASS.
- Boundary arithmetic re-derived independently: complete `s + w <= C` ⟺ `s < C - w + 1`;
  retained `s + w + 7200 > C` ⟺ `s >= C - w - 7199`. `sorted-map-range lo hi` is
  `[lo, hi)` (paths.md). Worked-example checks: C=1020,w=60 → hi=961 includes 960 ✓;
  C=7919 → lo=660 keeps 660 ✓; C=7920 → lo=661 drops 660 ✓; C=3600,w=3600,[0,3600) →
  lo=0, hi=1 includes bucket 0 ✓ (step 13). PASS.

## PState schemas
- Grouping by (key type, partitioner): only one group — `$$series` keyed by `SeriesKey`,
  `(hash-by :series)`. The plan already merged clock, counters, raw samples and both
  bucket maps into one fixed-keys record. PASS.
- Any Object type? no. PASS.
- Uniform record-like values use fixed-keys-schema? yes — the series record and the bucket
  aggregate `{:count :sum :min :max}`. PASS.
- Variant-shaped values at one position? none exist (every bucket has the same four
  fields; every series record has the same optional scalar fields). PASS.
- Inner collections > 100 elements subindexed?
  - `:raw`: ≤ 300 live entries, enforced by admission (`ts + 300 <= C` rejected) plus eager
    deletion in `advance-clock!`. Subindexed ✓.
  - 60-width buckets: ≤ 121 live, enforced by admission + eager bucket deletion.
    Subindexed ✓.
  - 3600-width buckets: ≤ 3 live (`C - 10800 < start <= C`, multiples of 3600), enforced
    by admission + eager bucket deletion. The plan subindexes it anyway, **against** the
    skill's "Don't subindex small collections (< 50 elements) — overhead without benefit"
    and against pstate-schema.md "Size Tracking: adds an extra disk read on every write".
    See Throughput section — **FAIL (minor)**, fixed below.
  - Width map (`:buckets` outer map): exactly 2 keys by protocol. Not subindexed ✓ (but
    removed by the fix below anyway).
- Schema syntax checked against pstate-schema.md: `(map-schema Long Long {:subindex? true})`,
  `fixed-keys-schema` inside `map-schema`, subindexed map nested inside a fixed-keys value —
  all documented forms (pstate-schema.md lines 102–125). `defrecord` key type: "Rama
  serializes defrecord types automatically"; "Clojure persistent data structures" are
  serializable, which covers the sorted label map. PASS.

## Partitioning
- Every write: `(hash-by :series)` on the depot; the topology has no partitioner, so the
  write lands on the depot partition = `hash(series) mod N`. Keyspace is large (tenants ×
  metrics × label sets) and per-series load is capped by the windows (≤ 300 accepted
  samples per 300 clock units; ≤ 27 KB live), so no series can be hot or heavy. PASS.
- `|all`: none used. PASS.
- Partitioning efficiency table: filled for N = 1, 16, 128 ✓; four categories including
  typical input, proportions 0.60 + 0.10 + 0.15 + 0.15 = 1.00 ✓; weighted sums computed ✓.
- Seeks/op are totals: every op touches exactly one task, so per-task = total; no fan-out
  reads exist. Recomputed: 0.6·2 + 0.1·2 + 0.15·2 + 0.15·1 = 1.85 at every N — flat, does
  not grow with N. PASS.
- Justifications draw only on spec text (efficiency contract, "must not touch other
  series"). No assumptions about unbuilt parts. PASS.
- Placement schemes with stored state: not needed — the spec's own bounds prove `hash` is
  balanced (no per-key skew possible), so there is no doubt to resolve. PASS.

## Topologies
- Microbatch unless justified? yes — one microbatch topology `metrics`. PASS.
- Writes needing single-digit-ms visibility: none. README: "All `!` methods are
  asynchronous writes. Tests call `wait-for-processing!` ... before any read." PASS.
- Stream topologies: none. PASS.
- Any choice made on test-synchronization grounds? no — microbatch chosen on retry safety
  (non-idempotent counters/folds) and absence of ack/latency requirements. PASS.
- **Per-series order inside one microbatch (traced).** Client appends `AdvanceClock 500`
  then `IngestSample 500 7` for series T; both land on the same depot partition
  (same `hash-by` key) in that order. microbatch.md: "`%mb` emits per task, in depot append
  order". dataflow.md: emit semantics are "downstream runs per emit", and the topology has
  no partitioner after the source, so record 1's `termval` of `:clock` runs before record 2
  is emitted. microbatch.md "Read visibility: reads inside the owning topology see its
  uncommitted PState writes" → record 2 reads clock 500 → accepted. Reverse order:
  ingest at clock 0 → rejected future, nothing stored; advance to 500; a later ingest of
  500 is accepted (nothing reserved). Matches README "Rejection does not reserve a
  timestamp". PASS.

## Production readiness
- Multiple concurrent clients: all writes serialize through one depot partition per series;
  each is judged against state at application time. Reads always come from `$$series`.
  yes. PASS.
- Client process restart: no read depends on client memory; the append counter only gates
  `wait-for-processing!`. A fresh client starts at 0 and, per the registry design, shares
  the process-wide total. yes. PASS.
- Worker restart mid-topology: microbatch attempt is reset and replayed from the committed
  offset; `$$series` is durable. yes. PASS.
- Large scale: series count unbounded but per-series footprint bounded by eager deletion;
  all collections that can exceed 100 entries are subindexed. yes. PASS.
- Non-idempotent writes in stream topologies: none (no stream topology). PASS.
- Multi-partition stream writes: none. PASS.

## Internal depot usage
- None. PASS.

## Cross-topology correctness
- No internal depots, no cross-topology flow. PASS.

## Stream topology correctness
- No `depot-partition-append!`. PASS.

## In-memory state efficiency
- No TaskGlobals, no caches. The only in-memory state is the client-side append counter
  (one long per IPC), which is transient synchronization state explicitly allowed by the
  README and never read by a query. PASS.

## Minimality — adversarial simplification

**Simplest sketch:** one depot hashed by series; one microbatch topology; one PState keyed
by series holding clock, four counters, a subindexed `ts → value` map, a subindexed
60-bucket map and a tiny 3600-bucket map; two query topologies that read clock then one
range; `get-series-info` via `foreign-select-one`. Diff against the plan: the plan is this
sketch plus (a) a width-keyed outer `:buckets` map and (b) subindexing of the 3600 map.
(a) and (b) are addressed under Throughput. Everything else is in the sketch.

### Depot `*series-events`
- **Delete it**: no way to write. Required by "All `!` methods are asynchronous writes."
- **Merge/bypass**: two depots (one per write kind) would lose the required per-series order
  across kinds ("an `advance-clock!` followed by an `ingest-sample!` on the same series is
  judged against the advanced clock"). One depot is minimal. PASS.

### Microbatch topology `metrics`
- **Delete it**: nothing applies writes. **Merge**: it is already the only topology. PASS.

### PState `$$series`
- **Delete it**: no durable state ("All authoritative business state must be durable Rama
  state"). **Merge**: already one PState. PASS.

### Query topology `query-raw`
- **Delete it / bypass with foreign-select**: the read needs the clock before it can clamp
  the range. Two `foreign-select-one` calls = two roundtrips and, worse, the clock could
  advance between them, returning expired samples (violates "Expired samples are never
  returned"). A query topology reads both in one synchronous segment on one task. Cost
  constructed side by side: query topology = 1 roundtrip, 1–2 seeks; foreign selects = 2
  roundtrips, 2 seeks, plus a correctness hole. Keep. PASS.

### Query topology `query-rollup`
- Same construction as `query-raw` (clock then range, atomic on the task). Keep. PASS.

### Client-side shared append counter
- **Delete it**: `wait-for-processing!` cannot compute the count to wait for.
- **Merge/bypass**: a per-client counter (harness docstring) returns early for a second
  client after another client's writes were processed — traced: client A appends 5, waits
  (processed = 5); client B appends 1 and waits for count 1 → returns immediately although
  processed may still be 5 → B's read misses its own write. Violates "Multiple clients
  wrapping the same deployed module must observe the same business state after the writing
  client synchronizes." A single counter shared by all wrappers of the same IPC fixes it
  and is the smallest change. An atom created inside `create-module` and closed over by
  `:wrap-client` would also work for the harness (`with-module` calls `create-module`
  once); the plan's registry keyed by IPC is a superset and equally small. PASS.

### Width-keyed outer `:buckets` map and subindexed 3600 map
- **Delete it (replace with two fixed fields, 3600 map unsubindexed)**: nothing required is
  lost — `query-rollup` on width 3600 reads a ≤ 3-entry map and filters in memory; expiry
  and fold on width 3600 become one read + one write-only `termval`. The only thing lost is
  a uniform code path, which the spec does not require. **FAIL (minor)** — see Throughput;
  fixed in PLAN.md.

## Throughput — adversarial

Dominant write: accepted `ingest-sample!`. Plan (as written) per accepted sample:
clock read (1 seek) + duplicate point lookup (1) + raw `termval` (0 read, but size-tracking
read on the subindexed `:raw` write: +1) + outer `:buckets` field read (1) + 60-bucket RMW
(1, +1 size-tracking) + 3600-bucket RMW (1, +1 size-tracking) + counter RMW (1) = **≈ 10
seeks**.

Constructed alternative: `:raw` and `:buckets-60` as direct fixed-keys fields with
`{:subindex-options {:track-size? false}}` (nothing ever queries `count`); `:buckets-3600`
as a plain non-subindexed `(map-schema Long (fixed-keys-schema ...))` field. Per accepted
sample: clock (1) + dup lookup (1) + raw write (0) + 60-bucket RMW (1) + 3600 map read +
`termval` (1) + counter RMW (1) = **5 seeks**. `query-rollup` width 60: 2 seeks (unchanged);
width 3600: 2 seeks (clock + the ≤ 3-entry map, filtered in memory — same as before minus
the outer map read). `advance-clock!` real advance: clock (1) + raw range (1) + 60 range
(1) + 3600 map read/write (1) = 4 seeks, unchanged count, one fewer field hop.
Storage: identical. Latency: identical or better. Aggregate cost: strictly lower on the
dominant write.

The plan itself quantified option C as "saves ~1 seek per accepted sample" and rejected it
"for uniformity of the range-scan design". SKILL.md: "Never trade I/O efficiency for code
simplicity ... When there is a conflict between simpler code and fewer disk reads ...
always choose fewer I/O operations." → **FAIL (minor)**. Fix: adopt the constructed
alternative (split fields, unsubindexed 3600 map, size tracking off). Applied to PLAN.md.

Reads: `query-raw` = clock + one range scan; no cheaper design exists that still clamps to
the clock (a single read without the clock could return expired rows). `get-series-info` =
one record, constant fields. PASS after fix.

## Spec coverage — trace every operation and constraint

### `advance-clock!` — monotone no-op
- **Source**: "A call with a value less than or equal to the current clock is a no-op."
- **Trace**: series at clock 7920; `advance-clock! S 5000` → `(> 5000 7920)` false → no
  transform, no deletes (PLAN "advance-clock! processing"). Fresh series, `advance-clock! S 0`
  → `(> 0 0)` false → nothing created; `get-series-info` returns merged zeros.
- **Fault tolerance**: worker restart — nothing in memory; retry — re-evaluates the same
  comparison, still no-op; single partition, no partial write.
- **Race**: two advances 1000 and 900 in either order → final clock 1000 (larger wins);
  out-of-order across hops impossible (no hops).
- **Flaws**: none found — the comparison is against the durable clock read in the same event.
- **Verdict**: PASS

### `advance-clock!` — raw expiry boundary
- **Source**: "An accepted sample with timestamp `ts` is retained while `ts + 300 > clock`.
  Once `ts + 300 <= clock` it is gone from `query-raw`."
- **Trace**: samples 701 and 1000 at clock 1000. `advance-clock! 1001`: cutoff
  `1001 - 299 = 702`; `(sorted-map-range-to 702)` is end-exclusive (paths.md) → keys
  `< 702` → {701} deleted; 1000 stays. Step 11 ✓. `advance-clock! 1000` earlier: cutoff 701
  → keys `< 701` → 701 retained ✓ (ts + 299 boundary). Query side: `lo = max(600, 1001-299=702)`
  excludes 701 even before deletion — belt and braces.
- **Fault tolerance**: deletes are `keypath ... NONE>`, idempotent under microbatch replay;
  all on one partition.
- **Race**: none (single task, serialized).
- **Flaws**: none found; both physical deletion and query clamp use the same inequality.
- **Verdict**: PASS

### `advance-clock!` — bucket completeness and retention
- **Source**: "A bucket is complete when `start + width <= clock`. A bucket is retained
  while `start + width + 7200 > clock`."
- **Trace**: bucket 660 (w=60): clock 7919 → cutoff `7919-60-7199 = 660` → keys `< 660` →
  660 survives ✓ (step 14a); clock 7920 → cutoff 661 → 660 deleted ✓ (step 14b). Query at
  7919: `lo = max(600, 660) = 660` includes it; at 7920: `lo = 661` excludes it. Bucket 960
  at clock 1020: `hi = min(1020, 961)` includes 960 ✓ (step 12). One jump 0 → 100000 with
  bucket 0 open: cutoff 92741 → bucket 0 deleted, never returned; `:accepted` untouched ✓.
- **Fault tolerance**: same as raw expiry.
- **Race**: none.
- **Flaws**: none found.
- **Verdict**: PASS

### `advance-clock!` — expiry never alters aggregates or counters
- **Source**: "Raw expiry never subtracts from a bucket." / "Counters never decrease; expiry
  does not touch them."
- **Trace**: the advance branch only writes `:clock` and deletes entries; step 13 (samples
  701, 1000 both raw-expired by clock 3600; bucket 0 of width 3600 returns count 2, sum 8).
- **Flaws**: none found.
- **Verdict**: PASS

### `advance-clock!` — work bound
- **Source**: "`advance-clock!` may do work proportional to the data it expires on that
  series. It must not touch other series."
- **Trace**: clock jump 0 → 10^12 with 3 samples: `sorted-map-range-to` seeks to the map
  start and scans 3 entries; no iteration over the numeric distance. All paths start with
  `(keypath *series ...)`.
- **Flaws**: none found.
- **Verdict**: PASS

### `ingest-sample!` — rule order and counters
- **Source**: the four-row table; "the first that fires decides the outcome"; "Counters
  never decrease".
- **Trace** (worked example, clock 1000): 1000 → not future, `1300 <= 1000` false, lookup
  nil → accepted; 1001 → future; 700 → `1000 <= 1000` expired (no dup lookup even though a
  sample could exist); 701 → accepted; 701 again → lookup finds 3 → duplicate. Counters
  {2,1,1,1} ✓ step 7. Fresh series, `ingest 0 v` → clock 0, `(> 0 0)` false, `(<= 300 0)`
  false, lookup nil → accepted; `ingest 1 v` → future.
- **Fault tolerance**: microbatch replay resets and reapplies → exactly one counter increment
  and one fold per record.
- **Race**: two clients, same ts → first applied wins, second is duplicate (serialized on
  one partition).
- **Flaws**: none found. Expired-before-duplicate ordering holds because the `<<cond`
  checks rule 2 before the point lookup.
- **Verdict**: PASS

### `ingest-sample!` — duplicate check correctness vs. reclaimed entries
- **Source**: "a sample with this timestamp was already accepted on this series → rejected,
  duplicate"; "The first accepted sample for a timestamp is final."
- **Trace**: a timestamp reaching rule 3 satisfies `ts > C - 300`; any raw entry with that
  key is by definition retained (expiry deletes only `ts < C - 299`). Re-offer of an expired
  ts is stopped at rule 2. Same ts, different value → original value retained (no write on
  duplicate).
- **Flaws**: none found.
- **Verdict**: PASS

### `ingest-sample!` — rollup fold
- **Source**: "Every accepted sample is folded into two buckets: the 60-wide bucket starting
  at `timestamp - (timestamp mod 60)` and the 3600-wide bucket ... A bucket carries
  `:count`, `:sum`, `:min`, `:max`."
- **Trace**: ts 701 v 3 → b60 660, b3600 0; first fold → `{:count 1 :sum 3 :min 3 :max 3}`;
  ts 1000 v 5 → b60 960, b3600 0 → bucket 0 `{:count 2 :sum 8 :min 3 :max 5}` ✓ step 13.
  Negative value −4 as first sample → min = max = −4 (nil branch in
  `fold-value-into-bucket`, never 0). Late sample into complete bucket → RMW updates it;
  next query returns new values ✓.
- **Fault tolerance**: microbatch exactly-once.
- **Flaws**: none found. `accumulator` form `(fn [*v] (term ...))` matches aggregators.md;
  read+`termval` fallback has identical I/O.
- **Verdict**: PASS

### `ingest-sample!` — bounded work
- **Source**: "`ingest-sample!` must do bounded work per sample, independent of how many
  samples or series exist."
- **Trace**: 1 clock read, 1 point lookup, 1 write-only raw store, 2 bucket RMWs, 1 counter
  RMW — none scans a collection. After the fix, 5 seeks.
- **Flaws**: none found.
- **Verdict**: PASS

### `get-series-info`
- **Source**: "returns the current clock and cumulative admission counters"; "For a series
  that has never been written, all values are 0."
- **Trace**: unknown series → `keypath` → nil → `submap` → empty → merge → five zeros ✓.
  Series with only `advance-clock! 1000` → `{:clock 1000}` merged → counters 0 ✓. Series with
  only rejections → `{:rejected-future 1}` merged, clock 0 ✓.
- **Fault tolerance**: pure read of durable state.
- **Flaws**: none found. `foreign-select-one` is safe because `submap` yields exactly one
  value.
- **Verdict**: PASS

### `query-raw`
- **Source**: "returns retained samples with `start <= timestamp < end` ... ascending order";
  "Expired samples are never returned."
- **Trace**: step 8 clock 1000, samples {701:3, 1000:5}, `[600,1100)` → lo 701, hi 1001 →
  both rows ascending ✓. Step 11 clock 1001 → lo 702 → only 1000 ✓. `start == end` → lo ≥ hi
  → `[]`. Range in the future (`start > clock`) → lo > hi → `[]`.
- **Fault tolerance**: read of committed state in one synchronous segment; a concurrent
  advance either committed before (clock read reflects it) or after (neither read sees it).
- **Flaws**: none found.
- **Verdict**: PASS

### `query-rollup`
- **Source**: "returns complete, retained, non-empty buckets whose start lies in `[start,
  end)`... ascending order."
- **Trace**: steps 9, 10, 12, 13, 14 all re-derived above under "Query topology:
  query-rollup". Non-empty is automatic (entries exist only via folds). Width 3600 after the
  fix: read the ≤ 3-entry map, keep `lo <= s < hi`, sort — same rows.
- **Flaws**: none found.
- **Verdict**: PASS

### Efficiency contract — read isolation
- **Source**: "Reads of one series must not read state belonging to any other series."
- **Trace**: every path begins `(keypath *series ...)`; no `MAP-VALS`/`ALL` at the top level.
- **Verdict**: PASS

### Series identity — structural equality and hashing
- **Source**: "Two series are the same iff all three parts are equal. `{"host" "a" "zone"
  "b"}` and `{"zone" "b" "host" "a"}` are the same labels."
- **Trace**: both inputs → `(into (sorted-map) labels)` → identical sorted map → identical
  `SeriesKey` value, identical serialized bytes (sorted iteration order), identical hash
  (Clojure map hashing is order-independent; a record's hash is value-based). Depot
  `hash-by`, query `|hash`, and `foreign-select-one` routing all hash the same client-built
  object → same task. `{}` labels → `(sorted-map)` equals `{}`; distinct from `{"a" "b"}`.
  pstate-schema.md lists defrecords and Clojure persistent structures as serializable.
- **Flaws**: none found.
- **Verdict**: PASS

### Write ordering — same series, different kinds
- **Source**: "Writes addressed to the same series must take effect in the order the client
  invoked them."
- **Trace**: see Topologies section (append order per partition, no partitioner after the
  source, uncommitted writes visible within the topology).
- **Verdict**: PASS

### Synchronization — shared counter across separately created wrappers
- **Source**: "Multiple clients wrapping the same deployed module must observe the same
  business state after the writing client synchronizes." / harness: "track the cumulative
  depot append count internally ... call wait-for-microbatch-processed-count".
- **Trace**: testing.md: the count is "tracked for the MODULE", cumulative. Client A (5
  appends) waits for 5; client B created afterwards appends 1 → shared registry total 6 →
  B waits for 6 → returns only after its own record is processed ✓. Client B created before
  any writes and calling `wait-for-processing!` with no writes → waits for the current total
  (already reached) → returns immediately ✓. Counter increments before each append, so a
  wait issued after an append always covers it.
- **Fault tolerance**: counter loss (process restart) cannot affect any read result.
- **Flaws**: none found.
- **Verdict**: PASS

### Task-count independence (2 vs 4 tasks)
- **Source**: "The module runs with both 2 and 4 tasks in private validation."
- **Trace**: no `|global`, no `|all`, no task-id arithmetic; hash placement only. Results
  are per-series and independent of which task owns the series.
- **Verdict**: PASS

### Durability
- **Source**: "All authoritative business state must be durable Rama state."
- **Trace**: clock, counters, raw, buckets all in `$$series`; the only client memory is the
  append counter, "transient synchronization counters are allowed".
- **Verdict**: PASS

## Self-consistency check
The only entries marked FAIL are the two that share one root cause (subindexed 3600 map
behind a width-keyed outer map, plus size tracking left on). No entry marked PASS contains
"gap", "tradeoff", or "not ideal" language. Verdict is **minor-fail**: the fix is a
localized schema/path edit with no architectural change, applied to PLAN.md below.

## Fixes applied to PLAN.md
1. Schema: `:buckets` (width → subindexed map) replaced by `:buckets-60` (subindexed sorted
   map, `{:subindex-options {:track-size? false}}`) and `:buckets-3600` (plain
   `map-schema Long → bucket record`, ≤ 3 entries by eager expiry). `:raw` also gets
   `track-size? false`.
2. `ingest-sample!` fold: 60-width via aggregator/RMW on the subindexed map; 3600-width via
   one read + write-only `termval` of the small map.
3. `advance-clock!` expiry: 60-width unchanged (range + point deletes); 3600-width via one
   read + `termval` of the pruned map.
4. `query-rollup`: per-width branch — range scan for 60, in-memory filter of the ≤ 3-entry
   map for 3600.
5. Alternatives table, cost figures, design decisions, and difficulty log updated to match.
