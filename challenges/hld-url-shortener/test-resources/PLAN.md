# Plan

<!-- Phase 1 artifact for hld-url-shortener (subsystem url-shortener-core, whole spec). Design only; no code. -->

Owner key: `alias`. Every write and every read is a point operation on one alias, so all
state for an alias lives on task `hash(alias)` and every operation is one hop.

## Reads

| Read | Method | Path (PState `$$links`) | Partition | Cost |
|---|---|---|---|---|
| `resolve-alias alias now` | `foreign-select-one` | `[(keypath alias :link)]` → nil ⇒ `:missing`; else status derived in wrapper from `:deleted?`, `:blocked?`, `expires-at` vs `now` (pure) | `hash(alias)` | 1 seek |
| `get-click-count alias` | `foreign-select-one` | `[(keypath alias :link :clicks) (nil->val 0)]` | `hash(alias)` | 1 seek |
| `get-create-outcome alias rid` | `foreign-select-one` | `[(keypath alias :outcomes rid)]` → nil / `:created` / `:rejected` (wrapper expands `:rejected` to `{:outcome :rejected :reason :alias-taken}`) | `hash(alias)` | 1 seek (subindexed point lookup) |

No read needs two PStates or two partitions, so there are no query topologies. Reads never
wait and never mutate; the wrapper holds no business state (status derivation is a pure
function of the returned map and the caller's `now`).

## Writes

All writes append to one depot `*alias-events`, partitioned `(hash-by :alias)`:

| Write | Record | Processing (on `hash(alias)`) |
|---|---|---|
| `create-link!` | `CreateLink{alias request-id target-url expires-at}` | read `[alias :outcomes request-id]` (1 seek) and `[alias :link]` (1 seek). Recorded ⇒ no-op. Link exists ⇒ `outcomes[rid] := :rejected`. Else `:link := {target-url expires-at deleted? false blocked? false clicks 0}`, `outcomes[rid] := :created`. |
| `delete-link!` | `DeleteLink{alias}` | read `:link`; if present `:deleted? := true`. |
| `block-link!` / `unblock-link!` | `BlockLink{alias}` / `UnblockLink{alias}` | read `:link`; if present `:blocked? := true/false`. |
| `record-click!` | `Click{alias click-id}` | read `:link` (1 seek); absent ⇒ drop (no trace). Read `[(keypath alias :click-ids) (subselect (set-elem click-id))]` (1 seek); non-empty ⇒ no-op. Else `[(keypath alias :click-ids) NONE-ELEM (termval click-id)]` and `:clicks := clicks + 1` (`termval` of the value already read). |

Every write is fixed work (≤2 seeks + ≤2 writes) independent of the alias's click or
request history — the `:click-ids` set and `:outcomes` map are subindexed point lookups.

## PState Design

One PState, because every piece of state shares key `alias` and partitioner `hash(alias)`:

```clojure
(declare-pstate mb $$links
  {String (fixed-keys-schema
            {:link      (fixed-keys-schema {:target-url String
                                            :expires-at Long        ; nil = never expires
                                            :deleted?   Boolean
                                            :blocked?   Boolean
                                            :clicks     Long})
             :outcomes  (map-schema String Keyword {:subindex? true})   ; request-id -> :created | :rejected
             :click-ids (set-schema String {:subindex? true})})})       ; counted click-ids
```

`:link` groups every scalar the hot reads need so `[alias :link]` is one seek returning a
plain map; the two unbounded collections (≤1,000 request-ids, ≤1,000,000 click-ids per
alias) are subindexed so they are never loaded whole.

Alternatives costed:
- **Option A (chosen)**: per-alias dedup set colocated with the counter. `record-click!` =
  2 seeks, 1 hop. `get-click-count` = 1 seek.
- **Option B**: global `$$clicks {click-id ...}` hashed by click-id for dedup, counter on the
  alias task. `record-click!` = 2 hops (dedup task then alias task), 2 seeks, and the count
  and dedup writes are on different partitions. Same read cost, one extra hop per
  observation at 100,000/s. Rejected on total cost.
- **Option C**: no dedup set, trust click-ids. Violates "deduplicated per (alias, click-id)".

## Depots

- `*alias-events`: `(hash-by :alias)`; record types `CreateLink`, `DeleteLink`, `BlockLink`,
  `UnblockLink`, `Click` (five `defrecord`s, each with an `:alias` field).
  One depot so that sequential writes from one client to one alias land in one depot
  partition in invocation order and are processed in that order (spec ordering rule).

## Topologies and PStates

- `core`: **microbatch** (default microbatch; no write needs millisecond visibility or an
  ack return — every `!` method returns nil and outcomes are read after
  `wait-for-processing!`). Owns `$$links`.
  - Concerns: create/reject recording, lifecycle flags, click dedup + count. None needs
    stream semantics. The counter increment and the outcome record are non-idempotent
    writes, which microbatch replays exactly once.
  - Same-alias records in one microbatch are processed sequentially on the alias's task in
    depot order and reads see the batch's own prior writes, so of N concurrent creates
    exactly the first is `:created`.

No stream topology, no internal depot, no tick depot (time is caller-supplied).

## Query Topologies

None. Every read is a single `foreign-select-one` against one PState on one partition.

## Partitioning efficiency

Optimal placement: `f(alias) → hash(alias) mod N`. The keyspace is 10M aliases (many keys
per task, negligible hash variance); no key holds a disproportionate share of storage
(≤2.2 KB of scalars + its own click set). A viral alias concentrates thousands of
resolves/s and clicks/s on one task, which is within a single task's capacity; replicating
hot links with `|all` was considered and rejected: it does not reduce aggregate seeks
(1 per resolve either way) and multiplies the 350/s create writes by N.

Dominant read: `resolve-alias` (100,000/s). Categories: existing alias, missing alias.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| existing alias | 0.9 | 1 | 0 |
| missing alias  | 0.1 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| existing alias | 0.9 | 1 | 0 |
| missing alias  | 0.1 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| existing alias | 0.9 | 1 | 0 |
| missing alias  | 0.1 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

Flat in N. `get-click-count` and `get-create-outcome` have the same profile (1 seek).

## Design Decisions

- **Subindexing**: `:outcomes` (≤1,000/alias, >100) and `:click-ids` (≤1,000,000/alias)
  are subindexed. `:link` is a fixed five-field record, inline.
- **Colocation**: depot `(hash-by :alias)` matches the PState key, so processing never
  repartitions. Every write is a single-task transaction.
- **Ordering**: one depot partition per alias ⇒ one client's sequential writes for an alias
  are processed in invocation order. Cross-client writes serialize in depot append order.
- **Retry safety**: microbatch exactly-once means a replayed batch neither double-counts
  clicks nor records a second outcome. Independently, every write is also guarded by a read
  (recorded outcome, existing link, counted click-id), so re-executing an event is a no-op.
- **Status precedence** is computed in the wrapper from stored facts plus caller `now`:
  `:deleted?` > `:blocked?` > (`expires-at` non-nil and `now >= expires-at`) > `:active`.
- **Output shape**: the wrapper constructs every returned map with exactly the documented keys
  (`:expires-at nil` is a present key; `:rejected` expands to `{:outcome :rejected :reason :alias-taken}`);
  stored records are never returned as-is.
- **Synchronization (harness only, not business state)**: `create-module` creates one
  counter shared by every wrapper it produces; each wrapper increments it immediately after a
  `foreign-append!` returns successfully (still inside the `!` method, so a write that returned
  before a barrier is always counted and a failed append never is), and `wait-for-processing!` calls
  `rtest/wait-for-microbatch-processed-count ipc module "core" @counter`. Because the counter
  is shared, a barrier from any client covers writes that returned from every client, at 2
  and 4 tasks alike.

## State primitive selection

- `$$links` (PState): durable source of truth. Per-source-event write volume O(1): one
  scalar record, one outcome entry, or one set element + counter. Bounded by inputs.
- No TaskGlobals: no derived cache is needed; every read is one seek.
- No external systems.

## Resource usage analysis

### Disk usage (PStates)
- `$$links :link`: alias ≤32 B + URL ≤2,048 B (typical ~100 B) + 3 small fields ≈ 150 B
  typical, 2.2 KB max. 10M aliases ≈ 1.5 GB total, /N per task.
- `:outcomes`: ≤64 B key + keyword ≈ 80 B; typical 1 per alias, max 1,000. ≈ 0.8 GB total.
- `:click-ids`: ≤64 B element + subindex overhead ≈ 90 B; up to 1M per viral alias
  (~90 MB for that alias, on one task). Grows with total observations (100,000/s peak).
- Depot `*alias-events`: retained per depot retention policy; ~150 B per record.

### Memory usage (TaskGlobals)
None.

### Minimization
- No data is duplicated across storage locations. The click counter is the only derived
  value and is required for fixed-work `get-click-count` (counting the set would be O(n)).
- `:click-ids` cannot be dropped without violating exactly-once counting. Element bytes are
  the caller's ids; no packing available.

## Design difficulty log

- Decision: single PState keyed by alias with subindexed `:outcomes` and `:click-ids`.
  Basis: all reads are point lookups by alias with a fixed-work bound; a per-alias dedup
  set costs 1 seek per observation, versus 2 hops for a globally hashed click-id set.
  Outcome: every operation is one hop, ≤2 seeks; hot aliases serialize on one task
  (thousands/s, within capacity).
- Decision: microbatch only. Basis: no `!` method requires immediate visibility or a return
  value; click counting and outcome recording are non-idempotent and need exactly-once.
  Outcome: retries are safe by construction; visibility latency ≥300 ms is acceptable per
  spec ("hundreds of ms acceptable").
- Decision: derive resolve status in the wrapper from stored facts. Basis: `now` is a read
  argument; storing a status would be stale by definition. Outcome: `resolve-alias` = 1 seek.
- Decision: one depot with five record types. Basis: per-alias invocation-order guarantee
  requires all of an alias's writes in one depot partition. Outcome: ordering holds without
  any coordination; the depot partitioner equals the PState partitioner.
