# Plan Validation

<!-- Phase 2 artifact for hld-notification-system. Adversarial review of PLAN.md against README.md, protocol.clj, IMPLICIT_SPEC.md. -->

## Query topology
None. Point reads 1 seek; page reads 1 seek + ≤100 iterations via `sorted-map-range-to-end 100`
(tail navigator, paths.md). N == M for every read. PASS.

## PState schemas
- `$$users` (user-id, hash(user)) and `$$submissions` (submission-id, hash(sid)): different key and
  partitioner → split justified. `$$task-pos` (task-id, local-only writes): a per-task counter has
  no business key to share. `$$winners` is a materialized temporary. PASS.
- No `Object`. Devices, deliveries, dead letters, submission header: `fixed-keys-schema`;
  `:next-attempt-at` nullable on a shared shape. PASS.
- `:devices` ≤8 enforced by the register rule; `:prefs` ≤16 by grammar; `:deliveries` ≤8 (derived
  from devices) → inline. `:recent`, `:dead-letters` ≤100,000/user → subindexed. PASS.

## Partitioning
- User-owned writes `hash(user-id)`, submission-owned `hash(submission-id)`: 100M users, unbounded
  ids; no key holds disproportionate storage. No `|all`. PASS.
- Tables N = 1/16/128, proportions 0.80+0.15+0.05 = 1.0, weighted seeks 1.0 (totals, one task per
  read), flat. Alternative (record on user partition + sid index) costed: 2 hops + 2 seeks on
  300,000/s attempts and a 2-seek query for `get-submission`. PASS.

## Topologies
Microbatch only. Non-idempotent writes (generation bump, `submit-seq`/`dl-seq`/`task-pos`, list
appends) are replayed exactly once; the submit path (block 1 → block 2) and conditional attempt hops
are one transaction (microbatch "Transaction scope"). Batch shape uses only documented constructs:
`<<batch` sequencing, `filter>`, `+group-by` with `aggs/+limit [1] … {:sort}` (aggregators.md §6:
batch-only, multiple vars, sort var), `materialize>` and a `($$t :> …)` source in the next block
(batch.md "Materialization"), `ops/current-task-id`. Rank is a Long so `:sort` needs no vector
comparison. No stream, no observability rationale. PASS.

## Production readiness
- Concurrent clients: per-owner serialization on one depot partition; same-id contenders across
  owners arbitrated by `(task, pos)` rank (fix 4); cross-owner writes have no spec ordering; a
  report for an unprocessed submission is ignored by rule 1 (spec-conformant). PASS after fix 4.
- Client restart: no business state in wrapper. PASS.
- Worker restart mid-submit (after block 1, before block 2): whole attempt is reset and replayed;
  `$$task-pos` and `submit-seq` are restored by the prime phase, so ranks and seqs are identical on
  replay and the same winner is chosen; no duplicate delivery, no duplicate recent entry, no double
  generation bump. PASS.
- Scale: subindexed lists; inline parts bounded. PASS.

## Internal depots / cross-topology / stream / TaskGlobals
None. PASS.

## Minimality
Sketch: one depot hashed by owner, one microbatch topology, user PState + submission PState.
Plan equals sketch. `submit-seq`: delete → recent order would be hop-3 arrival order from many
submission tasks (multi-upstream, not FIFO), violating one-client invocation order. `dl-seq`: delete
→ no newest-first key for the dead-letter page. Hop 3: bypass by writing `recent` on hop 1 → losers
of first-wins would appear in recent ("retries are ignored"). `$$task-pos`: delete → rank would need
`[task user seq]`, a vector sort key whose support in `+limit` is undocumented; keep the Long. PASS.

## Throughput
`report-attempt!` 1 seek + rare conditional hop; `submit!` 2 seeks at user (profile, task-pos), 1 seek
at sid, 1 blind write at user, plus the `+group-by` hop that replaces the former direct `|hash sid`. A 2-hop submit (skip the return) needs recent written before first-wins is known —
rejected above. PASS.

## Spec coverage

### submit! first-wins across recipients, atomic fan-out, snapshot
- Source: "If submission-id was already submitted (by anyone): no effect"; "one delivery per
  currently valid device ... using that device's current token and generation".
- Trace: user A has d1(gen 2, valid), d2(gen 1, invalid), category "news" enabled. c1 submit S1
  for A → block 1: seq 5, rank (tA, 41), deliveries {d1 {token, gen 2, pending, 0, next-attempt-at
  now}}; block 2: absent → write record, status `:dispatched`; `recent[5]=S1`. c2 submit S1 for
  user B in a later microbatch → B seq 9 (gap); block 2: present → stop; B's recent unchanged,
  record still owned by A. Disabled category + no devices → `:suppressed` (preference checked
  first). All hops one microbatch. PASS.

### Crossed submissions in one microbatch (serial-history consistency)
- Source: "If submission-id was already submitted (by anyone): no effect ... (first submission
  wins)"; "Sequential write calls from one client to the same logical owner ... are processed in
  invocation order"; IMPLICIT_SPEC "exactly one recorded, whichever is processed first".
- Trace (plan before fix 4): client 1 `submit(a,u); submit(b,u)`, client 2 `submit(b,v);
  submit(a,v)`, u on task 0, v on task 1, one microbatch. Hop 2 arrivals at `hash(a)` and `hash(b)`
  are independent, so b→u and a→v can both win. Any serial history must have a(u) < b(u) (client 1)
  and b(v) < a(v) (client 2); b→u wins ⇒ b(u) < b(v); a→v wins ⇒ a(v) < a(u): a cycle. The plan
  relied on undocumented arrival order. FAIL → fix 4.
- Trace (after fix 4): block 1 assigns pos in depot order: a(u) rank (0,1), b(u) (0,2), b(v) (1,1),
  a(v) (1,2). `+group-by a` contenders {(0,1),(1,2)} → min (0,1) = u; `+group-by b` contenders
  {(0,2),(1,1)} → min (0,2) = u. Winners a→u, b→u; serial history a(u), b(u), b(v), a(v) respects both
  clients. With tasks swapped (u on 1, v on 0): b→v, a→v; history b(v), a(v), a(u), b(u). Both
  outcomes are valid serial histories; neither is a cycle; the choice is deterministic given
  placement, not arrival. `recent`: u gets [b a] or nothing, v the mirror; losers' seqs are gaps.
  Same client, same id twice for one user in one batch → ranks (t,k) < (t,k+1) → first wins,
  second is a gap. Existing record from an earlier microbatch → block 2 stops before writing. PASS.
- Same-owner registry order across blocks: `register(u,d3)` then `submit(a,u)` then
  `register(u,d4)` in one batch → all three in block 1's pre-agg on `hash(u)` in depot order: the
  submit snapshots d3 but not d4. A separate block for registrations would have broken this. PASS.

### report-attempt! guard order, retries, dead letters, expiry
- Source: rules 1–5; "A stale report never expires a delivery."
- Trace: S1/d1 submitted at 100, ttl 50 → expires 150. Attempt 1 at 99 → early (next 100) →
  ignored. Attempt 1 transient at 100 → attempts 1, next 110. Attempt 1 again at 200 → attempt-no
  ≠ 2 → ignored, NOT expired. Attempt 2 at 109 → early. Attempt 2 transient at 110 → next 130.
  Attempt 3 accepted at 150 → ≥ expires → `:expired`, attempts 2, outcome discarded, no dead
  letter. Alternate: attempt 3 transient at 130 → `:failed`, dead letter {S1 d1 :retries-exhausted
  130} appended at `dl-seq`. Replay → attempt-no guard. PASS.

### invalid-token generation guard
- Source: "device ... is marked invalid iff its CURRENT generation equals the delivery's :generation".
- Trace (device refresh before stale invalidation): delivery snapshot gen 2; user re-registers d1
  → gen 3 (barrier); attempt 1 `:invalid-token` → delivery `:invalid-token`; block 3 effect hop to
  `hash(A)`: 3 ≠ 2 → device stays valid, token is the refreshed one. Without re-register: 2 = 2 →
  `valid? false`; later submit → no delivery for d1. Re-register after invalidation → gen 4, valid
  again. PASS.

### record-receipt! monotone, pending ignored
- Trace: pending + `:read` → ignored (not deferred); accepted → `:read` then `:delivered` → stays
  `:read`; expired + receipt → ignored. PASS.

### register-device! limit and generation
- Trace: 8 devices, new id → no-op; existing id → gen+1, valid, token replaced even if identical.
  Replay exactly-once. PASS.

### get-recent-submissions / get-dead-letters page work
- Source: "Read work bounded by the page size, independent of total history."
- Trace: 100,001 submissions → `sorted-map-range-to-end 100` = 1 seek + 100 iterations, wrapper
  reverses; gaps from losers skipped by the range. Same for dead letters. PASS.

### get-devices / get-preferences / get-submission
1 seek, inline values, `{}`/nil defaults. PASS.

### Ordering, barrier, 2/4 tasks
Per-owner depot partition; shared cumulative counter; task count irrelevant. PASS after fix 1.

## FAIL items (localized; fixed directly in PLAN.md)
1. **Sync counter semantics.** Increment after a successful `foreign-append!`, before the method
   returns.
2. **Hop-1 read shape.** "read devices + prefs (same seek)" reads the top-level user value, which
   also carries the subindexed `:recent`/`:dead-letters` handles. Fix: group `:devices`, `:prefs`,
   `:submit-seq`, `:dl-seq` under one inline `:profile` record so hop 1, `get-devices`,
   `get-preferences`, and the invalidation hop read a plain record with one seek.
3. **Output shape.** Delivery maps must carry exactly five keys (`:next-attempt-at nil` present);
   `get-submission` exactly eight. Fix: wrapper constructs returned maps explicitly.
4. **First-wins by arrival order at the submission task.** Crossed submissions from two owners can
   produce winners that no serial history explains (trace above). Fix: rank every contender at the
   owner task by `(task-id, per-task pos)` in the same synchronous step as `submit-seq`; arbitrate
   per id with `+group-by *sid` + `aggs/+limit [1] … {:sort *rank}` in block 1; persist the minimum
   in block 2 only when no record exists. The contract text is unchanged; "processed first" is now
   a deterministic order extending every per-owner order.

## Decision / Basis / Outcome
- Decision: minor-fail. Basis: two-owner placement with seq-stamped recent list, guard-ordered
  attempt function, and generation-compared invalidation satisfy every traced rule; item 4 changes
  the submit path's batch structure but not the PState layout, partitioning, or read design, and
  uses only documented batch constructs (a construct that failed at build time would fall back to
  `+vec-agg` + min, same semantics). Outcome: PLAN.md edited for items 1–4; proceed to build.
- Tests to add in Phase 5: the crossed-submission case at 2 and 4 tasks through two wrappers
  (assert exactly one owner per id and that the pair of winners is one of the serial-consistent
  outcomes), and device refresh before a stale `:invalid-token` report.

PHASE_VALIDATION:minor-fail
