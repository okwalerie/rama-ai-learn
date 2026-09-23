# HLD Hotel Reservation Challenge

Build the inventory core of a Booking.com-style hotel reservation
system: per-property room-type pools with per-night capacity and rate;
multi-night stays reserved atomically across every night of the
half-open range `[checkin, checkout)`; totals locked at booking time
from the per-night rates; cancellation that restores exactly the booked
quantity once; and a per-property booking event journal paged by
cursor.

This challenge covers **inventory and booking state only**. Search,
ranking, caches, change-data-capture pipelines, payment
authorization/capture, cancellation policies and refunds, physical room
assignment, channel managers, and notifications are out of scope.
Unlike the source case study, this bounded scope permits **no
overbooking**: a night's booked quantity never exceeds its capacity.

## Protocol

Your implementation must satisfy the `HotelReservationModule` protocol
defined in `src/hld_hotel_reservation/protocol.clj`. The README is the
authoritative contract; the protocol docstrings summarize it.

## Types and bounds

| Term | Type | Bounds |
|---|---|---|
| `property-id`, `room-type`, `guest-id`, `booking-id`, `request-id` | non-empty `String` | ≤ 128 characters |
| `night`, `checkin`, `checkout` (day number) | `Long` | `0 ≤ value ≤ 2^40` |
| stay length `checkout − checkin` | | `1 ≤ length ≤ 30` |
| `quantity` (rooms) | `Long` | `1 ≤ quantity ≤ 100` |
| `capacity` (rooms per night) | `Long` | `0 ≤ capacity ≤ 1,000,000` |
| `rate` (minor units per room-night) | `Long` | `0 ≤ rate ≤ 1,000,000,000` |
| `seq` | `Long` | positive |
| `after-seq` | `Long` | `≥ 0` |
| `limit` | `Long` | `1 ≤ limit ≤ 500` |

A night is an integer day number; a stay `[checkin, checkout)` covers
the nights `checkin, checkin+1, …, checkout−1`. The checkout day is not
a night of the stay, so a stay ending on day `d` and a stay starting on
day `d` never compete for inventory.

## Command conventions

Every mutating operation is a **command**. A command:

- takes a client-chosen `request-id` as its first argument after `this`;
- returns `nil` and never returns a business result;
- returns only after the command has been durably accepted for
  processing;
- produces exactly one durable **outcome**, readable with
  `get-outcome` after `wait-for-processing!`.

Request-ids are scoped to a property: the same `request-id` used for
two properties names two unrelated requests. Within one property a
`request-id` is shared across all command types. The `request-id` of
an accepted `reserve!` **is** the `booking-id`, and booking ids are
immutable and never reused.

**Outcome shape.** `get-outcome` returns `nil` if the property has never
processed that `request-id`, otherwise a map:

```clojure
{:status               :accepted | :rejected
 :command              :create-property | :create-room-type | :init-night
                       | :set-rate | :reserve | :cancel-booking
 :reason               <keyword>        ; present only when :rejected
 :conflicting-attempts <Long>           ; 0 initially, see below
 ...}                                   ; command-specific keys listed per command
```

**Validation classes.**

1. *Structural* violations (wrong type, out of the bounds above,
   `checkout <= checkin`, a stay longer than 30 nights) make the client
   method throw `IllegalArgumentException` synchronously. Nothing is
   appended and no outcome is created.
2. *Business rejections* produce a durable `:rejected` outcome with a
   `:reason` and change no inventory or booking state.
3. *Accepted* commands change state exactly as specified and produce a
   durable `:accepted` outcome.

**Validation order.** Structural validation runs first, before the
`request-id` is consulted. A malformed command therefore leaves an
unused `request-id` unused, and leaves an already-used `request-id`'s
outcome (including its `:conflicting-attempts`) unchanged. Queries are
validated against the same bounds and throw `IllegalArgumentException`
on violation without changing any state. Replay and conflict handling
(next paragraph) runs after structural validation and before any
business validation: a replayed or conflicting command is never
re-evaluated against current property state.

**Idempotency and conflicts.** The first processed command carrying a
given `request-id` in a property is the *original*. The *payload* of a
command is its command type together with every argument other than
`this` and `request-id`; two payloads are identical when they are equal
under Clojure `=`. A later command with the same `request-id` and an
identical payload is a *replay*: no effect, outcome unchanged. A
replayed rejected reservation stays rejected even if capacity has since
freed; a replayed accepted reservation never reserves twice; a replayed
cancellation never restores twice. A later command with the same
`request-id` but a different payload is a *conflicting attempt*: no
effect on state, no change to any original outcome field except
`:conflicting-attempts`, which increases by one.

**Ordering.** Commands issued by one client against the same property
are processed in the order the client issued them. Commands against
different properties have no relative ordering guarantee.

**Durability.** Outcomes, nights, bookings in every state, and journal
events are retained for the lifetime of the module.

## Commands

### `create-property! [this request-id property-id]`

| `:reason` | Condition |
|---|---|
| `:property-exists` | `property-id` already exists |

### `create-room-type! [this request-id property-id room-type]`

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-property` | `property-id` does not exist |
| `:room-type-exists` | `room-type` already exists in the property |

### `init-night! [this request-id property-id room-type night capacity rate]`

Configures one night of a room type with its capacity and initial rate.
Capacity is fixed forever by this command.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-property` | `property-id` does not exist |
| `:no-such-room-type` | `room-type` does not exist in the property |
| `:night-exists` | the night is already configured for this room type (regardless of values) |

### `set-rate! [this request-id property-id room-type night rate]`

Changes the rate of a configured night. Totals of existing bookings are
not affected.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-property` | `property-id` does not exist |
| `:no-such-room-type` | `room-type` does not exist in the property |
| `:night-not-configured` | the night has not been initialized |

Accepted outcome adds `:rate` (the new rate).

### `reserve! [this request-id property-id room-type guest-id checkin checkout quantity]`

Reserves `quantity` rooms of `room-type` for every night of
`[checkin, checkout)`, atomically: either every night's available count
drops by `quantity`, or nothing changes. The booking is identified by
`request-id`.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-property` | `property-id` does not exist |
| `:no-such-room-type` | `room-type` does not exist in the property |
| `:night-not-configured` | any night of the stay is not initialized |
| `:insufficient-capacity` | any night of the stay has fewer than `quantity` rooms available |

A rejected outcome for either of the last two reasons adds `:nights`,
the vector of offending nights in ascending order. The booking's
`:total` is locked at acceptance as the sum over the stay's nights of
`rate(night) × quantity`, using each night's rate at the moment the
command is processed. Accepted outcome adds `:booking-id`, `:total`,
`:nights` (number of nights), and `:seq` (the journal seq of the
`:reserved` event). This `:seq` is the booking's seq forever: a later
cancellation gets its own event seq and never overwrites it.

### `cancel-booking! [this request-id property-id booking-id guest-id]`

Cancels a confirmed booking, restoring exactly `quantity` rooms to each
night of its stay.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-property` | `property-id` does not exist |
| `:no-such-booking` | no accepted booking `booking-id` exists in the property |
| `:not-guest` | `guest-id` is not the booking's guest |
| `:booking-cancelled` | the booking is already cancelled |

Accepted outcome adds `:booking-id` and `:seq` (the journal seq of the
`:cancelled` event, distinct from the booking's own `:seq`).

## Queries

### `get-outcome [this property-id request-id]`

Outcome map as defined above, or `nil`.

### `get-night [this property-id room-type night]`

`{:night Long :capacity Long :rate Long :available Long}` for a
configured night, or `nil` if the property, room type, or night is
unknown. `:available` is `capacity` minus the quantity of all confirmed
bookings covering the night.

### `get-availability [this property-id room-type checkin checkout]`

A vector with one entry per night of `[checkin, checkout)` in ascending
order; each entry is the `get-night` map for that night, or `nil` for
an unconfigured night. Returns `nil` if the property or room type does
not exist. Bounded by the 30-night stay limit.

### `get-booking [this property-id booking-id]`

The accepted booking, or `nil`:

```clojure
{:booking-id String :guest-id String :room-type String
 :checkin Long :checkout Long :quantity Long :total Long
 :state :confirmed | :cancelled :seq Long}
```

`:seq` is the journal seq of the original `:reserved` event and never
changes; cancelling flips `:state` only.

### `get-booking-events [this property-id after-seq limit]`

Booking events of the property with `:seq` strictly greater than
`after-seq`, ascending, at most `limit`:

```clojure
{:seq Long :type :reserved | :cancelled :request-id String
 :booking-id String :guest-id String :room-type String
 :checkin Long :checkout Long :quantity Long :total Long}
```

Returns an empty vector when nothing follows `after-seq` or the
property is unknown. Seqs start at `1` and each accepted `reserve!` or
`cancel-booking!` receives the previous seq plus one, in processing
order. Each event's `:request-id` identifies the command that produced
that event: the reservation request for `:reserved`, and the cancellation
request for `:cancelled`. Its `:booking-id` always identifies the original
reservation. Other commands do not append events.

## Invariants

- For every configured night, `0 ≤ available ≤ capacity`, and
  `available = capacity − Σ quantity` over confirmed bookings covering
  the night. Booked quantity never exceeds capacity.
- Capacity never changes after `init-night!`; rates change only through
  `set-rate!`.
- A booking's `guest-id`, `room-type`, `checkin`, `checkout`,
  `quantity`, `total`, and `seq` never change; its state moves only
  `:confirmed → :cancelled`, once.
- A cancellation restores exactly the booking's quantity to exactly the
  booking's nights, exactly once.
- Journal seqs are contiguous; every accepted reserve and cancel appears
  exactly once.

## Resource guarantees

The work done by any operation must be bounded by its own inputs and
outputs and must not grow with unrelated history. These bounds also
hold as the property's **own** history grows, not only as other
propertys grow: no operation may read, deserialize, or rewrite an
unbounded per-property collection (all nights, all bookings, or the event journal) as one value.

- `reserve!` and `cancel-booking!`: proportional to the number of nights
  in the stay (at most 30), not to the number of bookings or configured
  nights of the property.
- `get-night`, `get-booking`, `get-outcome`: a constant number of
  storage reads. Availability must not be recomputed by scanning
  bookings.
- `get-availability`: proportional to the number of nights requested.
- `get-booking-events`: proportional to `limit`, not to `after-seq` or
  to the journal length.

Tests exercise both 2 and 4 tasks; all guarantees must hold for both.

**Shared state.** All business state lives in the deployed module.
Several clients produced by `:wrap-client` against the same cluster
observe the same state: a command issued through one client is visible,
after `wait-for-processing!`, through every other. The client wrapper
keeps no process-local business state (no in-memory copies of nights, availability, bookings, journal events,
or outcomes); it holds only handles to the cluster.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <HotelReservationModule implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified
  `HotelReservationModule` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `harness/Synchronizable`. See the docstring
on that protocol for implementation requirements. Tests call
`(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `hld-hotel-reservation.module`.

## File Location

Write your solution to:
```
implementations/hld-hotel-reservation/src/hld_hotel_reservation/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```

## Attribution and license

This challenge is adapted from "Design a Hotel Reservation System
(Booking.com / Airbnb)", module 8.28 of *The HLD Handbook* by
handbook-academy
(<https://hld.handbook.academy/curriculum/case-studies/hotel-reservation/>,
source repository <https://github.com/handbook-academy/engineering-handbook>),
licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

Changes: the prose above is a rewritten, bounded specification. It keeps
room-type inventory pools, half-open `[checkin, checkout)` date-range
semantics, all-or-nothing multi-night reservation, and idempotent
booking commands; it deliberately forbids overbooking (the source treats
overbooking as a feature), locks totals from per-night rates, and
removes search, caching, CDC, payment authorization and capture,
cancellation policies, sagas, physical room assignment, channel
managers, and all infrastructure discussion. The adapted prose in this
README remains licensed under CC BY-SA 4.0.
