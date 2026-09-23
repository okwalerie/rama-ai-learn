# Plan

<!-- Phase 1 artifact for hld-ad-click-aggregation, subsystem `click-accounting`
(the only subsystem in DECOMPOSITION.json, so this is PLAN.md). Sole
authorities: README.md, src/hld_ad_click_aggregation/protocol.clj,
IMPLICIT_SPEC.md, lib/harness Synchronizable docstring. No implementation
code here — design only. -->

## Reads

| Read | Method | Path / topology | Partition |
|---|---|---|---|
| `get-watermark c` | `foreign-select-one` | `[(keypath c :watermark) (nil->val 0)]` on `$$campaigns` | task of `hash(c)` (top-level key routing) |
| `get-request c rid` | `foreign-select-one` | `[(keypath c :requests rid)]` on `$$campaigns` → `nil` or the 10-key audit map | `hash(c)` |
| `get-window c w` | query topology `windows-in-range` | invoked as `(c, w, w + 60)`; client returns `(first result)` (`nil` when `[]`) | `hash(c)` |
| `get-windows c s e` | query topology `windows-in-range` | `(c, s, e)` → vector ascending by `:window-start` | `hash(c)` |

Why `get-window` shares the query topology: it needs two reads on the same
partition (totals + breakdown), so it must be a query topology; a one-window
range read costs exactly the same seeks as a dedicated topology (see Query
Topologies), so a second topology would add a mechanism with no gain.

All reads touch exactly one task, the campaign's own, and read only that
campaign's key — satisfies README "Reads of one campaign must not read state
belonging to any other campaign" and IMPLICIT_SPEC "Campaign isolation".

## Writes

| Write | Depot | Record |
|---|---|---|
| `advance-watermark! c w` | `*campaign-events` | `(->AdvanceWatermark c w)` |
| `record-click! c rid ts geo device spend valid? fraud?` | `*campaign-events` | `(->RecordClick c rid ts geo device spend valid? fraud?)` |

Both go to the same depot, partitioned `(hash-by :campaign-id)`, because the
README requires "Writes addressed to the same campaign must take effect in
the order the client invoked them (an `advance-watermark!` followed by a
`record-click!` on the same campaign is judged against the advanced
watermark)". One depot partition per campaign gives that order for free;
two depots would not (independent partitions, no relative order).

Latency: all writes are asynchronous and only need to be visible after
`wait-for-processing!` (README "Write ordering and synchronization").
Throughput: `record-click!` dominates and is skewed to hot campaigns
(IMPLICIT_SPEC "Write skew"); stale `advance-watermark!` calls are common
and must be cheap.

## PState Design

One PState, `$$campaigns`, keyed by `campaign-id`, hash-partitioned. Every
piece of state (watermark, audit records, windows) shares key type
(`String` campaign-id) and partitioner, so per the Phase 1 rule it is one
PState with a fixed-keys value, not three.

```clojure
;; Records used as depot events and as a typed breakdown key
(defrecord AdvanceWatermark [campaign-id watermark])
(defrecord RecordClick [campaign-id request-id timestamp geo device spend valid? fraud?])
(defrecord GeoDevice [geo device])

;; Shared sub-schemas
counters-schema  = (fixed-keys-schema {:clicks Long :billed-clicks Long
                                       :invalid-clicks Long :fraud-clicks Long
                                       :billed-spend Long})
audit-schema     = (fixed-keys-schema {:request-id String :timestamp Long
                                       :window-start Long :geo String :device String
                                       :spend Long :valid? Boolean :fraud? Boolean
                                       :disposition clojure.lang.Keyword
                                       :watermark Long})

$$campaigns:
{String                                     ; campaign-id
 (fixed-keys-schema
   {:watermark Long                         ; absent ⇒ 0
    :requests  (map-schema String audit-schema
                 {:subindex-options {:track-size? false}})
    :windows   (map-schema Long             ; window-start, sorted numerically
                 (fixed-keys-schema
                   {:totals    counters-schema
                    :breakdown (map-schema GeoDevice counters-schema
                                 {:subindex-options {:track-size? false}})})
                 {:subindex-options {:track-size? false}})})}
```

Design rationale per read:

- **Watermark**: inline `Long` field on the campaign value. One top-level
  read. Absent ⇒ `nil->val 0`, which also makes "never-written" and
  "advanced to 0" indistinguishable, as IMPLICIT_SPEC requires.
- **Audit record**: `:requests` is a subindexed map, so `get-request` and the
  replay check are one point seek regardless of how many request-ids the
  campaign has (README: "must not read anything proportional to the number of
  other requests"). The record is written whole with `termval` (no read) and
  never touched again, so a fixed-keys map with all ten keys is exact.
  Size tracking off: nothing ever asks how many requests exist, and tracking
  would add a read per click.
- **Windows**: `:windows` is a subindexed map keyed by `window-start` (Long,
  sorts numerically), so `get-windows` is one `sorted-map-range` seek that
  starts at `start` and iterates only populated windows inside `[start,
  end)` — never empty 60-unit slots, never windows outside the range
  (IMPLICIT_SPEC `get-windows` work bound). A window key exists iff a counted
  click was applied to it, so "exists ⇒ ≥ 1 counted click" holds structurally.
- **Breakdown**: subindexed map inside each window. Considered alternatives:

  - Option A (chosen): `:breakdown` subindexed, keyed by `GeoDevice` record.
    Write per click: one point read-modify-write of one entry (constant).
    `get-window`: 1 seek for totals + 1 seek + b iterations for the whole
    breakdown of b entries.
  - Option B: `:breakdown` as a plain (non-subindexed) map inside the window
    value. Write per click: read + rewrite the whole breakdown (b entries),
    i.e. cost proportional to window breakdown size — violates IMPLICIT_SPEC
    "the breakdown update must … be constant-cost with respect to … window
    size", and b is "unbounded in principle" with no enforced cap, so the
    subindex rule also forbids it. Read would save 1 seek per window.
  - Option C: flatten breakdowns into a separate subindexed map keyed by
    `[window-start GeoDevice]`. Same seeks as A on write; on read it would
    let one range scan cover many windows' breakdowns, but sort order of a
    composite record key under serialized-byte ordering is not documented,
    so range correctness cannot be guaranteed. Rejected on that risk; gain
    would be at most k seeks per `get-windows` over k windows.

  Chosen: A. Key type is a `defrecord` (a concrete class) rather than a
  Clojure vector or `Object`, so the schema stays fully typed; the query
  converts keys to `[geo device]` on output. Sort order of the breakdown is
  irrelevant (always read whole).
- **Counters**: fixed-keys of five `Long`s, updated via `+compound` with
  `aggs/+sum` of precomputed 0/1 deltas, which auto-initializes absent
  fields to 0 so every counters map always has all five keys
  (IMPLICIT_SPEC "every key is present (zero, never absent)").

Everything is durable PState (README "All authoritative business state must
be durable Rama state"). No TaskGlobals.

## Depots

- `*campaign-events`: `(hash-by :campaign-id)`, event types
  `[AdvanceWatermark RecordClick]`. Client-appended. One depot because the
  two event kinds are order-dependent on the same entity (campaign).

## Topologies and PStates

- **`click-accounting`: microbatch** — default microbatch. No concern needs
  stream: writes are asynchronous with visibility only required after
  `wait-for-processing!`, and no write returns a computed value. Microbatch
  is additionally required, not merely default: the counter increments are
  non-idempotent, and IMPLICIT_SPEC demands "Exactly-once effect per write
  under retries" with audit + totals + breakdown "applied together" —
  microbatch gives exactly-once and per-attempt atomicity by construction.
  Owns `$$campaigns` (schema above).

  Processing, all on the campaign's task (no partitioner hops; depot partition
  order = application order):

  `(source> *campaign-events :> %mb)`, `(%mb :> *event)`, `<<subsource *event`:

  - `AdvanceWatermark {c w}`: read `[(keypath c :watermark) (nil->val 0)]`
    → `*cur`; `<<if (> w *cur)` write `[(keypath c :watermark) (termval w)]`.
    Stale/equal advance: one read, no write. Constant cost; touches nothing
    else (closure stores nothing — IMPLICIT_SPEC "Advancing never creates,
    alters, or removes a window or an audit record"). Fresh campaign with
    `w = 0`: no write, campaign stays absent.
  - `RecordClick {c rid ts geo device spend valid? fraud?}`:
    1. Replay check: `[(keypath c :requests rid)]` → `*existing`. If non-nil,
       stop (no effect whatsoever). Decided before lateness, as IMPLICIT_SPEC
       "Idempotence check precedes disposition" requires.
    2. `*wm` = `[(keypath c :watermark) (nil->val 0)]`.
    3. `*ws = ts - (mod ts 60)`; disposition by a pure Clojure fn in order:
       `(>= *wm (+ *ws 180))` → `:late`; `fraud?` → `:fraud`; `(not valid?)`
       → `:invalid`; else `:billed`.
    4. Audit write: `[(keypath c :requests rid) (termval {…10 keys… :disposition *disp :watermark *wm})]` — write-only, no read.
    5. Counter update. The microbatch body is a batch block, so the
       aggregator must be the single tail after all branches unify — it
       must NOT sit inside `<<if` or inside a `<<subsource` case. Every
       `<<subsource` case therefore binds the same output vars:
       `*counted?`, `*ws`, `*gd` `(->GeoDevice geo device)`, and deltas
       `*d-billed *d-invalid *d-fraud` ∈ {0,1}, `*d-spend = (if billed
       spend 0)`. `AdvanceWatermark` and replayed clicks bind
       `*counted? false` (placeholders for the rest); a fresh click binds
       `*counted? (not= :late *disp)`. After the `<<subsource` closes:
       `(filter> *counted?)` then the one tail aggregator
       `(+compound $$campaigns {c {:windows {*ws {:totals {:clicks (aggs/+sum 1) :billed-clicks (aggs/+sum *d-billed) :invalid-clicks (aggs/+sum *d-invalid) :fraud-clicks (aggs/+sum *d-fraud) :billed-spend (aggs/+sum *d-spend)} :breakdown {*gd {…same five leaves…}}}}}})`.
       `:late` clicks and replays are filtered out, so a late-only window
       is never created (`get-window` stays `nil`). Steps 1–4 (replay
       probe, watermark read/write, audit `termval`) stay in pre-agg and
       execute depth-first per record in depot append order, so the
       watermark write of an earlier record is visible to a later one in
       the same microbatch. `+compound` aggregates in memory per
       microbatch, so m clicks into one window/pair in one batch cost one
       read-modify-write per distinct key, not m.

  Per-click cost: 1 seek (request probe) + 1 read (campaign value for
  watermark) + 0 (audit termval) + 1 read-modify-write (totals) + 1
  read-modify-write (breakdown entry) ≈ 4 RocksDB reads, independent of
  campaign size, window size, or task count.

  Only one topology: the two event kinds must be applied in one serial order
  per campaign against shared state, so they must be in the same topology
  (and only one topology can write `$$campaigns`).

## Query Topologies

- **`windows-in-range` `[*c *s *e :> *res]`**
  1. `(|hash *c)` — leading built-in partitioner, evaluated client-side.
  2. `(local-select> [(keypath *c :windows) (sorted-map-range *s *e) ALL (collect-one FIRST) LAST :totals] $$campaigns {:allow-yield? true} :> [*w *totals])`
     — one seek, iterates only populated windows in `[s, e)`. Unknown
     campaign or empty range ⇒ `sorted-map-range` on `nil` ⇒ 0 emits.
     (Implementation must confirm in the REPL that `:totals` navigates
     inside a subindexed-map value during range iteration; if it does not,
     fall back to `MAP-KEYS` + one point read `[(keypath *c :windows *w :totals)]`
     per window — +k seeks, still within the spec bound.)
  3. Per emitted window: `(local-select> [(keypath *c :windows *w :breakdown) (subselect ALL)] $$campaigns {:allow-yield? true} :> *entries)`
     — one seek + b iterations; never empty because a window exists only
     with ≥ 1 counted click (so every read is meaningful).
  4. `(build-window *w *totals *entries :> *win)` — pure fn producing
     `{:window-start w :totals … :breakdown {[geo device] counters …}}`.
  5. `(|origin)`, `(aggs/+vec-agg *win :> *rows)`, post-agg
     `(sort-by :window-start *rows :> *res)` (cost ∝ output size; keeps
     ascending order independent of emit order). Zero rows ⇒ `[]`.

  Input example 1: campaign with windows 60 and 4980, range `0 6000` →
  1 range seek + 2 breakdown seeks = 3 total reads, 3 meaningful.
  Input example 2: same campaign, range `0 120` → 1 + 1 = 2 reads, 2 meaningful.
  Input example 3: unknown campaign, any range → 1 read, 1 meaningful
  (it decides the empty result), 0 breakdown reads.
  Fixed or variable: **variable** (depends on the number of populated
  windows in range). Dynamic approach: `local-select>` emits one row per
  populated window; the breakdown read hangs off each emitted row, so exactly
  k breakdown reads are issued for k windows and none otherwise; aggregated
  with `aggs/+vec-agg`.

  Latency: one client→task hop; seeks on the critical path = 1 + k, all on
  one task. No client↔cluster roundtrips proportional to windows returned
  (IMPLICIT_SPEC `get-windows` latency).

## Partitioning efficiency

**Optimal placement.** Every read and every write is scoped to one campaign
and reads only that campaign's state; per-campaign write order is a
correctness requirement. So `f(campaign-id) → exactly one task` is the
placement that makes every operation a single local read on one task with
zero hops, and it keeps ordering by construction. Keyspace = campaigns, which
is large relative to task counts; a hot campaign's clicks are serialized by
the spec anyway (IMPLICIT_SPEC: "the per-click work bound is also the bound
on a single campaign's sustainable click rate"), so hash variance and
per-key skew are inherent to the required semantics, not to this design.
Implement with `(hash-by :campaign-id)` on the depot and `(|hash *c)` in
the query — no stored placement state can reduce a single-task, single-hop
operation further, so no `|direct` scheme is cheaper.

Considered and rejected on constructed cost: splitting a campaign across
tasks (audit/dedup by `hash([c rid])`, windows by `hash([c ws])`,
watermark on `hash(c)`). Per click: 3 hops and 3 tasks' seeks instead of 1
task, and the watermark-vs-click ordering would need an extra sequencing
mechanism (the ordering the spec requires is lost once a campaign's events
leave one partition). Read cost of `get-windows` becomes one seek per window
on scattered tasks (k seeks fanning to up to k tasks) instead of 1 range seek.
Strictly worse on both latency and throughput.

Dominant read: `windows-in-range` (serves `get-window` and `get-windows`).
Assumed breakdown size b = 50 entries per window; "dashboard day" = 24
populated windows.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| Single-window `get-window`, window present | 0.5 | 2 | 51 |
| Range covering ~24 populated windows | 0.3 | 25 | 1224 |
| Sparse/wide range, 2 populated windows (step 11) | 0.1 | 3 | 102 |
| Absent window / unknown campaign / empty range | 0.1 | 1 | 0 |
Weighted seeks = 0.5·2 + 0.3·25 + 0.1·3 + 0.1·1 = 9.9   |   Weighted iterator reads = 25.5 + 367.2 + 10.2 + 0 = 402.9

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| Single-window `get-window`, window present | 0.5 | 2 | 51 |
| Range covering ~24 populated windows | 0.3 | 25 | 1224 |
| Sparse/wide range, 2 populated windows | 0.1 | 3 | 102 |
| Absent window / unknown campaign / empty range | 0.1 | 1 | 0 |
Weighted seeks = 9.9   |   Weighted iterator reads = 402.9

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| Single-window `get-window`, window present | 0.5 | 2 | 51 |
| Range covering ~24 populated windows | 0.3 | 25 | 1224 |
| Sparse/wide range, 2 populated windows | 0.1 | 3 | 102 |
| Absent window / unknown campaign / empty range | 0.1 | 1 | 0 |
Weighted seeks = 9.9   |   Weighted iterator reads = 402.9

Flat across N: every operation is dispatched to exactly one task. The same
holds for `record-click!` (≈ 4 reads on one task at any N) and for the point
reads (1 seek). Results are identical at 2 or 4 tasks because placement only
decides *which* task holds a campaign, never how its state is computed.

## Design Decisions

- **Subindexing**: `:requests` (unbounded request-ids per campaign),
  `:windows` (unbounded windows per campaign, needs sorted range),
  `:breakdown` (unbounded `[geo device]` pairs per window, no enforced cap).
  All with `track-size? false`: no operation needs a count, and size
  tracking would add a read on every click. Not subindexed: the campaign
  value itself (fixed keys), counters (fixed 5 fields), audit record (fixed
  10 fields).
- **Colocation**: depot `hash-by :campaign-id` = PState top-level key =
  query leading `|hash *c`. Zero partitioner hops anywhere.
- **Ordering**: one depot, one topology, no hops ⇒ per-campaign application
  order equals per-partition append order equals client invocation order
  (and a single serial order across clients).
- **Retry / exactly-once**: microbatch exactly-once for all PState writes;
  audit + totals + breakdown for one click are in one attempt on one task,
  so no reader ever sees one without the others. Upstream replays (same
  `[campaign request-id]` appended twice) are a business rule, handled by
  the `:requests` existence check, not by Rama retry semantics.
- **Never delete**: no `NONE>` anywhere; nothing is pruned.
- **Malformed input**: README guarantees valid input; the pure disposition
  and delta fns take only integers/booleans/strings, so no record can throw
  and stall the microbatch.
- **Synchronization (`Synchronizable`)**: `wrap-client` keeps a cumulative
  append counter and calls
  `(rtest/wait-for-microbatch-processed-count ipc module-name "click-accounting" @count)`.
  The counter is **per cluster, not per client**: a process-wide registry
  `(defonce cluster-append-counts (atom {}))` keyed by the IPC instance;
  every client wrapping the same IPC increments and reads the same counter.
  Reason: the waited-on quantity is the topology's cumulative processed
  count for that cluster, so a per-client counter would let a second client
  return early on the first client's already-processed records
  (IMPLICIT_SPEC `wait-for-processing!` edge case). This is client-local
  bookkeeping only; no read result depends on it. Alternative considered:
  durable sync markers (extra depot record per wait, a `$$sync` PState, and
  a client poll loop) — correct too, but adds a depot source, a PState, and
  a network poll per wait for no business-state benefit; rejected on that
  computed cost.

## State primitive selection

- `$$campaigns` (PState): per `record-click!` writes 1 audit entry + 2
  counter entries (bounded constant); per `advance-watermark!` ≤ 1 field.
  Durable. It is the source of truth for every read.
- `cluster-append-counts` (client-side JVM atom): non-durable, supports
  only `wait-for-processing!`; lost on client restart, which only affects
  the ability to wait for writes issued before the restart — never read
  results. Explicitly allowed by README ("transient synchronization
  counters are allowed").
- No TaskGlobals, no external systems.

## Resource usage analysis

### Disk usage (PStates)
- `*campaign-events` record: click ≈ 8 fields ≈ 120 B; advance ≈ 40 B.
  Depot retention is Rama's default (not trimmed by this design).
- `$$campaigns` per campaign: fixed value with `:watermark` ≈ 40 B.
- `:requests` entry: key (request-id ~20 B) + 10-field record ≈ 150 B ⇒ ~170 B
  per distinct click, forever. 1 M clicks/day ⇒ ~170 MB/day cluster-wide,
  `/N` per task.
- `:windows` entry: key 8 B + totals (5 longs ≈ 60 B) ≈ 70 B per populated
  window; `:breakdown` entry: key (`GeoDevice`, ~30 B) + counters ≈ 90 B.
  For 24 windows/day × 50 pairs ⇒ ~110 KB/campaign/day.
- Growth is O(clicks) dominated by audit records, which the spec mandates
  ("Audit records for every first arrival … persist forever").

### Memory usage (TaskGlobals)
None. Only the client-side counter (one long per cluster).

### Minimization
- No data is duplicated across storage locations: totals are a separate sum
  from the breakdown (both required outputs; recomputing totals from the
  breakdown on read would cost b iterations and lose the "one seek for
  totals" property, and the spec requires totals to be consistent with the
  breakdown, which the single-attempt update guarantees).
- Audit record stores exactly the ten protocol fields; counters exactly the
  five protocol fields, all `Long` primitives.
- Size tracking disabled on all three subindexed maps.

## Design difficulty log

- **Breakdown storage.** I first wanted the breakdown as a plain map inside
  the window value (one seek fewer per window on read). Costing the write
  killed it: each click would rewrite the whole breakdown, so hot windows
  with thousands of pairs would violate the constant-cost breakdown update.
  The subindexed nested map was then forced. The remaining sub-question was
  the key type: a `[geo device]` vector needs a class in `map-schema`, and
  I could not confirm from the references that a vector literal is accepted
  as a key class, so I chose a `GeoDevice` defrecord and convert on output.
  A close call on ergonomics, not on cost.
- **`get-windows` read shape.** 1 + k seeks (range scan yields totals inline,
  one breakdown seek per window) versus 1 + 2k (range scan yields keys, then
  point-read totals and breakdown). The inline form depends on `:totals`
  being navigable from a range-iterated subindexed-map value, which the
  references describe but I could not execute here; I committed to the
  cheaper form and recorded the fallback. Neither form changes the
  asymptotic bound the spec requires.
- **Second-client synchronization.** The harness docstring suggests a
  per-client counter, but IMPLICIT_SPEC calls out that a second client must
  not return early. I weighed a durable marker mechanism (depot + PState +
  poll) against making the counter per-cluster on the client side. The
  per-cluster counter costs nothing in the module and is exactly the
  quantity the wait API measures, so it won once framed that way.
- **Everything else was forced** once the spec was read: one depot and one
  topology by the per-campaign ordering requirement; microbatch by
  exactly-once counters; hash-by-campaign by campaign isolation plus the
  serialized-per-campaign write bound; one PState by the shared-key rule;
  one query topology because a one-window range costs the same as a
  dedicated point query.
