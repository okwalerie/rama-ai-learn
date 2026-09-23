# Plan — subsystem `revisioned-flag-config-store`

<!-- Phase 1 artifact for hld-feature-flag-service, subsystem 1 of 2 (DECOMPOSITION.json).
Authority: README.md + src/hld_feature_flag_service/protocol.clj docstrings + harness
`Synchronizable` docstring. IMPLICIT_SPEC.md is derived guidance; its latency, throughput,
skew and snapshot notes are NOT requirements and justify nothing below. Subsystem 2
(bucketing-and-evaluation) is a black box: nothing here assumes its mechanisms. -->

## Scope

Owned: `put-flag-config!`, `get-flag-config`, and the module-wide `wait-for-processing!`
contract. Deliverable state: the durable per-flag current configuration, keyed by the
README "Flag identity" triple, holding the accepted map exactly as written, so that any
read about one flag is one read on one partition (README "Efficiency contract").

`compute-bucket` and `evaluate` are not designed here; the build session stubs them on the
reified protocol.

## Reads

| Read | Access | Path | Partition | Cost |
|---|---|---|---|---|
| `get-flag-config t e k` | `foreign-select-one` on `$$flags` | `[(keypath (->FlagId t e k))]` | `hash(FlagId)` | 1 seek, 0 iterations, 1 roundtrip |

Returns the stored map or `nil` (`keypath` on an absent key navigates to nil, multiplicity
1, so `foreign-select-one` is safe). The whole configuration is one non-subindexed value,
so the single select returns it `=` to what was accepted. No query topology: the read is
one path on one PState on one partition. The same one-seek path also delivers everything
subsystem 2 needs about a flag; that is a property of this record, not an assumption about
subsystem 2's code.

## Writes

| Write | Depot record | Effect on the flag's task |
|---|---|---|
| `put-flag-config! t e k config` | `(->PutFlagConfig (->FlagId t e k) config)` on `*flag-writes` | guard shape (pure CPU, no I/O; malformed → dropped, never thrown). Read stored `:revision` (1 seek). If nil or `(:revision config)` strictly greater → replace the whole entry with `config` (`keypath` + `termval`, no read). Else no-op. |

Client side: increment the shared sync counter, then `(foreign-append! depot record :append-ack)`.
Nothing is returned; the spec makes the write asynchronous and defers visibility to
`wait-for-processing!`.

Guard (`well-formed-config?`, plain Clojure fn called from dataflow) must be at least as strict
as the PState schema so no record can throw inside the microbatch (a deterministic throw
retries forever): `config` is a map; `:revision` is a positive integer; `:killed?` is a
boolean or nil; `:rules` is a vector whose elements are maps with keys ⊆
`#{:attribute :operator :value :serve}` and string `:attribute`; `:rollout` is absent, nil, or
a map with keys ⊆ `#{:threshold :serve}` and integer `:threshold` in `[0, 10000]`; no other
top-level keys. Normalization: `:revision` (and `:threshold` when present) coerced with `long`
so the `Long` positions always match; Clojure `=` treats `3` and `(int 3)` as equal, so the
stored map stays `=` to the accepted one. Nothing else is touched — in particular `:rollout`
absent vs `nil` is preserved as written.

## PState Design

### `$$flags` — one record per flag, config stored whole

```
$$flags: {FlagId<defrecord tenant env flag-key>
          fixed-keys{:revision      Long
                     :killed?       Boolean
                     :off-value     Object
                     :default-value Object
                     :rules         Vector<fixed-keys{:attribute String
                                                       :operator  Object
                                                       :value     Object
                                                       :serve     Object}>   ;; not subindexed
                     :rollout       fixed-keys{:threshold Long :serve Object}}}   ;; nullable
```

Why this shape:
- **Key = the identity triple as one `defrecord`.** Every spec bound is per flag: revisions
  compare per flag, ordering is per flag, reads touch one flag, "nothing about one flag may
  influence another". One key = one record = one seek for every owned operation. A record
  class is a concrete key type, hashes deterministically for `hash-by` and `|hash`, and its
  serialized form is the match key on disk.
- **Value = the configuration itself**, not `{:config ...}` nested one level: `get-flag-config`
  then returns the selected value directly, and no operation ever reads a subset of it.
- **`fixed-keys-schema`** because every configuration has the same fields (a nullable
  `:rollout` on otherwise same-shaped instances is not polymorphism).
- **`Object` leaves** (`:off-value`, `:default-value`, rule `:value`/`:serve`, rollout
  `:serve`, `:operator`): the protocol docstring fixes these as "arbitrary Clojure values
  compared with `=`; tests use strings, booleans, and integers", and "any other operator"
  must be stored and returned as written. No narrower JVM class admits every legal input;
  a narrower class would reject spec-legal writes. `Object` here is the data model, not a
  way to hide it. Every structural position (record, vector, map keys, `:revision`,
  `:threshold`, `:attribute`, `:killed?`) is concretely typed.
- **`:rules` not subindexed.** Every read of a configuration reads all of it (`get-flag-config`
  returns it whole; README bounds `evaluate` by "the one flag's configuration" and allows
  work proportional to rules). A subindexed vector would (a) make the whole-value
  `foreign-select-one` impossible (subindexed handles cannot cross the wire), forcing
  `foreign-select` + reassembly, (b) cost 1 seek + R iterations instead of 1 seek on every
  read and R element writes instead of 1 on every accepted put, and (c) buy nothing, since no
  operation addresses a rule by index. The spec fixes the access pattern as whole-record.

Alternatives costed:
- **Option B — nested `{tenant {env {flag-key config}}}` with subindexed inner maps.**
  Read: 3 seeks (tenant key, env map entry, flag entry) vs 1. Write: 3 seeks vs 1. Partitions
  by tenant, so a tenant with thousands of flags lands on one task and hash variance is over
  tenants (few) rather than flags (many). Rejected: 3× the seeks and worse balance, for a
  range access ("list flags of a tenant") no operation requires.
- **Option C — split `$$revisions {FlagId Long}` + `$$configs {FlagId config}`** so a stale
  put reads a `Long` instead of the record. Same key, same partitioner → the skill forbids
  the split; and the read of `:revision` from a ≈1–2 KB non-subindexed record is one seek
  plus microseconds of deserialization either way, while `get-flag-config` would need 2
  seeks or a query topology. Rejected on cost.

Chosen: A.

## Depots

- `*flag-writes`: `(hash-by :flag-id)`, client-appendable. Record type
  `PutFlagConfig [flag-id config]` (defrecord). One event type, so no `<<subsource`.

Same-flag ordering ("Writes addressed to the same flag must take effect in the order the
client invoked them") requires one log partition per flag: `hash-by :flag-id` gives it and
places each record on the task holding `$$flags[flag-id]`, so processing needs no partitioner
hop. Cross-flag order is unconstrained, as the spec allows.

## Topologies and PStates

- **`flags`: microbatch.** Why: default microbatch. Neither stream reason applies: the spec
  makes `put-flag-config!` asynchronous with visibility deferred to `wait-for-processing!`
  ("Hundreds of milliseconds" is not a README bound but nothing in the README demands
  millisecond visibility), and no write returns a value. Microbatch adds exactly-once PState
  updates across retries.
  - Concern: revision-gated whole-config replace. Microbatch is sufficient.
  - PStates owned: `$$flags` (schema above).
  - Dataflow shape (design sketch, not implementation):
    ```
    (source> *flag-writes :> %mb)
    (%mb :> {*flag-id :flag-id *config :config})
    (well-formed-config? *config :> *ok?)       (filter> *ok?)
    (normalize-config *config :> *cfg)
    (local-select> [(keypath *flag-id :revision)] $$flags :> *stored-rev)   ;; 1 seek; nil if absent
    (accept? *cfg *stored-rev :> *accept?)      (filter> *accept?)          ;; nil or strictly greater
    (local-transform> [(keypath *flag-id) (termval *cfg)] $$flags)          ;; no-read write
    ```
- No stream topology. No second microbatch topology (one sub-millisecond concern).
- No internal depots.

## Query Topologies

None. The only owned read is one path on one partition.

## Partitioning efficiency

**Optimal placement.** Every owned operation is scoped to one flag, so the placement the
dominant read wants is `f(FlagId) → exactly one task`, with flags spread evenly. `|hash` on
the triple (via `hash-by :flag-id`) is exactly that `f`: the keyspace is every flag ever
configured (tenants × envs × flags, large), and one key holds one small record that receives
one write per operator edit. Hashing on the full triple, not on tenant, is what keeps hash
variance over the large keyspace.

Stored-placement alternatives cost more than they save: any lookup of "where does this flag
live" is itself a seek, equal to the whole cost of the read it would assist. `|all`
(broadcast every configuration to every task, then serve reads on any task) was costed: read
stays 1 seek, but each accepted put becomes N seeks and storage grows N× over an unbounded
number of flags; the only benefit is read balance under per-flag skew the README does not
state. Rejected on total cost: N = 128 → 128 seeks per put vs 1.

Dominant read for the table: `get-flag-config` (the delivered one-flag read). Rows cover
every input regime. Seeks/op are cluster totals; every operation touches exactly one task,
so totals do not depend on N.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| configured flag, live/killed/untrusted (typical) | 0.85 | 1 | 0 |
| never-configured key under a populated tenant/env | 0.10 | 1 | 0 |
| any key before any write to the module | 0.05 | 1 | 0 |
Weighted seeks = 1.00   |   Weighted iterator reads = 0

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| configured flag, live/killed/untrusted (typical) | 0.85 | 1 | 0 |
| never-configured key under a populated tenant/env | 0.10 | 1 | 0 |
| any key before any write to the module | 0.05 | 1 | 0 |
Weighted seeks = 1.00   |   Weighted iterator reads = 0

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| configured flag, live/killed/untrusted (typical) | 0.85 | 1 | 0 |
| never-configured key under a populated tenant/env | 0.10 | 1 | 0 |
| any key before any write to the module | 0.05 | 1 | 0 |
Weighted seeks = 1.00   |   Weighted iterator reads = 0

Flat from N = 1 to N = 128. Writes: 1 seek (revision read) for every well-formed put,
accepted or stale; 0 seeks for a malformed put (guard precedes the read). Storage and write
load spread by flag. Nothing depends on N; the private validation runs at 2 and 4 tasks.

## Design Decisions

- **Subindexing:** none. The top-level map is RocksDB-backed by construction (one flag per
  key). `:rules` is read whole by every operation, so subindexing is pure cost (see PState
  Design).
- **Colocation:** depot `hash-by :flag-id` = PState key = routing of the foreign read, so the
  topology does zero partitioner hops and reads are one roundtrip.
- **Revision check in the topology, not the client:** the depot is the single authority for
  "was this write accepted", identical for every client wrapping the module; a client-side
  read-then-append would race between clients and could accept a stale write.
- **Whole-value `termval` replace:** the spec says acceptance replaces the entire map and
  rejection changes nothing; `termval` at `(keypath *flag-id)` does exactly that with no read,
  and cannot leave a blend of old and new fields.
- **Ordering:** `%mb` emits each task's depot partition in append order; same flag → same
  partition → sequential; each event's read+write is atomic on the single-threaded task.
- **Retry safety:** microbatch PState writes are exactly-once. Independently the write is
  idempotent: a re-delivered accepted put hits the equal-revision case and is a no-op.
- **Malformed input is dropped in dataflow**, never thrown, because a throwing record blocks
  the topology forever. Tests never send malformed configs; the guard exists for production
  and for a second client not written by us.

## Synchronization (`Synchronizable`)

`wait-for-processing!` calls
`(rtest/wait-for-microbatch-processed-count ipc module-name "flags" n)` where `n` is the
cumulative number of records appended to `*flag-writes` **by every client on this cluster**.
The counter is a process-wide `(defonce append-counts (atom {}))` keyed by the IPC instance;
each `wrap-client` increments it before every `foreign-append!` (same pattern as the existing
reference module for hld-metrics-pipeline).

Why shared, not per-client: the processed count is cumulative for the module. If client A
appends 3 and syncs, then client B appends 2 and waits for "2", the wait is already satisfied
by A's records while B's own writes may be unprocessed — violating "Multiple clients wrapping
the same deployed module must observe the same business state after the writing client
synchronizes". Rejected stale writes are depot records too, so they are counted and waited
on. A client that never wrote waits on the current total, which returns promptly. The README
explicitly permits transient synchronization counters; no business state lives in the client.

## State primitive selection

- `$$flags` (PState): source of truth for the current configuration. Per-source-event write
  volume O(1) records of size O(supplied configuration). Durable.
- `append-counts` (client-process atom): transient sync counter, permitted by README "transient
  synchronization counters are allowed". Meaningful only for the cluster it is keyed by.
- No TaskGlobals. No external systems.

## Resource usage analysis

### Disk usage (PStates)
- `$$flags` entry: key ≈ 3 short strings + record overhead ≈ 40–80 B; value ≈ 60 B fixed
  fields + ≈ 50–80 B per rule (tens of rules → ≈ 1–2 KB) + ≈ 30 B rollout. Growth: one entry
  per distinct flag, replaced in place on accepted writes; stale and superseded writes retain
  nothing in the PState. Per task: flags × entry size / N.
- `*flag-writes` depot: one record per put ≈ entry size. Rama's default depot retention;
  the topology never reads history back.

### Memory usage (TaskGlobals)
None.

### Minimization
- Minimal: the record is exactly the accepted map (required `=` on read-back) plus nothing.
  No derived or duplicated data; no per-tenant index because no owned operation lists flags
  and the README requires no range access.
- Numeric fields are `Long` boxes inside a persistent map — dictated by the map shape the
  spec fixes for the return value.

## Design difficulty log

- **Decision:** `Object` at the value leaves (`:off-value`, `:default-value`, rule
  `:operator`/`:value`/`:serve`, rollout `:serve`); every structural position concretely typed.
  **Basis:** protocol docstring fixes these as "arbitrary Clojure values compared with =" and
  any operator must be stored as written; a narrower class rejects legal writes, and a
  rejected write inside a microbatch throws and retries forever. **Outcome:** schema admits
  every spec-legal configuration; no residual uncertainty.
- **Decision:** `:rules` not subindexed. **Basis:** every operation reads the whole
  configuration by contract; subindexing costs 1 seek + R iterations per read and R element
  writes per put and breaks the single `foreign-select-one`; an invented rule cap would reject
  spec-legal configs. **Outcome:** 1 seek per read and per put.
- **Decision:** `fixed-keys-schema` value rather than an opaque stored map. **Basis:** keeps
  the model explicit; the shape guard is required anyway to keep throwing records out of the
  topology. **Outcome:** verified in Phase 2 with `create-test-pstate` on this exact schema:
  a written map reads back `=`, including `:rollout` absent vs `nil` and `false`/`0` values.
- **Decision:** shared (per-cluster) sync counter, not per-client. **Basis:** processed count
  is module-cumulative; a per-client count lets a second client's wait return before its own
  writes are processed, violating the README multi-client sentence. **Outcome:** each
  client's writes are visible to every client after its own `wait-for-processing!`.
- **Everything else was determined directly by the requirements:** key = identity triple,
  `|hash` on it, whole-value replace, microbatch, one depot, one topology, no query topology.

## Self-validation (Phase 1 Step 6, checklist applied; no PLAN_VALIDATION.md produced)

- Query topologies: none → no wasted-read check applies.
- PState grouping: one PState per (key, partitioner). `Object` only at leaves the protocol
  defines as arbitrary (justified above). Uniform record → fixed-keys; no polymorphic
  position. `:rules` not subindexed on costed whole-record access (justified above).
- Partitioning: `|hash` on the full triple — large keyspace, one small record per key; table
  filled for N = 1/16/128, proportions sum to 1, seeks are cluster totals, flat; `|all` and
  stored-placement schemes rejected with numbers.
- Topologies: microbatch only; no README write demands millisecond visibility; no
  test-synchronization reasoning influenced any choice.
- Production readiness: concurrent clients on one flag serialize on its task in append order
  (outcome = a serial order, max revision wins, first-applied wins on ties, never a blend);
  client restart loses only the transient counter; worker restart resumes from the committed
  microbatch, exactly-once; unbounded flags spread by hash; no stream topology; every write is
  single-partition, so no partial multi-partition failure.
- Internal depots / cross-topology flows / stream commit boundaries: none.
- Minimality: simplest sketch = one depot, one microbatch topology, one PState holding the
  config — which is this plan. Nothing to delete or merge.
- Throughput: 1 seek per put, 1 seek per read; no cheaper design exists since an accepted
  write must read the stored revision once and a read must fetch the record once.
- Spec coverage (owned items), traced: first write at revision 1 or 100 accepted; then lower
  → no-op; equal with different content → no-op, old map visible (step 6); higher with
  `:killed? true` accepted (step 7); higher with `:gt` operator accepted and returned as written
  (step 8); `:rules []`, `:rollout nil`, `:rollout` absent, thresholds 0 and 10000 stored and
  read back `=`; `false`/`0` values preserved; same flag-key under two envs → independent
  revisions (step 5); never-configured key → `nil` (step 10), also before any write; duplicate
  delivery → no-op; two clients → shared counter makes each client's writes visible after its
  own `wait-for-processing!`; identical at 2 and 4 tasks.
