# Plan Validation

<!-- Phase 2 artifact for hld-url-shortener. Adversarial review of PLAN.md against README.md, protocol.clj, IMPLICIT_SPEC.md. -->

## Query topology
None declared. Every read is one `foreign-select-one` on `hash(alias)`; N == M == 1 for
`resolve-alias`, `get-click-count`, `get-create-outcome` (PLAN Reads table). No wasted reads. PASS.

## PState schemas
- One PState `$$links`; no second PState shares (String alias, hash(alias)). PASS.
- No `Object`. `:link` is a uniform five-field `fixed-keys-schema`; `:expires-at` nullable on a
  shared shape (allowed). PASS.
- Unbounded inner collections subindexed: `:outcomes` (≤1,000/alias, no code cap → subindexed),
  `:click-ids` (≤1,000,000/alias → subindexed). `:link` is a 5-field record. PASS.

## Partitioning
- All writes `|hash alias` via depot `(hash-by :alias)`: 10M keys, hot alias ≤ thousands/s on one
  task, storage per alias ≤ 2.2 KB + own click set. PASS.
- No `|all`. Tables present for N = 1/16/128, categories existing 0.9 + missing 0.1 = 1.0,
  weighted seeks 1.0 at every N (totals, single task each). Flat. PASS.
- Placement alternatives costed (Option B: 2 hops per click at 100,000/s; `|all` hot replication
  ×N create writes with no seek saving). Rejections are numeric. PASS.

## Topologies
- Microbatch only; no `!` method needs ms visibility or an ack return (spec: outcomes read after
  `wait-for-processing!`). No stream topology. No test-observability rationale. PASS.

## Production readiness
- Concurrent clients: all writes for an alias serialize on one depot partition; cross-client order is
  depot order, which the spec allows. PASS.
- Client restart: wrapper holds no business state; the sync counter is harness bookkeeping. PASS.
- Worker restart mid-batch: microbatch replay is exactly-once; each write is also read-guarded
  (recorded outcome / existing link / counted click-id). PASS.
- Scale: 10M aliases, subindexed sets/maps. PASS.
- No stream topology, so no unresolved non-idempotent writes. PASS.

## Internal depots / cross-topology / stream correctness
None. PASS.

## In-memory state
No TaskGlobals. PASS.

## Minimality
Simplest sketch: one depot hashed by alias, one microbatch topology, one PState with link
record + outcome map + click-id set. The plan IS the sketch. Per mechanism:
- `$$links`: delete → nothing readable. `:outcomes`: delete → cannot replay first outcome
  ("first request wins"). `:click-ids`: delete → cannot dedup ("deduplicated per (alias, click-id)").
  `:clicks` counter: delete → `get-click-count` must count the set, violating "fixed work".
  None mergeable further (already one PState). PASS.

## Throughput
Hot reads: 1 seek each; no design does fewer than 1 seek for a durable point read. Click
observation: 2 seeks (link presence, click-id membership) — a single-seek design would require
the click-id set to carry the link's existence, impossible without a second structure. PASS.

## Spec coverage

### create-link! first-wins and reservation
- Source: "The first outcome for a pair ... is durably recorded and replayed"; "alias has ever been
  created ... :rejected :alias-taken".
- Trace: alias `a1`, r1 create (missing link) → link + `outcomes[r1]=:created`. r2 create with
  another URL → link present → `outcomes[r2]=:rejected`. r1 retry with different URL →
  `outcomes[r1]` recorded → no-op. delete `a1`, r3 create → link present (`deleted? true`) →
  `:rejected`. PLAN Writes row `create-link!`.
- Faults: replay → guarded by `outcomes[rid]`; single-partition write, no partial state.
- Races: two clients same alias different rids → sequential on one task, first in depot order
  `:created`; no partitioner hops, no reordering. PASS.

### delete / block / unblock
- Source: "Delete is irreversible. Block is reversible."; no effect on missing alias.
- Trace: missing → read `:link` nil → no write, alias stays creatable. Blocked then deleted →
  `deleted? true`, resolve derives `:deleted` (precedence) forever. Block then unblock → last write
  wins on one task. PASS.

### resolve-alias precedence and fixed work
- Source: ":missing > :deleted > :blocked > :expired > :active"; ":expired holds when ... now >=
  expires-at"; "Read work must be fixed".
- Trace: `expires-at 100`, now 100 → expired; now 99 → active; nil expires-at, now 10^12−1 →
  active; blocked + expired → blocked. One seek on `[alias :link]`, wrapper derivation, no reads of
  `:outcomes`/`:click-ids`. PASS.

### record-click! exactly-once, status-independent, drop when missing
- Source: "deduplicated per (alias, click-id) and counted ... regardless of the link's current
  status"; "Observations for aliases that do not exist are dropped and leave no trace."
- Trace: click c1 before create → `:link` nil → no write, no trace. Create; c1 again → counted
  (count 1). c1 duplicate → member → no-op. Delete; c2 → counted (2). 1,000,000 clicks → each 2
  seeks, independent of set size. Replay → membership guard. PASS.

### get-click-count / get-create-outcome
- 1 seek each; nil / 0 for unknown; immutable outcomes. PASS.

### Ordering and barrier
- Source: "Sequential write calls from one client to the same logical owner (alias) are processed in
  invocation order"; barrier rule.
- Trace: one client create→delete→click on `a1` → one depot partition, `%mb` emits in append
  order, no hops. Barrier: shared counter across all wrappers from one `create-module`;
  `wait-for-microbatch-processed-count` is cumulative. PASS after the fix below.

### 2 and 4 tasks
Nothing depends on task count; depot and PState partitioners agree. PASS.

## FAIL items (localized; fixed directly in PLAN.md)
1. **Sync counter semantics.** Plan increments "before each `foreign-append!`". A failed append
   would overcount and hang every later barrier. Fix: increment after a successful append, before
   the `!` method returns, so "returned before the barrier" ⇒ counted.
2. **Set navigation unspecified.** `[alias :click-ids click-id]` is written as a keypath; sets need
   `(set-elem click-id)` (membership, use `subselect` to get a value instead of zero emits) and
   `NONE-ELEM` (insert). Fix: state the navigators.
3. **Output shape.** Protocol maps must have exactly the documented keys (`:expires-at nil` is a
   present key). Fix: wrapper constructs every returned map explicitly.

## Decision / Basis / Outcome
- Decision: minor-fail. Basis: architecture (single PState, hash(alias), microbatch, wrapper-side
  status derivation) satisfies every traced constraint; defects are wording/navigator/shape level.
  Outcome: PLAN.md edited for items 1–3; proceed to build.

PHASE_VALIDATION:minor-fail
