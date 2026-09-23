# Plan

<!-- Phase 1 artifact for hld-rate-limiter (subsystem rate-limiter-core, whole spec). Design only; no code. -->

Owner key: `user-id`. Config, clock, both bucket dimensions, and the decision history of
a user are all read and written together, so they live on task `hash(user-id)` and every
operation is one hop with fixed work.

## Reads

| Read | Method | Path (PState `$$users`) | Partition | Cost |
|---|---|---|---|---|
| `get-decision user rid` | `foreign-select-one` | `[(keypath user :decisions rid)]` → nil or the recorded map | `hash(user)` | 1 seek |
| `get-config user` | `foreign-select-one` | `[(keypath user :limiter)]` → nil, else wrapper returns `{:version v :config config}` from the map | `hash(user)` | 1 seek |
| `get-status user endpoint now` | `foreign-select-one` | `[(keypath user :limiter)]` → nil if absent or endpoint not in `:config :endpoints`; else wrapper computes `T = max(now, clock)` and `available(T)` for the user bucket and the endpoint bucket (pure projection, nothing stored) | `hash(user)` | 1 seek |

No read spans two PStates or two partitions, so there are no query topologies. The bucket
math in `get-status` is a pure function of the returned map and the caller's `now`; no
business state lives in the wrapper.

## Writes

One depot `*user-events`, `(hash-by :user-id)`:

| Write | Record | Processing (on `hash(user-id)`) |
|---|---|---|
| `set-config!` | `SetConfig{user-id version config}` | read `[user :limiter]` (1 seek). If present and `version <= :version` ⇒ no-op. Else write `:limiter := {version, config, clock (kept, 0 if new), user-bucket {tokens capacity, at 0}, endpoint-buckets {e {tokens capacity, at 0}} for e in new config}`. One `termval` of the whole `:limiter` map ⇒ config and buckets switch atomically. `at 0` on a full bucket is harmless: `available(T) = min(capacity, capacity + T × refill) = capacity` (protocol: a reset bucket's `at` is irrelevant). |
| `check!` | `Check{user-id request-id endpoint cost now}` | read `[user :decisions rid]` (1 seek); recorded ⇒ no-op. Read `[user :limiter]` (1 seek). Compute `T = max(now, clock)`; apply protocol rules 3–5 in a pure function producing `{decision, new-limiter-or-nil}`. Write `decisions[rid] := decision`; if the decision debits, write the new `:limiter` (both buckets debited, `clock := T`) with one `termval`. Denied decisions write only the decision. |

Both writes are fixed work (≤2 seeks, ≤2 writes) independent of the user's decision
history because `:decisions` is a subindexed point lookup.

## PState Design

One PState: all state shares key `user-id` and partitioner `hash(user-id)`.

```clojure
(declare-pstate mb $$users
  {String (fixed-keys-schema
    {:limiter   (fixed-keys-schema                     ; nil until the first set-config!
                  {:version          Long
                   :config           (fixed-keys-schema
                                       {:shadow?   Boolean
                                        :user      (fixed-keys-schema {:capacity Long :refill Long})
                                        :endpoints (map-schema String (fixed-keys-schema {:capacity Long :refill Long}))})
                   :clock            Long
                   :user-bucket      (fixed-keys-schema {:tokens Long :at Long})
                   :endpoint-buckets (map-schema String (fixed-keys-schema {:tokens Long :at Long}))})
     :decisions (map-schema String                    ; request-id -> decision
                  (fixed-keys-schema {:allowed        Boolean
                                      :would-allow    Boolean
                                      :reason         Keyword   ; nil when allowed
                                      :tick           Long
                                      :config-version Long      ; nil for :no-config
                                      :remaining      (fixed-keys-schema {:user Long :endpoint Long})}) ; nil when no buckets evaluated
                  {:subindex? true})})})
```

Why this is the only reasonable shape: every read is a point lookup by user (plus
request-id), and the two-bucket debit must be atomic — keeping the user bucket, the
endpoint buckets, config, and clock in one inline `:limiter` value makes each debit and
each config switch a single `termval`, so no reader can observe one bucket debited and the
other not, or a new config with old balances. `:endpoints`/`:endpoint-buckets` are bounded
to 16 entries by the input grammar, so they stay inline. `:decisions` grows to 1,000,000
per user and is subindexed. `:config` is stored verbatim so `get-config` echoes it exactly.

Alternative considered: `$$decisions` keyed by `request-id` globally hashed. `check!` would
need 2 hops (dedup on the request-id task, then the user task), and `get-decision` would
need a second partition when the user is also required. Rejected: 2× hops on the dominant
write (1,000,000/s) for no read benefit.

## Depots

- `*user-events`: `(hash-by :user-id)`; record types `SetConfig`, `Check`. One depot so a
  client's sequential `set-config!`/`check!` calls for one user are processed in
  invocation order (same depot partition, depot order); cross-client writes serialize in
  append order and each check records the config version current when it is processed.

## Topologies and PStates

- `core`: **microbatch** (default microbatch; no write requires millisecond visibility or
  an ack return — decisions are read after `wait-for-processing!`). Owns `$$users`.
  - Concerns: config install with bucket reset; decision evaluation with atomic two-bucket
    debit and clock advance. Neither needs stream semantics.
  - Non-idempotent writes: bucket debit and clock advance. Microbatch replays a batch
    exactly once; additionally the `decisions[rid]` guard makes re-execution of a single
    event a no-op, and the version guard makes a replayed `set-config!` a no-op (no second
    reset after debits).
  - Same-user records in a batch are processed sequentially in depot order on one task and
    see the batch's prior writes, so two checks at the same tick see each other's debits.

No stream topology, no internal depot, no tick depot (time is caller-supplied).

## Query Topologies

None. Every read is one `foreign-select-one` on one partition.

## Partitioning efficiency

Optimal placement: `f(user) → hash(user) mod N`. 5,000,000 users ⇒ thousands of keys per
task at N = 128, negligible hash variance. Per-key skew: a hot user issues thousands of
checks/s, all on one task (required: its decisions must be serialized on one clock), which
is within one task's capacity. Storage per user is bounded (config ≤16 endpoints;
decisions ≤1,000,000 × ~120 B ≈ 120 MB worst case, subindexed).

Dominant read: `get-decision`/`get-status` (both 1 seek). Categories: configured user,
unconfigured user, unknown request-id.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| configured user, recorded decision | 0.85 | 1 | 0 |
| configured user, status read       | 0.10 | 1 | 0 |
| unconfigured user / unknown id     | 0.05 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| configured user, recorded decision | 0.85 | 1 | 0 |
| configured user, status read       | 0.10 | 1 | 0 |
| unconfigured user / unknown id     | 0.05 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| configured user, recorded decision | 0.85 | 1 | 0 |
| configured user, status read       | 0.10 | 1 | 0 |
| unconfigured user / unknown id     | 0.05 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

Flat in N. The dominant write `check!` is likewise 2 seeks at every N.

## Design Decisions

- **Subindexing**: `:decisions` only (≤1,000,000 per user). `:limiter` is ≤17 small
  records (grammar-enforced ≤16 endpoints), inline.
- **Colocation**: depot `(hash-by :user-id)` equals the PState key; no repartitioning.
- **Atomicity**: both-or-neither debit, and config+buckets switching together, are
  guaranteed by writing the whole `:limiter` value with one `termval` in one microbatch
  transaction on one task.
- **Ordering**: one depot partition per user ⇒ one client's writes for that user process
  in invocation order; cross-client writes serialize in append order; the recorded
  decisions are exactly those of that order.
- **Retry safety**: microbatch exactly-once; plus idempotence guards (`decisions[rid]`
  recorded ⇒ no-op; `version <= current` ⇒ no-op) so no double debit, no double reset.
- **Clock**: stored in `:limiter`; users without config have clock 0 (denied decisions
  never advance it, so an unconfigured user's `T` is always `now`). A newer config keeps
  the clock (spec: "user clock is unchanged").
- **Output shape**: the wrapper constructs every returned map explicitly — decision maps with all
  six keys (explicit nils), `get-config` as `{:version v :config c}` equal to the installed input,
  `get-status` with its four keys; stored records are never returned as-is.
- **Synchronization (harness only)**: one counter created in `create-module` and shared by
  all wrappers; incremented immediately after each `foreign-append!` returns successfully (inside
  the `!` method, so only actual appends are counted); `wait-for-processing!` calls
  `rtest/wait-for-microbatch-processed-count ipc module "core" @counter`. A barrier from
  any client therefore covers every write that returned from any client, at 2 and 4 tasks.
  No business state is held in the wrapper.

## State primitive selection

- `$$users` (PState): durable source of truth; per-source-event write volume O(1)
  (one `:limiter` value ≤17 records, one decision entry). Bounded by inputs.
- No TaskGlobals (no derived caches; every read is one seek). No external systems.

## Resource usage analysis

### Disk usage (PStates)
- `:limiter`: user-id ≤64 B + config (≤16 endpoints × ~100 B) + buckets (17 × 16 B)
  ≈ 2 KB max, ~400 B typical. 5M users ≈ 2 GB total, /N per task.
- `:decisions`: key ≤64 B + ~120 B value ≈ 190 B with subindex overhead. Grows with
  decisions (1,000,000/s peak fleet-wide ≈ 190 MB/s cluster-wide); per-user cap 1,000,000
  entries ≈ 190 MB on one task worst case.
- Depot `*user-events`: ~150 B per record, subject to depot retention.

### Memory usage (TaskGlobals)
None.

### Minimization
- `:remaining` duplicates a value derivable from bucket state at decision time, but the
  decision map is required to be immutable after later debits, so it must be stored.
- `:config` is stored verbatim (required for exact `get-config` echo); bucket parameters
  are not duplicated into the bucket records (buckets hold only `tokens`/`at`).
- No cross-location duplication.

## Design difficulty log

- Decision: one inline `:limiter` value holding config, clock, and all buckets; decisions
  subindexed beside it. Basis: atomic two-bucket debit and atomic config+reset switch are
  spec invariants; a single `termval` on one task satisfies both with 1 seek. A split
  (separate bucket PState or globally hashed decisions) adds a hop to the 1,000,000/s
  `check!` path without reducing any read cost. Outcome: `check!` = 2 seeks, 1 hop;
  every read = 1 seek.
- Decision: microbatch only. Basis: no ms-visibility or ack-return requirement; debit and
  clock are non-idempotent and need exactly-once. Outcome: retries safe; ≥300 ms
  visibility latency acceptable per spec.
- Decision: `get-status` computes `available(T)` in the wrapper. Basis: the read must not
  advance the clock or store refilled tokens; `now` is a read argument. Outcome: pure
  projection, 1 seek, repeatable.
- Decision: store unconfigured users as `:limiter nil`. Basis: denied decisions never
  advance the clock, so no clock exists before the first config. Outcome: no per-user row
  is created by `:no-config` checks except their decision entries.
