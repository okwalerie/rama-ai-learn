# Plan

<!-- Phase 1 artifact for hld-notification-system (subsystem notification-core, whole spec). Design only; no code. -->

Two owner keys per the spec: **recipient user** (devices, preferences, submit ordering,
recent list, dead letters) and **submission** (delivery lifecycle, attempts, receipts).
Submissions are globally first-wins by `submission-id` and every attempt/receipt/read of a
submission carries only `submission-id`, so submission records live on `hash(submission-id)`;
everything per-recipient lives on `hash(user-id)`.

## Reads

| Read | Method | Path | Partition | Cost |
|---|---|---|---|---|
| `get-devices user` | `foreign-select-one` | `$$users [(keypath user :profile :devices) (nil->val {})]` | `hash(user)` | 1 seek |
| `get-preferences user` | `foreign-select-one` | `$$users [(keypath user :profile :prefs) (nil->val {})]` | `hash(user)` | 1 seek |
| `get-submission sid` | `foreign-select-one` | `$$submissions [(keypath sid)]` (≤8 inline deliveries) | `hash(sid)` | 1 seek |
| `get-recent-submissions user` | `foreign-select` | `$$users [(keypath user :recent) (sorted-map-range-to-end 100) MAP-VALS]`; wrapper reverses to newest-first | `hash(user)` | 1 seek + ≤100 iters |
| `get-dead-letters user` | `foreign-select` | `$$users [(keypath user :dead-letters) (sorted-map-range-to-end 100) MAP-VALS]`; wrapper reverses | `hash(user)` | 1 seek + ≤100 iters |

Every read is one partition and one PState, so there are no query topologies. Page reads
are tail range scans over subindexed sorted maps keyed by a per-user sequence number, so
their work is bounded by 100 regardless of history.

## Writes

One depot `*events`, `(hash-by :owner)`; each record carries `:owner` = `user-id` for
user-owned writes and `submission-id` for submission-owned writes (the spec's two owners).

| Write | Record (`:owner`) | Processing |
|---|---|---|
| `register-device!` | `RegisterDevice{user-id device-id token}` (user) | on `hash(user)`: read `[user :profile]` (plain inline record, 1 seek); new id and 8 present ⇒ no-op; new ⇒ `{token, generation 1, valid? true}`; else generation+1, token, valid? true. |
| `set-preference!` | `SetPreference{user-id category enabled?}` (user) | on `hash(user)`: `[user :profile :prefs category] := enabled?`. |
| `submit!` | `Submit{submission-id user-id category payload ttl now}` (user) | **block 1, hop 1** on `hash(user)` (the depot partition), in depot order with the user's register/preference records: read `[user :profile]` (1 seek: devices, prefs, submit-seq — never the top-level value, which carries the subindexed list handles); `seq := submit-seq + 1` (write); `pos := $$task-pos[task] + 1` (local write, 1 seek); `rank := task × 2^40 + pos` (Long; `ops/current-task-id`); compute status and deliveries (`:pending`, `attempts 0`, `next-attempt-at now`, token/generation snapshot per valid device) into `record`. **Arbitration**: `(+group-by *sid (aggs/+limit [1] *rank *user *seq *record :+options {:sort *rank}))` — auto-partitions to `hash(sid)`, keeps the contender with the smallest rank per submission-id; `materialize> *sid *rank *user *seq *record :> $$winners`. **block 2** (after block 1 completes on all tasks) on `hash(sid)`: `($$winners :> …)`; read `[sid]` (1 seek); present ⇒ stop (an existing submission always wins). Else write the full record; `|hash user-id`: `recent[seq] := sid`. |
| `report-attempt!` | `ReportAttempt{submission-id device-id attempt-no outcome now}` (submission) | on `hash(sid)`: read record (1 seek); apply guards 1–4 and outcome rule 5 as a pure function; write the delivery. If dead-lettered ⇒ `|hash user-id`: read `[user :profile :dl-seq]`, `dl-seq := n+1`, `dead-letters[n+1] := {sid device reason at}`. If `:invalid-token` ⇒ `|hash user-id`: if `[user :profile :devices device :generation] == snapshot generation` ⇒ `valid? := false`. Both hops conditional (`<<if`). |
| `record-receipt!` | `RecordReceipt{submission-id device-id receipt}` (submission) | on `hash(sid)`: read record; if delivery state ∈ {accepted, delivered, read} ⇒ `state := max(state, receipt)`. |

All writes are fixed work: ≤3 hops, ≤3 seeks, fan-out bounded by 8 devices.

### Same-microbatch arbitration of a submission-id (why and how)

Without arbitration, two contenders for the same id from different user tasks reach
`hash(sid)` in arrival order. Client 1 `submit(a,u); submit(b,u)` and client 2
`submit(b,v); submit(a,v)` could then yield winners b→u and a→v: under a serial reading of
"first submission wins" plus per-owner invocation order that is a cycle (a(u) < b(u) < b(v)
< a(v) < a(u)). The contract is kept as written by defining "processed first" with a
deterministic total order that extends every per-owner order:

- `rank = task-id × 2^40 + pos`, where `pos` is a per-task counter (`$$task-pos`,
  `local-transform>` only, never routed) advanced once per `Submit` in depot order on the
  owner task. Within one task, rank order = depot order = each client's invocation order
  for that owner; across tasks, task-id breaks ties. Ranks are stable across a microbatch
  retry: `$$task-pos` is a PState, so the prime phase restores it and the replay assigns the
  same values.
- Existing submissions always win: earlier microbatches are earlier in the serial order and
  are checked in block 2 before any write.
- Within a microbatch, the winner of an id is the minimum rank among its contenders
  (`aggs/+limit [1] … {:sort *rank}` under `+group-by *sid`; `+limit` is batch-only and
  sorts by any single var). Fallback with the same semantics if `+limit` inside `+group-by`
  fails at build time: `(aggs/+vec-agg [*rank *user *seq *record] :> *cs)` and
  `(min-key first …)` in post-agg.
- The serial history is: all persisted submissions in (microbatch, rank) order. Each
  client's sequence for one owner is a subsequence (same task, increasing pos), and
  `submit-seq` (per user, assigned in the same synchronous step as `pos`) is monotone in
  rank, so `get-recent-submissions` acceptance order agrees with the history.

Microbatch shape (`<<sources` with three sequential `<<batch` blocks; `%mb` emits per task
in depot append order, microbatch.md):

```clojure
;; block 1 — user-owned records, depot order on hash(user); contenders → arbitration
(<<batch
  (%mb :> *rec)
  (<<cond
    (case> (instance? RegisterDevice *rec)) (… local-transform> $$users …)
    (case> (instance? SetPreference *rec))  (… local-transform> $$users …)
    (default>))
  (filter> (instance? Submit *rec))
  (extract *rec :> *sid *user *category *payload *ttl *now)
  (local-select> [(keypath *user :profile)] $$users :> *profile)
  (inc (:submit-seq *profile) :> *seq)
  (local-transform> [(keypath *user :profile :submit-seq) (termval *seq)] $$users)
  (ops/current-task-id :> *task)
  (local-transform> [(keypath *task) (nil->val 0) (term inc)] $$task-pos)
  (local-select> [(keypath *task)] $$task-pos :> *pos)
  (+ (* *task 1099511627776) *pos :> *rank)
  (build-record *sid *user *category *payload *ttl *now *profile :> *record)
  (+group-by *sid
    (aggs/+limit [1] *rank *user *seq *record :+options {:sort *rank}))
  (materialize> *sid *rank *user *seq *record :> $$winners))

;; block 2 — persist winners on hash(sid); existing always wins; then recent on hash(user)
(<<batch
  ($$winners :> *sid *rank *user *seq *record)
  (local-select> [(keypath *sid)] $$submissions :> *existing)
  (filter> (nil? *existing))
  (local-transform> [(keypath *sid) (termval *record)] $$submissions)
  (|hash *user)
  (local-transform> [(keypath *user :recent *seq) (termval *sid)] $$users))

;; block 3 — submission-owned records, depot order on hash(sid); effects → user task
(<<batch
  (%mb :> *rec)
  (filter> (or (instance? ReportAttempt *rec) (instance? RecordReceipt *rec)))
  (local-select> [(keypath *sid)] $$submissions :> *sub)
  (apply-guards *rec *sub :> *new-delivery *effect)      ; pure; *effect nil | dead-letter | invalidate
  (<<if (some? *new-delivery)
    (local-transform> [(keypath *sid :deliveries *device) (termval *new-delivery)] $$submissions))
  (filter> (some? *effect))
  (|hash *user)
  (<<if (= :dead-letter (:kind *effect)) (… dl-seq, dead-letters[n] …)
   (else>) (… invalidate iff current generation = snapshot generation …)))
```

Block 1 keeps every user-owned record in one pre-agg on the owner task in depot order, so a
submit sees exactly the registry/preference state of the writes invoked before it. Block 3
runs after block 2, so a report for a submission accepted in the same microbatch already
sees the record (the spec only requires that an unknown submission is ignored). Every
non-contender row is terminated by `filter>`, never by a dangling branch, so each pre-agg
has a single tail. Every appended record is a source record of one topology, so the
harness counter matches `wait-for-microbatch-processed-count` unchanged.

## PState Design

Two business PStates, justified by different keys/partitioners (`user-id` vs
`submission-id`), plus a per-task arbitration counter. Within each, everything sharing the
key is one fixed-keys value.

```clojure
(declare-pstate mb $$users
  {String (fixed-keys-schema
    {:profile      (fixed-keys-schema                                             ; inline record: one seek, no subindexed handles
                     {:devices    (map-schema String (fixed-keys-schema {:token String :generation Long :valid? Boolean})) ; ≤8, app-enforced
                      :prefs      (map-schema String Boolean)                        ; ≤16 categories (grammar)
                      :submit-seq Long                                               ; last assigned submit sequence
                      :dl-seq     Long})                                             ; last assigned dead-letter sequence
     :recent       (map-schema Long String {:subindex? true})                      ; seq -> submission-id
     :dead-letters (map-schema Long (fixed-keys-schema {:submission-id String :device-id String
                                                        :reason Keyword :at Long})
                               {:subindex? true})})})                              ; seq -> entry

(declare-pstate mb $$submissions
  {String (fixed-keys-schema
    {:submission-id String :user-id String :category String :payload String
     :submitted-at Long :expires-at Long
     :status       Keyword                                                         ; :suppressed | :no-devices | :dispatched
     :deliveries   (map-schema String                                              ; device-id -> delivery (≤8, inline)
                     (fixed-keys-schema {:token String :generation Long :state Keyword
                                         :attempts Long :next-attempt-at Long}))})}) ; next-attempt-at nil unless :pending

;; Per-task submit position for arbitration ranks. Key = own task id; written only with
;; local-transform> on the depot task, so each partition holds exactly its own key.
(declare-pstate mb $$task-pos {Long Long})
```

Why: `get-submission` must be one seek and the fan-out is bounded to 8, so deliveries are
inline in the submission record. Recent and dead-letter lists are unbounded (100,000 each)
and are read newest-first in pages of 100: a subindexed sorted map keyed by a per-user
monotone sequence gives one seek + 100 iterations via `sorted-map-range-to-end`.

Alternative costed — **submission record on the user partition + global
`{submission-id → user-id}` index**: `report-attempt!`/`record-receipt!` (300,000/s) would
need 2 seeks and 2 hops every time (index, then user) instead of 1 seek and a rare
conditional hop; `get-submission` would need a 2-seek query topology instead of one
`foreign-select-one`. `submit!` is 3 hops in both. Rejected on total cost.

## Depots

- `*events`: `(hash-by :owner)`; record types `RegisterDevice`, `SetPreference`, `Submit`
  (owner = user-id) and `ReportAttempt`, `RecordReceipt` (owner = submission-id). One
  depot so that a client's sequential writes to one owner occupy one depot partition in
  invocation order. Writes to different owners (e.g. a `submit!` and a later
  `report-attempt!`) have no ordering guarantee in the spec; test authoring must place a
  `wait-for-processing!` barrier between a submit and reports for it.

## Topologies and PStates

- `core`: **microbatch** (default microbatch; no write needs millisecond visibility or an
  ack return). Owns `$$users` and `$$submissions`.
  - Concerns: device registry, preferences, first-wins submit with snapshot fan-out,
    delivery guards, dead letters, receipts. None requires stream semantics.
  - Non-idempotent writes: generation increment, `submit-seq`/`dl-seq` increments, list
    appends. Microbatch replays a batch exactly once, so a worker restart mid-batch cannot
    bump a generation twice, duplicate a delivery, or double-append. The three-hop submit
    and the conditional attempt hops are cross-partition writes inside one microbatch and
    therefore commit atomically: no reader sees a `:dispatched` submission without its
    deliveries or a dead letter without its `:failed` delivery.
  - Same-owner records in a batch are processed sequentially on the owner's task in depot
    order (block 1 for user-owned, block 3 for submission-owned); each `submit!` snapshots
    the devices/prefs current when it runs.
  - Three `<<batch` blocks (see "Same-microbatch arbitration"): block 1 owner processing +
    `+group-by` arbitration + `materialize>`; block 2 persist winners + recent; block 3
    attempts/receipts. `<<batch` is a global barrier, so block 2 sees every contender.

No stream topology, no internal depot, no tick depot (retry timing is caller-supplied).

## Query Topologies

None. Every read is a single `foreign-select`/`foreign-select-one` on one partition.

## Partitioning efficiency

Optimal placement: recipient state `f(user) → hash(user)`; submission state
`f(sid) → hash(sid)`. 100M recipients and unbounded submission ids ⇒ negligible hash
variance; no key holds disproportionate storage (per user ≤8 devices, ≤16 prefs, and two
subindexed lists; per submission ≤8 deliveries). A hot recipient's submits serialize on
its task (required for snapshot ordering) but each submit's arbitration hop spreads across
`hash(sid)`.

Dominant read: `get-submission` (point read used after every attempt). Categories:
dispatched, suppressed/no-devices, unknown id.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| dispatched submission (≤8 deliveries) | 0.80 | 1 | 0 |
| suppressed / no-devices submission    | 0.15 | 1 | 0 |
| unknown submission-id                 | 0.05 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| dispatched submission (≤8 deliveries) | 0.80 | 1 | 0 |
| suppressed / no-devices submission    | 0.15 | 1 | 0 |
| unknown submission-id                 | 0.05 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| dispatched submission (≤8 deliveries) | 0.80 | 1 | 0 |
| suppressed / no-devices submission    | 0.15 | 1 | 0 |
| unknown submission-id                 | 0.05 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

Flat in N. Page reads are 1 seek + ≤100 iterations at every N; `get-devices` /
`get-preferences` are 1 seek.

## Design Decisions

- **Subindexing**: `:recent` and `:dead-letters` (≤100,000 per user). `:devices` (≤8,
  enforced by the register rule), `:prefs` (≤16 by grammar), `:deliveries` (≤8, derived
  from devices) stay inline.
- **Colocation**: user-owned records land on `hash(user)` and submission-owned on
  `hash(sid)`, matching the PState that each first touches. `submit!` must visit both
  (snapshot on the user task, first-wins on the submission task) and returns to the user
  task only to record acceptance.
- **Acceptance order**: `submit-seq` is assigned in block 1, in depot order, before
  arbitration. Recording `recent[seq]` in block 2 makes the recent list reflect invocation
  order for one client even though block-2 arrivals from different submission tasks may
  interleave; losers leave a gap in `seq`, which the range read ignores. Dead letters are
  sequenced at append time on the user task (`dl-seq`).
- **First-wins arbitration**: same-microbatch contenders for one id are ordered by
  `rank = (task, pos)`; the minimum wins; persisted submissions from earlier microbatches
  always win. This is a definition of "processed first" that extends every per-owner order,
  not a weakening of the contract; it removes the arrival-order dependence between
  submission tasks. Cost: one local seek + one write on `$$task-pos` per submit.
- **Guard order** (existence → pending → attempt-no/due → expiry → apply) is one pure
  function over the delivery map and the record's `expires-at`; `:attempts` is only
  advanced by applied reports, so a replayed report is a no-op even without microbatch.
- **Generation-guarded invalidation** compares the delivery's snapshot generation with the
  device's current generation on the user task at hop time; a re-registered device keeps
  its validity.
- **Retry safety**: microbatch exactly-once; all cross-partition writes commit atomically
  per batch.
- **Output shape**: the wrapper constructs every returned map explicitly — delivery maps with all
  five keys (`:next-attempt-at nil` present), `get-submission` with its eight keys, dead-letter
  entries with four; stored records are never returned as-is.
- **Synchronization (harness only)**: one counter created in `create-module`, shared by all
  wrappers, incremented immediately after each `foreign-append!` returns successfully (inside the
  `!` method, so only actual appends are counted); `wait-for-processing!` calls
  `rtest/wait-for-microbatch-processed-count ipc module "core" @counter`. Cross-client
  barriers hold at 2 and 4 tasks; no business state in the wrapper.

## State primitive selection

- `$$users` (PState): durable; per-source-event writes O(1) (one device record, one
  preference, seq + one list entry). Bounded by inputs.
- `$$submissions` (PState): durable; per-submit write ≤8 inline deliveries in one value;
  per-attempt one value rewrite. Bounded by the 8-device limit.
- `$$task-pos` (PState, one Long per task): durable so ranks are reproduced on microbatch
  replay (a TaskGlobal would reset on worker restart and could reorder a replayed batch).
- `$$winners` (materialized, in-memory, per microbatch): ≤ one row per distinct submission
  id in the batch; cleared after the batch.
- No TaskGlobals; no external systems.

## Resource usage analysis

### Disk usage (PStates)
- `$$users` inline part: ≤8 devices × ~300 B + ≤16 prefs × ~40 B + 2 longs ≈ 3 KB max,
  ~600 B typical. 100M users ≈ 60 GB total, /N per task.
- `:recent`: 8 B key + ≤64 B value ≈ 90 B with overhead; ≤100,000/user; grows at
  100,000/s ≈ 9 MB/s cluster-wide.
- `:dead-letters`: ~160 B per entry; ≤100,000/user.
- `$$submissions`: header ≤4.3 KB (payload ≤4,096) + ≤8 × ~330 B deliveries ≈ 7 KB max,
  ~1.5 KB typical; grows at 100,000/s ≈ 150 MB/s cluster-wide.
- Depot `*events`: ~200 B/record (submits up to 4.3 KB), subject to depot retention.

### Memory usage (TaskGlobals)
None.

### Minimization
- Token/generation snapshots are duplicated from `:devices` into each delivery by spec
  (snapshots must not change after submit).
- `:recent` stores only submission ids (not headers); `get-submission` fetches the header.
- No other duplication across locations.

## Design difficulty log

- Decision: submission record on `hash(submission-id)`; recipient lists on `hash(user-id)`;
  `submit!` as user → submission → user. Basis: global first-wins and id-only attempt/receipt
  reports bind the record to the submission id; the snapshot binds the first hop to the
  user. Alternative (record on user partition + id index) costs 2 hops + 2 seeks on every
  attempt/receipt (300,000/s) and a query topology for `get-submission`; chosen design
  costs 1 seek and a rare conditional hop. Outcome: `get-submission` = 1 seek;
  attempts = 1 seek + ≤1 hop; submit = 3 hops.
- Decision: recent list keyed by a block-1 sequence number. Basis: the block-2 return hop
  arrives via different submission tasks, so append order at the user task is not
  invocation order; assigning `seq` before arbitration fixes acceptance order to depot
  order. Outcome:
  one extra long per user; gaps from losing duplicates are harmless.
- Decision: microbatch only. Basis: generation bumps, sequence increments, and list appends
  are non-idempotent; no write needs ms visibility. Outcome: retries and mid-batch worker
  restarts are safe; cross-partition atomicity of submit fan-out and dead-lettering is free.
- Decision: deterministic same-microbatch arbitration (`+group-by *sid` + `+limit [1]` by
  `(task, pos)` rank; persist in a second `<<batch`). Basis: arrival order at `hash(sid)`
  from different user tasks is not a serial order consistent with per-owner invocation
  order (crossed submissions can form a cycle). Alternatives: (a) route all submits through
  a global task — a single-task bottleneck at 100,000/s; (b) document arrival order as the
  contract — weakens the public contract; rejected. Outcome: one extra local seek + write
  per submit, one materialized row per contender-id, three batch blocks instead of one.
- Decision: no cross-owner ordering mechanism. Basis: the spec orders writes only per
  owner; a report for an unprocessed submission is ignored by rule 1. Outcome: tests must
  barrier between a submit and its reports (documented in the depot section).
