# Implicit Spec

<!-- Phase 0 artifact for hld-metrics-pipeline. Requirements only — no PState,
depot, or topology design. Inputs: README.md, src/hld_metrics_pipeline/protocol.clj,
lib/harness Synchronizable docstring, skills/rama SKILL.md. -->

## Derived domain facts

These follow from the stated rules and every later phase may rely on them.

- **Series identity is by value.** `[tenant metric labels]` compared with structural
  equality. `{"host" "a" "zone" "b"}` and `{"zone" "b" "host" "a"}` are the same
  series, and so are equal maps of different concrete map types. Any keying or
  routing of a series must be a pure function of the triple's value, never of its
  printed form or insertion order. `{}` labels is a valid, distinct series.
- **The clock is monotone, so admissibility is monotone.** The admissible window at
  clock `C` is `C - 300 < ts <= C` and it only moves forward. Consequences:
  - A timestamp rejected as expired can never become admissible again.
  - Once a raw sample expires (`ts + 300 <= clock`), re-offering that timestamp is
    rejected by rule 2 (expired), never by rule 3 (duplicate). The duplicate check
    therefore only ever needs to distinguish timestamps inside the current window.
  - A bucket whose retention has ended (`start + width + 7200 <= clock`) can never
    receive another sample: every `ts` in it satisfies `ts + 300 <= clock`.
  - A bucket can still receive samples after it is complete, but only while
    `clock < start + width + 300` (the last admissible timestamp in the bucket is
    `start + width - 1`).
- **Live data per series is bounded by the windows, not by history.** Timestamps are
  integers and duplicates are rejected, so a series has at most 300 retained raw
  samples at any instant. A non-empty bucket that is not yet expired has
  `clock - width - 7200 < start <= clock` (no sample with `ts > clock` is ever
  accepted), so at most `7260 / 60 = 121` 60-buckets and `10800 / 3600 = 3`
  3600-buckets are retained or open per series at any instant. The number
  of series, the clock value, the cumulative counters, and the total samples ever
  accepted are all unbounded.
- **The initial clock is 0, so a never-advanced series admits exactly `ts = 0`.**
  `ingest-sample! S 0 v` on a fresh series is accepted; any `ts > 0` is future.
- **Rule order decides the counter.** A sample that is both future and a duplicate
  cannot exist; a sample that is both expired and previously accepted counts as
  `:rejected-expired`. Exactly one counter increments per applied `ingest-sample!`,
  so `:accepted + :rejected-future + :rejected-expired + :rejected-duplicate` equals
  the number of `ingest-sample!` calls applied to the series.
- **Boundary arithmetic (inclusive/exclusive) is fixed by the spec:**
  - `ts = C` accepted; `ts = C + 1` future.
  - `ts = C - 299` accepted; `ts = C - 300` expired.
  - Raw sample gone exactly when `ts + 300 == clock`.
  - Bucket complete exactly when `start + width == clock`.
  - Bucket gone exactly when `start + width + 7200 == clock`.
  - Query ranges are half-open `[start, end)`; `start == end` yields `[]`.
- **Values are signed 64-bit integers.** `value` may be negative or zero; a bucket's
  first sample sets `:min` and `:max` to that value (never to 0), `:count 1`,
  `:sum value`. Sums use 64-bit arithmetic. Overflow beyond 64 bits is out of scope.
- **Timestamps, clocks, and bounds may be large** (any non-negative 64-bit value). No
  operation may do work proportional to the numeric distance between two clock or
  timestamp values (e.g. a clock jump from 0 to 10^12, or `query-raw S 0 10^12`).

## Global requirements

- **All state is durable Rama state.** Nothing that a read depends on may live only
  in client memory, a TaskGlobal, or a JVM atom. Counters, clocks, retained samples,
  and bucket aggregates must survive worker restart without depot replay.
- **Multiple clients share one truth.** Tests create a second `wrap-client` on the
  same running cluster. Reads through any client must return the same results as
  reads through the client that issued the writes. A client created after writes
  were made must see them. Client-local bookkeeping may support synchronization but
  must never affect read results.
- **`wait-for-processing!` semantics.** When it returns on a client, every write
  previously issued through that client is applied and visible to reads through any
  client. This must hold for a client created on a cluster that has already
  processed writes from another client: it must genuinely wait for its own writes,
  not return early because other clients' work was already counted.
- **Per-series total order.** All writes to one series (both `advance-clock!` and
  `ingest-sample!`) take effect in the order the issuing client invoked them, even
  though they are different operation kinds. `advance-clock! 500` then
  `ingest-sample! 500 v` accepts; the reverse order rejects the sample as future and
  the timestamp stays unreserved. Writes from two clients to the same series are
  applied in some single serial order, each judged against the state at the moment
  it is applied. No ordering is required across different series.
- **Exactly-once effect per write.** Under retries, failures, or replays, each
  `ingest-sample!` increments exactly one counter once, stores at most one raw
  sample, and folds into each of its two buckets once. `advance-clock!` applied twice
  is naturally a no-op the second time, but its expiry effects must also be
  retry-safe.
- **Task-count independence.** The harness deploys with 2 or 4 tasks at random.
  Results must be identical for either count. The work bounds below are per
  operation and must not depend on task count. Throughput must grow with task count:
  no per-write or per-read step may funnel every series through a single task, and
  no read of series `S` may read state belonging to any other series or tenant.
- **Retention means reclamation.** Because live data per series is bounded by the
  windows (see above), a series' stored footprint must also stay bounded as its
  clock advances. Expired raw samples and expired buckets must eventually be
  physically removed, not merely hidden; the clock and the cumulative counters are
  never removed. Removal may happen eagerly during `advance-clock!` (whose budget is
  "work proportional to the data it expires on that series") or lazily, as long as
  expired data never reaches a result and the storage bound holds.
- **Never lose data that reads depend on.** Raw expiry must not alter any bucket.
  Bucket expiry must not alter counters. No operation deletes anything the spec
  does not expire.

## Operations

### `advance-clock! [tenant metric labels clock]` — write

- **Latency.** Asynchronous; effects must be visible after `wait-for-processing!`.
  No sub-millisecond bound, but a no-op advance (`clock <= current`) must be
  near-constant cost.
- **Throughput.** One call per series per scrape interval across all tenants;
  scales with the number of live series. A stale advance is common (out-of-order
  scrapers) and must be cheap.
- **Invariants.**
  - Clock never decreases. `advance-clock! S c` with `c <= current` changes nothing
    observable, including counters.
  - After `advance-clock! S c` with `c > current`: clock is `c`; every raw sample with
    `ts + 300 <= c` is absent from `query-raw`; every bucket with
    `start + width <= c` is complete; every bucket with `start + width + 7200 <= c`
    is absent from `query-rollup`; all five `get-series-info` values other than
    `:clock` are unchanged.
  - Advancing never creates, alters, or removes a bucket's aggregates.
- **Scale.** One clock per series; unbounded series. A single advance may expire
  up to 300 raw samples, up to 121 60-buckets, and up to 3 3600-buckets, or nothing.
- **Work bound.** May do work proportional to the data it expires on that series
  plus a constant. Must not touch other series. Must not do work proportional to
  `c - current` (a jump of 10^12 with three samples stored costs the same as a jump
  of 301).
- **Concurrency.** Two advances to the same series in flight: the larger wins, the
  smaller is a no-op regardless of application order. Interleaved with
  `ingest-sample!` on the same series: strictly per-series order from the issuing
  client.
- **Edge cases.**
  - Fresh series, `advance-clock! S 0`: no-op; `get-series-info` still all zeros.
  - Fresh series, `advance-clock! S 1000`: series now has clock 1000, counters 0,
    no samples, no buckets.
  - Advance exactly to `ts + 300`: that sample expires. Advance to `ts + 299`: retained.
  - Advance exactly to `start + width`: bucket complete. Advance exactly to
    `start + width + 7200`: bucket gone.
  - One advance may take a bucket from open straight to expired (e.g. clock 0 →
    100000). That bucket is never returned by any query, but the samples in it
    remain counted in `:accepted`.
  - Advance far enough that every raw sample and every bucket expires:
    `query-raw` and `query-rollup` return `[]`, `get-series-info` unchanged except
    `:clock`.

### `ingest-sample! [tenant metric labels timestamp value]` — write

- **Latency.** Asynchronous; visible after `wait-for-processing!`.
- **Throughput.** The dominant write. Volume = active series × sample rate; multi-
  tenant, high cardinality. Bursts of many samples for the same series (backfill of
  the last 300 units) and interleaving across many series are both normal.
- **Invariants.**
  - Outcome is decided by the four rules in order, against the series clock at the
    moment the write is applied.
  - Exactly one counter increments per applied call.
  - Accepted: the sample appears in `query-raw` (while retained) with its `value`;
    both the 60-bucket at `ts - (ts mod 60)` and the 3600-bucket at
    `ts - (ts mod 3600)` gain `count + 1`, `sum + value`, `min`/`max` updated.
  - Rejected (any reason): no raw sample stored, no bucket changed, only the
    matching counter increments. A rejection reserves nothing.
  - First accepted sample for a timestamp is final; a duplicate never changes the
    stored value or any bucket.
- **Scale.** Duplicate detection is against at most the 300 timestamps in the current
  window, but cumulative accepted count is unbounded.
- **Work bound.** Bounded per sample, independent of how many samples or series
  exist: the clock read, the duplicate check, the raw store, the two bucket folds,
  and the counter increment must each be constant-cost with respect to series size.
  The duplicate check must not scan retained samples.
- **Concurrency.** Two ingests with the same timestamp on the same series: the first
  applied is accepted, the second is a duplicate, whichever client sent them. Ingests
  for different timestamps or different series are independent.
- **Edge cases.**
  - Fresh series (clock 0): `ts = 0` accepted; `ts = 1` future. Rejections on a fresh
    series still produce a series whose `get-series-info` shows the rejection count
    with `:clock 0`.
  - `ts = clock` accepted; `ts = clock - 300` expired; `ts = clock - 299` accepted.
  - Late sample into an already-complete bucket: aggregates change and the next
    `query-rollup` returns the new values.
  - Re-offer of a timestamp whose raw copy expired: `:rejected-expired`, not duplicate.
  - Negative `value`, zero `value`, `value` larger than 32 bits: all valid; min/max/sum
    exact.
  - Sample at `ts` that is a multiple of 60 or 3600 (bucket start): belongs to the
    bucket starting at `ts`. Sample at `start + width - 1`: belongs to bucket `start`.
  - Same timestamp offered with different values: only the first accepted value is
    ever returned.

### `get-series-info [tenant metric labels]` — read

- **Latency.** Single-digit milliseconds; constant amount of data.
- **Throughput.** Dashboards and health checks; low relative to ingest but must not
  interfere with it.
- **Invariants.** Returns the current clock and the four cumulative counters. All
  five are monotone non-decreasing over the life of the series. Expiry never changes
  any of them. For a never-written series returns
  `{:clock 0 :accepted 0 :rejected-future 0 :rejected-expired 0 :rejected-duplicate 0}`
  with all five keys present.
- **Scale.** One record per series; constant size.
- **Work bound.** Constant; must read only this series.
- **Edge cases.** Series with only rejected ingests (no accepted samples, clock 0);
  series where every sample has expired (counters still show them); series that only
  had `advance-clock!` (clock set, counters 0).

### `query-raw [tenant metric labels start end]` — read

- **Latency.** Single-digit milliseconds for the ≤ 300 possible results; one
  request should not require repeated client↔cluster roundtrips proportional to the
  result size.
- **Throughput.** Dashboard and query-engine reads; may be issued for many series
  concurrently with ingest.
- **Invariants.**
  - Returns exactly the accepted, currently retained samples with
    `start <= ts < end`, as `{:timestamp ts :value v}` in ascending timestamp order,
    as a vector.
  - Never returns an expired sample, even if the range covers its timestamp and the
    query races an in-flight expiry: the result is consistent with the series clock
    at the time of the read.
  - Returns `[]` for unknown series, empty ranges, or ranges with no retained data.
- **Scale.** At most 300 results. Ranges may be far larger than the data
  (`0 .. 10^12`) or entirely below the retention window.
- **Work bound.** Proportional to this series' samples inside `[start, end)` plus a
  constant. Must not scale with samples outside the range (it must be able to seek
  to `start` rather than scan from the series' first timestamp), with other series,
  with other tenants, or with the numeric width of the range.
- **Concurrency.** Reads see a consistent snapshot of the series: a sample either is
  or is not present; a read never observes a sample stored but its counter not yet
  incremented in a way that contradicts `get-series-info` after
  `wait-for-processing!`.
- **Edge cases.**
  - `start == end` → `[]`. `end` just above a retained timestamp includes it; `end`
    equal to it excludes it.
  - Range straddling the retention boundary returns only the retained part
    (worked example step 11).
  - Range entirely in the future (`start > clock`) → `[]`.
  - Range covering timestamps that were only ever rejected → `[]`.

### `query-rollup [tenant metric labels width start end]` — read

- **Latency.** Single-digit milliseconds; at most 120 results for width 60 and
  2 for width 3600 (retained and complete excludes the open bucket at the clock).
- **Throughput.** Same profile as `query-raw`; long-range dashboards favor the 3600
  width.
- **Invariants.**
  - `width` is 60 or 3600; `start`, `end` are multiples of `width`, `start <= end`.
  - Returns exactly the buckets of that width with `start <= bucket-start < end` that
    are complete (`bucket-start + width <= clock`), retained
    (`bucket-start + width + 7200 > clock`), and non-empty, as
    `{:start :count :sum :min :max}` in ascending `:start`, as a vector.
  - Aggregates reflect every sample ever accepted into the bucket, including samples
    whose raw copy has expired and samples accepted after completion.
  - Incomplete buckets are never returned even if non-empty. Empty bucket slots are
    never returned even if complete and retained.
  - `[]` for unknown series, empty ranges, or when nothing qualifies.
- **Scale.** The set of bucket starts that can qualify is fully determined by the
  clock and the query bounds: at most `[max(start, clock - width - 7200 + 1 rounded
  up to width), min(end, clock - width + 1 rounded down to width))`. Everything
  outside that is either incomplete or expired.
- **Work bound.** Proportional to this series' non-empty buckets of that width whose
  start is in `[start, end)`, plus a constant. Must not iterate empty candidate slots
  across a wide range (`query-rollup S 60 0 10^12` costs the same as querying just
  the eligible window), must not touch the other width, other series, or other
  tenants.
- **Concurrency.** Same snapshot rule as `query-raw`. A late sample and a rollup
  query on the same series serialize; the query returns either the pre- or
  post-fold aggregates, never a partial fold (count updated but sum not).
- **Edge cases.**
  - `start == end` → `[]`.
  - Bucket whose end equals the clock is complete (step 12: clock 1020 completes
    bucket 960). Bucket whose end is clock + 1 is not.
  - Bucket whose `start + width + 7200 == clock` is gone (step 14).
  - A 3600-bucket may be retained while all its 60-buckets have expired; the two
    widths are independent (a 60-bucket never outlives its containing 3600-bucket).
  - A bucket populated only by samples whose raw copies have all expired is still
    returned with full aggregates (step 13).
  - A bucket with a single sample: `count 1`, `sum = min = max = value`.
  - Range that begins before the retention window and ends after the clock returns
    only the eligible middle portion.

### `wait-for-processing!` — synchronization (harness `Synchronizable`)

- **Invariant.** On return, all writes issued through this client before the call
  are applied, in per-series order, and visible to reads from any client.
- **Edge cases.** Called with no prior writes (returns immediately). Called on a
  second client after another client wrote and waited. Called on a second client
  after it issued its own writes while the first client's writes were already
  processed — must still block until its own writes are applied.

## Entity State × Write Matrix

Read operations: `info` = `get-series-info`, `raw` = `query-raw` (over a range
containing the relevant timestamp), `rollup` = `query-rollup` (either width, range
containing the relevant bucket).

### Entity: Series

States: **never-written** (clock 0, all counters 0, no samples, no buckets);
**written** (at least one applied write; clock ≥ 0; may still have no accepted
samples).

```
never-written x advance-clock! c, c == 0
  - info:   all zeros (no-op; indistinguishable from never-written)
  - raw:    []
  - rollup: []

never-written x advance-clock! c, c > 0
  - info:   {:clock c, counters 0}
  - raw:    []
  - rollup: []

never-written x ingest-sample! ts v, ts > 0
  - info:   {:clock 0 :rejected-future 1, others 0}; series now exists via its counter
  - raw:    [] (rejection stores nothing)
  - rollup: []

never-written x ingest-sample! 0 v
  - info:   {:clock 0 :accepted 1, others 0}
  - raw:    [{:timestamp 0 :value v}] for any range containing 0
  - rollup: [] (bucket 0 of either width is not complete at clock 0)

written x advance-clock! c, c <= clock
  - info:   unchanged
  - raw:    unchanged
  - rollup: unchanged

written x advance-clock! c, c > clock
  - info:   :clock c, counters unchanged
  - raw:    samples with ts + 300 <= c gone; others unchanged
  - rollup: buckets with start + width <= c now returned if non-empty and
            start + width + 7200 > c; buckets with start + width + 7200 <= c gone;
            aggregates of surviving buckets unchanged

written x ingest-sample! ts v, ts > clock
  - info:   :rejected-future + 1, else unchanged
  - raw:    unchanged (timestamp not reserved; a later admissible offer succeeds)
  - rollup: unchanged

written x ingest-sample! ts v, ts + 300 <= clock
  - info:   :rejected-expired + 1, else unchanged (even if ts was accepted earlier)
  - raw:    unchanged
  - rollup: unchanged (even if the bucket containing ts is still retained)

written x ingest-sample! ts v, admissible, ts already accepted
  - info:   :rejected-duplicate + 1, else unchanged
  - raw:    unchanged; original value still returned
  - rollup: unchanged

written x ingest-sample! ts v, admissible, ts not yet accepted
  - info:   :accepted + 1, else unchanged
  - raw:    now includes {:timestamp ts :value v}, in sorted position
  - rollup: 60-bucket and 3600-bucket containing ts each gain the sample; each is
            returned only if complete and retained at the current clock
```

### Entity: Raw sample (series × timestamp)

States: **absent** (never accepted); **retained** (accepted, `ts + 300 > clock`);
**expired** (accepted, `ts + 300 <= clock`).

```
absent x ingest-sample! ts, ts > clock
  - info:   :rejected-future + 1
  - raw:    ts not present
  - rollup: unchanged
  (state stays absent; may be accepted once clock >= ts)

absent x ingest-sample! ts, ts + 300 <= clock
  - info:   :rejected-expired + 1
  - raw:    ts not present
  - rollup: unchanged
  (state stays absent forever — the window never moves back)

absent x ingest-sample! ts, admissible
  - info:   :accepted + 1
  - raw:    {:timestamp ts :value v} present
  - rollup: both containing buckets updated
  (state -> retained)

retained x ingest-sample! ts (any value)
  - info:   :rejected-duplicate + 1
  - raw:    original value unchanged
  - rollup: unchanged
  (state stays retained)

retained x advance-clock! c, c < ts + 300
  - info:   :clock c
  - raw:    still present
  - rollup: buckets unchanged (may become complete/expired per bucket rules)

retained x advance-clock! c, c >= ts + 300
  - info:   :clock c; :accepted unchanged
  - raw:    ts absent
  - rollup: containing buckets still include the sample
  (state -> expired)

expired x ingest-sample! ts
  - info:   :rejected-expired + 1 (rule 2 fires before rule 3)
  - raw:    ts absent
  - rollup: unchanged

expired x advance-clock! (any)
  - info:   :clock advanced if larger
  - raw:    ts absent
  - rollup: unchanged by this sample
  (state stays expired; storage for it must eventually be reclaimed)
```

### Entity: Rollup bucket (series × width × start)

States: **empty** (no accepted sample in `[start, start+width)`); **open**
(non-empty, `start + width > clock`); **complete** (non-empty,
`start + width <= clock < start + width + 7200`); **expired**
(`start + width + 7200 <= clock`, empty or not).

```
empty x ingest-sample! accepted with ts in bucket
  - info:   :accepted + 1
  - raw:    sample present (while retained)
  - rollup: bucket {:count 1 :sum v :min v :max v}; returned only if
            start + width <= clock (state -> open or complete accordingly)

empty x ingest-sample! rejected (any reason)
  - info:   matching rejection counter + 1
  - raw:    unchanged
  - rollup: bucket still absent

empty x advance-clock! (any)
  - info:   :clock advanced if larger
  - raw:    unaffected by this bucket
  - rollup: bucket never returned (empty buckets are not results, complete or not)

open x ingest-sample! accepted with ts in bucket
  - info:   :accepted + 1
  - raw:    sample present
  - rollup: aggregates updated; still not returned (incomplete)

open x ingest-sample! duplicate ts in bucket
  - info:   :rejected-duplicate + 1
  - raw:    unchanged
  - rollup: aggregates unchanged

open x advance-clock! c, c < start + width
  - info:   :clock c
  - raw:    per raw rules
  - rollup: still not returned

open x advance-clock! c, start + width <= c < start + width + 7200
  - info:   :clock c
  - raw:    per raw rules (some samples in the bucket may already be expired)
  - rollup: bucket now returned with full aggregates (state -> complete)

open x advance-clock! c, c >= start + width + 7200
  - info:   :clock c; :accepted unchanged
  - raw:    all samples in the bucket are expired (ts + 300 <= c)
  - rollup: bucket never returned (state -> expired without ever being visible)

complete x ingest-sample! accepted late sample (ts in bucket, ts > clock - 300)
  - info:   :accepted + 1
  - raw:    sample present
  - rollup: bucket returned with updated count/sum/min/max immediately

complete x ingest-sample! expired ts in bucket (ts + 300 <= clock)
  - info:   :rejected-expired + 1
  - raw:    unchanged
  - rollup: aggregates unchanged; bucket still returned

complete x ingest-sample! duplicate ts in bucket
  - info:   :rejected-duplicate + 1
  - raw:    unchanged
  - rollup: aggregates unchanged

complete x advance-clock! c, c < start + width + 7200
  - info:   :clock c
  - raw:    samples in bucket expire as ts + 300 <= c; bucket unaffected
  - rollup: bucket still returned, aggregates unchanged

complete x advance-clock! c, c >= start + width + 7200
  - info:   :clock c; counters unchanged
  - raw:    all samples in bucket already expired
  - rollup: bucket absent (state -> expired)

expired x ingest-sample! any ts in bucket
  - info:   :rejected-expired + 1 (every ts in the bucket satisfies ts + 300 <= clock)
  - raw:    unchanged
  - rollup: bucket absent

expired x advance-clock! (any)
  - info:   :clock advanced if larger
  - raw:    unchanged
  - rollup: bucket absent (state stays expired; storage must eventually be reclaimed)
```
