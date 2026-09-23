# Plan Validation — subsystem `revisioned-flag-config-store`

<!-- Phase 2 artifact for hld-feature-flag-service, subsystem 1 of 2. Inputs: README.md,
src/hld_feature_flag_service/protocol.clj, harness Synchronizable docstring,
DECOMPOSITION.json, IMPLICIT_SPEC.md, PLAN-revisioned-flag-config-store.md. README and
protocol docstrings are authoritative; IMPLICIT_SPEC latency/throughput notes are not
requirements. Subsystem 2 (bucketing-and-evaluation) is out of scope. -->

## Query topology
None in the plan. Owned read is one `foreign-select-one` path on one partition (PLAN "Reads").
No wasted-read check applies. PASS.

## PState schemas
- Grouping: one PState (`$$flags`) per (FlagId, `|hash`). Option C (split `$$revisions` +
  `$$configs`, same key and partitioner) is rejected in the plan. PASS.
- `Object` leaves at `:off-value`, `:default-value`, rule `:operator`/`:value`/`:serve`,
  rollout `:serve`. Protocol docstring: "Values (:off-value, :default-value, :serve, rule
  :value, attribute values) are arbitrary Clojure values compared with =". Every structural
  position (record key, `:revision Long`, `:killed? Boolean`, `:attribute String`,
  `:threshold Long`, vector, fixed-keys maps) is concretely typed. `Object` here is the
  contract's own type, not hidden structure; `pstate-schema.md` "Use Object for heterogeneous
  values". PASS.
- Uniform record → `fixed-keys-schema`: yes. Nullable `:rollout` on otherwise same-shaped
  instances is not polymorphism. PASS.
- `:rules` not subindexed. Enforcement of whole-record access is the contract itself:
  `get-flag-config` "Returns the stored configuration map exactly as accepted" and README
  "put-flag-config! may do work proportional to its supplied configuration". No operation
  addresses a rule by index; subindexing would cost 1 seek + R iterations per read and R
  element writes per put and break the single `foreign-select-one`. Rules vectors are
  operator-authored (README "Input assumptions": a vector). PASS.
- Round-trip verified (Phase 2 probe with `create-test-pstate` on the plan's exact schema):
  a config without `:rollout` reads back without the key; `:rollout nil` reads back with
  `nil`; `{:threshold 0 :serve false}` reads back `=`; absent key → `nil`;
  `(keypath id :revision)` on an absent key → `nil`. PASS.

## Partitioning
- Depot `*flag-writes` `(hash-by :flag-id)`; `$$flags` keyed by the same FlagId; foreign read
  routes by the same key. Keyspace = every configured flag (tenants × envs × flags: large).
  One small record per key, one write per operator edit; per-flag write serialization is
  required by README ("Writes addressed to the same flag must take effect in the order the
  client invoked them"). No `|all`, no `|global`. PASS.
- Table filled for N = 1/16/128; rows cover configured, never-configured, pre-any-write;
  proportions 0.85+0.10+0.05 = 1.00 each. Weighted seeks recomputed = 1.00 at every N,
  iterator reads 0. Seeks are cluster totals (one task per op). Flat from N = 1 to 128. PASS.
- Justifications cite README only. No assumption about subsystem 2's mechanisms: the plan
  states the one-seek record is a property it delivers, not something it assumes. PASS.
- Placement alternatives: stored placement (1 extra seek per lookup ≥ the read it assists)
  and `|all` (N seeks per put, N× storage) rejected with numbers. PASS.

## Topologies
- Microbatch, default, justified: `put-flag-config!` "is an asynchronous write"; no owned
  write returns a value; no README bound demands millisecond visibility. PASS.
- No stream topology. No test-synchronization reasoning in the topology choice. PASS.

## Production readiness
- Concurrent clients on one flag: same depot partition, append order, each event's
  read+write atomic on the single-threaded task → some serial order; max revision wins;
  first-applied wins on ties; never a blend. PASS.
- Client restart: business state is entirely in `$$flags`; only the transient counter is lost,
  which README permits ("transient synchronization counters are allowed"). PASS.
- Worker restart mid-microbatch: attempt rolls back, replays from the committed offset;
  exactly-once PState updates; the write is also idempotent (equal revision → no-op). PASS.
- Scale: unbounded flags spread by hash; one record per flag; no unbounded inner
  collection (rules are operator-authored per config). PASS.
- No stream topology → no non-idempotent stream writes; every write is single-partition,
  no partial multi-partition failure. PASS.

## Internal depot usage / Cross-topology / Stream commit boundaries
None. PASS.

## In-memory state efficiency
No TaskGlobals. Client-side `append-counts` atom holds one Long per cluster. PASS.

## Minimality — adversarial simplification
Simplest sketch: one client-appendable depot hashed by flag identity, one microbatch topology
that reads the stored revision and replaces the whole map when strictly greater, one PState
keyed by flag identity holding the config, one cumulative append counter for the barrier.
Diff against the plan: identical.

### `*flag-writes` depot
- Delete: no durable, ordered write path; README requires durable Rama state and per-flag
  order. Merge: nothing to merge into. PASS.
### `flags` microbatch topology
- Delete: revision gate would move to the client (read-then-append), which two clients can
  race into accepting a stale write (README "strictly greater wins", multi-client sentence).
  Merge: only topology. PASS.
### `$$flags`
- Delete: no durable current configuration ("All authoritative business state must be durable
  Rama state"). Merge: single PState. PASS.
### shape guard (`well-formed-config?`)
- Delete: a schema-rejecting record throws inside the microbatch and retries forever, blocking
  every flag on that partition. Merge: it is a pure fn on the write path; nothing smaller. PASS.
### shared `append-counts` atom
- Delete: cannot compute the cumulative count `wait-for-microbatch-processed-count` needs
  (harness docstring prescribes exactly this counter). Per-client instead of shared: traced
  below (Synchronizable block) — returns early for a second client. PASS.

## Throughput — adversarial
- `get-flag-config`: 1 seek, 0 iterations, 1 roundtrip. Lower bound for a durable read. PASS.
- `put-flag-config!`: 1 seek (revision) + 1 no-read write. Alternative `(term f)` folding the
  compare into one path op is the same 1 seek; no cheaper design exists because acceptance
  depends on the stored revision. `|all` rejected above. PASS.

## Spec coverage

### `put-flag-config!` — accept iff strictly greater / first write
- Source: "Replace the flag's whole configuration with `config` iff (:revision config) is
  strictly greater than the stored revision (or no configuration is stored yet)." "The first
  write for a flag is accepted at any positive revision."
- Trace: flag `["acme" "prod" "new-checkout"]`, stored nil. Put rev 3 → select `:revision` →
  nil → accept → `termval` whole map. Put rev 100 on a fresh flag → nil → accept. Put rev 2 →
  stored 3, 2 > 3 false → filter drops, no write. Put rev 4 → accept, whole replace.
- Fault tolerance: worker restart → PState durable, replay from committed offset; retry →
  same decision from same state, or equal-revision no-op; single partition → no partial write.
- Race: two clients rev 5 and rev 6 in either order → ends at 6 (max); two clients rev 5 with
  different content → first in the partition's append order kept. Out-of-order across hops:
  no hops (depot partition = PState partition).
- Flaws: none found; the gate reads the stored revision on the owning task inside the event.
- Verdict: PASS.

### `put-flag-config!` — equal revision is a no-op (README step 6)
- Source: "Equal or lower is a stale write: no-op." Step 6: "put-flag-config! ... :revision 3
  :killed? true ... → no-op (equal revision)."
- Trace: stored rev 3 `:killed? false`; put rev 3 `:killed? true` → 3 > 3 false → filtered,
  no `local-transform>`; `get-flag-config` returns the rev-3 map with `:killed? false`.
- Fault/race: as above; a duplicate delivery of an accepted put is this same case.
- Flaws: none found. Verdict: PASS.

### `put-flag-config!` — whole replace, stored `=` accepted (steps 7, 8, 9)
- Source: "No partial updates"; "Returns the stored configuration map exactly as accepted".
- Trace: rev 4 `:killed? true` → `termval` replaces the entire value; rev 5 with rule
  `:operator :gt :value 18` → guard admits (`:operator`/`:value` are `Object`), stored as
  written; rev 6 with no `:rollout` → stored without the key. Probe: each reads back `=`,
  including `:rollout nil` vs absent and `false`/`0` values. `long` coercion keeps `=`.
- Flaws: none found. Verdict: PASS.

### `put-flag-config!` — isolation across triples (step 5)
- Source: "Same subject, different env, different cohort"; IMPLICIT_SPEC "a write to [t e k]
  never changes any other triple."
- Trace: `["acme" "staging" "new-checkout"]` rev 1 and `["acme" "prod" "new-checkout"]` rev 3
  are distinct FlagId records → distinct keys, possibly distinct tasks; each holds its own
  revision. Verdict: PASS.

### Same-flag ordering
- Source: "Writes addressed to the same flag must take effect in the order the client invoked
  them. No ordering is required between different flags."
- Trace: client appends rev 7 then rev 7' (same revision, different content) for one flag with
  `:append-ack`; both land in the same depot partition in append order; `%mb` emits that
  partition sequentially; 7 accepted, 7' rejected. Verdict: PASS.

### `get-flag-config`
- Source: "Returns the stored configuration map exactly as accepted, or nil if the flag has
  never been configured." "must read only the one flag's configuration."
- Trace: never-set key under a populated tenant → `keypath` → nil (step 10); before any write
  → nil; configured → whole map `=`. One seek, no dependence on flag/env/tenant count.
  Never returns rejected content (rejected writes never reach `local-transform>`).
- Verdict: PASS.

### Durable state
- Source: "All authoritative business state must be durable Rama state (depots and PStates);
  transient synchronization counters are allowed."
- Trace: configuration lives only in `$$flags`; the client holds only the append counter.
  Verdict: PASS.

### 2 and 4 tasks, multiple clients (`Synchronizable`)
- Source: "The module runs with both 2 and 4 tasks in private validation. Multiple clients
  wrapping the same deployed module must observe the same business state after the writing
  client synchronizes."
- Trace at 4 tasks: client A appends 3 records (counter 3), waits for 3. Client B (same IPC,
  shared atom) appends 2 (counter 5), waits for 5 → returns only after all 5 processed; A then
  reads B's flag via `foreign-select-one` and sees it. Per-client counter would have B wait
  for 2, already satisfied by A's records — rejected in the plan. Client that never wrote
  waits on the current total → prompt. All-stale batch: records still counted and processed
  before return. At 2 tasks identical (no N dependence). Verdict: PASS.

### Efficiency contract (owned rows)
- Source: "`evaluate` and `get-flag-config` must read only the one flag's configuration."
  "`put-flag-config!` may do work proportional to its supplied configuration, independent of
  other flags."
- Trace: read = 1 record; put = guard over the supplied map + 1 revision read + 1 record write.
  The delivered record makes the `evaluate` row achievable by one read of one key (subsystem 2
  decides where it runs). Verdict: PASS.

## Self-consistency
No entry above names a gap, tradeoff, or partial solution. Plan edits made in this phase
(minor, localized): the Phase 1 "verification item" on round-trip is replaced with the
verified result, and the difficulty log is rewritten in Decision/Basis/Outcome form.

PHASE_VALIDATION:pass
