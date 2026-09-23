# HLD Stock Exchange Challenge

Build the matching-engine core of an equities exchange: one independent
order book per symbol; deterministic strict price-time (FIFO) priority;
limit orders that are either good-till-cancelled (rest) or
immediate-or-cancel (never rest); execution at the resting order's
price; partial fills that keep their original priority; cancellation;
aggregated depth by price level; and an immutable, paginated trade tape.

This challenge covers **matching state only**. Market orders, auctions,
price bands and halts, pre-trade risk, settlement, clearing, wire
protocols, multicast distribution, hot standby, and external
authentication or participant roles are out of scope. Ownership is
still enforced: only the account that submitted an order may cancel it.
Self-trades (one account on both sides) are allowed and treated like
any other trade.

## Protocol

Your implementation must satisfy the `StockExchangeModule` protocol
defined in `src/hld_stock_exchange/protocol.clj`. The README is the
authoritative contract; the protocol docstrings summarize it.

## Types and bounds

| Term | Type | Bounds |
|---|---|---|
| `symbol`, `account-id`, `order-id`, `request-id` | non-empty `String` | ≤ 128 characters |
| `side` | keyword | `:buy` or `:sell` |
| `tif` (time in force) | keyword | `:gtc` or `:ioc` |
| `price` (integer ticks) | `Long` | `1 ≤ price ≤ 1,000,000,000` |
| `qty` (units) | `Long` | `1 ≤ qty ≤ 1,000,000,000` |
| `levels` | `Long` | `1 ≤ levels ≤ 50` |
| `seq` | `Long` | positive |
| `after-seq` | `Long` | `≥ 0` |
| `limit` | `Long` | `1 ≤ limit ≤ 500` |

Symbols exist implicitly: the first order for a symbol creates its
empty book. Implementations may assume that the total resting quantity
of a symbol fits in a signed 64-bit `Long`.

## Command conventions

Every mutating operation is a **command**. A command:

- takes a client-chosen `request-id` as its first argument after `this`;
- returns `nil` and never returns a business result;
- returns only after the command has been durably accepted for
  processing;
- produces exactly one durable **outcome**, readable with
  `get-outcome` after `wait-for-processing!`.

Request-ids are scoped to a symbol: the same `request-id` used on two
symbols names two unrelated requests. Within one symbol a `request-id`
is shared across both command types. The `request-id` of an accepted
`submit-limit-order!` **is** the `order-id`; order ids are therefore
never reused within a symbol.

**Outcome shape.** `get-outcome` returns `nil` if the symbol has never
processed that `request-id`, otherwise a map:

```clojure
{:status               :accepted | :rejected
 :command              :submit-limit-order | :cancel-order
 :reason               <keyword>        ; present only when :rejected
 :conflicting-attempts <Long>           ; 0 initially, see below
 ...}                                   ; command-specific keys listed per command
```

**Validation classes.**

1. *Structural* violations (wrong type, out of the bounds above, a
   `side` or `tif` outside the allowed keywords) make the client method
   throw `IllegalArgumentException` synchronously. Nothing is appended
   and no outcome is created.
2. *Business rejections* produce a durable `:rejected` outcome with a
   `:reason` and change no book state.
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
re-evaluated against current book state.

**Idempotency and conflicts.** The first processed command carrying a
given `request-id` in a symbol is the *original*. The *payload* of a
command is its command type together with every argument other than
`this` and `request-id`; two payloads are identical when they are equal
under Clojure `=`. A later command with the same `request-id` and an
identical payload is a *replay*: no effect, outcome unchanged. A
replayed submit never matches again and never creates a second order or
a fresh position in the queue. A later command with the same
`request-id` but a different payload is a *conflicting attempt*: no
effect on book state, no change to any original outcome field except
`:conflicting-attempts`, which increases by one.

**Ordering.** Commands issued by one client against the same symbol are
processed in the order the client issued them, one at a time; each
command is applied atomically to the book. Commands against different
symbols have no relative ordering guarantee.

**Durability.** Outcomes, every order in every state, and every trade are
retained for the lifetime of the module.

## Matching rules

Each symbol has a book of resting orders: bids and asks. An accepted
submit receives the symbol's next **order seq** (`Long`, starting at
`1`, contiguous over accepted submits) which fixes its time priority.
Matching for an incoming order proceeds as follows, and produces the
same result on every run:

1. The opposing side is scanned best price first: for an incoming buy,
   asks in ascending price; for an incoming sell, bids in descending
   price. Within one price, resting orders are taken in ascending order
   seq (FIFO).
2. A resting order is **crossable** when its price is at or better than
   the incoming limit: `ask-price <= limit` for an incoming buy,
   `bid-price >= limit` for an incoming sell.
3. While the incoming order has remaining quantity and the best
   opposing order is crossable, a trade executes for
   `min(incoming remaining, resting remaining)` at the **resting
   (maker) order's price**. The resting order's remaining quantity
   drops; if it reaches `0` the order is filled and leaves the book;
   otherwise it stays at the front of its level with its original seq.
4. When no crossable order remains or the incoming order is exhausted:
   a `:gtc` remainder rests on the incoming order's side at its limit
   price behind every earlier order at that price; an `:ioc` remainder
   is cancelled and never rests.

Every trade receives the symbol's next **trade seq** (`Long`, starting
at `1`, contiguous). All trades produced by one incoming order have
consecutive trade seqs. Accounts are recorded on trades but impose no
restriction: an order may trade against a resting order of the same
account.

## Commands

### `submit-limit-order! [this request-id symbol account-id side price qty tif]`

Submits a limit order identified by `request-id` and matches it per the
rules above. This command has no business rejections. Accepted outcome
adds:

```clojure
{:order-id        String     ; = request-id
 :seq             Long       ; order seq
 :filled-qty      Long       ; executed immediately
 :resting-qty     Long       ; placed on the book (always 0 for :ioc)
 :cancelled-qty   Long       ; unfilled :ioc remainder (always 0 for :gtc)
 :trade-count     Long
 :first-trade-seq Long | nil
 :last-trade-seq  Long | nil}
```

`filled-qty + resting-qty + cancelled-qty = qty`.

### `cancel-order! [this request-id symbol order-id account-id]`

Removes an open order's remaining quantity from the book.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-order` | no accepted order `order-id` exists in the symbol |
| `:not-owner` | `account-id` is not the order's account |
| `:order-not-open` | the order has no remaining quantity (filled, already cancelled, or an `:ioc` order) |

Accepted outcome adds `:order-id` and `:cancelled-qty` (the remaining
quantity that was removed). Cancelling never affects any other order.

## Queries

### `get-outcome [this symbol request-id]`

Outcome map as defined above, or `nil`.

### `get-order [this symbol order-id]`

The accepted order, or `nil`:

```clojure
{:order-id String :account-id String :side keyword :price Long :qty Long
 :tif keyword :seq Long
 :filled-qty Long :remaining-qty Long :cancelled-qty Long
 :state :open | :filled | :cancelled}
```

`qty = filled-qty + remaining-qty + cancelled-qty` always. `:state` is
`:open` when `remaining-qty > 0`, `:filled` when `filled-qty = qty`,
and `:cancelled` otherwise.

### `get-depth [this symbol side levels]`

Up to `levels` aggregated price levels of the resting book on `side`,
best first (`:buy` descending price, `:sell` ascending):

```clojure
[{:price Long :qty Long :order-count Long} ...]
```

`:qty` is the sum of remaining quantities at that price; only levels
with `:qty > 0` appear. Returns an empty vector for an empty side or an
unknown symbol.

### `get-trades [this symbol after-seq limit]`

Trades of the symbol with `:seq` strictly greater than `after-seq`,
ascending, at most `limit`:

```clojure
{:seq Long :price Long :qty Long
 :maker-order-id String :taker-order-id String
 :maker-account-id String :taker-account-id String
 :taker-side :buy | :sell}
```

Returns an empty vector when nothing follows `after-seq` or the symbol
is unknown. Trades are immutable.

## Invariants

- The book never crosses: after any command, the best bid price is
  strictly less than the best ask price (or one side is empty).
- Every trade price equals the maker order's limit price, and satisfies
  the taker's limit.
- For every order, `filled-qty` equals the sum of trade quantities in
  which it participates, and `remaining-qty` equals its depth
  contribution.
- Order seqs and trade seqs are contiguous and never reused.
- Priority never changes: a resting order's seq and price are fixed at
  acceptance; partial fills do not move it behind later orders.

## Resource guarantees

The work done by any operation must be bounded by its own inputs and
outputs and must not grow with unrelated history. These bounds also
hold as the symbol's **own** history grows, not only as other
symbols grow: no operation may read, deserialize, or rewrite an
unbounded per-symbol collection (all orders, a whole side, or the trade tape) as one value.

- `submit-limit-order!`: proportional to the number of trades it
  produces plus a constant, not to the number of resting orders or
  levels in the book or to past trade volume.
- `cancel-order!`, `get-order`, `get-outcome`: a constant number of
  storage reads and writes, independent of book size.
- `get-depth`: proportional to `levels`, not to the number of resting
  orders (a level with ten thousand orders costs the same as a level
  with one).
- `get-trades`: proportional to `limit`, not to `after-seq` or to the
  number of trades.

The book of a symbol must not be held as a single value that is read
and rewritten whole on every command.

Tests exercise both 2 and 4 tasks; all guarantees must hold for both.

**Shared state.** All business state lives in the deployed module.
Several clients produced by `:wrap-client` against the same cluster
observe the same state: a command issued through one client is visible,
after `wait-for-processing!`, through every other. The client wrapper
keeps no process-local business state (no in-memory copies of orders, books, depth, trades,
or outcomes); it holds only handles to the cluster.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <StockExchangeModule implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified
  `StockExchangeModule` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `harness/Synchronizable`. See the docstring
on that protocol for implementation requirements. Tests call
`(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `hld-stock-exchange.module`.

## File Location

Write your solution to:
```
implementations/hld-stock-exchange/src/hld_stock_exchange/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```

## Attribution and license

This challenge is adapted from "Design a Stock Exchange (Matching
Engine)", module 8.20 of *The HLD Handbook* by handbook-academy
(<https://hld.handbook.academy/curriculum/case-studies/stock-exchange/>,
source repository <https://github.com/handbook-academy/engineering-handbook>),
licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

Changes: the prose above is a rewritten, bounded specification. It keeps
per-symbol FIFO price-time priority, deterministic sequencing,
maker-price execution, cancellation, aggregated depth, and an immutable
sequenced trade feed; it restricts order types to GTC and IOC limit
orders, allows self-trades, and removes market orders, auctions, LULD
bands, pre-trade risk, settlement, OUCH/ITCH/FIX, multicast, hot
standby, co-location, and all infrastructure discussion. The adapted
prose in this README remains licensed under CC BY-SA 4.0.
