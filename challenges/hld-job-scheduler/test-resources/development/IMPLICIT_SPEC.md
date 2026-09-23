# Implicit Spec

<!-- Phase 0. Fill in before starting Phase 1 (Plan). After completing this, fill in PLAN.md. -->

Before designing any Rama-specific implementation, write out the implicit assumptions and expectations that a senior engineer would bring to this system based on the requirements and the domain.

For every operation the system exposes, fill in:
- **Latency**: Are the effects of this operation needed in single-digit milliseconds, or is hundreds of milliseconds / seconds acceptable?
- **Throughput**: What drives the volume of this operation and how does it scale with usage?
- **Consistency/correctness invariants**: What must always be true?
- **Data growth and scale**: Which collections are unbounded? What access patterns dominate? What needs efficient range access vs. point lookups?
- **Concurrency behavior**: What happens under concurrent writes to the same entity? What ordering guarantees matter?
- **Edge cases**: Also consider: empty inputs, boundary values, large ranges, missing keys, duplicate operations.
- **Entity state × write matrix**: For each entity that write operations target, list every state that entity can be in (e.g., "does not exist", "active", "completed"). Then for each write operation × entity state, write:

  ```
  Entity state x Write operation
    - related-read-op-1: what would it return after this and why
    - related-read-op-2: what would it return after this and why
  ```

  List every related read for EVERY row — not just the obvious or interesting ones. Without listing each read explicitly, it is easy to miss writes that lead to undesired application behavior that violates common sense. Think through how that write affects all related reads and if any inconsistency is created. ALWAYS assume all read operations can be called in every entity state — users query data in all states. Do NOT skip states because the spec is silent — the implicit spec exists to fill in what the protocol leaves unstated.

Ground these in the specific domain — don't list generic concerns. Every point must be tied to a concrete operation or behavior.

**The spec records requirements, not designs.** Do NOT prescribe implementation decisions — state representations, storage choices, data structures, or mechanisms. Anything written here is treated as a requirement by every later phase and becomes exempt from validation checks it would otherwise fail. State WHAT must be true (latency bounds, invariants, scale facts); leave HOW to the plan.

## Sources

- `challenges/hld-job-scheduler/README.md` (domain model, worked example, efficiency contract, write ordering)
- `challenges/hld-job-scheduler/src/hld_job_scheduler/protocol.clj` (docstrings are part of the contract)
- `lib/harness` `Synchronizable` docstring (`wait-for-processing!` semantics)

## Domain facts every operation shares

- **Execution = immutable DAG + logical clock + per-node state + claim log.** A DAG has 1..32 nodes. All node IDs, worker IDs, claim IDs, and execution IDs are non-empty strings. The clock is an integer starting at 0 and only moves forward.
- **Status is a pure function of current state at the current clock**: `:success` if a result was recorded; else `:running` if a lease exists with `clock < expiry`; else `:ready` if every dependency is `:success`; else `:pending`. A node with zero dependencies and no lease is `:ready` immediately after submission. Any read of status must reflect the current clock, so advancing the clock alone changes statuses (`:running` → `:ready` when a lease expires) without any other write.
- **Lease validity boundary**: valid iff `clock < expiry`. At `clock == expiry` the lease is invalid. A grant at clock `C` gives `expiry = C + 10`.
- **Tokens are per node, strictly increasing, starting at 1**, and never reset. `:attempts` == highest token issued for that node (0 if none). Expiry does not reset tokens; a reclaim continues the sequence.
- **Result is write-once per node** (effect ID `[execution-id node-id]`). Once `:success`, nothing changes that node again: not claims, not completions, not clock advances.
- **Execution status**: `:success` iff every node is `:success`; otherwise `:running` (even at clock 0 with no claims).
- **All writes are asynchronous and fire-and-forget from the caller's view.** Every `!` method returns nothing meaningful; outcomes are only observable through reads after `wait-for-processing!`. Denials and ignored writes must still be *decided* correctly; only `claim!` records its decision (via `get-claim`); `complete!`, `advance-clock!`, and `submit-execution!` leave no observable trace when ignored.
- **Ordering**: writes to the same execution take effect in client invocation order. Writes to different executions are unordered relative to each other. There is no ordering guarantee between two distinct clients.
- **Durability / client independence**: all state is Rama state. A second `wrap-client` over the same running cluster must observe exactly the same reads as the first. No decision, counter, or cache may live in client memory. `wait-for-processing!` on either client must guarantee visibility of that client's own prior writes.
- **Task count is 2 or 4, chosen at random.** All behavior must be identical regardless of task count. Per-execution ordering and atomicity must not depend on which task an execution lands on.
- **Efficiency contract (architecture-neutral)**: each operation may do work proportional to the size of the one DAG it touches (≤ 32 nodes) and nothing else. No operation may scan executions, other executions' claims, or workers. Reads of one execution touch only that execution's state.

## Operations

### `submit-execution!` [execution-id dag]

- **Latency**: Hundreds of ms acceptable. It is a write; visibility is only required after `wait-for-processing!`.
- **Throughput**: One per workflow run. Scales with number of workflow runs, independent of nodes per DAG (bounded at 32).
- **Invariants**:
  - Validation is all-or-nothing and precedes any state change: 0 nodes, >32 nodes, any dependency not a key, or any cycle (including self-dependency) → nothing is stored; `get-execution` returns `nil`, `get-node` returns `nil`, `get-claimable-nodes` returns `[]`, and `get-claim` returns `nil` for any claim-id (unless a claim was separately recorded as `:unknown-execution`, see below).
  - Cycle detection must handle multi-node cycles (`a→b→a`), self-cycles (`a→a`), and cycles embedded in an otherwise valid graph. A valid DAG may have multiple roots and multiple sinks, and may be disconnected.
  - Dependency sets may be empty. A node with an empty set is a root.
  - Existing execution-id → no-op, original DAG retained, clock retained, leases/results/claims retained. A resubmission with a different (even invalid) DAG must not change anything.
  - After acceptance: clock = 0, every node has attempts 0, lease nil, result nil; roots are `:ready`, all others `:pending`; execution status `:running`.
  - Dependency sets are stored as sets; `get-execution :dag` and `get-node :dependencies` return sets equal to what was submitted (order-independent).
- **Scale**: Number of executions is unbounded. Nodes per execution ≤ 32. Node IDs are arbitrary strings (sorting for `get-claimable-nodes` is by string ascending).
- **Concurrency**: Two submissions of the same execution-id in sequence: first wins, second is a no-op. Submission followed by a claim on the same execution from the same client must see the submission first (same-execution ordering). A claim on an execution-id that was rejected must be decided `:unknown-execution`.
- **Edge cases**: exactly 32 nodes → accepted; 33 → rejected. `{}` → rejected. Node whose dependency set contains a valid node and a missing node → rejected as a whole. A DAG where a node is referenced as a dependency but also has its own (valid) dependencies → fine. Dependency sets passed as a non-set collection are not required to be handled (tests supply sets), but treating them as sets is harmless. Unicode/long string IDs are just strings.

### `advance-clock!` [execution-id clock]

- **Latency**: Hundreds of ms acceptable; a write.
- **Throughput**: Driven by the caller's tick rate per execution. Small.
- **Invariants**:
  - Monotonic: `clock <= current` → no-op (including equal). `clock > current` → clock becomes exactly `clock` (jumps are allowed, e.g. 0 → 1000).
  - Unknown/rejected execution-id → no-op; nothing created.
  - Advancing the clock does not alter leases, tokens, attempts, results, or claim records. It only changes what is *derived*: leases with `expiry <= clock` become invalid, so their non-success nodes report `:ready`; `get-node :lease` still returns the expired lease (not cleared), `:attempts` unchanged.
  - Statuses of nodes with `:pending` or `:success` are unaffected by clock advances.
  - A subsequent `claim!` on the same execution must be decided against the advanced clock (per-execution ordering).
- **Scale**: One integer per execution; no growth.
- **Concurrency**: Sequence `advance 10, advance 5, advance 10` → clock 10 (second and third are no-ops). Interleaved with claims in invocation order: `claim (grant, expiry 10) → advance 10 → complete (ignored, expired) → claim (grant token 2, expiry 20)`.
- **Edge cases**: advance to 0 on a fresh execution is a no-op. Advance to exactly `expiry` invalidates the lease. Very large clock values are ordinary integers (use long arithmetic; `expiry = clock + 10` must not overflow in practice for test-sized values but should be computed on longs).

### `claim!` [execution-id node-id worker-id claim-id]

- **Latency**: Hundreds of ms acceptable; a write. Its decision must be computed atomically with respect to the execution's state at apply time.
- **Throughput**: Highest-volume write. Driven by number of workers × polling frequency × nodes. Many denials (`:lease-held`, `:dependencies-incomplete`) are expected under contention; each denial is still recorded as a claim.
- **Invariants**:
  - **Idempotency by claim-id within an execution**: if `claim-id` already decided for this execution-id → no effect whatsoever (no new token, no lease change, no new record, decision unchanged), even if node-id/worker-id differ or the node's state changed. `claim-id` namespaces are per execution: the same `claim-id` string in two different executions are two independent claims.
  - Every *new* claim records a decision, including all denials and including `:unknown-execution`. `get-claim` must return `:unknown-execution` decisions with `:clock 0` for executions that do not exist (and remain readable later, even if that execution-id is subsequently submitted — the decision is immutable).
  - Decision order is strict and first-match: unknown-execution → unknown-node → already-succeeded → dependencies-incomplete → lease-held → granted. E.g. a node that is `:success` and also has stale lease data reports `:already-succeeded`, never `:lease-held`. A node with incomplete dependencies is `:dependencies-incomplete` regardless of any lease (a lease cannot exist there anyway).
  - A grant at clock `C`: token = previous highest token for that node + 1 (first = 1); lease = `{:worker-id w :token t :expiry (+ C 10)}`; attempts increments to `t`; node status becomes `:running`. The recorded decision is `{:claim-id :node-id :worker-id :clock C :granted? true :token t :lease-expiry (+ C 10)}`.
  - A denial records `{:claim-id :node-id :worker-id :clock C :granted? false :reason <kw>}` with `C` = the execution clock at apply time (0 for unknown execution). Denials never change node state, tokens, or attempts.
  - Reclaim after expiry: at `clock >= expiry` the same or a different worker may be granted; the token continues from the previous value (e.g., 1 → 2). The same worker reclaiming its own expired lease gets a *new* token; its old token is dead.
  - A claim by the current lease holder on its own valid lease is denied `:lease-held` (no renewal semantics exist).
  - The recorded `:clock` in a claim is the clock observed at decision time, which is stable even after later `advance-clock!` calls.
- **Scale**: Claims per execution are unbounded over time (every poll is a claim), and must all remain readable via `get-claim` forever (never deleted). Dominant access: point lookup by `[execution-id claim-id]` for idempotency check and `get-claim`. Nodes per execution ≤ 32.
- **Concurrency**: Two claims on the same node from different workers at the same clock, in invocation order: first granted, second `:lease-held`. Two claims with the same claim-id: second is a replay regardless of content. Claim ordering relative to `advance-clock!` and `complete!` on the same execution is invocation order. Claims on different executions are independent and may be applied in any relative order.
- **Edge cases**: claim on rejected execution → `:unknown-execution`. Claim on node-id not in DAG (including empty-string-like garbage, though tests supply non-empty) → `:unknown-node`. Claim replay with a *different* node-id → `get-claim` returns the *original* node-id. Claim right at `clock == expiry` → granted (lease invalid). Claim on a node whose dependencies were all `:success` but which itself has an expired lease → granted with next token. A worker claiming a second node while holding a lease on another → allowed (no per-worker limits). `:unknown-execution` claim followed by `submit-execution!` for that id: the claim record persists as an `:unknown-execution` denial, and a *new* claim-id is required to claim; replaying the old claim-id is a no-op (still denied). Nodes with many dependencies (up to 31) → all must be `:success`.

### `complete!` [execution-id node-id worker-id token result]

- **Latency**: Hundreds of ms acceptable; a write.
- **Throughput**: At most one effective completion per node; ignored completions (stale, expired, duplicate) may be frequent under retries. Scales with nodes × attempts.
- **Invariants**:
  - Effective iff all hold at apply time: execution exists, node exists, node not `:success`, node's *current* lease has exactly this `worker-id` AND exactly this `token`, and `clock < lease expiry`.
  - When effective: node becomes `:success`, `result` stored immutably (any Clojure value; tests use strings and small maps; a `nil` result is permitted and is indistinguishable from "no result" in `get-node :result`, so `:status :success` is the authoritative completion signal, never `:result`), lease becomes `nil`, `:attempts` unchanged (still equals highest token issued). Downstream nodes whose dependencies are now all `:success` become `:ready`.
  - When not effective: *no* state change at all, and no record is kept (there is no `get-completion`). This includes: unknown execution, unknown node, already `:success` (second result never overwrites the first — "at most one result per effect ID"), wrong worker, wrong token (stale or future/never-issued token, e.g. token 0 or token > attempts), expired lease (`clock >= expiry`) even if nobody has reclaimed, and no lease ever granted.
  - The result stored is exactly the value supplied by the first effective completion; equality-preserving round-trip for strings and maps (nested maps, keywords, ints) is required.
  - Completing a node does not alter the clock, other nodes' leases, or claim records.
- **Scale**: One result per node, ≤ 32 per execution. Results are small (strings/small maps).
- **Concurrency**: `complete!` after `advance-clock!` that expired the lease → ignored, per ordering. `complete!` for token 1 after a reclaim issued token 2 → ignored. Two effective-looking completions in a row by the valid holder → first effective, second ignored (result unchanged). Completion interleaved with a claim by another worker on the same node: whichever is applied first wins deterministically by invocation order.
- **Edge cases**: token 0 (never issued) → ignored. Completion at exactly `clock == expiry` → ignored. Completion on the last remaining node → `get-execution :status` flips to `:success`. Completion whose `worker-id` matches but token belongs to that worker's *previous* expired lease → ignored. Completion on a `:pending` node (can have no lease) → ignored.

### `get-execution` [execution-id]

- **Latency**: Single-digit ms desired (a read on the hot path for dashboards/dispatchers); must not do work beyond one DAG (≤ 32 nodes).
- **Throughput**: High; polled by orchestrators per execution.
- **Invariants**: `nil` for unknown or rejected. Otherwise `:execution-id`, `:clock` (current), `:status` (`:success` iff all nodes `:success`, else `:running`), `:dag` (map node-id → set of deps, equal to submitted), `:node-statuses` (every node present, each derived at the current clock using the validity rule). `:node-statuses` must be consistent with `get-node :status` and `get-claimable-nodes` for the same snapshot.
- **Scale**: Output size ≤ 32 entries. Must read only this execution's state.
- **Concurrency**: After `wait-for-processing!` it reflects all of the caller's prior writes. Between two reads with no writes in between, results are identical (no time-based drift; the clock is logical).
- **Edge cases**: fresh execution: clock 0, status `:running`, roots `:ready`. All-success: `:status :success`, every status `:success`. Execution where every node has an expired lease: all `:ready`/`:pending` per deps.

### `get-node` [execution-id node-id]

- **Latency**: Single-digit ms desired.
- **Throughput**: High; workers poll nodes they hold.
- **Invariants**: `nil` if execution or node unknown. Otherwise `:node-id`, `:effect-id [execution-id node-id]` (constant for life of the node), `:dependencies` (set, as submitted), `:status` (derived at current clock), `:attempts` (highest token issued, 0 if none), `:lease` (most recent granted lease *even if expired*; `nil` if never granted or once `:success`), `:result` (value or `nil`; non-nil only when `:success`, and immutable thereafter).
  - Consistency: `:status :running` ⟺ `:lease` non-nil and `clock < expiry` and not success. `:status :ready` with non-nil `:lease` means the lease expired. `:status :success` ⟹ `:lease nil`.
- **Scale**: Point read of one node's state plus its ≤ 31 dependencies' statuses. Must not read other executions.
- **Concurrency**: Consistent with `get-execution` and `get-claimable-nodes` at the same point.
- **Edge cases**: node in rejected execution → `nil`. Node after expiry, before reclaim → `:status :ready`, `:lease` still the old one, `:attempts` unchanged. After reclaim → `:lease` shows new worker/token/expiry, `:attempts` incremented. After success → `:lease nil`, `:attempts` retained, `:result` set.

### `get-claim` [execution-id claim-id]

- **Latency**: Single-digit ms desired; point lookup.
- **Throughput**: Proportional to claims (workers check outcomes of their claims).
- **Invariants**: `nil` if no claim with that id was ever applied for this execution (note: "applied", so after `wait-for-processing!`). Otherwise the immutable decision map exactly as recorded: granted → `{:claim-id :node-id :worker-id :clock :granted? true :token :lease-expiry}`; denied → `{:claim-id :node-id :worker-id :clock :granted? false :reason}`. Never changes after being recorded, regardless of later clock advances, expiries, reclaims, or completions. `:unknown-execution` decisions are readable with `:clock 0` even though the execution does not exist.
- **Scale**: Claims per execution unbounded and retained forever; lookup by claim-id must not scan.
- **Concurrency**: Replayed claims never produce a second record. Same claim-id across two executions → two independent records.
- **Edge cases**: `get-claim` for a claim-id on an execution that exists but never saw that claim-id → `nil`. `get-claim` for a granted claim whose lease later expired → still `:granted? true` with the original `:lease-expiry`.

### `get-claimable-nodes` [execution-id]

- **Latency**: Single-digit ms desired; dispatch hot path.
- **Throughput**: High; every worker polls this to find work.
- **Invariants**: Vector of node-ids with derived status `:ready` at the current clock, sorted ascending by string. Empty vector (not `nil`) for unknown/rejected execution and for executions with nothing ready (all pending/running/success). Must agree exactly with the `:ready` entries of `get-execution :node-statuses`.
- **Scale**: ≤ 32 entries; bounded work per call.
- **Concurrency**: After a completion, newly unblocked nodes appear; after a grant, the node disappears; after clock advance past expiry, the node reappears.
- **Edge cases**: fresh execution → all roots sorted. Fully succeeded execution → `[]`. Sort is lexicographic on strings (`["enrich" "parse"]`, and e.g. `"a10"` before `"a2"`).

### `wait-for-processing!` (harness `Synchronizable`)

- **Invariant**: After it returns, every write this client previously invoked is visible to every subsequent read from *any* client on the cluster. Must be correct for whichever topology kinds the module uses (per harness docstring: microbatch requires tracking appended count; stream with full ack is a no-op).
- **Second client**: a second `wrap-client` constructed against the same cluster must produce identical reads and must be able to issue writes whose effects the first client sees after its own `wait-for-processing!` followed by the second client's; per-client counters must not be assumed to be global.

## Entity State × Write Matrix

Entities: **Execution** (keyed by execution-id), **Node** (keyed by `[execution-id node-id]`), **Claim** (keyed by `[execution-id claim-id]`). Node states are derived at the current clock; the write matrix below uses the derived status plus the lease/token substate that matters for decisions.

Reads considered for every row: `get-execution` (GE), `get-node` (GN), `get-claim` (GC), `get-claimable-nodes` (GCN).

### Execution states

`E0` does-not-exist (never submitted or rejected) · `E1` running (exists, ≥1 node not success) · `E2` success (all nodes success)

```
E0 does-not-exist x submit-execution! (valid dag)
  - GE: full map, clock 0, :status :running, all nodes present, roots :ready, others :pending
  - GN: each node → attempts 0, lease nil, result nil, status ready/pending, effect-id [eid nid]
  - GC: nil for any claim-id (unless previously recorded :unknown-execution — those persist)
  - GCN: sorted roots

E0 does-not-exist x submit-execution! (invalid dag: empty / >32 / unresolved / cycle)
  - GE: nil (nothing stored)
  - GN: nil
  - GC: nil (unchanged)
  - GCN: []

E0 does-not-exist x advance-clock!
  - GE: nil (no-op; must not create a phantom execution)
  - GN: nil
  - GC: nil
  - GCN: []

E0 does-not-exist x claim! (new claim-id)
  - GE: nil (still unknown; a denial must not create the execution)
  - GN: nil
  - GC: {:granted? false :reason :unknown-execution :clock 0 :node-id n :worker-id w :claim-id c}
  - GCN: []

E0 does-not-exist x claim! (replayed claim-id)
  - GE: nil
  - GN: nil
  - GC: the original :unknown-execution record (unchanged)
  - GCN: []

E0 does-not-exist x complete!
  - GE: nil (ignored)
  - GN: nil
  - GC: unchanged
  - GCN: []

E1 running x submit-execution! (same id, any dag)
  - GE: unchanged (original dag, current clock, current statuses)
  - GN: unchanged for every node (leases, attempts, results retained)
  - GC: unchanged
  - GCN: unchanged

E1 running x advance-clock! (clock <= current)
  - GE: unchanged, :clock unchanged
  - GN: unchanged
  - GC: unchanged
  - GCN: unchanged

E1 running x advance-clock! (clock > current)
  - GE: :clock = new value; :node-statuses recomputed: :running nodes with expiry <= clock become :ready
  - GN: for expired nodes :status :ready, :lease still the expired lease, :attempts same; all else unchanged
  - GC: unchanged (recorded :clock/:lease-expiry are historical)
  - GCN: gains the newly expired nodes, sorted

E1 running x claim! / complete!
  - see Node-state rows below; execution-level fields: :clock unchanged; :status becomes :success only when the last node completes (E1 → E2)

E2 success x submit-execution! (same id)
  - GE: unchanged, :status :success
  - GN: unchanged
  - GC: unchanged
  - GCN: []

E2 success x advance-clock! (> current)
  - GE: :clock advances; :status stays :success; all node-statuses :success
  - GN: unchanged except nothing (leases already nil)
  - GC: unchanged
  - GCN: []

E2 success x claim! (new claim-id, any node in dag)
  - GE: unchanged
  - GN: unchanged
  - GC: denied :already-succeeded with current clock
  - GCN: []

E2 success x claim! (new claim-id, node not in dag)
  - GE: unchanged
  - GN: nil for that node-id
  - GC: denied :unknown-node
  - GCN: []

E2 success x complete!
  - GE: unchanged
  - GN: unchanged (:result immutable)
  - GC: unchanged
  - GCN: []
```

### Node states (within an existing execution E1)

`N0` unknown-node (id not in DAG) · `N1` pending (some dep not success) · `N2` ready-never-leased (deps success, attempts 0) · `N3` running (lease valid, clock < expiry) · `N4` ready-expired (deps success, lease exists, clock >= expiry) · `N5` success (result recorded, lease nil)

```
N0 unknown-node x claim! (new claim-id)
  - GE: unchanged
  - GN: nil
  - GC: denied :unknown-node, :clock C
  - GCN: unchanged

N0 unknown-node x complete!
  - GE: unchanged
  - GN: nil
  - GC: unchanged
  - GCN: unchanged

N1 pending x claim! (new claim-id)
  - GE: unchanged
  - GN: unchanged (status :pending, attempts 0, lease nil)
  - GC: denied :dependencies-incomplete, :clock C
  - GCN: unchanged

N1 pending x complete! (any worker/token)
  - GE: unchanged
  - GN: unchanged (no lease → cannot match)
  - GC: unchanged
  - GCN: unchanged

N1 pending x advance-clock!
  - GE: clock changes, node stays :pending
  - GN: unchanged except derived status still :pending
  - GC: unchanged
  - GCN: node absent

N1 pending x (last dependency's complete! becomes effective)
  - GE: node-status → :ready
  - GN: status :ready, attempts 0, lease nil
  - GC: unchanged
  - GCN: node appears, sorted

N2 ready-never-leased x claim! (new claim-id)
  - GE: node-status → :running
  - GN: status :running, attempts 1, lease {w, token 1, expiry C+10}, result nil
  - GC: granted, :token 1, :lease-expiry C+10, :clock C
  - GCN: node removed

N2 ready-never-leased x claim! (replayed claim-id)
  - GE: unchanged
  - GN: unchanged
  - GC: original decision
  - GCN: unchanged

N2 ready-never-leased x complete! (any worker, any token)
  - GE: unchanged
  - GN: unchanged (no lease; token 0/any never matches)
  - GC: unchanged
  - GCN: unchanged

N2 ready-never-leased x advance-clock!
  - GE: clock changes; node stays :ready
  - GN: unchanged
  - GC: unchanged
  - GCN: node still present

N3 running x claim! (new claim-id, any worker incl. current holder)
  - GE: unchanged
  - GN: unchanged (lease, attempts unchanged)
  - GC: denied :lease-held, :clock C
  - GCN: unchanged (node absent)

N3 running x claim! (replayed claim-id)
  - GE: unchanged
  - GN: unchanged
  - GC: original decision
  - GCN: unchanged

N3 running x complete! (holder worker, current token)
  - GE: node-status → :success; :status → :success if it was the last node
  - GN: status :success, :result = given value, :lease nil, :attempts unchanged
  - GC: unchanged (the granting claim still shows :granted? true)
  - GCN: node removed; dependents whose deps are now all success appear

N3 running x complete! (wrong worker OR wrong token)
  - GE: unchanged
  - GN: unchanged (still :running, lease intact)
  - GC: unchanged
  - GCN: unchanged

N3 running x advance-clock! (new clock < expiry)
  - GE: clock changes; node stays :running
  - GN: unchanged
  - GC: unchanged
  - GCN: node absent

N3 running x advance-clock! (new clock >= expiry)  → N4
  - GE: node-status → :ready
  - GN: status :ready, :lease still {w, t, expiry} (not cleared), :attempts unchanged
  - GC: unchanged
  - GCN: node appears

N4 ready-expired x claim! (new claim-id, any worker incl. previous holder)
  - GE: node-status → :running
  - GN: status :running, attempts t+1, lease {new w, token t+1, expiry C+10}
  - GC: granted, :token t+1, :lease-expiry C+10, :clock C
  - GCN: node removed

N4 ready-expired x claim! (replayed claim-id)
  - GE: unchanged
  - GN: unchanged
  - GC: original decision
  - GCN: unchanged

N4 ready-expired x complete! (previous holder with its old token)
  - GE: unchanged
  - GN: unchanged (status :ready, expired lease still shown, result nil)
  - GC: unchanged
  - GCN: unchanged (node still present)

N4 ready-expired x complete! (any other worker/token)
  - GE: unchanged
  - GN: unchanged
  - GC: unchanged
  - GCN: unchanged

N4 ready-expired x advance-clock!
  - GE: clock changes; node stays :ready
  - GN: unchanged
  - GC: unchanged
  - GCN: node still present

N5 success x claim! (new claim-id)
  - GE: unchanged
  - GN: unchanged (:result immutable, :lease nil)
  - GC: denied :already-succeeded, :clock C
  - GCN: unchanged

N5 success x claim! (replayed claim-id)
  - GE: unchanged
  - GN: unchanged
  - GC: original decision (e.g. the grant that led to success)
  - GCN: unchanged

N5 success x complete! (any worker/token/result, incl. the original completer)
  - GE: unchanged
  - GN: unchanged; :result stays the first value
  - GC: unchanged
  - GCN: unchanged

N5 success x advance-clock!
  - GE: clock changes; node stays :success
  - GN: unchanged
  - GC: unchanged
  - GCN: unchanged
```

### Claim states (within an execution, keyed by claim-id)

`C0` not-yet-decided · `C1` decided-granted · `C2` decided-denied

```
C0 not-yet-decided x claim!
  - GC: new immutable record (granted or denied per node/execution rules above)
  - GE / GN / GCN: change only if granted (see node rows)

C1 decided-granted x claim! (same claim-id, any args)
  - GC: unchanged original grant
  - GE / GN / GCN: unchanged (no new token, no lease change)

C2 decided-denied x claim! (same claim-id, any args, even if the node is now claimable)
  - GC: unchanged original denial
  - GE / GN / GCN: unchanged

C1 / C2 x advance-clock! / complete! / submit-execution!
  - GC: unchanged (records are immutable history)
```

## Cross-cutting requirements

- **Atomic decision per write**: each `claim!` and `complete!` must observe a single consistent snapshot of the execution (clock, node leases, dependency results) and apply its effect atomically; no interleaving between the check and the write for the same execution.
- **Retry safety**: reapplying any write must not double-issue tokens, double-record claims, or overwrite a result. Idempotency for `claim!` is by claim-id; for `complete!` by the "not already success + exact lease" check; for `submit-execution!` by existence; for `advance-clock!` by monotonicity. Therefore every write is naturally idempotent under retry provided it is applied atomically.
- **No deletion**: claim records, results, attempts, and expired leases are retained for the life of the module.
- **Per-execution isolation**: work and reads bounded to one execution; independent executions do not affect each other's ordering or correctness.
