# Implicit Spec — hld-hotel-reservation (private, phase 0)

Private challenge-author artifact. Encrypted during challenge runs. It
records requirements, adversarial cases, and review concerns derived from
README.md; it does not prescribe storage, partitioning, or topology
choices. Entries under "Contract decisions" give decision, basis, and
outcome only.

## Contract decisions

| Decision | Basis | Outcome |
|---|---|---|
| Room-type pools with per-night capacity, no physical rooms | Source models inventory as room type × date; physical assignment is a separate concern | Availability is a per-night counter, never a room list |
| No overbooking (explicit deviation from source) | Makes the conservation invariant exact and testable | `available ≥ 0` always; `:insufficient-capacity` is a rejection |
| Half-open `[checkin, checkout)` with 1..30 nights | Source date-range semantics; bounds per-command work | Checkout day never consumes inventory |
| Capacity immutable after `init-night!`; rate mutable via `set-rate!` | Requested scope; keeps totals stable and the invariant simple | `:night-exists` on re-init |
| Total locked at reservation from rates at processing time | Source: price locked at hold/booking, not at current rate | Later `set-rate!` never changes `:total` |
| `booking-id` = `request-id`; cancel restores once | Immutable ids; replayed cancels cannot double-restore | `:booking-cancelled` on second cancel |
| Missing night rejects the whole stay before capacity is checked | Requested scope; makes the check order deterministic | `:night-not-configured` reported with all missing nights |
| Per-property booking event journal | Shared cursor convention; gives a paged audit read | `:reserved` and `:cancelled` events with contiguous seqs |
| Request-ids scoped per property; structural errors throw; conflicts counted | Shared conventions | See README |
| Structural validation precedes request-id handling; replay/conflict precedes business validation | A malformed retry must not alter an existing outcome; a replay must not be re-evaluated against current state | Unused id stays unused; `:conflicting-attempts` unchanged by malformed input; queries throw on bad bounds |
| Payload = command type + all args except `this`/`request-id`, compared with `=` | Cross-command-type reuse of an id must count as a conflict | Same args under a different command are a conflicting attempt |
| Booking `:seq` is the `:reserved` event seq forever | The cancel event has its own seq; overwriting would break the link between `get-booking` and the journal | `get-booking` `:seq` unchanged after cancel; cancel outcome carries the `:cancelled` event seq |

## Operations

### `create-property!` / `create-room-type!` / `init-night!` / `set-rate!`

- Latency: hundreds of milliseconds acceptable.
- Throughput: configuration writes; bulk seasonal rate updates may touch
  thousands of nights in sequence.
- Invariants: capacity immutable; rate change never alters bookings or
  availability.
- Edge cases: capacity 0 (closed night; any reserve → insufficient);
  rate 0; re-init with different capacity rejected; set-rate on an
  unconfigured night rejected; set-rate mid-way between two
  reservations: the first total uses the old rate, the second the new.

### `reserve!`

- Latency: p99 hundreds of milliseconds acceptable; visibility after the
  barrier.
- Throughput: the hot write on popular properties; touches ≤ 30 nights.
- Invariants: atomic across nights; no night below 0; total exact.
- Concurrency: two reservations racing for the last rooms of an
  overlapping night, issued back-to-back: the first processed wins; the
  second is rejected with the overlapping nights in `:nights`, and no
  other night of the second is decremented.
- Edge cases: 1-night stay; 30-night stay; stays that touch end-to-end
  (`[1,4)` and `[4,7)`) both accepted at capacity 1; quantity equal to
  available on every night (accepted, all become 0); quantity above
  capacity on only the last night (rejected as a whole); one middle
  night unconfigured (rejected `:night-not-configured` listing it; no
  capacity check performed); reserve 100 rooms.

### `cancel-booking!`

- Invariants: restores exactly `quantity` on exactly the stay's nights;
  only once.
- Edge cases: cancel then reserve the same nights again (accepted);
  cancel by wrong guest; cancel a booking-id that was a rejected reserve
  (`:no-such-booking`); cancel after a rate change (availability
  restored; total unchanged); cancel twice.

### Queries

- `get-availability` returns `nil` entries for unconfigured nights and
  never throws for gaps.
- `get-booking-events` pages with contiguous seqs; `:cancelled` events
  carry the booking's original fields.
- `get-night` `:available` reflects every confirmed booking and no
  cancelled one.

## Entity state × write matrix

Entities: **property** (absent / exists), **room type** (absent /
exists), **night** (unconfigured / configured cap c, rate r, available
a), **booking** (absent / confirmed / cancelled), **request-id**
(unused / original).

Night × `init-night!`(c, r)
- unconfigured: configured(c, r, a=c). get-night → map;
  get-availability shows it.
- configured: `:night-exists`; unchanged (even with equal values).

Night × `set-rate!`(r')
- unconfigured: `:night-not-configured`.
- configured: rate r'; available unchanged; existing bookings' totals
  unchanged; get-night → r'.

Nights × `reserve!`(q over stay S)
- any night of S unconfigured: `:night-not-configured` with those
  nights; no availability changes; get-booking → nil.
- all configured, some a < q: `:insufficient-capacity` with those
  nights; no availability changes.
- all configured, all a ≥ q: each a −= q; booking confirmed with total
  Σ r × q; get-booking → `:confirmed`; get-booking-events +1
  `:reserved`; get-night on each night shows the decrement.

Booking × `cancel-booking!`
- absent: `:no-such-booking`.
- confirmed: cancelled; each night a += q; get-booking → `:cancelled`
  with same total and same `:seq`; events +1 `:cancelled` with a new
  seq that the cancel outcome reports.
- cancelled: `:booking-cancelled`; availability unchanged.

Request-id × any command
- original accepted reserve × replay: no second decrement, same
  `:seq`, events unchanged.
- original rejected reserve × replay: still rejected even after
  capacity freed or nights configured.
- original accepted cancel × replay: no second restore.
- original × different payload: `:conflicting-attempts` +1 only.

## Adversarial cases for private tests

Boundary
- 30-night stay at capacity 1 on every night; then a 1-night stay on
  each of those nights rejected; after cancel, all accepted.
- Stay `[10,13)` and stay `[13,16)` with capacity 1: both accepted; stay
  `[12,14)` rejected with `:nights [12 13]`.
- Total with rate 10^9 × quantity 100 × 30 nights = 3×10^12.
- `checkout = checkin` and 31-night stay: structural throw, no outcome.
- Journal of 1200 events paged at 500.

Retry / idempotency
- Replay an accepted reserve after another guest's cancel: no change.
- Replay a rejected reserve after capacity freed: still rejected; a new
  request-id succeeds.
- Conflicting resubmit with a different quantity: counter +1, no
  change.
- Same request-id reused for `set-rate!` after a reserve used it: the
  set-rate is a conflicting attempt; rate unchanged.
- Same request-id in two properties: independent.
- Malformed command reusing an existing request-id (quantity 0): throws;
  the original outcome and its `:conflicting-attempts` are unchanged.
- Malformed command with a fresh request-id: throws; `get-outcome` for
  that id stays nil; the same id then succeeds as an original.
- Out-of-bounds query arguments (`limit 0`, `limit 501`, `after-seq -1`,
  empty ids): `IllegalArgumentException`, no state change.
- Two clients from `:wrap-client` on the same cluster: a command through
  one is visible through the other after `wait-for-processing!`.

Ordering
- reserve A, reserve B (overlapping last room), cancel A, reserve C
  (same nights) issued back-to-back with one barrier: A accepted, B
  rejected, A cancelled, C accepted; seqs 1, 2, 3.
- set-rate between two reserves without a barrier: totals differ
  accordingly.
- Interleaved commands across two properties: each property's seqs
  contiguous from 1.

Conservation / consistency
- For every configured night: `available = capacity − Σ quantity` over
  confirmed bookings covering it, and `0 ≤ available ≤ capacity`.
- Every booking's `:total` equals Σ over its nights of the rate in
  effect at its `:seq` × quantity, and is unchanged after rate changes.
- Number of events = number of accepted reserves + accepted cancels;
  seqs contiguous; each cancelled booking has exactly one `:cancelled`
  event after its `:reserved` event.

## Efficiency review concerns

- Availability must be maintained per night incrementally; `get-night`
  must not scan bookings.
- All bounds must hold as one property's own history grows (nights, bookings, events),
  not only as other propertys grow.
- The client wrapper must hold no process-local business state.
- `reserve!` must read and write only the stay's nights (≤ 30), not the
  room type's whole calendar or the property's booking list.
- Bookings must be stored for direct lookup by id, not inside a
  per-property blob rewritten on every reservation.
- `get-booking-events` must seek to `after-seq` directly.
- Bulk rate updates must not rewrite unrelated nights or bookings.
- Retry safety: reprocessing a reserve after a failure must not
  decrement a night twice; reprocessing a cancel must not restore twice.
- `get-availability` for 30 nights must be a bounded range read rather
  than 30 independent random lookups where the storage layout allows
  it; either way it must not depend on how many nights are configured.
