# Plan Validation — hld-ticketing-system (phase 2, private, fresh pass)

Inputs: README.md, `src/hld_ticketing_system/protocol.clj`,
`test-resources/IMPLICIT_SPEC.md`, `test-resources/PLAN.md` (post
phase-1 repair), skill `references/batch.md`, `microbatch.md`,
`dataflow.md`, `aggregators.md`, `paths.md`, `pstate-schema.md`,
`operate.md`, `testing.md`, `lib/harness`. Supersedes the previous
validation in full.

Verdict: **pass** — every check below passes after scenario tracing; no
edit to `PLAN.md` was required.

## Rama semantics relied on (cited)

| Claim | Source | Consequence |
|---|---|---|
| `%mb` emits per task in depot append order; microbatches are sequential | `microbatch.md` | Ingress positions in append order; no overlap between loops of successive microbatches |
| Owning-topology reads see uncommitted writes; outside readers see committed state | `microbatch.md` "Read visibility" | Loop sees its own earlier commands; queries never see a half-applied hold |
| `local-transform>` allowed in microbatch pre-agg; `+group-by` auto-partitions; post-agg has no partitioners | `batch.md` | Stamp precedes the only partitioner |
| `+vec-agg` order undocumented | `aggregators.md` | Explicit sort by position present |
| Yielding gives up arrival ordering; yielded reads use a snapshot | `dataflow.md` | One event per event-id per microbatch makes yields safe |
| `termval` replaces a map; deleting a parent of a subindexed structure orphans elements; absent fixed-keys fields navigate to nil | `paths.md`; `pstate-schema.md` | Event entry never written whole; existence = `:clock` non-nil |
| `depot.microbatch.max.records` per-depot dynamic option via `set-launch-depot-dynamic-option!` | `microbatch.md`; `operate.md` | Batch bound correctly scoped |
| `sorted-map-range-from start {:max-amt n}` + `ALL` → `[k v]` pairs | `paths.md` | Compensation pages recover `:seq` from keys |
| Processed count cumulative, persists across `update-module!` | `testing.md` | Shared atom valid |

## ETL structure review (ordered per-event batching)

- Ingress stamp: `PLAN.md` lines 186–190, pre-agg, before `+group-by`, on the depot task = PState task for `event-id`. Synchronous, append order. PASS.
- Explicit sort by position (line 197). PASS.
- One loop per event-id (lines 194–209); every command branch unifies into the request write and one `continue>` (lines 312–314). PASS.
- Position (`:ingress-seq`) distinct from `:next-comp-seq` and from the clock; not part of the `=` payload (depot record). PASS.
- Bounded buffering: `depot.microbatch.max.records` = 200 (line 235); worst case ≈ 26 MB of 1000-seat `add-seats!` records; transient. PASS.
- No next batch before commit; no exposure before commit; single task per event. PASS.
- Yielding inside `add-seats!` (1000-key `submap` with `:allow-yield?`, write loop with `yield-if-overtime`) cannot admit another command of the same event: no other event for that event-id exists in the microbatch. PASS.
- Build-time verifications have pre-agg-only fallbacks (`materialize>` + second `<<batch`; explode + `+map-agg` for `submap`). PASS.

## Query topology: `hold`
- Examples: existing hold N=2, M=2; unknown event N=1, M=1; unknown hold N=2, M=2 (nil answers). Variable; `<<if` on the clock read; one emit per branch; emits a plain record (no subindexed child) with derived `:state`, `:hold-id`, `:payment-ref`. PASS.

## Query topology: `seats`
- Examples: 64 known seats N=65, M=65; 1 seat N=2, M=2; unknown event N=1, M=1. Each read answers a required key (nil for unknown seats is required output). Variable by input list; no padded reads. PASS.

## PState schemas
- One PState `$$events` by `event-id`, hash. Object: none. Fixed-keys for uniform records: yes. `:payload`/`:outcome` via `definterface` + `defrecord`: yes. Subindexed: `:seats`, `:holds`, `:compensations`, `:requests`. Non-subindexed `seat-ids` in a hold (≤ 8) and `unavailable-seats` (≤ 8) bounded by client structural validation. PASS.
- Field-level blob review: the largest stored value is a request payload of a 1000-seat `add-seats!` (≈ 128 KB), bounded by README; nothing grows with history. PASS.

## Partitioning
- `(hash-by :event-id)`; per-event ordering and atomic multi-seat holds force one task per event; events are many. Table N = 1/16/128 present; 0.40+0.35+0.15+0.10 = 1.00; seeks/op = 1 flat. PASS.

## Topologies
- One microbatch topology; no low-latency write requirement; no stream; no test-synchronization reasoning. PASS.

## Production readiness
- Concurrent clients serialized per event on one task; client holds only handles + sync atom; worker restart → exactly-once (compensation seq, seat acquisition roll back with the attempt); scale → all growing collections subindexed. PASS.

## Internal depot usage / cross-topology / stream correctness / in-memory state
None / single topology / no stream / no TaskGlobal.

## Minimality — adversarial simplification
Sketch: one depot by event, one microbatch topology, one PState with subindexed seats/holds/compensations/requests, foreign selects for outcome/clock/compensations, query topologies for hold and seats. Diff: ordered batching only.
- Ordered batching: without it, either a 200-command batch with 1000-seat adds runs synchronously (cooperative rule violated) or per-record yields let hold B read seats between hold A's check and A's seat writes (README "one active hold per seat", ordering). TaskGlobal queue does not roll back; durable inbox adds state. Keep.
- `*commands`: split breaks "hold, advance, hold, confirm" ordering and cross-type conflicts. Keep.
- `core`: stream duplicates compensation records and seat acquisition on retry. Keep.
- Denormalized `user-id`/`deadline` on seats: delete → `get-seats` needs up to k hold reads (1+2k vs 1+k seeks); costed. Keep.
- `hold`/`seats` query topologies: bypass = 2+ roundtrips. Keep.
- `:next-comp-seq`: contiguous compensation seqs required. Keep. Expiry index: correctly absent (README forbids per-hold background work).

## Throughput — adversarial
- `get-seats` 1 + k seeks (arbitrary keys, no range). `hold-seats!` 2 + 8 seeks. `advance-clock!` one field write. `get-compensations` 1 seek + limit. Ingress stamp adds one navigation of an entry every command already reads plus one field write. No cheaper construction.

## Spec coverage — trace every operation and constraint

### `create-event!` / `add-seats!`
- Source: "`:event-exists`"; "Atomic: either every seat is added or none is"; "`:seat-exists` any `seat-id` already exists".
- Trace: create "e1" → `:clock` nil → write fields `:clock 0`, `:next-comp-seq 0` only. Re-create → `:event-exists`. add 1000 seats with one known → `submap` (allow-yield) finds it → `:seat-exists`, nothing written. add 999 new → 999 write-only records in a yielding loop, `AddSeatsAccepted 999`. PASS.

### `advance-clock!`
- Source: "`:clock-regression` `now` is less than the current clock"; "`now` equal ... accepted with no change".
- Trace: clock 5 → now 5 accepted `:clock 5`; now 4 rejected; now 2^53 accepted, one field write, no seat or hold touched. PASS.

### `hold-seats!`
- Source: order `:no-such-event`, `:no-such-seat`, `:deadline-passed`, `:seat-unavailable`; "A seat covered only by an inactive (expired) hold is available"; "`:unavailable-seats`, the vector of offending seat-ids in the order given".
- Trace: clock 10; s1 held h1 deadline 10 (inactive), s2 held h2 deadline 20, s3 confirmed, s4 released (fields nil), s9 absent. Hold g `[s4 s2 s1 s3]` deadline 15 → all exist; 15 > 10; unavailable `[s2 s3]` in given order → rejected, no writes; `get-hold g` → nil. Hold g2 `[s1 s4]` deadline 15 → accepted: both seat records `{:hold-id g2 :user-id u :deadline 15 :confirmed? false}`, hold record `:held`; h1 stays expired. Hold with s9 → `:no-such-seat` before the deadline check. Deadline 10 at clock 10 → `:deadline-passed`. PASS.

### `confirm-hold!` — fencing, expiry, compensation
- Source: order `:no-such-event`, `:no-such-hold`, `:not-owner`, `:hold-confirmed`, `:hold-released`, `:hold-expired`; "an expired hold is never revived, whether or not its seats have been re-held since"; compensation rule.
- Trace: h1 (owner u1, deadline 10, `:held`), clock 10, s1 re-held by g2. confirm h1 by u1 ref p1 → `10 >= 10` → `:hold-expired`; compensation seq 1 written at `:compensations 1`, `:next-comp-seq` 1; outcome carries `:compensation-seq 1`; no seat written; g2 untouched. confirm h1 by u2 → `:not-owner`, no record. confirm h2 (active) by owner ref p2 → hold `:confirmed` + `payment-ref`; each of its seats rewritten `{:hold-id h2 :user-id owner :deadline d :confirmed? true}` (write-only; the seat's holder is h2 by the one-active-hold invariant). Second confirm of h2 under a new rid, ref p2 → `:hold-confirmed`, no record; ref p3 → record 2. Replay of the compensated confirm → step 1 exits; still one record. Two different rids with the same payment-ref after expiry → two records, seqs contiguous. PASS.

### `release-hold!`
- Source: order incl. `:hold-confirmed`, `:hold-released`, `:hold-expired`; "Releasing never affects seats that belong to another hold".
- Trace: release confirmed h2 → `:hold-confirmed`. Release active h3 → seats reset to nil fields, hold `:released`; a following hold on those seats accepted. Release again → `:hold-released`. Release expired h1 (seats re-held by g2) → `:hold-expired`, no seat write. PASS.

### Reject-before-create → create → original read → replay → conflict
- Trace: fresh event "e2". (1) hold rid "h0" on "e2" → `:clock` nil → `:no-such-event`; request record `h0` under "e2"; `:ingress-seq` 1. (2) create "e2" → writes `:clock 0`, `:next-comp-seq 0` fields only (lines 266–271); `h0` survives. (3) `get-outcome "e2" "h0"` → `:rejected :no-such-event`, counter 0. (4) replay `h0` identical → no-op, still rejected. (5) `h0` with a different deadline → counter 1, no hold. Same in one microbatch (positions 1..5) or across barriers. PASS.

### `get-seats`, `get-hold`, `get-clock`
- Trace: s1 `{:hold-id h1 :deadline 10}` at clock 10 → `{:state :available :hold-id nil :user-id nil}`; at clock 9 → `:held h1`. Duplicate seat-ids collapse (client dedups). `get-hold h1` at clock 10 → `:expired`; confirmed hold → `:confirmed` with `:payment-ref`. `get-clock` unknown → nil. PASS.

### `get-compensations`
- Trace: 1200 records, after 500, limit 500 → one seek + 500 iterations → seqs 501..1000 via `ALL` pairs + client `assoc :seq`; `Long/MAX_VALUE` → `[]`; unknown event → `[]`. PASS.

### Idempotency / validation order / ordering / durability / shared state
- Replay of an accepted hold after expiry and re-hold → no-op; conflicting payload → counter +1 (request record has no subindexed child); malformed 9-seat hold with used rid → client throws. "hold A, hold B (overlap), advance past A, hold C, confirm A" in one microbatch → positions 1..5 in one loop: A accepted, B `:seat-unavailable`, clock advanced, C accepted, confirm A `:hold-expired` + compensation 1, C active. Nothing deleted (released seats reset per spec; hold records retained). Second client shares the atom. PASS.

### Resource guarantees (own history)
- Event with 10⁵ seats, 10⁶ holds, 10⁶ expired: `hold-seats!` 2 + 8 seeks; `get-seats` 1 + k; `advance-clock!` 1 read + 1 write; `get-compensations` after 999,000 = 1 seek + limit. PASS.

## Findings and fixes applied
None. The plan already applies every prior fix (compensation pages via `ALL` + client `:seq`, `Long/MAX_VALUE` guard, field-path creation, `:hold-id` in the `hold` result).

## Test plan requirements (for phase 5)
- Both 2- and 4-task deployments; second `:wrap-client` visibility; `rtest/update-module!` with the same module then seats/holds/clock/compensations/outcomes unchanged and a further compensation seq continues; explicit `wait-for-processing!` before every read.
- Independent expectations from test inputs (deadlines 7, 11, 12; clock at exactly a deadline and at deadline − 1; holds of 1, 3, 8 seats with the last seat the blocker; distinct payment-refs).
- Reject-before-create scenario as traced; rejected outcomes preserved unchanged after creation.
- Deep-page scaling on compensation pages at high `after-seq`; field-level no-blob review of stored records.
- Efficiency capture: account for the fixed ingress navigation + field write per command; keep scan detection sensitive to growth in seats/holds.
- No assertions on topology names, microbatch counts, or tight timing.
- Targeted mutants: `clock <= deadline` treated as active; confirm reviving an expired hold; release touching re-held seats; compensation on `:not-owner` or same-ref `:hold-confirmed`; replay creating a second compensation; `:unavailable-seats` reordered; missing `:seq` in compensation pages; outcome lost after `create-event!`.
