# Plan Validation — hld-hotel-reservation (phase 2, private, fresh pass)

Inputs: README.md, `src/hld_hotel_reservation/protocol.clj`,
`test-resources/IMPLICIT_SPEC.md`, `test-resources/PLAN.md` (post
phase-1 repair), skill `references/batch.md`, `microbatch.md`,
`dataflow.md`, `aggregators.md`, `paths.md`, `pstate-schema.md`,
`operate.md`, `testing.md`, `lib/harness`. Supersedes the previous
validation in full.

Verdict: **minor-fail** — one localized clarification made directly in
`PLAN.md` (see "Findings and fixes applied"). The architecture (nested
room-type → night Long-keyed maps, bookings, events, requests; one
microbatch topology with ordered per-property batching; one query
topology) stands.

## Rama semantics relied on (cited)

| Claim | Source | Consequence |
|---|---|---|
| `%mb` append order; sequential microbatches; owning-topology read visibility | `microbatch.md` | Positions in append order; loop sees its own earlier reservations; outside readers see committed availability only |
| Pre-agg `local-transform>` allowed; `+group-by` auto-partitions; post-agg no partitioners | `batch.md` | Stamp precedes the only partitioner |
| `+vec-agg` order undocumented | `aggregators.md` | Explicit sort present |
| Yielding gives up arrival ordering | `dataflow.md` | Safe because one event per property per microbatch |
| `termval` replaces a map; parent deletion orphans subindexed children; absent fields navigate to nil; subindexed child reached via parent is a handle that cannot cross the wire | `paths.md`; `pstate-schema.md`; `foreign-client.md` | Property and room-type entries never written whole; `availability` emits only the derived vector |
| `sorted-map-range start end` is `[start, end)`; on nil → empty submap; Long keys sort numerically | `paths.md`; `pstate-schema.md` | A stay is one seek + ≤ 30 iterations; unconfigured range → `{}` |
| `depot.microbatch.max.records` per depot via `set-launch-depot-dynamic-option!` | `microbatch.md`; `operate.md` | Batch bound correctly scoped |
| `sorted-map-range-from` + `ALL` pairs | `paths.md` | Event `:seq` recovered from keys |
| Processed count persists across `update-module!` | `testing.md` | Shared atom valid |

## ETL structure review (ordered per-property batching)

- Ingress stamp in pre-agg (`PLAN.md` lines 188–192), synchronous, before `+group-by`, on the depot task = PState task. PASS.
- Explicit sort by position (line 199). PASS.
- One loop per property (lines 196–210); each command's branches unify into the request write and one `continue>` (lines 304–306). PASS.
- `:ingress-seq` distinct from journal `:next-seq`; not in the `=` payload. PASS.
- Bounded buffering: `depot.microbatch.max.records` = 1000 (≈ 450 KB per task); transient. PASS.
- No next batch before commit; no exposure before commit; single task per property; a reservation's ≤ 30 night writes commit together. PASS.
- No yield inside a command (≤ 30 nights); `yield-if-overtime` between commands cannot admit a same-property command. PASS.
- Build-time verification fallbacks are pre-agg-only. PASS.

## Query topology: `availability`
- Input 1 (30 configured nights): 2 point reads + 1 range seek (30 iterations), all meaningful. Input 2 (room type exists, nothing configured): the range returns `{}`, which is the required all-nil vector (not a padded read). Input 3 (unknown property/room type): `:exists?` nil → result nil, range skipped by `<<if`. Variable, handled; one emit; emits only a plain vector. PASS.

## PState schemas
- One PState `$$properties` by `property-id`, hash. Object: none. Fixed-keys: yes. `:payload`/`:outcome` polymorphic via `definterface` + `defrecord`: yes. Subindexed: `:room-types`, `:room-types.*.nights`, `:bookings`, `:events`, `:requests`. Room-type entry `{:exists? :nights}` owns a subindexed child and is only ever field-written. PASS.
- Field-level blob review: no stored value grows with history. PASS.

## Partitioning
- `(hash-by :property-id)`; per-property ordering and atomic multi-night reservation force one task per property; properties many. Table N = 1/16/128; 0.50+0.25+0.15+0.10 = 1.00; seeks/op = 1 flat; iterator reads depend only on the requested range. PASS.

## Topologies
- One microbatch; no low-latency write; no stream; no test-sync reasoning. PASS.

## Production readiness
- Concurrent clients serialized per property; client holds only handles + atom; worker restart → exactly-once (no double decrement or restore); scale → all growing collections subindexed; reserve reads only the stay's nights. PASS.

## Internal depot usage / cross-topology / stream correctness / in-memory state
None / single / none / none.

## Minimality — adversarial simplification
Sketch: one depot by property, one microbatch topology, one PState with room types → nights, bookings, events, requests; foreign selects plus one query topology. Diff: ordered batching only.
- Ordered batching: without it, a 1000-command batch runs synchronously (cooperative rule), or per-record yields let reservation B read availability between A's check and A's night writes (README atomicity, no overbooking). Keep.
- `*commands`: split breaks reserve/cancel/reserve ordering and set-rate-between-reserves. Keep.
- `core`: stream double-decrements on retry. Keep.
- Nested nights (A) vs composite keys (B) vs point reads (C): only A gives a guaranteed-sorted contiguous range (1 seek + 30 vs 30 seeks). `availability` query topology: bypass = 2 roundtrips. `:exists?` field: replaces an empty-map `termval` that would own a subindexed child. `:next-seq`: contiguous seqs. Keep all.

## Throughput — adversarial
- reserve 3 point seeks + 1 range (property entry incl. ingress, request, room-type `:exists?`, nights range); cancel same plus booking; `get-availability` 2 + range; `get-night` 3. No cheaper construction that keeps the range read.

## Spec coverage — trace every operation and constraint

### `create-property!` / `create-room-type!` / `init-night!` / `set-rate!`
- Source: `:property-exists`; `:no-such-property`, `:room-type-exists`; `:night-exists` "regardless of values"; "Totals of existing bookings are not affected".
- Trace: create p1 → `:next-seq` nil → write field `:next-seq 0` only. create rt "std" → `:exists?` nil → write field `:exists? true`; re-create → `:room-type-exists`. init night 10 cap 2 rate 50 → nested write `{:capacity 2 :rate 50 :available 2}` (creates `:nights` implicitly); re-init cap 9 → `:night-exists`, unchanged. set-rate night 10 → 70 → record rewritten with rate 70, `:available` untouched; booking totals untouched; set-rate night 99 → `:night-not-configured`. Capacity 0 accepted (closed night). PASS.

### `reserve!`
- Source: order `:no-such-property`, `:no-such-room-type`, `:night-not-configured`, `:insufficient-capacity`; "either every night's available count drops by `quantity`, or nothing changes"; rejected `:nights` ascending; `:total` = Σ rate × quantity at processing time; outcome `:booking-id :total :nights :seq`.
- Trace: nights 10..12 cap 2 (rates 50, 60, 70), 13 unconfigured. reserve r1 `[10,14)` qty 1 → range `{10 11 12}`; missing `[13]` → `:night-not-configured [13]`, no capacity check, no writes. reserve r2 `[10,13)` qty 2 → all `available` 2 ≥ 2 → nights → 0; booking `{:total 360 :state :confirmed :seq 1}`; event 1 `:reserved` with `:request-id "r2"`; `:next-seq` 1; outcome `{:booking-id "r2" :total 360 :nights 3 :seq 1}`. reserve r3 `[11,13)` qty 1 → `:insufficient-capacity [11 12]`, nothing decremented. `[13,16)` after configuring 13..15 does not compete with `[10,13)`. Total 10^9 × 100 × 30 = 3×10^12 fits in Long. PASS.

### `cancel-booking!`
- Source: order `:no-such-property`, `:no-such-booking`, `:not-guest`, `:booking-cancelled`; "restoring exactly `quantity` rooms to each night of its stay"; outcome `:seq` distinct from the booking's; event shape with `:request-id`.
- Trace: cancel rid "k1" of r2 by wrong guest → `:not-guest`. cancel "k1" by guest → range `[10,13)` read, each night `available + 2`, booking `:state :cancelled` (`:seq 1`, `:total 360` untouched), event 2 `{:type :cancelled :request-id "k1" :booking-id "r2" ... :total 360}`, outcome `{:booking-id "r2" :seq 2}`. cancel again → `:booking-cancelled`, availability unchanged. cancel a rejected reserve's id → `:no-such-booking`. After a rate change the restore is quantity-only. PASS.

### Reject-before-create → create → original read → replay → conflict
- Trace: fresh property "p2". (1) reserve rid "x1" → `:next-seq` nil → `:no-such-property`; request `x1` under "p2"; `:ingress-seq` 1. (2) create "p2" → field write `:next-seq 0` only (lines 265–269); `x1` survives. (3) `get-outcome "p2" "x1"` → rejected, counter 0. (4) replay identical → no-op, still rejected even after room type and nights are configured. (5) different quantity → counter 1, no booking. Same in one microbatch or across barriers. Same pattern for `create-room-type!` after an `init-night!` rejected with `:no-such-room-type` (room-type entry is field-written only). PASS.

### `get-night` / `get-availability` / `get-booking`
- Trace: `[10,14)` → `[m10 m11 m12 nil]`; unknown room type → nil; room type with no nights → `[nil nil nil nil]`; `get-night` 13 → nil; `get-booking r2` after cancel → `:cancelled`, `:seq 1`, `:total 360`. PASS.

### `get-booking-events`
- Trace: 1200 events, after 500, limit 500 → one seek + 500 iterations → 501..1000 with `:seq` from keys; `Long/MAX_VALUE` → `[]`; unknown property → `[]`. PASS.

### Invariants
- `0 ≤ available ≤ capacity`: decrement only after every night is checked; restore only once (second cancel rejected before writes); capacity written only by `init-night!`; booking fields written once; seqs advanced only on accepted reserve/cancel; retry rolls back all night writes together. PASS.

### Idempotency / validation order / ordering / durability / shared state
- Replay of an accepted reserve after another guest's cancel → no-op; replay of a rejected reserve after capacity freed → still rejected; conflicting quantity → counter +1; set-rate reusing a reserve's rid → different type → conflict; malformed quantity 0 with used rid → client throws. "reserve A, reserve B (overlap), cancel A, reserve C" in one microbatch → positions 1..4 in one loop → A ok, B rejected, cancel ok, C ok, seqs 1, 2, 3. set-rate between two reserves without a barrier → totals differ. Nothing deleted. Second client shares the atom. PASS.

### Resource guarantees (own history)
- Property with 10⁵ nights and 10⁶ bookings: reserve 3 seeks + 1 range (≤ 30 iterations); `get-availability` 2 + range; `get-booking-events` after 900,000 = 1 seek + limit. PASS.

## Findings and fixes applied (decision / basis / outcome)

| # | Finding | Basis | Fix applied to PLAN.md |
|---|---|---|---|
| 1 | The `:cancelled` event's `:request-id` was unspecified ("carrying the booking's original fields") | README event shape lists `:request-id` and `:booking-id` separately; the cancel command has its own request-id | Stated: `:cancelled` event `:request-id` = the cancel command's request-id, `:booking-id` = the booking, other fields from the booking |

Remaining contract note (no plan change): README `get-booking-events` does not say in words which request-id a `:cancelled` event carries; the plan and tests read it as the cancelling command's id. Flagged for the README author.

## Test plan requirements (for phase 5)
- 2- and 4-task deployments; second `:wrap-client`; `update-module!` persistence (nights, bookings, events, outcomes; next seq continues); explicit waits.
- Independent expectations: rates 50, 60, 70 (and 10^9 × 100 × 30), capacities 1, 2, 5, quantities 1, 2, 100; a middle night unconfigured; end-to-end stays `[1,4)`/`[4,7)`.
- Reject-before-create scenario as traced for both property and room type; rejected outcomes preserved.
- Invariant sweeps: per night `available = capacity − Σ quantity over confirmed bookings covering it` (bookings read individually), `0 ≤ available ≤ capacity`; each booking total = Σ rate at its seq × quantity; event count = accepted reserves + cancels; `:cancelled` event `:request-id` equals the cancel's request-id.
- Deep-page scaling at high `after-seq`; field-level no-blob review (no per-property calendar or booking blob).
- Efficiency capture: account for the fixed ingress navigation + field write; keep detection of whole-calendar or all-bookings scans.
- No assertions on topology names, microbatch counts, or tight timing.
- Targeted mutants: partial decrement before a later night fails; capacity check before configured check; `:nights` unsorted; total using current rate at cancel; cancel restoring twice; booking `:seq` overwritten by the cancel seq; `get-availability` returning `[]` instead of nil for an unknown room type; missing `:seq` in event pages; outcome lost after `create-property!`.
