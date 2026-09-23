# Plan

<!-- Phase 1 artifact for hld-metrics-pipeline, subsystem `metrics-pipeline` (whole spec).
Authority: README.md + protocol docstrings + harness Synchronizable. IMPLICIT_SPEC.md is
derived guidance; its numeric latency/throughput figures and snapshot-isolation notes are
NOT requirements and are not used to justify anything below. -->

## Series identity (shared by every read and write)

A series is the structural triple `[tenant metric labels]`. All PState keys and depot
partitioning use one canonical value built by a single client-side function:

```clojure
(defrecord SeriesKey [tenant metric labels])

(defn series-key [tenant metric labels]
  ;; labels canonicalized to a sorted map so equal label sets serialize to identical
  ;; bytes regardless of the caller's map type or insertion order. Top-level PState
  ;; keys are compared by serialized form on disk, and depot/|hash routing must agree.
  (->SeriesKey tenant metric (into (sorted-map) labels)))
```

`(= (sorted-map) {})`, so `{}` labels is a valid distinct series. Every protocol method
(reads and writes) goes through `series-key`, so the depot partitioner `(hash-by :series)`,
the query-topology leading `(|hash *series)`, and `foreign-select-one` routing all land on
the same task for the same series.

Depot records:

```clojure
(defrecord AdvanceClock [series clock])
(defrecord IngestSample [series timestamp value])
```

## Reads

All three reads touch exactly one series, so each is served entirely from one task.

| Read | Access method | Why |
|---|---|---|
| `get-series-info` | `foreign-select-one` | one path, one PState, one partition, fixed-size answer |
| `query-raw` | query topology `"query-raw"` | needs the clock first (to clamp the range), then a range read on the same partition: 2 dependent reads → one roundtrip via query topology |
| `query-rollup` | query topology `"query-rollup"` | same shape: clock read, then a range read of one width |

### `get-series-info`

```clojure
(def INFO-KEYS [:clock :accepted :rejected-future :rejected-expired :rejected-duplicate])
(def ZERO-INFO (zipmap INFO-KEYS (repeat 0)))

(merge ZERO-INFO
       (foreign-select-one [(keypath (series-key tenant metric labels)) (submap INFO-KEYS)]
                           series-pstate))
```

- `keypath` on an unknown series → `nil`; `submap` on `nil` → `{}`; the merge yields all
  five keys as `0`. `foreign-select-one` is safe: `submap` always navigates to exactly one
  value. Fields never written yet (e.g. a series that only had `advance-clock!`) are
  absent and default to `0` by the same merge.
- `submap` is used instead of selecting the whole record because the record also holds
  subindexed structures, which cannot cross the wire.
- Cost: 1 seek (the series record), 1 network roundtrip.

### `query-raw [series start end]` → query topology `"query-raw"`

Retained raw samples in `[start, end)`. Retained ⟺ `ts + 300 > clock` ⟺ `ts >= clock - 299`.
Accepted samples never exceed the clock, so `ts <= clock`.

```
lo = max(start, clock - 299)
hi = min(end,   clock + 1)
```

If `lo < hi`: one `sorted-map-range lo hi` read on `:raw`, mapped to
`{:timestamp ts :value v}` rows (submap iteration is ascending). Else `[]` with no second
read. Expired entries that have not yet been physically reclaimed (only possible between
an `advance-clock!` and its expiry work, which is the same event — so effectively never,
see Writes) can never fall inside `[lo, hi)` because `lo >= clock - 299`.

Sketch of the topology body (design, not final code):

```clojure
(<<query-topology topologies "query-raw" [*series *start *end :> *result]
  (|hash *series)                                                     ; client-side routed
  (local-select> [(keypath *series :clock) (nil->val 0)] $$series :> *clock)
  (raw-window *clock *start *end :> [*lo *hi])                        ; plain defn, pure arithmetic
  (<<if (< *lo *hi)
    (local-select> [(keypath *series :raw) (sorted-map-range *lo *hi)] $$series :> *submap)
    (raw-rows *submap :> *result)                                     ; (mapv (fn [[ts v]] {:timestamp ts :value v}) submap)
   (else>)
    (identity [] :> *result))
  (|origin))
```

- `*result` is bound on both branches, emitted exactly once; pre-agg only, no aggregator.
- Unknown series: `:clock` → `0`, `hi = min(end, 1)`; `lo = max(start, -299) = start`;
  a non-empty window only for `start = 0, end >= 1`, in which case the `:raw` read on a
  `nil` record navigates to an empty submap → `[]`. All other inputs skip the second read.

### `query-rollup [series width start end]` → query topology `"query-rollup"`

Eligible bucket starts `s` of width `w` at clock `C`:
complete `s + w <= C` ⟺ `s <= C - w`; retained `s + w + 7200 > C` ⟺ `s > C - w - 7200`.
Since every stored start is a multiple of `w` and the query bounds are aligned:

```
lo = max(start, C - w - 7199)     ; inclusive
hi = min(end,   C - w + 1)        ; exclusive
```

Check against the worked example: `C=1020, w=60` → `hi = 961` includes bucket `960`;
`C=7919` → `lo = 660` keeps bucket `660`; `C=7920` → `lo = 661` drops it. `C=1000,
w=3600, [0,3600)` → `hi = min(3600, -2599)` → `lo >= hi` → `[]`.

```clojure
(<<query-topology topologies "query-rollup" [*series *width *start *end :> *result]
  (|hash *series)
  (local-select> [(keypath *series :clock) (nil->val 0)] $$series :> *clock)
  (rollup-window *clock *width *start *end :> [*lo *hi])
  (<<cond
    (case> (>= *lo *hi))
    (identity [] :> *result)
    (case> (= *width 60))
    (local-select> [(keypath *series :buckets-60) (sorted-map-range *lo *hi)] $$series :> *submap)
    (bucket-rows *submap :> *result)                ; (mapv (fn [[s agg]] (assoc agg :start s)) submap) — ascending
    (default>)                                       ; width 3600: plain map, ≤ 3 entries
    (local-select> [(keypath *series :buckets-3600)] $$series :> *m3600)
    (bucket-rows-in *m3600 *lo *hi :> *result))     ; keep lo <= s < hi, sort-by start, assoc :start
  (|origin))
```

- Non-empty is automatic: a bucket entry exists only if a sample was folded into it.
- Expired buckets not yet reclaimed cannot be in `[lo, hi)` because `lo > C - w - 7200`
  (the 3600 branch applies the same `[lo, hi)` filter in memory).
- The 3600 map is read whole: it holds at most 3 live entries (eager expiry, see Writes),
  so "filter in memory" is ≤ 3 comparisons — 1 seek, same as a range scan would cost.
- Both reads happen in one synchronous segment on the single-threaded task, so the clock
  and the range are read against the same committed state (no write can interleave).

## Writes

Both writes are records appended to one depot (per-series order across both kinds is
required) and processed by one microbatch topology on the series' task.

| Write | Depot record | Effect |
|---|---|---|
| `advance-clock!` | `(->AdvanceClock series clock)` | if `clock > current`: set clock, physically delete expired raw samples and expired buckets of both widths; else no-op |
| `ingest-sample!` | `(->IngestSample series timestamp value)` | apply the four admission rules against the current clock; increment exactly one counter; on accept store raw + fold into the 60 and 3600 buckets |

Client side: `(foreign-append! depot record :append-ack)` — nothing waits on PState
visibility at append time (microbatch does not participate in ack); visibility is provided
by `wait-for-processing!` (see Synchronization).

### `advance-clock!` processing (on the series' task, one event, no partitioner)

```clojure
(local-select> [(keypath *series :clock) (nil->val 0)] $$series :> *cur)
(<<if (> *clock *cur)
  (local-transform> [(keypath *series :clock) (termval *clock)] $$series)
  ;; raw expiry: keys < clock - 299  (⟺ ts + 300 <= clock)
  (local-select> [(keypath *series :raw) (sorted-map-range-to (- *clock 299)) (subselect MAP-KEYS)]
                 $$series :> *expired-ts)
  (ops/explode *expired-ts :> *ts)
  (local-transform> [(keypath *series :raw *ts) NONE>] $$series)
  ;; 60-bucket expiry: keys < clock - 60 - 7199  (⟺ start + 60 + 7200 <= clock)
  (local-select> [(keypath *series :buckets-60) (sorted-map-range-to (- *clock 7259)) (subselect MAP-KEYS)]
                 $$series :> *expired-starts)
  (ops/explode *expired-starts :> *s)
  (local-transform> [(keypath *series :buckets-60 *s) NONE>] $$series)
  ;; 3600-bucket expiry: plain ≤ 3-entry map — read, prune keys < clock - 3600 - 7199, write back
  (local-select> [(keypath *series :buckets-3600)] $$series :> *m3600)
  (prune-buckets *m3600 (- *clock 10799) :> *pruned)          ; plain defn: (into {} (filter #(>= (key %) cutoff) m))
  (local-transform> [(keypath *series :buckets-3600) (termval *pruned)] $$series))
```

(The raw-delete chain, the 60-bucket chain and the 3600 read/write are three independent
branches off the `<<if` body — anchored with `anchor>`/`<<branch` — so no branch runs once
per key exploded by another, and a zero-emit `ops/explode` in one branch does not
terminate the others.)

- `subselect MAP-KEYS` materializes the expired keys as one vector before any delete, so
  the structure is never mutated while being iterated.
- `sorted-map-range-to` is a seek to the map's first key and a scan up to the cutoff: cost
  is 1 seek + (number of expired entries). A huge clock jump costs the same as a small one.
- `keypath` + `NONE>` is delete-only, no read.
- The 3600 map is a single small value: 1 read + 1 write regardless of how many of its ≤ 3
  entries expire. `prune-buckets` on `nil` returns `{}` (or `nil` — either navigates as
  empty on the next read).
- Stale advance (`clock <= cur`): 1 seek, nothing written. Fresh series with `clock = 0`:
  also a no-op (nothing is created; `get-series-info` stays all zeros).
- Real advance: clock read (1) + raw range (1) + 60 range (1) + 3600 map read (1) = 4 seeks
  + k iterated/deleted entries.
- No yielding anywhere: bounded work (≤ 300 + 121 deletes + one ≤ 3-entry rewrite) and
  per-series ordering must be preserved.

### `ingest-sample!` processing

```clojure
(local-select> [(keypath *series :clock) (nil->val 0)] $$series :> *clock)
(<<cond
  (case> (> *timestamp *clock))            (identity :rejected-future  :> *outcome)
  (case> (<= (+ *timestamp 300) *clock))   (identity :rejected-expired :> *outcome)
  (default>)
    (local-select> [(keypath *series :raw *timestamp)] $$series :> *existing)   ; point lookup, nil if absent
    (<<if (some? *existing)
      (identity :rejected-duplicate :> *outcome)
     (else>)
      (identity :accepted :> *outcome)
      (local-transform> [(keypath *series :raw *timestamp) (termval *value)] $$series)   ; write-only
      (bucket-start *timestamp 60   :> *b60)
      (bucket-start *timestamp 3600 :> *b3600)
      ;; 60-bucket: one read-modify-write of one entry in the subindexed map
      (+compound $$series {*series {:buckets-60 {*b60 (+fold-bucket *value)}}})
      ;; 3600-bucket: the whole ≤ 3-entry map is one value — read it, fold, write-only termval
      (local-select> [(keypath *series :buckets-3600)] $$series :> *m3600)
      (fold-into-map *m3600 *b3600 *value :> *m3600')        ; (update m b fold-value-into-bucket v)
      (local-transform> [(keypath *series :buckets-3600) (termval *m3600')] $$series)))
(local-transform> [(keypath *series *outcome) (nil->val 0) (term inc)] $$series)
```

with

```clojure
(defn bucket-start [ts w] (- ts (mod ts w)))

(defn fold-value-into-bucket [b v]           ; b may be nil (first sample)
  (if (nil? b)
    {:count 1 :sum v :min v :max v}
    {:count (inc (:count b)) :sum (+ (:sum b) v) :min (min (:min b) v) :max (max (:max b) v)}))

(def +fold-bucket (accumulator (fn [*v] (term (fn [*b] (fold-value-into-bucket *b *v))))))
```

(If `+compound` with an accumulator leaf proves awkward at the REPL, the equivalent for the
60 map is `local-select>` of `[(keypath *series :buckets-60 *b60)]` followed by
`(fold-value-into-bucket *cur *value :> *new)` and a write-only
`[(keypath *series :buckets-60 *b60) (termval *new)]`; same I/O — 1 seek.)

- Rule order is exactly the spec's: future → expired → duplicate → accepted. The duplicate
  lookup runs only for admissible timestamps, which are `> clock - 300`, so any entry it
  finds is a retained sample: physically unreclaimed expired entries (none exist, since
  reclamation is eager) could never be mistaken for duplicates.
- Exactly one counter field is incremented per applied record; the outcome keyword is the
  field name.
- A rejection writes nothing except its counter; a rejected timestamp is not reserved.
- Bucket aggregates only grow and only via accepted samples; raw expiry never touches them.
- Accepted-sample cost: clock read (1) + duplicate lookup (1) + raw write (0, write-only)
  + 60-bucket RMW (1) + 3600 map read (1, write-only back) + counter RMW (1) = 5 seeks.
  Size tracking is disabled on both subindexed maps (nothing queries `count`), so no
  extra per-write read. Rejected sample: 1–2 seeks + counter.

## PState Design

One PState, keyed by series, partitioned by hash of series. All per-series data shares the
key and partitioner, so it is one PState with a fixed-keys value (not several PStates).

```clojure
(declare-pstate mb $$series
  {SeriesKey
   (fixed-keys-schema
     {:clock              Long
      :accepted           Long
      :rejected-future    Long
      :rejected-expired   Long
      :rejected-duplicate Long
      ;; retained raw samples: timestamp -> value (≤ 300 live)
      :raw                (map-schema Long Long {:subindex-options {:track-size? false}})
      ;; 60-wide buckets: bucket-start -> aggregates (≤ 121 live)
      :buckets-60         (map-schema Long
                            (fixed-keys-schema {:count Long :sum Long :min Long :max Long})
                            {:subindex-options {:track-size? false}})
      ;; 3600-wide buckets: bucket-start -> aggregates (≤ 3 live) — one small value, NOT subindexed
      :buckets-3600       (map-schema Long
                            (fixed-keys-schema {:count Long :sum Long :min Long :max Long}))})})
```

`{:subindex-options {:track-size? false}}` is subindexing with size tracking off
(pstate-schema.md "Size Tracking"): tracking costs an extra disk read on every write and
nothing here ever queries `count`.

Why this shape (worked backwards from the reads):

- **Scalars in the record.** `get-series-info` is one seek + `submap`. Every write reads
  the clock from the same record (1 seek) and updates one scalar field in it.
- **`:raw` subindexed, sorted by timestamp.** Up to 300 retained entries (> 100 → must be
  subindexed). `query-raw` is one range scan; the duplicate check is one point lookup; the
  raw store is a write-only `termval`; expiry is one range scan + point deletes.
- **`:buckets-60` subindexed, sorted by bucket start.** Up to 121 live entries (> 100):
  `query-rollup` is one range scan, the fold is one read-modify-write of one entry, expiry
  is one range scan + point deletes.
- **`:buckets-3600` as a plain (non-subindexed) map field.** At most 3 live entries
  (`C - 10800 < start <= C`, multiples of 3600, enforced by admission + eager expiry), far
  under the 50–100-element subindex threshold. The whole map is one stored value: fold =
  1 read + write-only `termval`; expiry = 1 read + write-only `termval`; query = 1 read and
  an in-memory filter/sort of ≤ 3 entries. Each width is its own fixed field so the
  subindexed map is addressed directly from the record with no intermediate width-map hop.
- **Bucket value as `fixed-keys-schema`.** Uniform record shape; navigates as a plain map;
  the query returns it with `:start` assoc'ed — no conversion. Sums/min/max are `Long`
  (64-bit; negative and zero values are ordinary longs).

Alternatives costed:

| Option | ingest (accepted) | query-raw | advance (expiring k) | storage per series | verdict |
|---|---|---|---|---|---|
| A: record + subindexed `:raw` + width-keyed outer map of two subindexed bucket maps | 1 clock + 1 dup lookup + 0 raw write + 1 outer-map read + 2 bucket RMW + 1 scalar RMW ≈ 6 seeks (+3 size-tracking reads if left on) | 2–3 seeks + n iter | 1 + 1 + 3 seeks + k iter + k deletes | bounded (≤ ~26 KB) | rejected: pays a seek per accepted sample (the dominant write) for a uniform code path; skill rule "never trade I/O efficiency for code simplicity" |
| B: `:raw` as a plain (non-subindexed) sorted map in the record | 1 seek, but reads+rewrites a ≤300-entry blob (~5 KB) per sample and per expiry | 1 seek, filter in memory | 1 seek, rewrite blob | bounded | 300× write bytes per sample on the dominant write; exceeds the 100-element subindex rule |
| **C (chosen)**: `:raw` and `:buckets-60` subindexed (size tracking off) as direct fields; `:buckets-3600` a plain ≤ 3-entry map field | 1 clock + 1 dup lookup + 0 raw write + 1 bucket-60 RMW + 1 map read/termval + 1 scalar RMW = 5 seeks | 1–2 seeks + n iter | 4 seeks + k iter + k deletes | same | chosen: lowest aggregate cost on the dominant write; the price is one per-width `<<cond` branch in query and expiry code and a ≤ 3-element in-memory filter |
| D: lazy reclamation (never delete; clamp queries) | saves 3 seeks per real advance | same | 1 seek | **unbounded** growth per long-lived series | violates bounded storage; the spec's advance budget ("work proportional to the data it expires") exists precisely for eager expiry |

## Depots

- `*series-events`: `(declare-depot setup *series-events (hash-by :series))`.
  Records: `AdvanceClock`, `IngestSample`. One depot because the two write kinds are
  order-dependent on the same series (README: an `advance-clock!` followed by an
  `ingest-sample!` on the same series is judged against the advanced clock). Same key →
  same partition → local order. Partitioner matches the PState key, so no repartitioning
  in the topology.

## Topologies and PStates

- **`metrics`: microbatch** — owns `$$series`.
  - Why microbatch: default. Neither stream reason applies: the spec makes all `!` methods
    asynchronous (no ack coordination, no value returned to the appender) and sets no
    millisecond visibility requirement — visibility is defined by `wait-for-processing!`.
  - Why not stream, explicitly: every write here except the clock `termval` and the raw
    `termval` is non-idempotent (four counters, two bucket folds, and the expiry deletes
    are idempotent but the counters/folds are not). A stream retry after commit would
    double-count; the fix would be durable per-series applied-offset dedup state read and
    written on every event — extra I/O on the dominant write to buy a latency property the
    spec does not ask for. Microbatch gives exactly-once for all of it for free. Ordered,
    colocated, atomic per-event writes do NOT require stream: `%microbatch` emits each
    task's depot partition in append order, and this topology has no partitioner after the
    source, so each record's dataflow runs to completion on its task before the next record
    is emitted, seeing the previous record's uncommitted writes. `advance-clock!` then
    `ingest-sample!` in the same microbatch is therefore judged against the advanced clock.
  - Processing concerns: (1) clock advance + expiry, (2) sample admission + counters + raw
    store + bucket folds. Both are the same per-record latency class (a handful of seeks),
    so one topology. `<<subsource` on the record class dispatches the two concerns.
  - Source: `(source> *series-events :> %microbatch)` `(%microbatch :> *event)`
    `(<<subsource *event (case> AdvanceClock :> {:keys [*series *clock]}) ... (case> IngestSample :> {:keys [*series *timestamp *value]}) ...)`.
  - Retry: a microbatch retry resets and reapplies all `$$series` writes exactly once.
  - Worker restart: `$$series` is durable; the topology resumes from its committed offset.
  - Malformed input: the spec guarantees valid input; the only decision logic is integer
    comparisons on longs, so nothing can throw deterministically and stall the topology.

- No stream topology. No internal depots. No tick depots (expiry is driven by the
  application's explicit clock, not wall time).

## Query Topologies

- **`query-raw`** `[*series *start *end :> *result]`
  - Example 1 — series at clock 1000 with 200 retained samples in `[700, 1000]`, query
    `[600, 1100)`: window `[701, 1001)` → 2 reads (clock, range), 2 meaningful, 200 iterated.
  - Example 2 — same series, query `[0, 500)`: window `[701, 500)` empty → 1 read (clock),
    1 meaningful, second read skipped.
  - Example 3 — unknown series, query `[0, 10^12)`: clock `0`, window `[0, 1)` → 2 reads,
    range read on empty structure returns `{}` (cannot be known without reading).
  - Example 4 — unknown series, query `[5, 10^12)`: window `[5, 1)` empty → 1 read.
  - Fixed or variable: **variable** (1 or 2). Handled dynamically by `<<if (< *lo *hi)`
    around the range read; both branches bind `*result`.

- **`query-rollup`** `[*series *width *start *end :> *result]`
  - Example 1 — clock 1020, `w=60`, `[600, 1020)`: window `[600, 961)` → 2 reads, 2
    meaningful, iterates the non-empty buckets in it (2 in the worked example).
  - Example 2 — clock 1000, `w=3600`, `[0, 3600)`: `hi = -2599` → 1 read, second skipped.
  - Example 3 — clock 10^6, `w=60`, `[0, 10^12)`: window `[992801, 999941)` → 2 reads,
    at most 119 iterated entries regardless of the 10^12 bound.
  - Fixed or variable: **variable** (1 or 2). Same dynamic `<<if`.

Neither topology needs an aggregator: exactly one row reaches `|origin`.

## Partitioning efficiency

**Optimal placement.** Every read and every write addresses exactly one series and needs
all of that series' data (clock, counters, raw, both bucket maps) together: `f(series) →
one task`. Balanced load wants series spread evenly: series cardinality is large
(multi-tenant × metrics × label sets), and no series can hold a disproportionate share of
storage — live footprint per series is capped by the windows (≤ 300 raw, ≤ 121 + 3
buckets). Ingest rate per series is bounded by distinct admissible timestamps (≤ 300 per
300 clock units accepted; rejected samples touch 1–2 seeks). So `f = hash(series) mod N`
is exactly `|hash` / `(hash-by :series)`. `|all` would multiply the dominant write by N;
`|direct` has nothing to add because there is no skew to correct.

Dominant read: `query-raw` (same shape as `query-rollup`). Seeks are totals across the
cluster; every operation touches one task.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| typical dashboard range inside the retention window (~60 samples) | 0.60 | 2 | 60 |
| full-window read (300 samples) | 0.10 | 2 | 300 |
| window non-empty but no samples stored in it (sparse series) | 0.15 | 2 | 0 |
| range entirely outside window / future / unknown series (skipped 2nd read) | 0.15 | 1 | 0 |
Weighted seeks = 0.60·2 + 0.10·2 + 0.15·2 + 0.15·1 = **1.85**   |   Weighted iterator reads = 36 + 30 + 0 + 0 = **66**

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| typical range (~60 samples) | 0.60 | 2 | 60 |
| full-window read (300) | 0.10 | 2 | 300 |
| non-empty window, no data | 0.15 | 2 | 0 |
| skipped second read | 0.15 | 1 | 0 |
Weighted seeks = **1.85**   |   Weighted iterator reads = **66**

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| typical range (~60 samples) | 0.60 | 2 | 60 |
| full-window read (300) | 0.10 | 2 | 300 |
| non-empty window, no data | 0.15 | 2 | 0 |
| skipped second read | 0.15 | 1 | 0 |
Weighted seeks = **1.85**   |   Weighted iterator reads = **66**

Flat across N: per-operation disk work is independent of cluster size; aggregate
throughput scales with tasks. The writes are likewise single-task (`advance-clock!`: 1
seek no-op, 4 seeks + k iterations real; `ingest-sample!`: 2–5 seeks), independent of N.

## Design Decisions

- **Subindexing**: `:raw` (≤ 300 live entries, range-scanned and point-looked-up) and
  `:buckets-60` (≤ 121 live), both with `{:subindex-options {:track-size? false}}` because
  nothing queries `count` and tracking adds a read per write. Not subindexed: the
  per-series record (fixed keys) and `:buckets-3600` (≤ 3 live entries, stored as one small
  value and filtered in memory).
- **Colocation**: depot `(hash-by :series)` = PState key = query leading `(|hash *series)`.
  No partitioner inside the ETL; each record is one synchronous, atomic segment on one task.
- **Eager reclamation in `advance-clock!`**: physically deletes expired raw samples and
  buckets in the same event that advances the clock. Storage per series stays bounded; the
  queries additionally clamp their ranges to the live window, so correctness never depends
  on reclamation timing.
- **Range arithmetic** is done in plain `defn`s (`raw-window`, `rollup-window`,
  `bucket-start`) returning `[lo hi]`; all comparisons are on longs, never iterate over
  the numeric distance between clocks or bounds.
- **Client normalizes results**: `get-series-info` merges zero defaults; the query
  topologies return vectors ready for the protocol (`[]` when empty).
- **Synchronization (`wait-for-processing!`)**: the client tracks appends and calls
  `(rtest/wait-for-microbatch-processed-count ipc module-name "metrics" n)`. The count is
  module-cumulative, so `n` must be the total appended by **all** clients in this process,
  not this client's own count — otherwise a second client whose own count is smaller than
  the already-processed total would return before its writes are applied. Design: a
  registry `(atom {})` keyed by the IPC instance, created inside `create-module` and
  captured by the returned `:wrap-client` (full-spec review moved it out of a process-wide
  `defonce`, which strongly retained every closed IPC for the JVM's lifetime); every
  `wrap-client` on the same IPC increments the same counter before each append and
  waits for that shared total. This is a transient synchronization counter (explicitly
  permitted by the README); it never influences a read result. Read results always come
  from `$$series`, so a client created after writes were made sees them with no local
  state at all.

## State primitive selection

- `$$series` (PState): source of truth for clock, counters, retained raw samples, bucket
  aggregates. Per-source-event write volume: `advance-clock!` O(1 + expired entries on
  that series) — bounded by the windows (≤ 424 deletes); `ingest-sample!` O(1): at most
  1 raw insert + 1 bucket-60 entry update + 1 rewrite of the ≤ 3-entry 3600 map + 1
  counter. Durable, replicated.
- TaskGlobals: none. Nothing needs in-memory caching; every read is 1–2 seeks.
- Client-side transient: the shared append counter used only by `wait-for-processing!`.
  Non-durable by design; losing it cannot affect any read.
- External systems: none.

## Resource usage analysis

### Disk usage (PStates)
- `$$series` record per series: key (`SeriesKey` with tenant, metric, sorted labels —
  typically 50–200 B) + 5 longs (40 B) + 2 subindex handles + the ≤ 3-entry 3600 map
  (~200 B) ≈ 300–450 B.
- `:raw` per series: ≤ 300 live entries × (8 B key + 8 B value + ~30 B RocksDB/subindex
  overhead) ≈ 14 KB worst case.
- `:buckets-60`: ≤ 121 entries × (8 B key + ~60 B serialized 4-field map + ~30 B) ≈ 12 KB.
  `:buckets-3600`: ≤ 3 entries ≈ 200 B, inside the record.
- Total live per series ≈ 26–27 KB worst case, bounded by the windows because expiry
  physically deletes. Total per task = (#series / N) × ≤ 27 KB. Growth is in series
  count, not in time.
- Depot `*series-events`: one record per write (~100–250 B with the series key); grows
  without bound unless trimmed (`depot.max.entries.per.partition`), which is an
  operational choice — topologies never replay it after restart.

### Memory usage (TaskGlobals)
None.

### Minimization
- No duplication across storage locations: the raw value and the bucket aggregates are
  different data (the spec requires both, and aggregates must outlive raw samples).
- Bucket values could be packed into a `[count sum min max]` vector (saves the keyword
  overhead, ~30 B/bucket) at the cost of a conversion on every read; kept as a fixed-keys
  map because the protocol returns exactly that map shape. ≤ 121 buckets/series makes
  the difference immaterial.
- Counters/clock are primitives (`Long`). Nothing in memory to shrink.

## Design difficulty log

- **Eager vs lazy reclamation.** Lazy (never delete, clamp queries) is strictly less code
  and 3 fewer seeks per real advance, and every query stays correct because of the clamp.
  It lost on storage: a long-lived series would accumulate every sample ever accepted.
  The README's advance budget ("work proportional to the data it expires on that series")
  reads as permission for exactly this work, so eager won. Not a close call once storage
  was counted.
- **How to delete a range of a subindexed map.** I could not find documentation that
  transforming the submap navigated by `sorted-map-range-to` (e.g. `MAP-VALS NONE>` or
  `(termval {})`) writes deletions back to a subindexed map. I chose the composition of
  documented primitives instead: select the expired keys as one vector via
  `(sorted-map-range-to cutoff) (subselect MAP-KEYS)`, then `keypath` + `NONE>` per key
  (delete-only, no read). Same I/O (1 seek + k iterations + k buffered deletes) and no
  mutation during iteration. The build phase may verify the single-transform form at the
  REPL and swap it in; the plan does not depend on it.
- **Subindexing the 3600-width map.** It holds at most 3 live entries, so subindexing is
  overhead by the guidelines. The first draft kept one `map-schema` for both widths behind
  a width-keyed outer map (uniform `(keypath *series :buckets *width)` path) at the cost of
  ~1 extra seek per accepted sample plus an outer-map hop. Plan validation rejected that
  under the skill rule "never trade I/O efficiency for code simplicity": the 3600 map is
  now a plain fixed field (`:buckets-3600`) handled by one `<<cond` branch in query and
  expiry, and size tracking is off on both subindexed maps. Ingest (accepted) is 5 seeks.
- **Stream vs microbatch.** Not contested once retry safety was considered: counters and
  folds are non-idempotent and the spec asks for no ack coordination or millisecond
  visibility. Per-series ordering, which is the property that might tempt a stream
  choice, holds equally in microbatch because the topology has no partitioner after the
  source.
- **Series key representation.** A record with a canonicalized (sorted) label map vs. a
  canonical string. The string needed escaping-safe printing and is less readable; the
  record with sorted labels gives deterministic bytes for equal series, which matters
  because top-level PState keys are matched on serialized form. Forced once that was
  noticed.
- **Cross-client synchronization.** The harness docstring's per-client counter is wrong
  for a second client that writes after another client's writes were processed (it would
  return early). A process-wide counter shared by all clients of the same IPC fixes it
  with the same API. An offset-based wait (`foreign-depot-partition-info` end offsets vs
  `microbatch-depot-info` processed offsets) would be process-independent but its return
  shape is undocumented here; noted as a hardening option, not required by the spec.
