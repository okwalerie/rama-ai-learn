# HLD Ticketing System Challenge

Build the seat-inventory core of a Ticketmaster-style ticketing system:
per-event assigned seats; atomic multi-seat holds with a deadline on a
deterministic per-event logical clock; owner-fenced confirm and release
that can never touch seats another hold has since acquired; and an
explicit compensation record whenever a successful-payment confirmation
arrives too late to be honored.

This challenge covers **inventory state only**. Virtual waiting rooms,
bot defenses, payment-provider calls, refunds, notifications, seat maps
served from caches, resale, and general admission counters are out of
scope. Time never advances on its own: tests drive it with a command.

## Protocol

Your implementation must satisfy the `TicketingSystemModule` protocol
defined in `src/hld_ticketing_system/protocol.clj`. The README is the
authoritative contract; the protocol docstrings summarize it.

## Types and bounds

| Term | Type | Bounds |
|---|---|---|
| `event-id`, `seat-id`, `user-id`, `hold-id`, `request-id`, `payment-ref` | non-empty `String` | ≤ 128 characters |
| `seat-ids` (add-seats) | vector of `seat-id` | 1 to 1000 entries, all distinct |
| `seat-ids` (hold) | vector of `seat-id` | 1 to 8 entries, all distinct |
| `seat-ids` (get-seats) | vector of `seat-id` | 1 to 64 entries |
| `now`, `deadline` (logical time) | `Long` | `0 ≤ value ≤ 2^53` |
| `seq` | `Long` | positive |
| `after-seq` | `Long` | `≥ 0` |
| `limit` | `Long` | `1 ≤ limit ≤ 500` |

## Logical clock

Each event has its own logical clock, a `Long` that starts at `0` when
the event is created and only moves through `advance-clock!`. Time values
use abstract logical ticks, not milliseconds or wall-clock timestamps.
A hold
with `deadline` is **active** while `clock < deadline` and **inactive**
as soon as `clock >= deadline`. No timer, wall clock, or background
expiry exists; all deadline decisions are made against the event clock
at the moment a command is processed or a query is answered, which makes
every outcome deterministic.

## Command conventions

Every mutating operation is a **command**. A command:

- takes a client-chosen `request-id` as its first argument after `this`;
- returns `nil` and never returns a business result;
- returns only after the command has been durably accepted for
  processing;
- produces exactly one durable **outcome**, readable with
  `get-outcome` after `wait-for-processing!`.

Request-ids are scoped to an event: the same `request-id` used for two
different events names two unrelated requests. Within one event a
`request-id` is shared across all command types. The `request-id` of an
accepted `hold-seats!` **is** the `hold-id`.

**Outcome shape.** `get-outcome` returns `nil` if the event has never
processed that `request-id`, otherwise a map:

```clojure
{:status               :accepted | :rejected
 :command              :create-event | :add-seats | :advance-clock
                       | :hold-seats | :confirm-hold | :release-hold
 :reason               <keyword>        ; present only when :rejected
 :conflicting-attempts <Long>           ; 0 initially, see below
 ...}                                   ; command-specific keys listed per command
```

**Validation classes.**

1. *Structural* violations (wrong type, out of the bounds above,
   duplicate seat-ids in one `add-seats!` or `hold-seats!` call, empty
   vectors) make the client method throw `IllegalArgumentException`
   synchronously. Nothing is appended and no outcome is created.
2. *Business rejections* produce a durable `:rejected` outcome with a
   `:reason` and change no seat or hold state. The only additional
   effect a rejection may have is the compensation record described
   under `confirm-hold!`.
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
re-evaluated against current event state.

**Idempotency and conflicts.** The first processed command carrying a
given `request-id` in an event is the *original*. The *payload* of a
command is its command type together with every argument other than
`this` and `request-id`; two payloads are identical when they are equal
under Clojure `=`. A later command with the same `request-id` and an
identical payload is a *replay*: no effect, outcome unchanged. In
particular a replayed hold whose original was rejected stays rejected,
and a replayed hold whose original has since expired does not renew or
re-hold anything. A later command with the same `request-id` but a
different payload is a *conflicting attempt*: no effect on event state,
no change to any original outcome field except `:conflicting-attempts`,
which increases by one.

**Ordering.** Commands issued by one client against the same event are
processed in the order the client issued them. Commands against
different events have no relative ordering guarantee.

**Durability.** Outcomes, seats, holds (in every terminal state), clock
values, and compensation records are retained for the lifetime of the
module. Nothing is ever deleted.

## Commands

### `create-event! [this request-id event-id]`

Creates an event with clock `0` and no seats.

| `:reason` | Condition |
|---|---|
| `:event-exists` | `event-id` already exists |

### `add-seats! [this request-id event-id seat-ids]`

Adds available seats to the event. Atomic: either every seat is added or
none is.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-event` | `event-id` does not exist |
| `:seat-exists` | any `seat-id` already exists in the event |

Accepted outcome adds `:added` (Long, the number of seats added).

### `advance-clock! [this request-id event-id now]`

Sets the event clock to `now`.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-event` | `event-id` does not exist |
| `:clock-regression` | `now` is less than the current clock |

`now` equal to the current clock is accepted with no change. Accepted
outcome adds `:clock` (the new value).

### `hold-seats! [this request-id event-id user-id seat-ids deadline]`

Creates hold `request-id` owned by `user-id` over all of `seat-ids`,
active until the event clock reaches `deadline`. Atomic: all seats or
none.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-event` | `event-id` does not exist |
| `:no-such-seat` | any `seat-id` does not exist in the event |
| `:deadline-passed` | `deadline <= clock` |
| `:seat-unavailable` | any seat is confirmed, or is covered by an active hold |

A seat covered only by an inactive (expired) hold is available: the new
hold takes it and the old hold stays expired. A seat covered by a
released hold is available. A rejected outcome for `:seat-unavailable`
adds `:unavailable-seats`, the vector of offending seat-ids in the order
given. Accepted outcome adds `:hold-id` (equal to `request-id`) and
`:deadline`.

### `confirm-hold! [this request-id event-id hold-id user-id payment-ref]`

Reports that payment `payment-ref` succeeded for `hold-id` and turns
every seat of the hold into a confirmed (sold) seat.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-event` | `event-id` does not exist |
| `:no-such-hold` | no accepted hold `hold-id` exists in the event |
| `:not-owner` | `user-id` is not the hold's owner |
| `:hold-confirmed` | the hold is already confirmed |
| `:hold-released` | the hold was released |
| `:hold-expired` | the hold is inactive (`clock >= deadline`) |

The check for `:hold-expired` is made against the clock when the command
is processed; an expired hold is never revived, whether or not its seats
have been re-held since. Confirming never affects seats that belong to
another hold.

**Compensation.** When a confirmation carrying a `payment-ref` cannot be
honored, the payment must be logically reversed. The module records a
**compensation record** and the outcome adds `:compensation-seq` when
the rejection reason is:

- `:hold-expired` or `:hold-released`, always; or
- `:hold-confirmed` and `payment-ref` differs from the `payment-ref`
  recorded by the confirming command (a second, distinct payment).

`:no-such-event`, `:no-such-hold`, `:not-owner`, and `:hold-confirmed`
with the same `payment-ref` produce no compensation record. No
payment-provider call is ever made; the record is the whole deliverable.

A compensation record is a **logical** record tied to one rejected
confirmation `request-id`: it states that this confirmation attempt
presented a payment that yielded no seats. It is not a promise that any
refund is executed, and it is not deduplicated across request-ids: two
rejected confirmations under different request-ids that present the
same `payment-ref` produce two records. Only a replay (same
`request-id`, same payload) produces no second record.

Accepted outcome adds `:seat-ids` (the confirmed seats, in the hold's
order) and `:payment-ref`.

### `release-hold! [this request-id event-id hold-id user-id]`

Releases an active hold, making its seats available.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-event` | `event-id` does not exist |
| `:no-such-hold` | no accepted hold `hold-id` exists in the event |
| `:not-owner` | `user-id` is not the hold's owner |
| `:hold-confirmed` | the hold is confirmed; confirmed seats can never be released |
| `:hold-released` | the hold was already released |
| `:hold-expired` | the hold is inactive |

Releasing never affects seats that belong to another hold. Accepted
outcome adds `:seat-ids`.

## Queries

### `get-outcome [this event-id request-id]`

Outcome map as defined above, or `nil`.

### `get-clock [this event-id]`

The event's clock (`Long`), or `nil` if the event does not exist.

### `get-seats [this event-id seat-ids]`

A map from each requested `seat-id` to its state as of the current clock,
or to `nil` when the seat does not exist. Returns `nil` when the event
does not exist. Duplicate seat-ids in `seat-ids` are not a structural
error here: they collapse to one map key.

```clojure
{:state   :available | :held | :confirmed
 :hold-id String | nil      ; the active or confirming hold, nil when :available
 :user-id String | nil}
```

A seat whose last hold is inactive and not confirmed is `:available`
with `nil` hold-id and user-id.

### `get-hold [this event-id hold-id]`

The hold record, or `nil` if no accepted hold with that id exists:

```clojure
{:hold-id String :user-id String :seat-ids [String ...] :deadline Long
 :state :active | :expired | :released | :confirmed
 :payment-ref String | nil}       ; set when :confirmed
```

`:state` is `:active` when neither confirmed nor released and
`clock < deadline`; `:expired` when neither confirmed nor released and
`clock >= deadline`.

### `get-compensations [this event-id after-seq limit]`

Compensation records of the event with `:seq` strictly greater than
`after-seq`, ascending, at most `limit`:

```clojure
{:seq Long :request-id String :hold-id String :user-id String
 :payment-ref String :reason keyword}
```

Returns an empty vector when nothing follows `after-seq` or the event is
unknown. Seqs start at `1` and are contiguous in processing order.

## Invariants

- A seat is covered by at most one active hold at any clock value, and a
  confirmed seat is covered by no active hold.
- A confirmed seat stays confirmed forever with the same hold and user.
- A hold's `seat-ids`, `user-id`, and `deadline` never change; its state
  moves only `active → confirmed`, `active → released`, or
  `active → expired` (by the clock), and never leaves a terminal state.
- Every rejected confirmation that meets the compensation rule has
  exactly one compensation record, and no other command creates one.
- The clock of an event never decreases.

## Resource guarantees

The work done by any operation must be bounded by the sizes of its own
inputs and outputs and must not grow with unrelated history. These bounds also
hold as the event's **own** history grows, not only as other
events grow: no operation may read, deserialize, or rewrite an
unbounded per-event collection (all seats, all holds, or the compensation log) as one value.

- `hold-seats!`, `confirm-hold!`, `release-hold!`: proportional to the
  number of seats in the hold (at most 8), not to the number of seats or
  holds in the event.
- `add-seats!`: proportional to the number of seats added.
- `get-seats`: proportional to the number of seat-ids requested.
- `get-hold`, `get-clock`, `get-outcome`: a constant number of storage
  reads.
- `get-compensations`: proportional to `limit`, not to `after-seq` or to
  the number of records.

Expiry must not require any per-hold background work; an event with a
million expired holds costs nothing until one of its seats is touched.

Tests exercise both 2 and 4 tasks; all guarantees must hold for both.

**Shared state.** All business state lives in the deployed module.
Several clients produced by `:wrap-client` against the same cluster
observe the same state: a command issued through one client is visible,
after `wait-for-processing!`, through every other. The client wrapper
keeps no process-local business state (no in-memory copies of seats, holds, clocks, compensation records,
or outcomes); it holds only handles to the cluster.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <TicketingSystemModule implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified
  `TicketingSystemModule` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `harness/Synchronizable`. See the docstring
on that protocol for implementation requirements. Tests call
`(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `hld-ticketing-system.module`.

## File Location

Write your solution to:
```
implementations/hld-ticketing-system/src/hld_ticketing_system/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```

## Attribution and license

This challenge is adapted from "Design a Ticketing System (BookMyShow /
Ticketmaster)", module 8.18 of *The HLD Handbook* by handbook-academy
(<https://hld.handbook.academy/curriculum/case-studies/ticketing-system/>,
source repository <https://github.com/handbook-academy/engineering-handbook>),
licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

Changes: the prose above is a rewritten, bounded specification. It keeps
the available/held/sold seat state machine, all-or-nothing multi-seat
holds, owner tokens and fencing on confirm, and the boundary race
between hold expiry and late payment confirmation (resolved here as an
explicit compensation record); it replaces wall-clock TTLs with a
test-driven per-event logical clock and removes waiting rooms, bot
defenses, payment-provider integration, saga orchestration, caching,
and all infrastructure discussion. The adapted prose in this README
remains licensed under CC BY-SA 4.0.
