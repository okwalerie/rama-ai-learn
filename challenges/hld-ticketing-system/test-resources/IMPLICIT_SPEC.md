# Implicit Spec — hld-ticketing-system (private, phase 0)

Private challenge-author artifact. Encrypted during challenge runs. It
records requirements, adversarial cases, and review concerns derived from
README.md; it does not prescribe storage, partitioning, or topology
choices. Entries under "Contract decisions" give decision, basis, and
outcome only.

## Contract decisions

| Decision | Basis | Outcome |
|---|---|---|
| Per-event logical clock driven by `advance-clock!` | Deterministic expiry with no timers; tests control every boundary | Active iff `clock < deadline`; expiry is lazy, no background work |
| `hold-id` = `request-id` of the hold | One id space per event; the hold-id is the fencing token because every new hold has a fresh id | Confirm/release name a specific hold generation and cannot reach a re-held seat |
| Owner fencing via `user-id` on confirm/release | Source's owner-token check; prevents cross-user release | `:not-owner` rejection, no compensation |
| Compensation record for `:hold-expired`, `:hold-released`, and `:hold-confirmed` with a different payment-ref | Any payment presented that yields no seats must be reversible; a same-ref re-confirm is a duplicate delivery, not a second payment | Durable per-event record with contiguous seq; outcome carries `:compensation-seq` |
| Compensation records are logical, one per rejected confirmation request-id | The module cannot execute refunds; deduplicating across request-ids by payment-ref would require unbounded cross-request state and hide retries | No refund promise; two request-ids with the same payment-ref → two records; only a replay yields no second record |
| Duplicate seat-ids are structural only for `add-seats!`/`hold-seats!` | A query result is a map keyed by seat-id, so duplicates collapse harmlessly; a command's seat set must be unambiguous | `get-seats` accepts duplicates |
| Expired hold never revived, even if seats remain free | Source exercise solution ("do not extend the hold"); keeps semantics independent of whether a re-hold happened | `:hold-expired` regardless of seat state |
| Request-ids scoped per event; structural errors throw; conflicts counted | Shared conventions across the five packages | See README |
| Structural validation precedes request-id handling; replay/conflict precedes business validation | A malformed retry must not alter an existing outcome; a replay must not be re-evaluated against current state | Unused id stays unused; `:conflicting-attempts` unchanged by malformed input; queries throw on bad bounds |
| Payload = command type + all args except `this`/`request-id`, compared with `=` | Cross-command-type reuse of an id must count as a conflict | Same args under a different command are a conflicting attempt |
| No per-user "one active hold per event" rule, no general admission, no refunds/relist | Bounded scope | Seat states are exactly available/held/confirmed |

## Operations

### `create-event!` / `add-seats!`

- Latency: hundreds of milliseconds acceptable.
- Throughput: rare; bursts of up to 1000 seats per call, tens of calls
  per large event.
- Invariants: seat-ids unique per event; atomic add.
- Growth: seats per event up to tens of thousands; unbounded events.
- Edge cases: add-seats with one known seat among 1000 → whole call
  rejected, none added; adding seats after holds exist does not disturb
  them.

### `advance-clock!`

- Invariants: monotone; equal value accepted as no-op.
- Concurrency: a hold and an advance issued back-to-back are applied in
  issue order; a hold whose deadline equals the value of a later advance
  becomes inactive exactly after that advance.
- Edge cases: advance to `2^53`; advance by 0; regression by 1 rejected.

### `hold-seats!`

- Latency: p99 hundreds of milliseconds acceptable; tests only require
  visibility after the barrier.
- Throughput: onsale bursts; the operation touches at most 8 seats.
- Invariants: all-or-nothing; a seat has at most one active hold; a
  confirmed seat can never be held.
- Concurrency: two holds for overlapping seats issued back-to-back: the
  first processed wins all its seats; the second is rejected with the
  overlapping seats in `:unavailable-seats`, and none of its
  non-overlapping seats are held either.
- Edge cases: deadline = clock (rejected), deadline = clock + 1
  (accepted, expires on the next advance); seat under an expired hold
  (accepted); seat under a released hold (accepted); seat under a
  confirmed hold (rejected); 8 seats; hold for the same user on seats the
  user already holds (rejected `:seat-unavailable`, no special case).

### `confirm-hold!`

- Invariants: only an active, owned hold can be confirmed; confirmed
  seats are permanent; compensation records are exact.
- Edge cases: confirm at clock exactly = deadline (expired →
  compensation); confirm after the seats were re-held by another user
  (expired → compensation; the new hold untouched); confirm with a
  different request-id and the same payment-ref after a successful
  confirm (`:hold-confirmed`, no compensation); with a different
  payment-ref (`:hold-confirmed`, compensation); confirm by a non-owner
  presenting a payment-ref (`:not-owner`, no compensation); confirm of a
  hold-id that was a rejected hold request (`:no-such-hold`).

### `release-hold!`

- Edge cases: release after expiry (`:hold-expired`, no effect even if
  seats still free); release after confirm (`:hold-confirmed`); double
  release (`:hold-released`); release by non-owner; release after seats
  re-held (the new hold is untouched).

### Queries

- `get-seats` reflects expiry lazily: a held seat becomes `:available` in
  the query immediately after an advance past its deadline, without any
  command touching the seat.
- `get-hold` state transitions with the clock: `:active` → `:expired`
  after an advance, and back is impossible.
- `get-compensations` paging: contiguous seqs, `[]` for unknown event and
  for `after-seq` at or beyond the last seq.

## Entity state × write matrix

Entities: **event** (absent / exists), **seat** (absent / available /
held-active(h) / held-inactive(h) / confirmed(h)), **hold** (absent /
active / expired / released / confirmed), **request-id** (unused /
original).

Seat × `hold-seats!` (new hold g)
- absent: `:no-such-seat`; get-seats → nil entry; nothing held.
- available: held-active(g). get-seats → `:held`, hold-id g;
  get-hold g → `:active`.
- held-active(h): `:seat-unavailable`. get-seats → `:held` h unchanged;
  get-hold g → nil (rejected holds are not holds).
- held-inactive(h): held-active(g). get-seats → `:held` g; get-hold h →
  `:expired`; get-hold g → `:active`.
- confirmed(h): `:seat-unavailable`. get-seats → `:confirmed` h.

Seat × `confirm-hold!` on hold h
- held-active(h): confirmed(h). get-seats → `:confirmed`, user = owner;
  get-hold h → `:confirmed` with payment-ref.
- held-inactive(h): `:hold-expired` + compensation. get-seats →
  `:available` (or `:held` g if re-held); get-hold h → `:expired`;
  get-compensations has a new record.
- held-active(g≠h) (re-held): `:hold-expired` + compensation; g
  untouched.
- confirmed(h): `:hold-confirmed`; compensation iff payment-ref differs.

Seat × `release-hold!` on hold h
- held-active(h): available. get-seats → `:available`; get-hold h →
  `:released`.
- held-inactive(h): `:hold-expired`; get-seats unchanged.
- held-active(g≠h): `:hold-expired` for h; g untouched.
- confirmed(h): `:hold-confirmed`; unchanged.

Event × `advance-clock!`
- exists: clock = now. get-clock → now; every hold with deadline <= now
  reads `:expired`; their seats read `:available`.

Request-id × any command
- original accepted × replay: no change anywhere; in particular a
  replayed hold after expiry does not re-hold, and a replayed confirm
  creates no second compensation.
- original rejected × replay: still rejected; a replayed rejected confirm
  creates no second compensation record.
- original × different payload: `:conflicting-attempts` +1 only.

## Adversarial cases for private tests

Boundary
- Deadline equal to clock rejected; deadline clock+1 accepted; after
  `advance-clock!` to exactly deadline the hold reads `:expired` and its
  seats `:available`.
- 8-seat hold where the 8th seat is unavailable: none held.
- 1000-seat `add-seats!`; `get-seats` with 64 ids mixing known, unknown,
  held, expired, confirmed.
- Compensation paging with `limit` 1 and 500.

Retry / idempotency
- Replay an accepted hold after it expired and its seats were re-held:
  no change to the new hold; outcome still `:accepted` with the original
  deadline.
- Replay a rejected hold after the blocking seat became free: still
  rejected; a new request-id succeeds.
- Replay an accepted confirm: no second compensation, no change.
- Replay a compensated confirm: exactly one record remains.
- Two rejected confirms under different request-ids with the same
  payment-ref (e.g. after expiry): two compensation records, seqs
  contiguous.
- `get-seats` with duplicated seat-ids: one key per distinct seat, no
  throw; `hold-seats!` with duplicated seat-ids: throws, nothing held.
- Malformed command reusing an existing request-id (9-seat hold): throws;
  the original outcome and its `:conflicting-attempts` are unchanged.
- Malformed command with a fresh request-id: throws; `get-outcome` for
  that id stays nil; the same id then succeeds as an original.
- Out-of-bounds query arguments (`limit 0`, `limit 501`, `after-seq -1`,
  empty ids): `IllegalArgumentException`, no state change.
- Two clients from `:wrap-client` on the same cluster: a command through
  one is visible through the other after `wait-for-processing!`.
- Same request-id different payload for hold: `:conflicting-attempts`
  increments; no seats change.
- Same request-id reused for `advance-clock!` after a hold used it: the
  advance is a conflicting attempt; the clock does not move.

Ordering
- hold A, hold B (overlapping), advance past A's deadline, hold C (same
  seats), confirm A — issued back-to-back with one barrier at the end:
  A accepted, B rejected, C accepted, A's confirm rejected `:hold-expired`
  with compensation; C still active.
- release A then hold C on A's seats without an intermediate barrier: C
  accepted.
- confirm then release on the same hold: release rejected
  `:hold-confirmed`.

Conservation / consistency
- For every seat, exactly one of: available, one active hold, or one
  confirmed hold; never two active holds.
- Union of `:seat-ids` over confirmed holds equals the set of seats
  reading `:confirmed`.
- Number of compensation records equals the number of confirm outcomes
  carrying `:compensation-seq`, and seqs are contiguous.
- A hold's record never changes after reaching `:released` or
  `:confirmed`.

## Efficiency review concerns

- Expiry must be evaluated lazily from stored deadlines; no scan of all
  holds on `advance-clock!` and no per-hold timers.
- All bounds must hold as one event's own history grows (seats, holds, compensation records),
  not only as other events grow.
- The client wrapper must hold no process-local business state.
- `hold-seats!` must read only the requested seats (≤ 8), not the
  event's whole seat map or hold list.
- `get-seats` must not load every seat of the event to answer for 64.
- `get-hold` must be a direct lookup; hold records must not live inside
  a single per-event blob that grows with every hold.
- Compensation records must be appended with contiguous seqs and paged
  from `after-seq` without scanning earlier records.
- Retry safety: reprocessing a hold after a failure must not hold seats
  twice or produce two compensation records for one confirm.
- Confirm/release must locate the hold's seats from the hold record
  (bounded), not by scanning seats for the hold-id.
