# HLD Metrics Pipeline Challenge

Build a multi-tenant metrics store: per-series raw sample retention plus
60-unit and 3600-unit rollups, driven by an explicit per-series logical
clock that decides admission, completeness, and expiry.

## Attribution

This challenge is adapted from the case study
["Design a Metrics Pipeline"](https://hld.handbook.academy/curriculum/case-studies/metrics-pipeline/)
by The HLD Handbook contributors
([handbook-academy/engineering-handbook](https://github.com/handbook-academy/engineering-handbook)),
licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
The prose in this README is adapted from that case study and is likewise
offered under CC BY-SA 4.0.

### What was changed

- Wall-clock time is replaced by an explicit, per-series, monotonic logical
  clock so tests are deterministic.
- The case study's raw / 5-minute / 1-hour tiers become raw / 60-unit /
  3600-unit tiers. Retention is shortened to 300 units (raw) and 7200 units
  past bucket end (rollups).
- Sample values are integers, not float64. Series identity is the structural
  triple `[tenant metric labels]` rather than a hashed label set.
- Admission rules (future, expired, duplicate) are made explicit and testable.

### What was excluded

Scraping, PromQL, alerting rules, Gorilla/XOR compression, WAL and block
formats, object-storage tiering, cardinality caps, HA deduplication, and all
capacity numbers. Nothing in this challenge depends on Prometheus, Thanos,
or Mimir.

## Domain model

**Series.** A series is `[tenant metric labels]`. `tenant` and `metric` are
non-empty strings. `labels` is a map from non-empty string keys to string
values and may be empty. Two series are the same iff all three parts are
equal. `{"host" "a" "zone" "b"}` and `{"zone" "b" "host" "a"}` are the same
labels.

**Clock.** Each series has its own integer clock, initially `0`.
`advance-clock!` moves it forward. A call with a value less than or equal to
the current clock is a no-op.

**Sample.** An integer `timestamp >= 0` and an integer `value`. Let `C` be
the series clock when an `ingest-sample!` write is applied. Checks run in
this order and the first that fires decides the outcome:

| Order | Condition | Outcome |
|---|---|---|
| 1 | `timestamp > C` | rejected, future |
| 2 | `timestamp + 300 <= C` | rejected, expired |
| 3 | a sample with this timestamp was already accepted on this series | rejected, duplicate |
| 4 | otherwise | accepted |

So the admissible window at clock `C` is `C - 300 < timestamp <= C`.
Rejection never reserves a timestamp: if `500` is rejected as future at
clock `400`, a sample at `500` offered after the clock reaches `500` is
accepted normally. The first accepted sample for a timestamp is final.

**Raw retention.** An accepted sample with timestamp `ts` is retained while
`ts + 300 > clock`. Once `ts + 300 <= clock` it is gone from `query-raw`.

**Rollups.** Every accepted sample is folded into two buckets: the 60-wide
bucket starting at `timestamp - (timestamp mod 60)` and the 3600-wide bucket
starting at `timestamp - (timestamp mod 3600)`. A bucket carries
`:count`, `:sum`, `:min`, `:max` over every sample ever accepted into it.

- A bucket is **complete** when `start + width <= clock`.
- A bucket is **retained** while `start + width + 7200 > clock`.
- Raw expiry never subtracts from a bucket. A bucket's aggregates only ever
  grow, and only through accepted samples.
- A late-but-admissible sample may land in a bucket that is already
  complete. Its aggregates change accordingly; queries return the current
  value.

**Queries.** `query-raw` returns retained samples with
`start <= timestamp < end`. `query-rollup` returns complete, retained,
non-empty buckets whose start lies in the half-open range `[start, end)`.
Both `start` and `end` for rollup queries are multiples of the width. Both
queries return ascending order.

**Series info.** `get-series-info` returns the current clock and cumulative
admission counters. Counters never decrease; expiry does not touch them.

## Worked example

Series `S = ["acme" "http_requests" {"host" "web-1"}]`, clock `0`.

1. `advance-clock! S 1000` → clock `1000`. Admissible window is `700 < ts <= 1000`.
2. `ingest-sample! S 1000 5` → accepted.
3. `ingest-sample! S 1001 8` → rejected, future.
4. `ingest-sample! S 700 4` → rejected, expired (`700 + 300 <= 1000`).
5. `ingest-sample! S 701 3` → accepted.
6. `ingest-sample! S 701 9` → rejected, duplicate.
7. `get-series-info S` →
   `{:clock 1000 :accepted 2 :rejected-future 1 :rejected-expired 1 :rejected-duplicate 1}`
8. `query-raw S 600 1100` → `[{:timestamp 701 :value 3} {:timestamp 1000 :value 5}]`
9. `query-rollup S 60 600 1020` → `[{:start 660 :count 1 :sum 3 :min 3 :max 3}]`.
   Bucket `960` (end `1020`) is not complete because `1020 > 1000`.
10. `query-rollup S 3600 0 3600` → `[]`. Bucket `0` ends at `3600 > 1000`.
11. `advance-clock! S 1001` → sample `701` expires (`701 + 300 <= 1001`).
    `query-raw S 600 1100` → `[{:timestamp 1000 :value 5}]`.
    `query-rollup S 60 600 1020` still returns bucket `660` with count `1`, sum `3`.
12. `advance-clock! S 1020` → bucket `960` is complete.
    `query-rollup S 60 600 1020` →
    `[{:start 660 :count 1 :sum 3 :min 3 :max 3} {:start 960 :count 1 :sum 5 :min 5 :max 5}]`
13. `advance-clock! S 3600` → `query-rollup S 3600 0 3600` →
    `[{:start 0 :count 2 :sum 8 :min 3 :max 5}]`. Both samples count even though
    the raw copy of `701` expired long ago.
14. `advance-clock! S 7919` → bucket `660` is still retained (`720 + 7200 = 7920 > 7919`).
    `advance-clock! S 7920` → bucket `660` is gone; `query-rollup S 60 600 1020`
    → `[{:start 960 :count 1 :sum 5 :min 5 :max 5}]`.
15. `advance-clock! S 5000` → no-op; clock stays `7920`.

Rejection does not reserve a timestamp: with a fresh series `T` at clock
`400`, `ingest-sample! T 500 1` is rejected as future. After
`advance-clock! T 500`, `ingest-sample! T 500 7` is accepted and
`query-raw T 500 501` → `[{:timestamp 500 :value 7}]`.

## Efficiency contract

Bounds concern application-level records examined or updated, allowing
input/output-size costs and ordinary lookup and ranking overhead.

- `query-raw` and `query-rollup` must do work proportional to the queried
  series' data inside the requested range, not to other series, other
  tenants, or data outside the range.
- `ingest-sample!` must do bounded work per sample, independent of how many
  samples or series exist.
- `get-series-info` must examine a bounded amount of state, independent of
  retained samples and buckets.
- `advance-clock!` may do work proportional to the data it expires on that
  series. It must not touch other series.
- Reads of one series must not read state belonging to any other series.

## Input assumptions

- All IDs (`tenant`, `metric`, label keys) are non-empty strings; label
  values are strings.
- Timestamps, clocks, values, and range bounds are signed 64-bit integers;
  timestamps, clocks, and bounds are `>= 0`; `start <= end`; rollup bounds
  are aligned to the width. Inputs guarantee all cumulative counters and
  bucket sums fit signed 64-bit integers. Time comparisons must remain
  correct up to `Long/MAX_VALUE`; intermediate arithmetic must not overflow.
- Tests supply valid input except for the explicitly specified rejections:
  future samples, expired samples, duplicate timestamps, and stale clock
  advances.

## Write ordering and synchronization

All `!` methods are asynchronous writes. Tests call
`(harness/wait-for-processing! client)` after a group of writes and before
any read. Writes addressed to the same series must take effect in the order
the client invoked them (for example, an `advance-clock!` followed by an
`ingest-sample!` on the same series is judged against the advanced clock).
No ordering is required between different series.

All authoritative business state must be durable Rama state (depots and
PStates); transient synchronization counters are allowed. The module runs
with both 2 and 4 tasks in private validation. Multiple clients wrapping
the same deployed module must observe the same business state after the
writing client synchronizes. Tests alternate synchronized client phases;
no cross-client concurrent ordering or snapshot isolation is required.

## Protocol

Your implementation must satisfy the `MetricsPipeline` protocol defined in
`src/hld_metrics_pipeline/protocol.clj`. The docstrings there are part of
the contract.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <MetricsPipeline implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified `MetricsPipeline` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `rama-challenges.harness/Synchronizable`. See
the docstring on that protocol for implementation requirements. Tests call
`(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `hld-metrics-pipeline.module`.

## File Location

Write your solution to:
```
implementations/hld-metrics-pipeline/src/hld_metrics_pipeline/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
