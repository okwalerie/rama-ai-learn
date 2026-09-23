# Implicit Spec — hld-stock-exchange (private, phase 0)

Private challenge-author artifact. Encrypted during challenge runs. It
records requirements, adversarial cases, and review concerns derived from
README.md; it does not prescribe storage, partitioning, or topology
choices. Entries under "Contract decisions" give decision, basis, and
outcome only.

## Contract decisions

| Decision | Basis | Outcome |
|---|---|---|
| Limit GTC and limit IOC only; no market orders | Bounded scope; IOC already exercises "never rests"; market orders would need a price-less fill rule | Market orders remain an optional future extension |
| `order-id` = `request-id`, scoped per symbol | Ids never reused; replay of a submit cannot create a fresh queue position | Duplicate submits are replays or conflicting attempts, never new orders |
| Execution at the maker's price | Source: price-time priority with resting-price execution | Every trade price equals maker limit |
| Partial fills keep seq and price | Source: FIFO within level; priority is fixed at acceptance | `:seq` immutable |
| Two contiguous per-symbol sequences (orders, trades) | Deterministic replay checks; trade seqs for one taker are consecutive | Tests can diff full tapes |
| Self-trades allowed; owner fencing on cancel only | Requested scope; a cancel must not remove someone else's order; external authentication/roles are excluded, ownership is not | `:not-owner` rejection |
| Structural errors throw; submit has no business rejections | Every well-formed order is matchable | Only cancel has durable rejections |
| Depth aggregated per price with order count | Output bounded by `levels` regardless of orders per level | `get-depth` cost proportional to levels |
| Structural validation precedes request-id handling; replay/conflict precedes business validation | A malformed retry must not alter an existing outcome; a replay must not be re-evaluated against current state | Unused id stays unused; `:conflicting-attempts` unchanged by malformed input; queries throw on bad bounds |
| Payload = command type + all args except `this`/`request-id`, compared with `=` | Cross-command-type reuse of an id must count as a conflict | Same args under a different command are a conflicting attempt |

## Operations

### `submit-limit-order!`

- Latency: tests only need visibility after the barrier; design target
  is low milliseconds per order.
- Throughput: dominant write; hot symbols receive most of it; cancels
  outnumber trades.
- Invariants: no crossed book after any command; conservation
  `filled + resting + cancelled = qty`; trade quantities conserve
  between maker and taker; determinism.
- Growth: resting orders per symbol may reach tens of thousands; trades
  unbounded; orders in terminal states unbounded.
- Concurrency: only issue order matters; two orders that would cross
  each other from different sides, issued back-to-back, trade in issue
  order (the first rests, the second takes).
- Edge cases: buy limit equal to best ask (crosses); buy limit one tick
  below best ask (rests, no trade); incoming order larger than the whole
  opposing side (sweeps all levels, remainder rests or is cancelled);
  incoming order exactly equal to the resting order (both filled); IOC
  with no crossable liquidity (`:cancelled-qty = qty`, no order on book,
  `get-order` shows `:cancelled`); GTC resting behind ten earlier orders
  at the same price; sweep that touches 500 resting orders (trade seqs
  consecutive, first/last in outcome); self-trade (same account both
  sides) executes normally; price 1 and price 10^9; qty 10^9.

### `cancel-order!`

- Invariants: only the target order changes; level aggregate drops by
  exactly the cancelled quantity; a cancelled order's seq is not reused
  or shifted.
- Edge cases: cancel a partially filled order (removes remainder,
  `filled-qty` unchanged, state `:cancelled`); cancel the only order at
  a level (level disappears from depth); cancel a filled order
  (`:order-not-open`); cancel an IOC order (`:order-not-open`); cancel
  twice; cancel with wrong account; cancel of a cancel request-id
  (`:no-such-order`); cancel of an order-id from another symbol
  (`:no-such-order`).

### Queries

- `get-depth`: levels ordered best first; `:order-count` counts open
  orders at the level; a level that was swept to zero does not appear;
  `levels` larger than the number of levels returns all of them.
- `get-trades`: paging with `limit` 1 and 500; `after-seq` beyond the
  tape → `[]`.
- `get-order`: `:state` consistent with quantities in every case.

## Entity state × write matrix

Entities: **symbol book** (absent / has resting orders), **order**
(absent / open remaining r / filled / cancelled), **request-id**
(unused / original).

Order × `submit-limit-order!` (incoming order g)
- absent symbol: book created; g rests (`:gtc`) or is cancelled
  (`:ioc`). get-depth → g's level or `[]`; get-order g → `:open` or
  `:cancelled`; get-trades → `[]`.
- resting opposing crossable orders exist: trades in price/seq order;
  each maker's remaining drops; g's state per remainder rule.
  get-trades → new consecutive trades; get-depth on the opposing side
  shrinks; get-depth on g's side gains g's remainder (gtc only).
- resting same-side orders only: no trade; g rests behind them at its
  price (gtc) or is cancelled (ioc).

Order × `cancel-order!`
- absent: `:no-such-order`.
- open(r): remaining 0, cancelled-qty r, state `:cancelled`; depth
  level −r; get-trades unchanged.
- filled: `:order-not-open`; unchanged.
- cancelled: `:order-not-open`; unchanged.

Request-id × any command
- original accepted submit × replay: no matching, no new order, no
  trades; outcome identical (same `:seq`, same trade seqs).
- original accepted submit × different payload: `:conflicting-attempts`
  +1; book untouched.
- original cancel (accepted or rejected) × replay: unchanged.
- request-id of an order reused for a cancel: conflicting attempt of the
  submit; nothing cancelled.

## Adversarial cases for private tests

Boundary
- Cross exactly at the limit; miss by one tick.
- Sweep across 20 price levels with a GTC remainder that then becomes
  the new best on its side.
- IOC that fills partially: `filled + cancelled = qty`, nothing rests.
- 500-order deep level: cancel the 250th, then an incoming sweep fills
  1..249 and 251.. in seq order.
- qty 10^9 against many small makers; totals within Long.

Retry / idempotency
- Replay an accepted submit after the book changed: no second order, no
  new trades, depth unchanged.
- Replay a submit that was fully filled: outcome unchanged; `get-order`
  still `:filled`.
- Conflicting resubmit with a better price: ignored, counter +1.
- Replay of an accepted cancel: outcome unchanged; no second cancel.
- Same request-id across two symbols: two independent orders.
- Malformed command reusing an existing request-id (price 0): throws;
  the original outcome and its `:conflicting-attempts` are unchanged.
- Malformed command with a fresh request-id: throws; `get-outcome` for
  that id stays nil; the same id then succeeds as an original.
- Out-of-bounds query arguments (`limit 0`, `limit 501`, `after-seq -1`,
  empty ids): `IllegalArgumentException`, no state change.
- Two clients from `:wrap-client` on the same cluster: a command through
  one is visible through the other after `wait-for-processing!`.

Ordering
- Ten GTC buys at one price, then one sell that fills 3.5 of them: fills
  in seq order; the fourth is partially filled and remains first at its
  level; a later buy at the same price rests behind it.
- Submit, cancel, submit(same account, new id) at the same price without
  intermediate barriers: the new order has a later seq and sits behind
  any survivors.
- Interleaved commands on two symbols: each symbol's order and trade
  seqs contiguous from 1.

Conservation / consistency
- For every order, `filled-qty` = sum of trade qtys naming it as maker
  or taker; `remaining-qty` = its contribution to depth.
- Sum of `:qty` in `get-depth` at a price = sum of `remaining-qty` over
  open orders at that price; `:order-count` matches.
- Best bid < best ask after every command.
- Trade tape is identical across two runs of the same command sequence.

## Efficiency review concerns

- Matching must walk only the crossable orders it fills; it must not
  load or serialize the entire book, a whole side, or all orders at a
  level beyond those consumed.
- All bounds must hold as one symbol's own history grows (orders, levels, trades),
  not only as other symbols grow.
- The client wrapper must hold no process-local business state.
- Depth must come from per-level aggregates maintained on the write
  path, not by iterating orders at each level.
- Cancel must find the order by id and its level aggregate directly;
  no scan of the book for the order.
- Order records and trades must not live in a single per-symbol blob
  rewritten per command.
- `get-trades` must seek to `after-seq` directly.
- Retry safety: reprocessing a submit after a failure must not double
  fill makers, double count depth, or emit duplicate trade seqs.
- Priority within a level must be preserved after partial fills without
  rewriting the whole level.
