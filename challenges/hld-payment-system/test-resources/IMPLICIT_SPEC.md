# Implicit Spec — hld-payment-system (private, phase 0)

Private challenge-author artifact. Encrypted during challenge runs. It
records requirements, adversarial cases, and review concerns derived from
README.md; it does not prescribe storage, partitioning, or topology
choices. Entries under "Contract decisions" give decision, basis, and
outcome only.

## Contract decisions

| Decision | Basis | Outcome |
|---|---|---|
| Closed ledger with an explicit `"clearing"` account per tenant | Every transaction must sum to zero, including funding; a closed system makes the conservation check total | Sum of all balances is always 0; only clearing may be negative |
| Single-step charge (no authorize/capture) | Bounded scope; the ledger semantics of a settled charge are what is tested | One transaction per charge |
| `charge-id` = `request-id`; refunds reference the charge by that id | One id space per tenant; the cached outcome and the charge share an identity | Refund of a rejected charge is `:no-such-charge` |
| Partial refunds capped by remaining refundable amount, then by merchant funds | Source: refunds are new balanced entries, never edits; merchant cannot go negative | Fixed check order `:refund-exceeds-charge` before `:insufficient-funds` |
| Structural errors throw; business rejections durable and journal-free | Rejections must be replayable but must not appear as money movement | Rejected commands never consume a seq |
| Whole-journal pagination only (no per-account entries) | Requested bounded scope | `get-journal` per tenant with contiguous seqs |
| Request-ids scoped per tenant; conflicts counted | Shared conventions | See README |
| Structural validation precedes request-id handling; replay/conflict precedes business validation | A malformed retry must not alter an existing outcome; a replay must not be re-evaluated against current state | Unused id stays unused; `:conflicting-attempts` unchanged by malformed input; queries throw on bad bounds |
| Payload = command type + all args except `this`/`request-id`, compared with `=` | Cross-command-type reuse of an id must count as a conflict | Same args under a different command are a conflicting attempt |
| Postings are an ordered pair: source (negative delta) then destination (positive delta) | Tests compare journal entries with `=`; signed integer deltas avoid debit/credit ambiguity | `:fund` clearing→account, `:charge` customer→merchant, `:refund` merchant→customer |
| A charge's `:seq` is its own transaction seq forever | Refunds are separate transactions with their own seqs; the charge record only accumulates `:refunded-total` | `get-charge` `:seq` and the charge outcome's `:seq` unchanged by refunds |

## Operations

### `create-tenant!` / `create-account!`

- Latency: hundreds of milliseconds acceptable.
- Invariants: currency immutable; kind immutable; clearing exists from
  tenant creation with kind `:clearing` and balance 0.
- Edge cases: re-create tenant with a different currency → rejected,
  currency unchanged; create account before tenant → `:no-such-tenant`;
  create `"clearing"` → structural throw.

### `fund!`

- Throughput: proportional to top-ups; may target the same account many
  times.
- Invariants: clearing decreases by exactly the amount; account
  increases; one seq consumed.
- Edge cases: fund a merchant (allowed); fund amount at the upper bound
  repeatedly (balances stay within Long by the stated assumption);
  fund unknown account → rejected, clearing unchanged.

### `charge!`

- Latency: low hundreds of milliseconds acceptable; visibility after the
  barrier.
- Throughput: the dominant write; hot merchants receive many charges.
- Invariants: customer never below 0; exactly two postings; charge record
  created only on acceptance.
- Concurrency: two charges against one customer with combined amount
  above the balance, issued back-to-back: the first processed succeeds,
  the second is `:insufficient-funds` (issue order is processing order).
- Edge cases: amount equal to balance (accepted, balance 0); customer and
  merchant swapped (`:wrong-account-kind`); customer charging themself
  (`:wrong-account-kind` since one id cannot be both kinds); charge from
  merchant to merchant (`:wrong-account-kind`).

### `refund!`

- Invariants: cumulative refunds ≤ charge amount; merchant never below 0;
  refund credits the original customer even if it has been charged
  since.
- Edge cases: full refund in one step; two partial refunds summing to
  exactly the amount; a third refund of 1 → `:refund-exceeds-charge`;
  refund when merchant has already spent (been refunded) most funds →
  `:insufficient-funds`; refund referencing a refund-id or fund
  request-id → `:no-such-charge`; refund referencing a rejected charge →
  `:no-such-charge`; refund of a charge from another tenant →
  `:no-such-charge`.

### Queries

- `get-balance` for clearing equals minus the sum of all funding.
- `get-charge` `:refunded-total` tracks every accepted refund; its
  `:seq` stays the charge's original seq after any number of refunds.
- Every journal `:postings` vector is exactly `[source destination]`
  with `:delta` `−amount` then `+amount`.
- `get-journal` pages: `after-seq 0` returns from 1; deltas sum to 0 per
  transaction; `:charge-id` nil for `:fund`, set for `:charge`
  (its own id) and `:refund`.

## Entity state × write matrix

Entities: **tenant** (absent / exists), **account** (absent / exists
kind k balance b), **charge** (absent / rejected request / accepted
with refunded r), **request-id** (unused / original).

Account × `fund!`(a)
- absent: `:no-such-account`. get-account → nil; clearing unchanged;
  journal unchanged.
- exists(b): balance b+a; clearing −a. get-balance → b+a; get-journal
  +1 `:fund`.

Customer × `charge!`(a)
- absent: `:no-such-account`.
- exists kind :merchant: `:wrong-account-kind`.
- exists :customer b < a: `:insufficient-funds`; get-charge → nil;
  journal unchanged.
- exists :customer b ≥ a: balance b−a; merchant +a; get-charge →
  record with `:refunded-total 0`; journal +1 `:charge`.

Charge × `refund!`(a)
- absent / rejected request: `:no-such-charge`; balances unchanged.
- accepted amount A, refunded r, r+a > A: `:refund-exceeds-charge`.
- accepted, r+a ≤ A, merchant balance < a: `:insufficient-funds`.
- accepted, r+a ≤ A, merchant balance ≥ a: refunded r+a; merchant −a;
  customer +a; journal +1 `:refund` with `:charge-id`.

Request-id × any command
- original accepted × replay: no second transaction, balances
  unchanged, `:seq` in outcome unchanged.
- original rejected × replay: still rejected even if the condition no
  longer holds (funds arrived, account created).
- original × different payload: `:conflicting-attempts` +1 only; no
  transaction.

## Adversarial cases for private tests

Boundary
- Amount 1 and amount 10^12; balance exactly equal to charge amount.
- Refund sequence 1 + (A−1), then 1 more → rejected.
- Journal of 1200 transactions paged at 500.
- 128-character ids; currency `"usd"` (structural throw), `"USD"` ok.

Retry / idempotency
- Replay an accepted charge: balances unchanged, journal length unchanged.
- Replay a rejected `:insufficient-funds` charge after funding: still
  rejected; new request-id succeeds.
- Same request-id, different amount: original outcome and balances
  intact, `:conflicting-attempts 1`.
- Same request-id reused for a refund after a charge used it: the refund
  is a conflicting attempt of the charge; no refund posted.
- Same request-id in two tenants: independent.
- Malformed command reusing an existing request-id (amount 0): throws;
  the original outcome and its `:conflicting-attempts` are unchanged.
- Malformed command with a fresh request-id: throws; `get-outcome` for
  that id stays nil; the same id then succeeds as an original.
- Out-of-bounds query arguments (`limit 0`, `limit 501`, `after-seq -1`,
  empty ids): `IllegalArgumentException`, no state change.
- Two clients from `:wrap-client` on the same cluster: a command through
  one is visible through the other after `wait-for-processing!`.

Ordering
- fund, charge, refund issued back-to-back in one tenant with one
  barrier: seqs 1, 2, 3 in that order; all accepted.
- Two charges racing for the last funds: exactly one accepted.
- Interleaved commands across two tenants: each tenant's seqs are
  contiguous from 1.

Conservation / consistency
- After any sequence of commands: sum over all accounts including
  clearing = 0; all non-clearing balances ≥ 0; each balance equals the
  sum of that account's deltas over the whole journal.
- For each accepted charge, refunded-total = sum of amounts of `:refund`
  transactions with that `:charge-id`, and ≤ amount.
- Number of journal transactions = number of accepted fund/charge/refund
  outcomes; seqs contiguous.

## Efficiency review concerns

- Balances must be maintained incrementally; `get-balance` must not sum
  the journal.
- All bounds must hold as one tenant's own history grows (accounts, charges, journal),
  not only as other tenants grow.
- The client wrapper must hold no process-local business state.
- `charge!` must read only the two accounts involved, not the tenant's
  account map.
- `refund!` must locate the charge by direct lookup and update its
  refunded total without scanning refunds.
- `get-journal` must start at `after-seq` directly; cost independent of
  journal length and of `after-seq`.
- Charges and journal transactions must not be stored in a single
  per-tenant blob that is rewritten on every transaction.
- Retry safety: reprocessing a command after a failure must not post a
  transaction twice or consume two seqs.
- Outcome storage must not grow the per-request cost with prior request
  count in the tenant.
