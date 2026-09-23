# Plan — subsystem `execution-lifecycle`

Integration note: this historical phase-1 design preceded claims and reads.
The final combined PState schema is documented in the later subsystem plans
and implemented in `../hld_job_scheduler/module.clj`: a bounded `:state`
record and subindexed `:claims` within the same per-execution PState. The
single ordered depot, microbatch topology, and shared synchronization counter
remain as designed here.

<!-- Phase 1 artifact for hld-job-scheduler, subsystem 1 of 3 (DECOMPOSITION.json).
Authority: README.md + protocol docstrings + harness `Synchronizable` docstring.
IMPLICIT_SPEC.md is derived guidance; its "single-digit ms desired" latency notes,
throughput guesses and snapshot/consistency-across-reads notes are NOT requirements and
justify nothing below. Later subsystems (claims-and-leases, completion-and-reads) are
black boxes: nothing here assumes their mechanisms. -->

## Scope

Owned operations: `submit-execution!`, `advance-clock!`, and the module-wide
`wait-for-processing!` contract. Deliverable state: the durable per-execution record
holding the validated immutable DAG and the logical clock, keyed and partitioned so that
every later operation on one execution can be served from that execution's task alone
(README "Efficiency contract", "Write ordering and synchronization").

`claim!`, `complete!`, `get-execution`, `get-node`, `get-claim`, `get-claimable-nodes`
are not designed here. The build session stubs them on the reified protocol.

## Reads

This subsystem owns no protocol read. The record it writes is readable with one
`foreign-select-one` on one partition, which is what the spec's "reads of one execution
must not read state belonging to any other execution" needs to be achievable:

| Access | Path | Partition | Cost |
|---|---|---|---|
| whole execution record | `[(keypath execution-id)]` on `$$executions` | `hash(execution-id)` | 1 seek |
| clock only | `[(keypath execution-id :clock)]` | same | 1 seek (record is one serialized value) |
| existence | `[(keypath execution-id :dag)]` → `nil` ⟺ never submitted or rejected | same | 1 seek |

No query topology is needed for anything this subsystem owns.

## Writes

Both writes are records appended to one depot and processed by one microbatch topology
on the execution's task.

| Write | Depot record | Effect on the execution's task |
|---|---|---|
| `submit-execution!` | `(->SubmitExecution execution-id dag)` | validate the DAG (pure CPU, before any disk read). If invalid → no read, no write. Else read `:dag`; if non-nil → no-op; else write `:dag` = normalized DAG and `:clock` = 0 |
| `advance-clock!` | `(->AdvanceClock execution-id clock)` | read `:dag` and `:clock` (one seek, one record). If `:dag` nil or `clock <= current` → no-op. Else write `:clock` = `clock` |

Client side: `(foreign-append! depot record :append-ack)` after incrementing the shared
sync counter (see Synchronization). Neither write returns anything; the spec makes all
`!` methods asynchronous, visible only after `wait-for-processing!`.

Validation (`valid-dag?`, a plain Clojure function called from dataflow) returns
true iff: `dag` is a map; `1 <= (count dag) <= 32`; every value satisfies `coll?` and,
coerced with `set`, is a subset of the key set; and Kahn's algorithm consumes every node (no cycle; a self-edge
is a cycle because the node's in-degree never reaches 0). O(V+E) ≤ 32 + 32·31 steps. It
must never throw: a record that throws deterministically retries the microbatch forever.
The stored DAG is `(into {} (map (fn [[k v]] [k (set v)])) dag)` so `:dag` values are
sets regardless of the caller's collection type. `clock` is stored via `long` so the
`Long` schema position always matches.

## PState Design

### `$$executions` — obvious once the efficiency contract is read

```
$$executions: {execution-id<String>
               fixed-keys{:dag   {node-id<String> Set<node-id<String>>}   ;; not subindexed
                          :clock Long}}
```

- Key = execution-id because every spec bound is stated per execution: work proportional
  only to the one DAG touched; reads of one execution touch no other execution's state;
  ordering per execution. One key = one record = one seek for anything about an execution.
- `fixed-keys-schema` because every execution has the same two fields. Fixed-keys writes
  are field-scoped, so later subsystems that own additional per-execution data can add
  fields to this same record (same key, same partitioner → must NOT be a separate
  PState per the skill's one-PState-per-(key, partitioner) rule). This plan only defines
  `:dag` and `:clock`; it does not define or assume what those fields are.
- Existence ⟺ `:dag` non-nil. Submit writes with `multi-path` on the two owned fields,
  never a whole-record `termval`, so the write touches only fields this subsystem owns.
- `:dag` is a plain nested map, NOT subindexed: the size limit (≤ 32 keys, ≤ 31 refs
  per key) is enforced by `valid-dag?` before storage, which is exactly the
  "application-enforced upper bound" that exempts a collection from subindexing.
  Subindexing would turn a one-seek record read into 1 seek + 32 iterations for every
  DAG-wide operation and add per-node write amplification, with no benefit for a
  collection that cannot exceed 32.

Alternatives costed:

- **Option B — separate `$$clocks {String Long}`** so `advance-clock!` never deserializes
  the DAG. Cost per advance: 1 seek either way (a ≤ 16 KB record deserialization is
  microseconds, a seek is ~0.5 ms). It doubles PState partitions per task and turns
  existence-check + clock read into two seeks. Rejected on cost: Option A is 1 seek,
  Option B is 2 seeks for `advance-clock!` and adds memory overhead on every task.
- **Option C — per-node subindexed map `{eid {node-id fixed-keys{:deps ...}}}`**.
  Whole-DAG read: 1 seek + 32 iterator reads vs 1 seek; submit: 32 element writes vs 1.
  Rejected: strictly more I/O for a bounded collection.

Chosen: A.

## Depots

- `*execution-events`: `(hash-by :execution-id)`, client-appendable. Record types
  `SubmitExecution [execution-id dag]` and `AdvanceClock [execution-id clock]`
  (defrecords; dispatch with `<<subsource` / `case>`).

Same depot for both kinds because the spec orders all writes addressed to the same
execution ("a `claim!` invoked after an `advance-clock!` on the same execution is
decided against the advanced clock"): same-key ordering across write kinds requires one
log partition per execution-id AND that the ordered write kinds be applied by one
topology in depot order — two topologies consuming the same depot progress
independently, so a second topology could read `:clock` before `lifecycle` has applied
an earlier advance. `hash-by :execution-id` places the record on the task
holding `$$executions[execution-id]`, so processing needs no partitioner hop. This depot
is the module's ingress for same-execution-ordered writes; that is a property of the depot
this subsystem declares, not an assumption about later subsystems' code.

## Topologies and PStates

- **`lifecycle`: microbatch.** Why: default microbatch. Neither stream reason applies —
  no write must be visible in single-digit milliseconds (the spec makes all `!` methods
  asynchronous and defers visibility to `wait-for-processing!`), and no write returns a
  value. Microbatch additionally gives exactly-once PState updates on retry.
  - Concern: DAG validation + conditional record creation. Microbatch is sufficient.
  - Concern: monotonic clock update. Microbatch is sufficient.
  - PStates owned: `$$executions` (schema above).
  - Dataflow shape (design sketch, not implementation):
    ```
    source> *execution-events :> %mb
    (%mb :> *event)
    <<subsource *event
      case> SubmitExecution :> {*execution-id :execution-id *dag :dag}
        (valid-dag? *dag :> *valid?)  (filter> *valid?)
        (normalize-dag *dag :> *ndag)
        (local-transform> [(keypath *execution-id)
                           (not-selected? :dag some?)          ;; only if never submitted
                           (multi-path [:dag (termval *ndag)] [:clock (termval 0)])]
                          $$executions)
      case> AdvanceClock :> {*execution-id :execution-id *clock :clock}
        (long *clock :> *c)
        (local-transform> [(keypath *execution-id)
                           (selected? :dag some?)              ;; only if exists
                           (selected? :clock (nil->val 0) (pred< *c))  ;; only if current < *c
                           :clock (termval *c)]
                          $$executions)
    ```
    Each write is one conditional `local-transform>`: the existence / monotonicity
    check happens inside the path on the record the transform has already loaded, so
    an accepted or rejected-at-guard event costs exactly one read of one key (paths.md
    "No-Read Optimizations": navigating into a nested `fixed-keys` record loads it, so
    a separate `local-select>` before the transform would access the same key twice).
    A guard that does not match navigates to nothing and writes nothing.
- No stream topology. No second microbatch topology: both concerns are sub-millisecond
  per event; nothing differs by an order of magnitude.
- No internal depots.

## Query Topologies

None. This subsystem owns no read that needs more than one path on one partition.

## Partitioning efficiency

**Optimal placement.** Every owned operation and every spec bound is scoped to one
execution-id, so the placement the work wants is `f(execution-id) → exactly one task`,
with executions spread evenly. `|hash` (via `hash-by :execution-id`) is exactly that
`f`: the keyspace is every execution ever submitted (large), and no execution-id can be
hot for this subsystem's data — each key holds one record bounded by 32 nodes, and
receives one submit plus a caller-driven trickle of clock advances. No stored placement
state can beat one seek on one task, so `|direct` schemes are not competitive (their
placement lookup alone costs the same seek).

Dominant operation for the table: `advance-clock!` (repeats per execution; submit is once
per execution). Rows cover every input regime of both writes. Seeks/op are totals
across the cluster; every operation touches exactly one task, so the totals do not
depend on N.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| advance, execution exists, clock > current (typical) | 0.55 | 1 | 0 |
| advance, execution exists, clock <= current (stale) | 0.15 | 1 | 0 |
| advance, unknown/rejected execution | 0.05 | 1 | 0 |
| submit, valid DAG, new id | 0.15 | 1 | 0 |
| submit, existing id (resubmit) | 0.05 | 1 | 0 |
| submit, invalid DAG | 0.05 | 0 | 0 |
Weighted seeks = 0.95   |   Weighted iterator reads = 0

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| advance, execution exists, clock > current (typical) | 0.55 | 1 | 0 |
| advance, execution exists, clock <= current (stale) | 0.15 | 1 | 0 |
| advance, unknown/rejected execution | 0.05 | 1 | 0 |
| submit, valid DAG, new id | 0.15 | 1 | 0 |
| submit, existing id (resubmit) | 0.05 | 1 | 0 |
| submit, invalid DAG | 0.05 | 0 | 0 |
Weighted seeks = 0.95   |   Weighted iterator reads = 0

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| advance, execution exists, clock > current (typical) | 0.55 | 1 | 0 |
| advance, execution exists, clock <= current (stale) | 0.15 | 1 | 0 |
| advance, unknown/rejected execution | 0.05 | 1 | 0 |
| submit, valid DAG, new id | 0.15 | 1 | 0 |
| submit, existing id (resubmit) | 0.05 | 1 | 0 |
| submit, invalid DAG | 0.05 | 0 | 0 |
Weighted seeks = 0.95   |   Weighted iterator reads = 0

Flat from N = 1 to N = 128. Storage and event load also spread by execution-id.
The private validation runs at 2 and 4 tasks; nothing in the design depends on N.

## Design Decisions

- **Subindexing:** none. `:dag` is capped at 32 entries by validation before storage.
  The top-level map is RocksDB-backed by construction (one execution per key).
- **Colocation:** depot `hash-by :execution-id` = PState key = `|hash` of every later
  local read, so the topology does zero partitioner hops.
- **Validation in the topology, not the client:** the depot is then the single authority
  for "was this DAG accepted", identical for every client wrapping the module, and a
  rejected submission is guaranteed to leave no trace. The cost is one depot record
  per rejected submission (no PState I/O — validation precedes the read).
- **Existence = `:dag` present**, and submit writes only its own two fields. This keeps
  the "rejected id is indistinguishable from never submitted" invariant local to this
  subsystem regardless of what else may ever be stored at that key.
- **Ordering:** `%mb` emits each task's depot partition in append order; same
  execution-id → same partition → sequential, and each event's read+write is atomic on
  the single-threaded task. Cross-execution order is unconstrained, as the spec allows.
- **Retry safety:** microbatch PState writes are exactly-once. Independently, both writes
  are idempotent: submit is guarded by `:dag` nil, advance by `clock > current`.
- **Client-side `long` coercion** of `clock` and set-normalization of dependency values
  happen in the topology too, so a second client built by someone else cannot poison the
  schema.

## Synchronization (`Synchronizable`)

`wait-for-processing!` calls
`(rtest/wait-for-microbatch-processed-count ipc module-name "lifecycle" n)` where `n` is
the cumulative number of records appended to `*execution-events` **by every client on
this cluster**, not just this client. The counter lives in a process-wide
`(defonce append-counts (atom {}))` keyed by the IPC instance; each `wrap-client`
increments `(get-in @append-counts [ipc])` before every `foreign-append!`.

Why shared rather than per-client: the processed count is cumulative for the module.
If client A appends 3 and syncs, then client B appends 2 and waits for "2", the wait is
already satisfied by A's records and B's own writes may be unprocessed — violating
"multiple clients wrapping the same deployed module must observe the same business state
after the writing client synchronizes". Keying by the IPC makes the second client see the
true total; keying by IPC (not module name) keeps counts correct across tests that launch
fresh clusters in one JVM. The README explicitly permits transient synchronization
counters; no business state lives in the client.

Later subsystems that add write kinds must count their appends through the same counter
and wait on every topology they add; that is a requirement the harness docstring states,
not a mechanism assumed here.

## State primitive selection

- `$$executions` (PState): source of truth for DAG and clock. Per-source-event write
  volume O(1) records (submit: one record ≤ 32 nodes; advance: one `Long`). Durable.
- `append-counts` (client-process atom): transient sync counter, permitted by README
  "transient synchronization counters are allowed". Lost with the client process; it is
  only meaningful for the lifetime of the test cluster it is keyed by.
- No TaskGlobals. No external systems.

## Resource usage analysis

### Disk usage (PStates)
- `$$executions` entry: key ≈ 8–40 B; `:dag` worst case 32 node-ids × (≈16 B id + set of
  ≤ 31 refs × ≈16 B) ≈ 16 KB, typical (≈ 4–8 nodes, 1–2 deps each) ≈ 300 B; `:clock` 8 B.
  Growth: one entry per accepted execution, never deleted. Per task: executions × entry
  size / N.
- `*execution-events` depot: one record per call; submit ≈ DAG size, advance ≈ 40 B.
  Depot retention is Rama's default; the topology never reads history back.

### Memory usage (TaskGlobals)
None.

### Minimization
- Minimal: the record holds exactly the two values the spec makes authoritative (DAG,
  clock). No derived or duplicated data. Dependency sets are stored as submitted (the
  spec requires equality with the submitted sets); no reverse index is stored because no
  owned operation needs one and the spec does not require one.
- `:clock` as `Long` primitive box; DAG as nested Clojure collections — required by the
  return shape the spec fixes (`{node-id #{dep ...}}`).

## Design difficulty log

- **Decision: validate the DAG in the topology, not the client.**
  Basis: README "Write ordering and synchronization" requires every client wrapping the
  module to observe the same business state; topology-side validation makes acceptance
  one durable decision. Cost comparison: client-side saves one depot record per rejected
  submit; topology-side costs zero PState I/O on rejection because validation precedes
  the read. Outcome: topology-side; no remaining uncertainty.
- **Decision: existence ⟺ `:dag` non-nil, with field-scoped writes.**
  Basis: the protocol defines existence as "the DAG was stored" and requires that
  submission "never removes claim decisions recorded before the execution existed";
  a whole-key existence test or a whole-record `termval` would couple this subsystem
  to whatever else is stored at the key. Outcome: `:dag` test + `multi-path`; invariant
  holds regardless of other fields at the key.
- **Decision: sync counter shared per IPC, not per client.**
  Basis: README multi-client visibility sentence. With per-client counts, client B's
  wait for its own count of 2 is already satisfied by client A's earlier 3 processed
  records, so B returns before its writes are applied. Outcome: one atom keyed by IPC;
  requires later subsystems to count their appends through it and to wait on every
  microbatch topology (harness docstring).
- **Everything else was determined directly by the requirements:** key = execution-id,
  `|hash`, no subindex (32-node cap), microbatch (asynchronous writes, no return
  values), one depot (same-execution ordering across write kinds).

## Self-validation (Phase 1 Step 6, checklist applied; no PLAN_VALIDATION.md produced)

- Query topologies: none → no wasted-read check applies.
- PState grouping: one PState per (key, partitioner). No `Object`. Uniform record →
  fixed-keys. No polymorphic position. Only inner collection (`:dag`) is bounded by
  `valid-dag?` at ≤ 32 → not subindexed.
- Partitioning: `|hash` on execution-id — large keyspace, no hot key for this data;
  table filled for N = 1/16/128, proportions sum to 1, seeks are cluster totals, flat.
- Topologies: microbatch only; no low-latency write exists in the spec; no
  test-synchronization reasoning influenced any choice.
- Production readiness: concurrent clients on one execution serialize on its task;
  client restart loses only the transient counter (business state is in Rama); worker
  restart resumes from committed microbatch, exactly-once; unbounded executions spread by
  hash; no stream topology → no non-idempotent-write concern; single-partition writes →
  no partial multi-partition failure.
- Internal depots / cross-topology flows / stream commit boundaries: none.
- Minimality: simplest sketch = one depot, one microbatch topology, one PState with two
  fields — which is this plan. Nothing to delete or merge.
- Throughput: 1 seek per write (0 for rejected submits); no cheaper design exists since
  every accepted write must at least read its execution's record once.
- Spec coverage (owned items), traced: valid DAG at 1 and 32 nodes accepted; `{}`, 33
  nodes, unresolved ref, `a→b→a`, `a→a` rejected with no write; resubmit with a different
  DAG no-op; advance on unknown/rejected id no-op (record stays absent); `advance 10,
  advance 5, advance 10` → 10; advance to 0 on fresh execution no-op; advance then
  same-execution write from the same client is applied after it (same partition, append
  order); retry of any event reproduces the same state; two clients → shared counter
  makes each client's own writes visible after its `wait-for-processing!`.
