# Plan Validation — hld-stock-exchange (phase 2, private, fresh pass)

Inputs: README.md, `src/hld_stock_exchange/protocol.clj`,
`test-resources/IMPLICIT_SPEC.md`, `test-resources/PLAN.md` (post
phase-1 repair), skill `references/batch.md`, `microbatch.md`,
`dataflow.md`, `aggregators.md`, `paths.md`, `pstate-schema.md`,
`operate.md`, `testing.md`, `lib/harness`. Supersedes the previous
validation in full.

Verdict: **minor-fail** — one localized addition made directly in
`PLAN.md` (see "Findings and fixes applied"). The architecture (levels +
per-level FIFO queues + order records + trade tape, one microbatch
topology with ordered per-symbol batching) stands.

## Rama semantics relied on (cited)

| Claim | Source | Consequence |
|---|---|---|
| `%mb` append order; sequential microbatches; owning-topology read visibility | `microbatch.md` | Positions in append order; match-loop iterations see earlier level/queue updates; outside readers never see a half-matched book |
| Pre-agg `local-transform>` allowed; `+group-by` auto-partitions; post-agg no partitioners | `batch.md` | Stamp precedes the only partitioner |
| `+vec-agg` order undocumented | `aggregators.md` | Explicit sort present |
| Yielding gives up arrival ordering; yielded reads use a snapshot | `dataflow.md` | Yields inside the match loop are safe only because no other same-symbol event exists in the microbatch |
| `termval` replaces; `keypath` + `NONE>` is delete-only; delete a subindexed structure directly at its key; parent deletion orphans | `paths.md`; `pstate-schema.md` | Symbol entry never written whole; emptied level queue deleted at its own key |
| `sorted-map-range-from-start n` = first n entries (a submap, empty on nil) | `paths.md` | Best-level read is one seek; emptiness test required and present |
| Long keys sort numerically | `pstate-schema.md` | bid-key = 1,000,000,001 − price gives best-first ascending order |
| `depot.microbatch.max.records` per depot; `topology.microbatch.phase.timeout.seconds` per topology; both dynamic | `microbatch.md` "Config"; `operate.md` | Batch bound and timeout mitigation are real knobs |
| `sorted-map-range-from` + `ALL` pairs | `paths.md` | Trade `:seq` recovered from keys |
| Processed count persists across `update-module!` | `testing.md` | Shared atom valid |

## ETL structure review (ordered per-symbol batching)

- Ingress stamp in pre-agg (`PLAN.md` lines 184–188), synchronous, before `+group-by`, on the depot task = PState task. PASS.
- Explicit sort by position (line 195). PASS.
- One loop per symbol (lines 192–207); submit, cancel, replay, conflict branches all unify into the request write and one `continue>` (lines 320–323). PASS.
- `:ingress-seq` distinct from `:next-order-seq`/`:next-trade-seq`; not in the `=` payload. PASS.
- Bounded buffering: `depot.microbatch.max.records` = 1000 (≈ 400 KB per task); transient. PASS.
- No next batch before commit; no exposure before commit; single task per symbol. PASS.
- Yields between maker steps admit only other symbols' loops and reads; the next same-symbol command is the next element of this event's own vector. All trades of one taker are consecutive. PASS.
- Long single command: a sweep over very many makers is bounded only by trades produced (README-mandated cost). With the fix below the phase timeout is raised at build so such a command completes rather than retrying. PASS.
- Build-time verification fallbacks are pre-agg-only. PASS.

## Query topologies
None. `get-depth` returns `[[key {:qty :order-count}] ...]` (plain), client maps key → price. `get-order` plain record with stored `:state`. PASS.

## PState schemas
- One PState `$$symbols` by `symbol`, hash. Object: none. Fixed-keys: yes. `:payload`/`:outcome` polymorphic via `definterface` + `defrecord`: yes. Subindexed: `:orders`, `:ask-levels`, `:bid-levels`, `:ask-queue`/`:bid-queue` outer and inner, `:trades`, `:requests`. PASS.
- Field-level blob review: no stored value grows with history; level aggregates are two Longs; queue entries one order-id. PASS.

## Partitioning
- `(hash-by :symbol)`; README applies a symbol's commands "one at a time; each command is applied atomically" → one task per symbol; symbols in the thousands. Table N = 1/16/128; 0.50+0.20+0.20+0.10 = 1.00; seeks/op = 1 flat; iterator reads depend only on `levels`. PASS.

## Topologies
- One microbatch; no low-latency write; no stream; no test-sync reasoning. PASS.

## Production readiness
- Concurrent clients serialized per symbol; client holds only handles + atom; worker restart → exactly-once (no double fills, duplicate trade seqs, or double depth); scale → all collections subindexed including per-level queues. PASS.

## Internal depot usage / cross-topology / stream correctness / in-memory state
None / single / none / none.

## Minimality — adversarial simplification
Sketch: one depot by symbol, one microbatch topology, one PState with orders, per-side level aggregates, per-level FIFO queues, trades, requests. Diff: ordered batching only.
- Ordered batching: without it, a 1000-command batch or a long sweep runs synchronously (cooperative rule), or per-record yields let a second same-symbol order match against a half-applied book (README "one at a time ... atomically"). Keep.
- `*commands`: split breaks submit/cancel/submit ordering and cross-type conflicts. Keep.
- `core`: stream duplicates fills on retry. Keep.
- Level aggregates: delete → depth iterates orders (README: 10⁴-order level must cost the same as one). Per-level queues: delete → FIFO needs a scan. Combined level record (Option B): equal I/O but embeds a subindex handle and cannot be returned for depth. Order-id in queue entries: delete → one extra seek per trade. Two counters: contiguous seqs. Keep all.

## Throughput — adversarial
- Per trade: level (1) + queue head (1) + maker record (1) = 3 seeks; writes: trade, maker, queue delete, aggregate, optional level/queue delete. Cancel 4 seeks incl. ingress navigation, 5 writes. Depth 1 seek + levels. No cheaper construction that keeps the required outputs.

## Spec coverage — trace every operation and constraint

### `submit-limit-order!` — matching, maker price, FIFO
- Source: "opposing side is scanned best price first ... Within one price, resting orders are taken in ascending order seq (FIFO)"; "crossable when ... `ask-price <= limit` for an incoming buy"; "trade executes for `min(...)` at the resting (maker) order's price"; "otherwise it stays at the front of its level with its original seq".
- Trace: asks 100×5 (o1 seq 1), 100×3 (o2 seq 2), 101×4 (o3 seq 3). Buy limit 100 qty 6 gtc (oseq 4): best ask level `{100 {:qty 8 :order-count 2}}` crossable; head `[1 "o1"]`; t = 5 → trade 1 @100×5, o1 filled → queue entry deleted, aggregate `{3, 1}`; next iteration head `[2 "o2"]`, t = 1 → trade 2 @100×1, o2 remaining 2 (queue entry and seq 2 unchanged), aggregate `{2, 1}`; remaining 0 → exit. Outcome `{:filled-qty 6 :resting-qty 0 :cancelled-qty 0 :trade-count 2 :first-trade-seq 1 :last-trade-seq 2}`; order record `:filled`. Same-price asymmetric makers: o1 (5) and o2 (3) at 100 submitted in different microbatches keep FIFO by seq, since seq is assigned from the durable counter in processing order and the queue is keyed by seq. PASS.

### `submit-limit-order!` — IOC, sweep, rest
- Source: "a `:gtc` remainder rests ... behind every earlier order at that price; an `:ioc` remainder is cancelled and never rests"; "All trades produced by one incoming order have consecutive trade seqs".
- Trace: buy 100 qty 5 ioc → fills 2 from o2 (level 100 qty 0 → level and its queue map deleted), best ask now 101 not crossable → `cancelled-qty 3`, `resting-qty 0`, nothing rests, `get-order` `:cancelled`, depth asks `[{101 4 1}]`. Buy 100 qty 1 gtc with no crossable ask → rests: bid level key 1,000,000,001 − 100 `{1, 1}`, queue `{oseq → id}`; best bid 100 < best ask 101. 500-order level, cancel the 250th, sweep → fills seqs in order skipping the cancelled entry; trade seqs consecutive. PASS.

### `cancel-order!`
- Source: order `:no-such-order`, `:not-owner`, `:order-not-open`; "Cancelling never affects any other order"; outcome `:order-id :cancelled-qty`.
- Trace: o2 (remaining 2, account A). cancel by B → `:not-owner`. cancel by A → queue entry deleted, aggregate `{qty − 2, count − 1}` (level deleted at 0), order `{:remaining-qty 0 :cancelled-qty 2 :state :cancelled}` with `:filled-qty` unchanged; outcome `{:order-id "o2" :cancelled-qty 2}`. cancel again → `:order-not-open`. cancel an ioc order → remaining 0 → `:order-not-open`. cancel a cancel's rid or another symbol's id → `:no-such-order`. PASS.

### Reject-before-create → first submit → original read → replay → conflict
- Trace: fresh symbol "S2". (1) cancel rid "k1" for order "z" → `:orders z` nil → `:no-such-order`; request `k1` under "S2"; `:ingress-seq` 1. (2) submit rid "o9" → counters read nil→0, order seq 1, nested writes create levels/queues/orders; no whole-entry write (lines 126–139); `k1` survives. (3) `get-outcome "S2" "k1"` → rejected, counter 0. (4) replay `k1` → no-op, still rejected. (5) `k1` with a different account → counter 1. Same in one microbatch or across barriers. PASS.

### `get-depth`
- Trace: levels deleted at qty 0 on the write path, so `sorted-map-range-from-start levels` over live levels yields only `:qty > 0`; bids ascending by bid-key = descending price; unknown symbol → `[]`. PASS.

### `get-trades`
- Trace: 1200 trades, after 700, limit 500 → one seek + 500 iterations → 701..1200 with `:seq` from keys; `Long/MAX_VALUE` → `[]`. PASS.

### Invariants
- Never crossed: the loop exits only when the best opposing level is not crossable and the remainder rests at its own limit, strictly inside. Trade price = maker price by construction. `filled + resting + cancelled = qty` written once. Order and trade seqs contiguous from per-symbol counters advanced only on acceptance / per trade. Priority fixed at acceptance (queue keyed by seq; partial fills never rewrite the queue entry). PASS.

### Idempotency / validation order / ordering / durability / shared state
- Replay of an accepted submit → step 1 no-op (no new order, trades, or depth change). Conflicting resubmit with a better price → counter +1. Cancel reusing the order's rid → different record type → conflict. Malformed price 0 with used rid → client throws. Interleaved symbols: independent counters. Orders and trades never deleted; only queue entries and zero-qty levels are removed (spec-driven). Second client shares the atom. PASS.

### Resource guarantees (own history)
- Symbol with 10⁷ orders and 10⁸ trades: submit = 3 + 3/trade + 1 seeks; cancel 4; depth 1 seek + levels; trades after 9×10⁷ = 1 seek + limit. Best-level lookup ranges over live levels only. PASS.

## Findings and fixes applied (decision / basis / outcome)

| # | Finding | Basis | Fix applied to PLAN.md |
|---|---|---|---|
| 1 | The stated limitation "a sweep consuming 10⁶ makers would exceed `topology.microbatch.phase.timeout.seconds`, retrying indefinitely" had no mitigation, leaving a spec-legal command able to stall the topology | `microbatch.md`: a record that retries forever blocks the topology; `operate.md`: per-topology dynamic options settable at launch | Added: raise `topology.microbatch.phase.timeout.seconds` for `core` at build via `set-launch-topology-dynamic-option!` well above the longest bounded sweep; option remains adjustable at runtime |

## Test plan requirements (for phase 5)
- 2- and 4-task deployments; second `:wrap-client`; `update-module!` persistence (book, orders, trades, outcomes; next order/trade seq continue); explicit waits.
- Independent expectations: prices 99, 100, 101 and 1, 10^9; quantities 5, 3, 4, 10^9; distinct accounts on both sides plus a self-trade.
- Same-price asymmetric makers across processing boundaries: makers of different quantities at one price submitted in separate `wait-for-processing!` rounds, then one taker; assert fills in seq order and the partially filled maker keeps its position ahead of a later same-price order.
- Reject-before-create scenario as traced (rejected cancel before the symbol's first order); rejected outcomes preserved.
- Invariant sweeps: best bid < best ask; per order `filled = Σ trade qty naming it`; depth `:qty`/`:order-count` = Σ over open orders read individually; tape identical across two runs.
- Deep-page scaling at high `after-seq`; field-level no-blob review (no per-symbol book blob).
- Efficiency capture: account for the fixed ingress navigation + field write; keep detection of per-level or per-side scans (a 10⁴-order level must cost the same as a 1-order level).
- No assertions on topology names, microbatch counts, or tight timing.
- Targeted mutants: execution at taker price; partial maker re-queued behind later orders; ioc remainder resting; zero-qty level left in depth; cancel not decrementing `:order-count`; trade seqs restarting per command; replay re-matching; missing `:seq` in trade pages; bid ordering ascending by price; outcome lost after the symbol's first submit.
