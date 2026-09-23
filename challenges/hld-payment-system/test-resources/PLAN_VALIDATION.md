# Plan Validation — hld-payment-system (phase 2, private, fresh pass)

Inputs: README.md, `src/hld_payment_system/protocol.clj`,
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
| `%mb` append order; sequential microbatches; owning-topology read visibility | `microbatch.md` | Positions in append order; loop sees its own earlier commands; outside readers see committed balances only |
| Pre-agg `local-transform>` allowed; `+group-by` auto-partitions; post-agg no partitioners | `batch.md` | Stamp precedes the only partitioner |
| `+vec-agg` order undocumented | `aggregators.md` | Explicit sort present |
| Yielding gives up arrival ordering | `dataflow.md` | Safe only because one event per tenant exists per microbatch |
| `termval` replaces a map; parent deletion orphans subindexed children; absent fields navigate to nil | `paths.md`; `pstate-schema.md` | Tenant entry never written whole; existence = `:currency` non-nil |
| `depot.microbatch.max.records` per depot via `set-launch-depot-dynamic-option!` | `microbatch.md`; `operate.md` | Batch bound correctly scoped |
| `sorted-map-range-from` + `ALL` pairs | `paths.md` | Journal `:seq` recovered from keys |
| Processed count persists across `update-module!` | `testing.md` | Shared atom valid |

## ETL structure review (ordered per-tenant batching)

- Ingress stamp in pre-agg (`PLAN.md` lines 162–166), synchronous, before `+group-by`, on the depot task = PState task. PASS.
- Explicit sort by position (line 173). PASS.
- One loop per tenant (lines 170–184); each command's branches unify into the request write and one `continue>` (lines 274–276). PASS.
- `:ingress-seq` distinct from journal `:next-seq`; not in the `=` payload. PASS.
- Bounded buffering: `depot.microbatch.max.records` = 1000 (≈ 450 KB per task); transient. PASS.
- No next batch before commit; no exposure before commit; single task per tenant. PASS.
- No yield inside a command (constant work); `yield-if-overtime` between commands cannot admit a same-tenant command. PASS.
- Build-time verification fallbacks are pre-agg-only. PASS.

## Query topologies
None; every read is one path on one partition and returns plain values (`{:kind :balance}`, charge record, journal rows). PASS.

## PState schemas
- One PState `$$tenants` by `tenant-id`, hash. Object: none. Fixed-keys: yes. `:payload`/`:outcome` polymorphic via `definterface` + `defrecord`: yes. Subindexed: `:accounts`, `:charges`, `:journal`, `:requests`. `postings` is exactly 2 by construction. PASS.
- Field-level blob review: no stored value grows with history. PASS.

## Partitioning
- `(hash-by :tenant-id)`; per-tenant ordering and contiguous seqs force one task per tenant; tenants many. Table N = 1/16/128; 0.50+0.35+0.05+0.10 = 1.00; seeks/op = 1 flat. PASS.

## Topologies
- One microbatch; no low-latency write; no stream; no test-sync reasoning. PASS.

## Production readiness
- Concurrent clients serialized per tenant; client holds only handles + atom; worker restart → exactly-once (no double posting, no double seq); scale → subindexed; `charge!` reads exactly two account records. PASS.

## Internal depot usage / cross-topology / stream correctness / in-memory state
None / single / none / none.

## Minimality — adversarial simplification
Sketch: one depot by tenant, one microbatch topology, one PState with subindexed accounts/charges/journal/requests, foreign selects only. Diff: ordered batching only.
- Ordered batching: without it, a 1000-command batch for a hot tenant runs synchronously (cooperative rule), or per-record yields let two charges against one customer interleave between balance read and write (README ordering, "customer never below 0"). Keep.
- `*commands`: split breaks "fund, charge, refund → seqs 1, 2, 3" and cross-type conflicts. Keep.
- `core`: stream re-posts on retry. Keep.
- `:charges`: delete → refund scans the journal (forbidden). Stored balances: delete → `get-balance` sums the journal (forbidden). `:next-seq`: per-tenant contiguity. Keep.

## Throughput — adversarial
- fund 4 seeks / 5 writes; charge 4 / 6; refund 5 / 6 (each seek is a distinct required record; `:currency`, `:next-seq`, `:ingress-seq` are navigations of one top-level entry). No cheaper construction.

## Spec coverage — trace every operation and constraint

### `create-tenant!` / `create-account!`
- Source: "`:tenant-exists` ... regardless of currency"; "`:account-exists`"; structural throw for `"clearing"`.
- Trace: create t1 USD → `:currency` nil → write fields `:currency "USD"`, `:next-seq 0`, and `accounts "clearing" {:kind :clearing :balance 0}`; never the entry whole. Re-create t1 EUR → `:tenant-exists`, currency stays USD. create-account "clearing" → client throws. create-account before tenant → `:no-such-tenant`. PASS.

### `fund!`
- Source: postings `[{:account-id "clearing" :delta −amount} {:account-id account-id :delta +amount}]`; outcome `:seq :balance`.
- Trace: fund c1 700 → c1 700, clearing −700, journal 1 `{:type :fund :charge-id nil :postings [{clearing −700} {c1 +700}]}`, outcome `{:seq 1 :balance 700}`. Fund unknown account → `:no-such-account`, clearing unchanged, no seq consumed. Fund a merchant → allowed. PASS.

### `charge!`
- Source: order `:no-such-tenant`, `:no-such-account`, `:wrong-account-kind`, `:insufficient-funds`; postings customer → merchant; outcome `:charge-id :seq :customer-balance`; "The charge's `:seq` ... never changes".
- Trace: c1 700 (customer), m1 (merchant). charge r1 c1→m1 300 → c1 400, m1 300, journal 2 with `:charge-id "r1"`, charge record `{:amount 300 :refunded-total 0 :seq 2}`, outcome `{:charge-id "r1" :seq 2 :customer-balance 400}`. charge r2 500 → `:insufficient-funds`, no journal, no charge record. m1→c1 → `:wrong-account-kind`. c1→c1 → one id cannot be both kinds → `:wrong-account-kind`. amount = balance → accepted, balance 0. PASS.

### `refund!`
- Source: postings merchant → customer "using the accounts of the original charge"; order `:no-such-charge`, `:refund-exceeds-charge`, `:insufficient-funds`; outcome `:refund-id :charge-id :seq :refunded-total`; charge `:seq` unchanged.
- Trace: refund f1 of r1 100 → m1 200, c1 500, journal 3 `{:type :refund :charge-id "r1" :postings [{m1 −100} {c1 +100}]}`, charge `refunded-total 100`, `:seq 2` untouched; outcome `{:refund-id "f1" :charge-id "r1" :seq 3 :refunded-total 100}`. f2 200 → total 300 = amount, accepted. f3 1 → `:refund-exceeds-charge` (checked before merchant funds). refund of "f1" → not in `:charges` → `:no-such-charge`. Merchant drained → `:insufficient-funds`. PASS.

### Reject-before-create → create → original read → replay → conflict
- Trace: fresh tenant "t2". (1) fund rid "x1" → `:currency` nil → `:no-such-tenant`; request `x1` under "t2"; `:ingress-seq` 1. (2) create "t2" → field writes only (lines 242–249); `x1` survives. (3) `get-outcome "t2" "x1"` → rejected, counter 0. (4) replay identical → no-op, still rejected. (5) different amount → counter 1, no posting. Same in one microbatch or across barriers. PASS.

### `get-journal`
- Trace: 1200 rows, after 1000, limit 500 → one seek + 200 iterations → 1001..1200 with `:seq` from keys; `Long/MAX_VALUE` → `[]`; unknown tenant → `[]`. PASS.

### Conservation invariants
- Source: "sum of balances over all accounts including `"clearing"` is `0`"; "Every account other than `"clearing"` has balance `≥ 0`"; "Every account's balance equals the sum of its posting deltas".
- Trace: every accepted transaction writes exactly `−a` and `+a` to two accounts and one journal row in one event; source check precedes writes; rejected commands write nothing and consume no seq; a retry rolls back all four writes together. PASS.

### Idempotency / validation order / ordering / durability / shared state
- Replay of an accepted charge → no second posting; replay of `:insufficient-funds` after funding → still rejected; refund reusing the charge's rid → different record type → conflict +1, no posting; malformed amount 0 with used rid → client throws. fund, charge, refund back-to-back in one microbatch → positions 1..3 in one loop → seqs 1, 2, 3. Two charges racing for the last funds → exactly one accepted. Journal rows never modified. Second client shares the atom. PASS.

### Resource guarantees (own history)
- Tenant with 10⁶ accounts and 10⁸ journal rows: charge 4 seeks; `get-balance` 2; `get-journal` after 9×10⁷ = 1 seek + limit. PASS.

## Findings and fixes applied
None. The plan already applies every prior fix (`ALL` pairs + client `:seq`, `Long/MAX_VALUE` guard, field-path tenant creation and `:next-seq` handling, ordered signed postings, charge `:seq` immutable).

## Test plan requirements (for phase 5)
- 2- and 4-task deployments; second `:wrap-client`; `update-module!` persistence (balances, charges, journal, outcomes; next seq continues); explicit waits.
- Independent expectations: amounts 7, 300, 10^12; refunds 1 + (A−1) then 1 more; two tenants with the same request-id.
- Reject-before-create scenario as traced; rejected outcomes preserved after `create-tenant!`.
- Invariant sweeps after every scenario: Σ balances = 0; non-clearing ≥ 0; each balance = Σ deltas over the full journal read by pages; refunded-total per charge = Σ refund rows naming it.
- Deep-page scaling at high `after-seq`; field-level no-blob review.
- Efficiency capture: account for the fixed ingress navigation + field write; keep scan detection sensitive to account/journal growth.
- No assertions on topology names, microbatch counts, or tight timing.
- Targeted mutants: refund postings reversed; refund overwriting charge `:seq`; `:insufficient-funds` before `:refund-exceeds-charge`; rejected command consuming a seq; replay re-posting; missing `:seq` in journal rows; `create-account!` accepting `"clearing"`; outcome lost after `create-tenant!`.
