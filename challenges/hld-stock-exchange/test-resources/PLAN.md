# Plan — hld-stock-exchange (subsystem: matching-engine, whole spec)

Private reference-implementation plan (Phase 1). Inputs: README.md,
`src/hld_stock_exchange/protocol.clj`, `test-resources/IMPLICIT_SPEC.md`,
`test-resources/DECOMPOSITION.json`. Design only; no topology code.

## Reads

All reads are scoped by `symbol`, the depot partition key and the
top-level PState key: one task per read.

| Read | Access method | Path | RocksDB reads |
|---|---|---|---|
| `get-outcome sym rid` | `foreign-select-one` | `[(keypath sym :requests rid)]` on `$$symbols` → request record or nil; client renders the outcome map | 2 |
| `get-order sym oid` | `foreign-select-one` | `[(keypath sym :orders oid)]` → order record (includes stored `:state`) or nil; client `assoc`s `:order-id` | 2 |
| `get-depth sym side levels` | `foreign-select` | `[(keypath sym <side>-levels) (sorted-map-range-from-start levels) ALL]` → `[[level-key {:qty :order-count}] ...]` best first; client maps `level-key` back to `price` | 1 + 1 seek + `levels` iterations |
| `get-trades sym after limit` | `foreign-select` | client returns `[]` without a call when `after = Long/MAX_VALUE` (`(inc after)` would overflow); else `[(keypath sym :trades) (sorted-map-range-from (inc after) {:max-amt limit}) ALL]` → `[seq trade]` pairs ascending; client builds each trade as `(assoc trade :seq seq)` because the stored record does not carry `:seq` | 1 + 1 seek + `limit` iterations |

Every read is one path on one partition → no query topology. Depth comes
from per-level aggregates maintained on the write path, so a level with
10⁴ orders costs one iterator read.

Level key: asks are keyed by `price`; bids are keyed by
`bid-key = 1,000,000,001 − price` (a positive Long, since
`1 ≤ price ≤ 10⁹`). Ascending key order is therefore best-first on both
sides, so matching and depth use the same `sorted-map-range-from-start`
navigation. Long keys sort numerically in subindexed maps (skill:
"numbers sort the same as their in-memory forms"); a composite
`[price seq]` key was rejected because serialized-vector order is not
guaranteed to be numeric.

Client validation: ids non-empty ≤ 128, `side` ∈ `#{:buy :sell}`, `tif` ∈
`#{:gtc :ioc}`, `price`/`qty` `1..10⁹`, `levels` 1..50, `limit` 1..500,
`after-seq ≥ 0`; violations throw `IllegalArgumentException` before any
call.

## Writes

One depot `*commands`, `(hash-by :symbol)`, two `defrecord` types with
`symbol` and `request-id`:

| Command | Depot record |
|---|---|
| `submit-limit-order!` | `(->SubmitOrder symbol request-id account-id side price qty tif)` |
| `cancel-order!` | `(->CancelOrder symbol request-id order-id account-id)` |

Client appends with `:ack` after structural validation and returns `nil`.
Record equality is payload equality; a cancel reusing an order's
request-id is a conflicting attempt of the submit.

## PState Design

One PState `$$symbols`, owned by microbatch topology `core`:

```clojure
(declare-pstate mb $$symbols
  {String                                              ; symbol
   (fixed-keys-schema
     {:ingress-seq    Long                             ; last ingress position assigned (ordering only; see ETL structure)
      :next-order-seq Long                             ; last order seq issued
      :next-trade-seq Long                             ; last trade seq issued
      :orders     (map-schema String                   ; order-id (= request-id) ->
                    (fixed-keys-schema
                      {:account-id String :side clojure.lang.Keyword
                       :price Long :qty Long :tif clojure.lang.Keyword :seq Long
                       :filled-qty Long :remaining-qty Long :cancelled-qty Long
                       :state clojure.lang.Keyword})   ; :open | :filled | :cancelled
                    {:subindex? true})
      :ask-levels (map-schema Long                     ; price ->
                    (fixed-keys-schema {:qty Long :order-count Long})
                    {:subindex? true})
      :bid-levels (map-schema Long                     ; bid-key = 1000000001 - price ->
                    (fixed-keys-schema {:qty Long :order-count Long})
                    {:subindex? true})
      :ask-queue  (map-schema Long                     ; price ->
                    (map-schema Long String {:subindex? true})   ; order seq -> order-id (FIFO)
                    {:subindex? true})
      :bid-queue  (map-schema Long                     ; bid-key ->
                    (map-schema Long String {:subindex? true})
                    {:subindex? true})
      :trades     (map-schema Long                     ; trade seq ->
                    (fixed-keys-schema
                      {:price Long :qty Long
                       :maker-order-id String :taker-order-id String
                       :maker-account-id String :taker-account-id String
                       :taker-side clojure.lang.Keyword})
                    {:subindex? true})
      :requests   (map-schema String
                    (fixed-keys-schema
                      {:command              clojure.lang.Keyword
                       :payload              ICommand
                       :outcome              IOutcome
                       :conflicting-attempts Long})
                    {:subindex? true})})})
```

```clojure
(definterface ICommand)   ; SubmitOrder, CancelOrder
(definterface IOutcome)
(defrecord SubmitAccepted [order-id seq filled-qty resting-qty cancelled-qty
                           trade-count first-trade-seq last-trade-seq] IOutcome)
(defrecord CancelAccepted [order-id cancelled-qty] IOutcome)
(defrecord Rejected [reason] IOutcome)
```

Book layout alternatives costed:

- **Option A (chosen): per side, `levels` (price-key → aggregate) plus
  `queue` (price-key → subindexed `seq → order-id`).** Matching reads the
  best level (1 seek on `levels`), the queue head (1 seek on the nested
  map), and the maker order (1 seek). Depth is a range over `levels`
  (1 seek + `levels` iterations). Cancel removes one queue entry (write-
  only `NONE>`) and adjusts one aggregate (1 seek). A partial fill writes
  only the maker's order record and the aggregate; the queue entry keeps
  its seq, so priority is preserved without rewriting the level.
- **Option B: one map per side with the aggregate and queue in one record
  `{:qty :order-count :queue (subindexed)}`.** Saves one seek per
  level touch on the write path but `get-depth` cannot return the record
  whole over the wire (it embeds a subindexed handle) and would need a
  per-value `submap` projection; equal iterator cost, more fragile.
  Rejected on read-path simplicity at equal I/O.
- **Option C: whole side as one sorted structure of orders.** Rejected:
  depth would iterate orders instead of levels (README: a level with ten
  thousand orders must cost the same as one), and cancel would scan.

Why one PState: all collections share the key `symbol` and partitioner.
The top-level value is three Longs and seven subindex handles. It is
**never written whole**, not even when a symbol first appears: the first
field-path or nested write creates it, and the three counters are read
and written by field path only —
`[(keypath *sym :ingress-seq) (termval pos)]`,
`[(keypath *sym :next-order-seq) (termval oseq)]`,
`[(keypath *sym :next-trade-seq) (termval tseq)]` — because a `termval`
of the entry would replace the map and orphan the seven subindexed
children (`paths.md`, `pstate-schema.md`) and would also erase
`:requests` entries already stored under the key (a symbol's first
command may be a rejected cancel, whose outcome must survive every later
write). Every growing collection is subindexed; a level's queue is itself
subindexed because one level can hold thousands of orders.

## Depots

- `*commands`: `(hash-by :symbol)`, records `SubmitOrder`, `CancelOrder`.
  One depot: the README applies a symbol's commands one at a time in
  issue order (submit, cancel, submit without barriers must interleave
  correctly) and request-ids are shared across both types.

## Topologies and PStates

- `core`: **microbatch** (default). No single-digit-millisecond
  visibility requirement (barrier-based), no ack-return, and every write
  is non-idempotent (seq counters, fills, aggregates, trades).
  Microbatch exactly-once is what makes "reprocessing a submit must not
  double-fill makers, double-count depth, or emit duplicate trade seqs"
  hold under retry: a failed attempt's writes are discarded and the
  microbatch is re-applied once.
  - Owns `$$symbols`.
  - ETL structure: one `<<batch` block implementing **ordered per-symbol
    batching** (next subsection). A symbol's commands of one microbatch
    are applied by ONE loop event per symbol, in depot append order, each
    command completely (including its outcome) before the next; the loop
    yields cooperatively between bounded steps. Readers outside the
    topology see only committed state, so no partially matched book is
    ever observable.

### Ordered per-symbol batching (ETL structure)

Two rules collide. The README applies a symbol's commands one at a time
in issue order, each atomically. The skill's cooperative-multitasking
rule forbids unbounded synchronous work on a task, and a single sweep can
consume arbitrarily many resting makers while one microbatch can carry
thousands of commands for one symbol. A `yield-if-overtime` inside plain
per-record processing would let the next same-symbol record start
against a half-applied book (`dataflow.md` "Yielding and ordering").
Resolution: make a symbol's whole batch of commands one event, ordered
by a durable ingress position, and yield only inside that event.

```clojure
(<<sources mb
  (source> *commands :> %mb)
  (<<batch
    ;; pre-agg: runs on the depot partition task (hash of symbol) in depot
    ;; append order, synchronously, before any partitioner or yield
    (%mb :> *cmd)
    (get *cmd :symbol :> *sym)
    (local-select> [(keypath *sym :ingress-seq) (nil->val 0)] $$symbols :> *prev)
    (inc *prev :> *pos)
    (local-transform> [(keypath *sym :ingress-seq) (termval *pos)] $$symbols)
    (vector *pos *cmd :> *pair)
    ;; agg: one row per symbol; +group-by hash-partitions by *sym, the same
    ;; routing the PState uses for its top-level key
    (+group-by *sym
      (aggs/+vec-agg *pair :> *pairs))
    ;; post-agg: one event per symbol per microbatch
    (sort-pairs-by-position *pairs :> *ordered)   ; defn sorting by *pos; +vec-agg order is NOT assumed
    (loop<- [*remaining *ordered :> *done]
      (<<if (empty? *remaining)
        (:> true)
       (else>)
        (first *remaining :> [*pos *cmd])
        ;; apply-command: the complete per-command procedure below,
        ;; including its bounded inner match loop (which yields) and the
        ;; request-record write; replay, conflict, rejection, and
        ;; acceptance branches all unify here, so exactly one continue>
        ;; runs per command
        (yield-if-overtime)
        (continue> (rest *remaining))))))
```

Properties relied on:

- `%mb` emits per task in depot append order and the pre-agg segment up
  to the `+group-by` partitioner is synchronous (async boundaries are
  partitioners and the like, `dataflow.md`), so ingress positions are
  assigned in append order and are unique per symbol; reads inside the
  owning topology see the attempt's own writes (`microbatch.md` "Read
  visibility"), so consecutive records see each other's increments.
- `+group-by` needs no explicit partitioner and emits one row per key;
  post-agg has only `*sym` and `*pairs` in scope and allows no
  partitioner (`batch.md`, `aggregators.md`). `+vec-agg` element order
  is not documented, hence the explicit sort by position.
- Yields inside the loop (`yield-if-overtime` between commands and
  between maker steps) let other events on the task run: other symbols'
  loops, query topologies, foreign reads. None touches this symbol's
  uncommitted writes: the only writer of a symbol within a microbatch is
  this one loop, external readers see committed state only, and the next
  microbatch does not start until this one completes on all tasks
  (`microbatch.md` "Sequential processing", "Read visibility"). Ordering
  within a symbol is preserved, and a retry rolls back and reapplies the
  ingress positions and the business writes together (exactly-once).
- `:ingress-seq` is a durable per-symbol Long written by field path only.
  It counts every command (replays, conflicts, rejections included), is
  independent of `:next-order-seq`/`:next-trade-seq`, and is never
  exposed.
- Batch bound: `(set-launch-depot-dynamic-option! setup "*commands"
  "depot.microbatch.max.records" 1000)` caps records per depot partition
  per microbatch, so a grouped vector holds at most one batch (≤ 1000
  records ≈ 400 KB per task), never lifetime history. Group memory is
  transient batch state, not a TaskGlobal and not a durable inbox.
- Cost: one `+group-by` hop per command (a network partitioner, landing
  on the same task as the depot partition), plus one extra navigation of
  the symbol's top-level entry and one field write in pre-agg. The
  earlier "zero partitioners" claim is withdrawn.
- Limitation, stated plainly: a single command's work remains bounded
  only by its own inputs and outputs (README). A sweep that consumes 10⁶
  makers costs ~3×10⁶ seeks (minutes) inside one microbatch attempt and
  would exceed `topology.microbatch.phase.timeout.seconds`, retrying
  indefinitely. No input cap is imposed and no cross-microbatch staged
  matching is designed here; yields keep the task responsive during such
  a command, they do not shorten it. Mitigation configured at build:
  `topology.microbatch.phase.timeout.seconds` for `core` is raised via
  `set-launch-topology-dynamic-option!` (`operate.md` "Dynamic Options";
  exact arity confirmed at build) to a value well above the longest
  sweep the README bounds allow, so a legitimately long command completes
  once instead of retrying; the option is dynamic and can be raised
  further at runtime without a redeploy.
- Build-time verifications: (a) `local-transform>` and `loop<-` are
  accepted in the post-agg of a microbatch `<<batch`; fallback is
  `(materialize> *sym *ordered :> $$grouped)` and running the loop in the
  pre-agg of a second `<<batch` that reads `($$grouped :> *sym *ordered)`.
  (b) `+group-by` routing lands on the PState partition of `*sym` at 2
  and 4 tasks (a mismatch shows as foreign reads returning nil).
  (c) `sort-pairs-by-position` and `first`-destructuring are plain
  `defn`s callable in dataflow.

Per-command processing (`apply-command`, on the symbol's task, inside
the loop above):

1. `[(keypath *sym :requests *rid)]` → replay (no-op) or conflict
   (`:conflicting-attempts` +1); stop (unify to the loop's `continue>`).
2. `SubmitOrder` (no business rejections):
   - Read `[(keypath *sym :next-order-seq)]` and
     `[(keypath *sym :next-trade-seq)]` (nil ⇒ 0; one top-level entry,
     one seek). `oseq = (inc next-order-seq)`; opposing side = asks for a buy, bids
     for a sell; `crossable?(level-price)` = `price ≤ limit` for a buy,
     `price ≥ limit` for a sell (with bid-key translated back to price).
   - `loop<-` `[*remaining qty, *filled 0, *trades 0, *first nil, *last nil]`,
     with `(yield-if-overtime)` at the top of every iteration (one
     iteration = one bounded maker step): read the best opposing level with
     `[(keypath *sym <opp>-levels) (sorted-map-range-from-start 1)]`
     (1 seek; navigates to one submap, `{}` when the side is empty). If
     the submap is empty or its single level is not crossable → exit. Else read the queue
     head `[(keypath *sym <opp>-queue lvl-key) (sorted-map-range-from-start 1)]`
     → `[maker-seq maker-oid]` (1 seek); read the maker order (1 seek).
     `t = (min remaining maker.remaining-qty)`; `tseq = (inc trade-seq)`.
     Write trade `[(keypath *sym :trades tseq) (termval {...})]` at the
     maker's price with maker/taker ids, accounts, and taker side. Write
     the maker record with `filled-qty + t`, `remaining-qty − t`, and
     `:state :filled` when it reaches 0 (`termval` of the read record).
     If the maker is now filled: delete its queue entry
     `[(keypath *sym <opp>-queue lvl-key maker-seq) NONE>]` (write-only)
     and update the level aggregate `{qty − t, order-count − 1}`; if the
     aggregate's qty is now 0, delete the level with
     `[(keypath *sym <opp>-levels lvl-key) NONE>]` and the now-empty
     queue map with `[(keypath *sym <opp>-queue lvl-key) NONE>]`
     (deleting the subindexed map directly, per the skill's subindex
     deletion rule — spec requires zero-qty levels not to appear in
     depth). Else update the aggregate `{qty − t}` only.
     `continue>` with `remaining − t`.
   - After the loop: if `tif = :gtc` and `remaining > 0`: write queue
     entry `[(keypath *sym <own>-queue own-key oseq) (termval oid)]`
     (write-only) and level aggregate `(nil->val {:qty 0 :order-count 0})`
     then `{qty + remaining, order-count + 1}` (1 seek); `resting =
     remaining`, `cancelled = 0`, state `:open`. If `:ioc`:
     `cancelled = remaining`, `resting = 0`, state `:cancelled` (or
     `:filled` if remaining is 0). Write the order record once with final
     quantities and state; write the counters by field path
     (`:next-order-seq` ← oseq, `:next-trade-seq` ← last issued);
     `SubmitAccepted`.
   - Yielding here is safe only because of the ETL structure above: the
     next command for the same symbol is the next element of this
     event's own vector, not a separately queued event, so it cannot
     start against a half-applied book. The loop does ~3 seeks + ≤ 5
     writes per trade and is bounded by trades produced, as the README
     requires; a 500-trade sweep is ~10 ms warm, yielding roughly every
     5 ms of it.
3. `CancelOrder`: read `[(keypath *sym :orders oid)]` → nil ⇒
   `:no-such-order` (cancel request-ids and other symbols' orders are
   absent here). `account-id` mismatch → `:not-owner`. `remaining-qty = 0`
   → `:order-not-open` (filled, cancelled, or any `:ioc`). Else `r =
   remaining-qty`; delete the queue entry `[(keypath *sym <side>-queue
   key seq) NONE>]`; read/update the level aggregate `{qty − r,
   order-count − 1}` (delete the level when qty reaches 0); write the
   order record with `remaining-qty 0`, `cancelled-qty r`, `:state
   :cancelled`; `CancelAccepted oid r`.
4. Write the request record with `:conflicting-attempts 0`. Every branch
   of steps 1–4 (replay, conflict, rejection, acceptance) ends here and
   unifies into the outer loop's single `continue>`; no branch may
   short-circuit past it.

I/O bounds: submit = 3 seeks + 3 seeks per trade + 1 seek for the
resting level, writes ≤ 5 per trade + 5; cancel = 4 seeks, 5 writes
(the extra seek and write per command are the ingress position on the
symbol's top-level entry, which the command reads anyway). Proportional
to trades produced plus a constant; never touches the whole side, all
orders at a level, or the tape.

Invariants by construction: trades execute at the maker's price and only
while crossable, so the book never crosses; the maker's queue position is
its immutable `seq`; order seqs and trade seqs are issued from the
per-symbol counters in processing order and are contiguous; all trades of
one taker are consecutive because no other command of the symbol runs
mid-match (the match loop's yields admit only other symbols' loops and
reads).

## Query Topologies

None. Every read is a single path on one partition.

## Partitioning efficiency

Optimal placement: every read and command is scoped to one symbol, and
the README applies a symbol's commands serially and atomically, so
`f(symbol) → one task`: `(hash-by :symbol)` on the depot and default hash
routing on `$$symbols`. Symbols number in the thousands; a hot symbol is a
serial stream by spec (a matching engine is single-threaded per symbol),
so no partitioner can spread it without violating ordering. Seeks/op =
tasks touched = 1.

Dominant read: `get-depth` (5 levels typical).

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| liquid symbol, 5 levels requested | 0.50 | 1 | 5 |
| liquid symbol, 50 levels, 10⁴ orders per level | 0.20 | 1 | 50 |
| thin symbol with 2 levels, 10 requested | 0.20 | 1 | 2 |
| unknown symbol or empty side | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 12.9

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| liquid symbol, 5 levels requested | 0.50 | 1 | 5 |
| liquid symbol, 50 levels, 10⁴ orders per level | 0.20 | 1 | 50 |
| thin symbol with 2 levels, 10 requested | 0.20 | 1 | 2 |
| unknown symbol or empty side | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 12.9

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| liquid symbol, 5 levels requested | 0.50 | 1 | 5 |
| liquid symbol, 50 levels, 10⁴ orders per level | 0.20 | 1 | 50 |
| thin symbol with 2 levels, 10 requested | 0.20 | 1 | 2 |
| unknown symbol or empty side | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 12.9

Flat across N; iterator reads depend only on `levels` and the number of
non-empty levels, never on orders per level.

## Design Decisions

- Subindexing: `:orders`, `:*-levels`, `:*-queue` (outer and inner),
  `:trades`, `:requests` grow without bound → subindexed. The top-level
  record is not.
- Colocation: depot `(hash-by :symbol)` = PState key = `+group-by *sym`
  routing. The only partitioner is the `+group-by` hop, which lands on
  the task that already holds the symbol.
- Ordering and yielding: ordered per-symbol batching (one loop event per
  symbol per microbatch, sorted by durable `:ingress-seq`), with
  `yield-if-overtime` between commands and between maker steps.
  `depot.microbatch.max.records` = 1000 bounds the per-microbatch group
  memory.
- Symbol entry creation: a symbol's top-level entry is never written
  whole; only field paths and nested paths are written, so a rejected
  command stored under a not-yet-traded symbol keeps its outcome.
- Bid ordering via `bid-key = 1,000,000,001 − price` keeps both sides
  ascending-is-best with plain Long keys.
- Level deletion at qty 0 is spec-driven (zero levels never appear in
  depth); orders and trades are never deleted.
- `:state` is stored in the order record (written by the topology), so
  `get-order` is a single path with no client-side domain logic.
- Determinism: matching depends only on the book and the command
  sequence; the trade tape is identical across runs of the same sequence.

### Synchronization and client contract

`create-module` creates one `(atom 0)` in its closure shared by every
`:wrap-client`; each command increments it before appending;
`wait-for-processing!` calls
`(rtest/wait-for-microbatch-processed-count ipc module-name "core" @cnt)`.
Module-level cumulative counting keeps this valid across a second client
and `update-module!`. The client holds no business state.

## State primitive selection

- `$$symbols` (PState): durable; writes per submit ≤ 5 per trade + 5,
  bounded by trades produced (which is bounded by the opposing resting
  orders the command consumes). `:ingress-seq` lives here because it
  must roll back with the attempt on retry; a TaskGlobal counter would
  not.
- Per-microbatch grouped vectors (`+vec-agg` output): transient batch
  state inside one microbatch attempt, bounded by
  `depot.microbatch.max.records`; not a TaskGlobal, not durable, holds
  nothing after the attempt commits.
- No TaskGlobal.
- Client atom: sync bookkeeping only.

## Resource usage analysis

### Disk usage (PStates)
- Top-level entry: ≤ 128 B + ~200 B.
- Order record: ids ≤ 256 B + 9 scalars/keywords → ~400 B; 10⁷ orders per
  symbol ≈ 4 GB over the symbol's task (spread by symbol hash).
- Level aggregate: 8 B key + ~30 B. Queue entry: 8 B + 8 B + order-id
  ≤ 128 B → ~150 B; only resting orders occupy queue entries.
- Trade record: ~550 B.
- Request record: payload ~350 B + outcome ~150 B.
- Growth per submit: order + request (+ queue entry while resting) +
  one trade per fill.

### Memory usage (TaskGlobals)
None. Transient per-microbatch memory: the grouped `[pos cmd]` vectors,
≤ `depot.microbatch.max.records` (1000) records per task per microbatch
at ≤ ~400 B each ≈ 400 KB, released when the attempt commits.

### Minimization
- The order-id is duplicated into queue entries (≤ 128 B) so matching
  reads the head without a secondary lookup; required for the 3-seek
  per-trade bound.
- Request payloads are verbatim by the `=` replay contract.
- `:ingress-seq` is one Long per symbol; it cannot be folded into
  `:next-order-seq` because that counter must stay contiguous over
  accepted submits only.

## Design difficulty log

- **Decision:** ordered per-symbol batching — pre-agg assigns a durable
  `:ingress-seq` position per command, `+group-by` symbol with
  `+vec-agg`, explicit sort by position, one `loop<-` per symbol per
  microbatch that applies each command completely, with
  `yield-if-overtime` between commands and between maker steps.
  **Basis:** README "one at a time, atomically" per symbol; skill
  cooperative-multitasking rule; `dataflow.md` states yielding gives up
  arrival ordering across events; `+vec-agg` ordering is undocumented.
  Alternatives: (1) per-record processing with no yield — keeps ordering
  but a sweep over many makers or a thousand queued commands blocks the
  task unboundedly, violating the cooperative rule; (2) per-record
  processing with `yield-if-overtime` — interleaves same-symbol commands
  against a half-applied book; (3) grouping without a durable position,
  trusting `+vec-agg` order — unverifiable and would silently break
  price-time priority; (4) a durable inbox PState or TaskGlobal queue —
  extra state and the TaskGlobal variant does not roll back on retry.
  **Outcome:** ordering and cooperative yielding both hold; cost is one
  `+group-by` hop plus one top-level read and field write per command;
  a single pathological command can still exceed the microbatch phase
  timeout (stated above, no input cap invented). Needs build-time
  verification of post-agg `local-transform>`/`loop<-` acceptance,
  `+group-by`-to-PState routing agreement at 2 and 4 tasks, and a test
  that interleaves many same-symbol commands in one microbatch and checks
  order/trade seqs against a sequential oracle.
- **Decision:** never write a symbol's top-level entry whole; create it
  implicitly through field-path and nested writes only.
  **Basis:** `paths.md` (`termval` replaces the map), `pstate-schema.md`
  (orphaned subindexed children), and the fact that a rejected cancel
  can store a `:requests` entry under a symbol before any order exists.
  **Outcome:** rejected outcomes and `:ingress-seq` survive the symbol's
  first accepted submit. Verify with: cancel on an unknown order (rejected)
  → first submit for that symbol → replay of the cancel still rejected,
  conflicting cancel payload increments the counter, both when the two
  commands share a microbatch and when they are separated by a barrier.
- **Decision:** level/queue split (Option A) over a combined level
  record (Option B). **Basis:** equal I/O; a combined record would embed
  a subindexed handle and could not be returned over the wire for depth.
  **Outcome:** `get-depth` is one plain range read; one extra seek per
  level touch on the write path.
- **Decision:** nested `price → seq` maps over composite `[price seq]`
  keys. **Basis:** `pstate-schema.md` guarantees in-memory sort order for
  numbers, not for serialized vectors. **Outcome:** FIFO within a level
  is a range-from-start on Long keys.
- One depot, one PState, and microbatch are determined directly by the
  ordering, exactly-once, and shared-request-id requirements.
