# Plan Validation — subsystem `execution-lifecycle`

Inputs: README.md, `protocol.clj`, harness `Synchronizable` docstring, IMPLICIT_SPEC.md
(derived guidance only), DECOMPOSITION.json, `PLAN-execution-lifecycle.md`. Scope is the
first subsystem only: `submit-execution!`, `advance-clock!`, `$$executions`,
`*execution-events`, `lifecycle`, `wait-for-processing!`. Public README/protocol are
authoritative; IMPLICIT_SPEC latency/throughput notes justify nothing.

## Query topology
None declared. No wasted-read check applies. PASS.

## PState schemas
- Grouping by (key type, partitioner): one PState, `$$executions` `{String fixed-keys{:dag :clock}}`, `|hash` on execution-id. No second PState shares the group. Plan explicitly requires later per-execution fields to join this record rather than add a PState. PASS.
- `Object` type: none. PASS.
- Uniform record → `fixed-keys-schema`: yes. PASS.
- Polymorphic positions: none. PASS.
- Non-subindexed inner collection `:dag`: enforced ≤ 32 keys, ≤ 31 refs per key by `valid-dag?` before any write (README: "more than 32 nodes" rejected; dependency values must be keys). Enforcement mechanism named. PASS.

## Partitioning
- `submit-execution!` and `advance-clock!` both write on `hash(execution-id)`: keyspace is every execution ever submitted (unbounded), one ≤ 16 KB record per key, one submit plus caller-driven advances per key. No hot key. PASS.
- No `|all`. PASS.
- Efficiency table: filled for N = 1/16/128; six rows cover every input regime of both writes (typical, stale, unknown, new, resubmit, invalid); proportions sum to 1.0 in each table; weighted sums computed (0.95 seeks, 0 iterator reads); seeks are cluster totals (each op touches exactly one task, so total = 1). Recomputed: 0.55+0.15+0.05+0.15+0.05 = 0.95. Flat across N. Justifications cite only README bounds. No assumption about later subsystems' mechanisms. PASS.
- Placement schemes: rejected with cost arithmetic (a placement lookup costs the same seek `|hash` avoids). PASS.
- **Accounting caveat (minor):** the dataflow sketch performs a `local-select>` and then a nested-field `local-transform>` on the same key for each accepted write. Per paths.md "No-Read Optimizations", navigating into a nested `fixed-keys` record loads it, so the sketch is two accesses of one key while the table counts one. The single-key, flat-across-N conclusion is unchanged. Fixed in PLAN (see Fixes).

## Topologies
- Microbatch by default; both concerns (validate+create, monotonic clock) need no stream semantics. README: all `!` methods asynchronous, visible only after `wait-for-processing!`; no single-digit-ms write exists. PASS.
- No stream topology. PASS.
- No topology choice made on test-synchronization grounds. PASS.

## Production readiness
- Multiple concurrent clients: all writes for one execution land on one depot partition and are applied sequentially by one task. Scenario: clients A and B submit `"run-1"` with different DAGs concurrently; the record earlier in the partition wins, the later one hits `:dag` non-nil → no-op. Matches "Resubmitting an existing ID is a no-op". PASS.
- Client restart: only the transient counter is lost; README permits "transient synchronization counters". Business state in `$$executions`. PASS.
- Worker restart mid-microbatch: microbatch replays from the last committed offset with exactly-once PState commit; both writes are additionally idempotent (`:dag` nil guard, `clock > current` guard). PASS.
- Scale: executions unbounded, spread by hash; per-key record bounded by validation. PASS.
- Non-idempotent stream writes: none (no stream topology). PASS.
- Multi-partition stream writes: none. PASS.

## Internal depot usage
None. PASS.

## Cross-topology correctness
No internal depot flows. PASS.

## Stream topology correctness
No stream topology. PASS.

## In-memory state efficiency
No TaskGlobals. The client-side atom holds one long per IPC. PASS.

## Minimality — adversarial simplification
Simplest sketch: one client-appendable depot hashed by execution-id, one microbatch topology, one PState `{execution-id record}`. The plan is this sketch.

### `*execution-events` depot
- Delete: no durable ingress; README "All authoritative business state must be durable Rama state (depots and PStates)". Kept.
- Merge: nothing to merge into.

### `lifecycle` microbatch topology
- Delete: nothing applies writes. Kept.
- Merge: nothing else exists in this subsystem.

### `$$executions`
- Delete: `get-execution` (later subsystem) needs DAG + clock durably; README Domain model. Kept.
- Merge: nothing else exists.

### Shared sync counter (per-IPC atom)
- Delete (use per-client atom as the harness docstring suggests): client A appends 3 and waits for 3; client B appends 2 and waits for 2, already satisfied → B returns before its writes are processed, violating README "Multiple clients wrapping the same deployed module must observe the same business state after the writing client synchronizes." Kept.
- Merge: it is already the minimal mechanism (one atom, one `swap!` per append).

### Topology-side DAG validation (vs client-side)
- Delete client-side variant: client-side validation saves one depot record per rejected submit; topology-side makes acceptance one durable decision identical for every client and costs no PState I/O on rejection. Both satisfy the README; the plan's choice does not add a mechanism. PASS.

## Throughput — adversarial
Per accepted write the minimum work is one read-modify-write of the execution's record. The plan's sketch does a select then a transform of the same key; merging into one conditional `local-transform>` (see Fixes) removes the duplicate access. Rejected submits cost 0 PState accesses. No cheaper design exists: each accepted write must read its record at least once to decide (existence / monotonicity). PASS after fix.

## Spec coverage — owned operations and constraints

### `submit-execution!` — valid DAG
- Source: "The whole DAG is validated before storing … `get-execution` returns `nil` [if rejected]. Resubmitting an existing ID is a no-op."
- Trace: `{"fetch" #{} "parse" #{"fetch"} "enrich" #{"fetch"} "publish" #{"parse" "enrich"}}` → `valid-dag?`: 4 keys, all refs resolve, Kahn consumes 4 nodes → valid; `:dag` nil → write `:dag` (sets) and `:clock 0`. 32-node chain → Kahn consumes 32 → accepted. 33 nodes → count check fails → no read, no write.
- Fault tolerance: worker restart → replay of the microbatch re-runs the same event; `:dag` guard makes it a no-op if already committed; exactly-once commit anyway. Single partition → no partial write.
- Race: two clients, same id → depot order wins (traced above). Out-of-order across partitioner hops: none (no hops).
- Flaws: none found; validation precedes any read, rejection leaves no trace.
- Verdict: PASS.

### `submit-execution!` — rejected DAGs
- Source: "rejected with no effect if it has 0 nodes, more than 32 nodes, a dependency on a node ID that is not a key, or a cycle (a node depending on itself is a cycle)."
- Trace: `{}` → count 0 → rejected. `{"a" #{"b"} "b" #{"a"}}` → in-degrees 1,1 → Kahn consumes 0 → rejected. `{"a" #{"a"}}` → in-degree 1 never 0 → rejected. `{"a" #{"missing"}}` → subset check fails → rejected. Cycle embedded in a valid graph (`a→b→c→b`, plus root `r`) → Kahn consumes `r`, `a`? No: `a` depends on `b`, so only `r` is consumed → 1 ≠ 4 → rejected. Each leaves `$$executions[id]` `:dag` nil → later `get-execution` nil.
- Fault tolerance: no write to replay.
- Flaws: `valid-dag?` coerces every value with `set`; a non-collection value would throw and stall the microbatch. README "Input assumptions" says dependency values are sets, so the spec is met, but the plan's own "must never throw" rule is not met by the stated definition. Fixed in PLAN (coll? guard).
- Verdict: PASS after fix.

### `submit-execution!` — resubmission and pre-existing claim decisions
- Source (protocol): "If `execution-id` already exists, the call is a no-op; the original DAG is immutable. Submission never removes claim decisions recorded before the execution existed."
- Trace: submit `"run-1"` (4 nodes), advance to 10, resubmit `"run-1"` with a 2-node DAG → `:dag` non-nil → filtered out; `:dag` and `:clock 10` unchanged. Submit after a prior `:unknown-execution` denial stored under the same key by a later subsystem: submit writes only `:dag`/`:clock` via `multi-path`, never a whole-record `termval`, so other fields at the key survive; existence test is `:dag` non-nil, so a key created by a denial still counts as "never submitted".
- Fault tolerance / race: as above.
- Flaws: none found.
- Verdict: PASS.

### `advance-clock!` — monotonic
- Source: "`advance-clock!` is monotonic: a value `<=` the current clock is a no-op." Protocol: "Unknown execution-id: no-op."
- Trace: fresh execution (clock 0): advance 0 → `0 > 0` false → no-op. Advance 10 → write 10. Advance 5 → no-op. Advance 10 → no-op. Advance 1000 → 1000 (jumps allowed). Unknown id `"nope"` → `:dag` nil → no-op, no key created.
- Fault tolerance: replay of `advance 10` when clock is already 10 → no-op. Exactly-once commit anyway.
- Race: two clients advance 10 and 7 concurrently → depot order; either order ends at 10.
- Flaws: none found.
- Verdict: PASS.

### Same-execution write ordering
- Source: "Writes addressed to the same execution must take effect in the order the client invoked them (a `claim!` invoked after an `advance-clock!` on the same execution is decided against the advanced clock)."
- Trace (owned part): same client does `submit "run-1"`, `advance 10`, `advance 5`; all three append to partition `hash("run-1")` in that order; `%mb` emits that partition in append order on the owning task; each event's read+write is atomic → clock 10.
- Cross-subsystem achievability (this subsystem's deliverable per DECOMPOSITION): the plan states same-key ordering "requires one log partition per execution-id". That is necessary but not sufficient: it also requires the ordered event kinds to be applied by one topology in depot order, since two topologies over the same depot progress independently and a second topology reading `:clock` could observe a pre-advance value. Fixed in PLAN by stating the full condition, without designing the later subsystems' mechanisms.
- Verdict: PASS after fix.

### Efficiency contract
- Source: "No operation may do work proportional to the number of executions … Reads of one execution must not read state belonging to any other execution."
- Trace: both writes touch exactly the key `execution-id` on one task; `valid-dag?` is O(V+E) ≤ 32+992 on the one DAG. At 2 tasks and 4 tasks the record for `"run-1"` lives on `hash("run-1") mod N` and the depot partition is the same task, so no hop, no scan.
- Verdict: PASS.

### Durable state
- Source: "All authoritative business state must be durable Rama state (depots and PStates); transient synchronization counters are allowed."
- Trace: DAG and clock in `$$executions`; events in `*execution-events`; only the append counter is client-side. Worker restart at 2 or 4 tasks: PState reloads from disk, microbatch resumes from committed offset.
- Verdict: PASS.

### `wait-for-processing!` with a second client
- Source: "Multiple clients wrapping the same deployed module must observe the same business state after the writing client synchronizes." Harness: "track the cumulative depot append count … call wait-for-microbatch-processed-count … for each microbatch topology."
- Trace (4 tasks): client A appends submit + 2 advances (counter 3), waits for 3 → its writes committed. Client B appends 2 advances (counter 5), waits for 5 → B's writes committed; A now reads clock via `foreign-select-one` and sees B's value. Reverse: B reads without syncing after A synced → sees A's committed writes (foreign reads see committed state). Per-client counting would let B's wait return at 2 (already satisfied by A's 3) → fails; the shared per-IPC atom prevents this. At 2 tasks identical: processed count is module-wide, not per task.
- Later topologies: the harness docstring requires waiting on every microbatch topology; the plan states later write kinds must count through the same counter and wait on every topology added. That is the harness's requirement, not an assumed mechanism.
- Verdict: PASS.

### Retry / replay ordering
- Source (IMPLICIT_SPEC, consistent with README): "reapplying any write must not … overwrite a result … for `submit-execution!` by existence; for `advance-clock!` by monotonicity."
- Trace: microbatch attempt fails after processing `advance 10` but before commit; retry re-emits the partition from the committed offset; PState state is the committed (pre-10) state; `advance 10` applies once. If the failure is after commit, the offset has advanced and the event is not re-emitted.
- Verdict: PASS.

## Self-consistency
No entry above calls anything a gap or tradeoff. Three items are "PASS after fix"; all three fixes are localized edits to `PLAN-execution-lifecycle.md` applied in this phase (see below). No architectural change.

## Fixes applied to PLAN-execution-lifecycle.md
1. Dataflow sketch: each write is one conditional `local-transform>` (existence / monotonicity checked inside the path with `not-selected?` / `selected?`), so the one read per accepted write in the efficiency table is what the topology does. Prose about `keypath … :clock` + `termval` being read-free removed.
2. `valid-dag?`: added `(every? coll? (vals dag))` before `set` coercion so the function cannot throw.
3. Depots: ordering statement now reads "one depot partition per execution-id AND applied by one topology in depot order"; two topologies over the same depot do not preserve cross-kind order.
4. Design difficulty log rewritten in Decision / Basis / Outcome form per the artifact-plan template.

## Verdict
PHASE_VALIDATION:minor-fail
