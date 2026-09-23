# HLD Payment System Challenge

Build the ledger core of a Stripe-style payment platform: per-tenant
closed double-entry ledgers in a single currency; customer and merchant
accounts plus one explicit funding clearing account; funding, charges,
and partial refunds recorded as balanced, append-only journal
transactions; idempotent commands whose success or failure outcome is
cached durably; and a paginated whole-journal read.

This challenge covers **ledger state only**. Card networks,
authorization/capture phases, payment-service-provider calls, webhooks,
tokenization, fraud scoring, foreign exchange, disputes, fees, payouts,
and reconciliation files are out of scope. Money enters a tenant only
through `fund!`, which moves value from the tenant's clearing account.

## Protocol

Your implementation must satisfy the `PaymentSystemModule` protocol
defined in `src/hld_payment_system/protocol.clj`. The README is the
authoritative contract; the protocol docstrings summarize it.

## Types and bounds

| Term | Type | Bounds |
|---|---|---|
| `tenant-id`, `account-id`, `charge-id`, `request-id` | non-empty `String` | ≤ 128 characters |
| `currency` | `String` | exactly 3 uppercase ASCII letters (`"USD"`) |
| `kind` | keyword | `:customer` or `:merchant` |
| `amount` (minor units) | `Long` | `1 ≤ amount ≤ 1,000,000,000,000` |
| `seq` | `Long` | positive |
| `after-seq` | `Long` | `≥ 0` |
| `limit` | `Long` | `1 ≤ limit ≤ 500` |

Amounts are integer minor units (cents). Implementations may assume that
the sum of all amounts ever submitted to one tenant fits in a signed
64-bit `Long`.

Every tenant has exactly one **clearing account** with `account-id`
`"clearing"` and `:kind :clearing`, created with the tenant. It is the
only account whose balance may be negative. Clients cannot create,
fund, charge, or refund the clearing account directly.

## Command conventions

Every mutating operation is a **command**. A command:

- takes a client-chosen `request-id` as its first argument after `this`;
- returns `nil` and never returns a business result;
- returns only after the command has been durably accepted for
  processing;
- produces exactly one durable **outcome**, readable with
  `get-outcome` after `wait-for-processing!`.

Request-ids are scoped to a tenant: the same `request-id` in two tenants
names two unrelated requests. Within one tenant a `request-id` is
shared across all command types. The `request-id` of an accepted
`charge!` **is** the `charge-id`; the `request-id` of an accepted
`refund!` is the refund's id.

**Outcome shape.** `get-outcome` returns `nil` if the tenant has never
processed that `request-id`, otherwise a map:

```clojure
{:status               :accepted | :rejected
 :command              :create-tenant | :create-account | :fund | :charge | :refund
 :reason               <keyword>        ; present only when :rejected
 :conflicting-attempts <Long>           ; 0 initially, see below
 ...}                                   ; command-specific keys listed per command
```

**Validation classes.**

1. *Structural* violations (wrong type, out of the bounds above, a
   `kind` other than `:customer`/`:merchant`, an `account-id` of
   `"clearing"` passed to `create-account!`, `fund!`, or `charge!`)
   make the client method throw `IllegalArgumentException`
   synchronously. Nothing is appended and no outcome is created.
2. *Business rejections* produce a durable `:rejected` outcome with a
   `:reason` and change no ledger state. A rejected command never
   appends a journal transaction.
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
re-evaluated against current ledger state.

**Idempotency and conflicts.** The first processed command carrying a
given `request-id` in a tenant is the *original*. The *payload* of a
command is its command type together with every argument other than
`this` and `request-id`; two payloads are identical when they are equal
under Clojure `=`. A later command with the same `request-id` and an
identical payload is a *replay*: no effect, outcome unchanged. A
replayed rejected charge stays rejected even if funds have since
arrived; a replayed accepted charge never charges twice. A later command
with the same `request-id` but a different payload is a *conflicting
attempt*: no effect on ledger state, no change to any original outcome
field except `:conflicting-attempts`, which increases by one.

**Ordering.** Commands issued by one client against the same tenant are
processed in the order the client issued them. Commands against
different tenants have no relative ordering guarantee.

**Durability.** Outcomes, accounts, charges, and journal transactions are
retained for the lifetime of the module. Journal transactions are never
modified; corrections are new transactions.

## Commands

### `create-tenant! [this request-id tenant-id currency]`

Creates the tenant with its clearing account at balance `0`.

| `:reason` | Condition |
|---|---|
| `:tenant-exists` | `tenant-id` already exists (regardless of currency) |

### `create-account! [this request-id tenant-id account-id kind]`

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-tenant` | `tenant-id` does not exist |
| `:account-exists` | `account-id` already exists in the tenant |

Accounts start at balance `0`. An account's `kind` never changes.

### `fund! [this request-id tenant-id account-id amount]`

Brings external money into a customer or merchant account. Appends one
journal transaction with postings, in this order:
`[{:account-id "clearing" :delta −amount} {:account-id account-id :delta +amount}]`.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-tenant` | `tenant-id` does not exist |
| `:no-such-account` | `account-id` does not exist in the tenant |

Accepted outcome adds `:seq` (the journal seq) and `:balance` (the
account's balance after funding).

### `charge! [this request-id tenant-id customer-id merchant-id amount]`

Moves `amount` from a customer to a merchant. Appends one journal
transaction with postings, in this order:
`[{:account-id customer-id :delta −amount} {:account-id merchant-id :delta +amount}]`.
The charge is identified by `request-id`.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-tenant` | `tenant-id` does not exist |
| `:no-such-account` | `customer-id` or `merchant-id` does not exist in the tenant |
| `:wrong-account-kind` | `customer-id` is not `:customer` or `merchant-id` is not `:merchant` |
| `:insufficient-funds` | the customer's balance is less than `amount` |

Accepted outcome adds `:charge-id`, `:seq`, and `:customer-balance`
(after the charge). The charge's `:seq` is the seq of its own journal
transaction and never changes; later refunds receive their own seqs and
do not alter it.

### `refund! [this request-id tenant-id charge-id amount]`

Returns part or all of an accepted charge to its customer. Appends one
journal transaction with postings, in this order:
`[{:account-id merchant-id :delta −amount} {:account-id customer-id :delta +amount}]`,
using the accounts of the original charge. Several partial refunds of
one charge are allowed.

| `:reason` | Condition (checked in order) |
|---|---|
| `:no-such-tenant` | `tenant-id` does not exist |
| `:no-such-charge` | no **accepted** charge `charge-id` exists in the tenant |
| `:refund-exceeds-charge` | `amount` plus the charge's already refunded total exceeds the charge amount |
| `:insufficient-funds` | the merchant's balance is less than `amount` |

Accepted outcome adds `:refund-id` (equal to `request-id`),
`:charge-id`, `:seq` (the refund transaction's own journal seq), and
`:refunded-total` (for the charge, after this refund). The charge's
`:seq` as returned by `get-charge` and by the charge's outcome is
unchanged.

## Queries

### `get-outcome [this tenant-id request-id]`

Outcome map as defined above, or `nil`.

### `get-tenant [this tenant-id]`

`{:tenant-id String :currency String}`, or `nil`.

### `get-account [this tenant-id account-id]`

`{:account-id String :kind :customer | :merchant | :clearing :balance Long}`,
or `nil` if the tenant or account does not exist. `"clearing"` is a
valid `account-id` here.

### `get-balance [this tenant-id account-id]`

The account's balance (`Long`), or `nil` if the tenant or account does
not exist. Equivalent to `(:balance (get-account ...))`.

### `get-charge [this tenant-id charge-id]`

The accepted charge, or `nil`:

```clojure
{:charge-id String :customer-id String :merchant-id String
 :amount Long :refunded-total Long :seq Long}
```

`:seq` is the journal seq of the original charge transaction, forever;
refunds update `:refunded-total` only.

### `get-journal [this tenant-id after-seq limit]`

Journal transactions of the tenant with `:seq` strictly greater than
`after-seq`, ascending, at most `limit`:

```clojure
{:seq        Long
 :request-id String
 :type       :fund | :charge | :refund
 :charge-id  String | nil                    ; the charge for :charge and :refund
 :postings   [{:account-id String :delta Long} ...]}   ; exactly two, deltas sum to 0
```

`:postings` is an ordered vector of exactly two entries: the **source**
account with a negative `:delta` first, then the **destination** account
with the equal positive `:delta`. A `:delta` is a signed integer change
to that account's balance; no accounting debit/credit convention is
used. Source and destination per type: `:fund` is `"clearing"` →
`account-id`; `:charge` is `customer-id` → `merchant-id`; `:refund` is
`merchant-id` → `customer-id`.

Returns an empty vector when nothing follows `after-seq` or the tenant
is unknown. Seqs start at `1` and each accepted `fund!`, `charge!`, or
`refund!` receives the previous seq plus one, in processing order.
`create-tenant!` and `create-account!` do not append transactions.

## Invariants

- Every journal transaction's postings sum to zero; the tenant's
  currency is the currency of every posting.
- For every tenant, at all times, the sum of balances over all accounts
  including `"clearing"` is `0`.
- Every account other than `"clearing"` has balance `≥ 0` at all times.
- Every account's balance equals the sum of its posting deltas over the
  journal.
- For every accepted charge, `0 ≤ refunded-total ≤ amount`, and
  `refunded-total` equals the sum of its accepted refunds.
- A journal transaction, once appended, never changes; seqs are
  contiguous.

## Resource guarantees

The work done by any operation must be bounded by its own inputs and
outputs and must not grow with unrelated history. These bounds also
hold as the tenant's **own** history grows, not only as other
tenants grow: no operation may read, deserialize, or rewrite an
unbounded per-tenant collection (all accounts, all charges, or the journal) as one value.

- `fund!`, `charge!`, `refund!`: a constant number of storage reads and
  writes, independent of the number of accounts, charges, or
  transactions in the tenant.
- `get-account`, `get-balance`, `get-charge`, `get-tenant`,
  `get-outcome`: a constant number of storage reads. Balances must not
  be recomputed by scanning the journal.
- `get-journal`: proportional to `limit`, not to `after-seq` or to the
  journal length.

Tests exercise both 2 and 4 tasks; all guarantees must hold for both.

**Shared state.** All business state lives in the deployed module.
Several clients produced by `:wrap-client` against the same cluster
observe the same state: a command issued through one client is visible,
after `wait-for-processing!`, through every other. The client wrapper
keeps no process-local business state (no in-memory copies of accounts, balances, charges, journal entries,
or outcomes); it holds only handles to the cluster.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <PaymentSystemModule implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified
  `PaymentSystemModule` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `harness/Synchronizable`. See the docstring
on that protocol for implementation requirements. Tests call
`(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `hld-payment-system.module`.

## File Location

Write your solution to:
```
implementations/hld-payment-system/src/hld_payment_system/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```

## Attribution and license

This challenge is adapted from "Design a Payment System (Stripe /
PayPal)", module 8.19 of *The HLD Handbook* by handbook-academy
(<https://hld.handbook.academy/curriculum/case-studies/payment-system/>,
source repository <https://github.com/handbook-academy/engineering-handbook>),
licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

Changes: the prose above is a rewritten, bounded specification. It keeps
the balanced double-entry journal, immutable entries with refunds as new
transactions, idempotency keys with cached responses and
different-payload conflicts, and per-currency ledgers; it collapses
authorization/capture/settlement into a single charge, adds an explicit
clearing account so the ledger is closed, and removes card networks,
sagas, tokenization, fraud, webhooks, disputes, fees, and all
infrastructure discussion. The adapted prose in this README remains
licensed under CC BY-SA 4.0.
