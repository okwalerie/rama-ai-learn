# Plan — hld-payment-system (subsystem: tenant-ledger, whole spec)

Private reference-implementation plan (Phase 1). Inputs: README.md,
`src/hld_payment_system/protocol.clj`, `test-resources/IMPLICIT_SPEC.md`,
`test-resources/DECOMPOSITION.json`. Design only; no topology code.

## Reads

All reads are scoped by `tenant-id`, the depot partition key and the
top-level PState key: one task per read.

| Read | Access method | Path | RocksDB reads |
|---|---|---|---|
| `get-outcome t rid` | `foreign-select-one` | `[(keypath t :requests rid)]` on `$$tenants` → request record or nil; client renders the outcome map | 2 |
| `get-tenant t` | `foreign-select-one` | `[(keypath t :currency)]` → `String` or nil; client builds `{:tenant-id :currency}` | 1 |
| `get-account t a` | `foreign-select-one` | `[(keypath t :accounts a)]` → `{:kind :balance}` or nil; client `assoc`s `:account-id` | 2 |
| `get-balance t a` | `foreign-select-one` | `[(keypath t :accounts a :balance)]` → `Long` or nil | 2 |
| `get-charge t cid` | `foreign-select-one` | `[(keypath t :charges cid)]` → charge record or nil; client `assoc`s `:charge-id` | 2 |
| `get-journal t after limit` | `foreign-select` | client returns `[]` without a call when `after = Long/MAX_VALUE` (`(inc after)` would overflow); else `[(keypath t :journal) (sorted-map-range-from (inc after) {:max-amt limit}) ALL]` → `[seq row]` pairs ascending; client builds each transaction as `(assoc row :seq seq)` because the stored row does not carry `:seq` | 1 + 1 seek + `limit` iterations |

Every read is one path on one PState on one partition → no query
topology needed. Balances are stored, never recomputed from the journal.

Client validation: ids non-empty ≤ 128, currency `[A-Z]{3}`, `kind` ∈
`#{:customer :merchant}`, amount `1..10^12`, `"clearing"` not accepted by
`create-account!`/`fund!`/`charge!`, `limit` 1..500, `after-seq ≥ 0`;
violations throw `IllegalArgumentException` before any call.

## Writes

One depot `*commands`, `(hash-by :tenant-id)`, five `defrecord` types with
`tenant-id` and `request-id`:

| Command | Depot record |
|---|---|
| `create-tenant!` | `(->CreateTenant tenant-id request-id currency)` |
| `create-account!` | `(->CreateAccount tenant-id request-id account-id kind)` |
| `fund!` | `(->Fund tenant-id request-id account-id amount)` |
| `charge!` | `(->Charge tenant-id request-id customer-id merchant-id amount)` |
| `refund!` | `(->Refund tenant-id request-id charge-id amount)` |

Client appends with `:ack` after structural validation and returns `nil`.
Record equality is payload equality; distinct record types never compare
equal, so a refund reusing a charge's request-id is a conflicting attempt.

## PState Design

One PState `$$tenants`, owned by microbatch topology `core`:

```clojure
(declare-pstate mb $$tenants
  {String                                            ; tenant-id
   (fixed-keys-schema
     {:currency String                              ; non-nil ⇔ tenant exists
      :ingress-seq Long                              ; last ingress position assigned (ordering only; see ETL structure)
      :next-seq Long                                 ; last journal seq issued (0 initially)
      :accounts (map-schema String                   ; account-id ("clearing" included) ->
                  (fixed-keys-schema
                    {:kind    clojure.lang.Keyword   ; :customer | :merchant | :clearing
                     :balance Long})
                  {:subindex? true})
      :charges  (map-schema String                   ; charge-id (= request-id of the charge) ->
                  (fixed-keys-schema
                    {:customer-id    String
                     :merchant-id    String
                     :amount         Long
                     :refunded-total Long
                     :seq            Long})          ; seq of the charge transaction, immutable
                  {:subindex? true})
      :journal  (map-schema Long                     ; seq ->
                  (fixed-keys-schema
                    {:request-id String
                     :type       clojure.lang.Keyword ; :fund | :charge | :refund
                     :charge-id  String              ; nil for :fund
                     :postings   (vector-schema
                                   (fixed-keys-schema {:account-id String :delta Long}))}) ; exactly 2
                  {:subindex? true})
      :requests (map-schema String                   ; request-id ->
                  (fixed-keys-schema
                    {:command              clojure.lang.Keyword
                     :payload              ICommand
                     :outcome              IOutcome
                     :conflicting-attempts Long})
                  {:subindex? true})})})
```

```clojure
(definterface ICommand)   ; the five depot records
(definterface IOutcome)
(defrecord TenantAccepted [] IOutcome)
(defrecord AccountAccepted [] IOutcome)
(defrecord FundAccepted [seq balance] IOutcome)
(defrecord ChargeAccepted [charge-id seq customer-balance] IOutcome)
(defrecord RefundAccepted [refund-id charge-id seq refunded-total] IOutcome)
(defrecord Rejected [reason] IOutcome)
```

Why this design is the only reasonable one: every read is a point lookup
by `(tenant-id, id)` or a seq range; each collection (accounts, charges,
journal, requests) grows without bound per tenant and is subindexed; the
balance is a field of the account record so `charge!` reads exactly two
account records and `get-balance` reads one. All collections share the
tenant key and partitioner → one PState (skill rule). The top-level value
is a String, two Longs, and four subindex handles. It is **never written
whole**, not even at tenant creation: a tenant's key may already hold
`:requests` entries (commands rejected with `:no-such-tenant` before the
tenant existed) and an `:ingress-seq`, and a `termval` of the entry
would erase them and orphan the four subindexed children (`paths.md`,
`pstate-schema.md`). Tenant existence is `:currency` non-nil, which only
`CreateTenant` writes; the scalar fields are read and written by field
path only (`[(keypath *t :currency) (termval c)]`,
`[(keypath *t :next-seq) (termval seq)]`,
`[(keypath *t :ingress-seq) (termval pos)]`).

Considered and rejected: a global journal or a global seq generator
(`ModuleUniqueIdPState`) — seqs must be contiguous **per tenant** starting
at 1, which only a per-tenant counter colocated with the tenant provides.

Storage granularity: `postings` is a 2-element vector by construction; no
field holds a growing collection.

## Depots

- `*commands`: `(hash-by :tenant-id)`, five record types. One depot
  because all commands of a tenant are ordered relative to each other
  (fund, charge, refund back-to-back must yield seqs 1, 2, 3 and the
  charge must see the funding), and request-ids are shared across types.

## Topologies and PStates

- `core`: **microbatch** (default). No single-digit-millisecond
  visibility requirement (barrier-based), no ack-return (commands return
  `nil`), and all writes are non-idempotent (balances, seq, refunded
  total, `:conflicting-attempts`). Microbatch exactly-once guarantees
  that a retried command never posts twice or consumes two seqs.
  Concerns: replay/conflict, tenant/account creation, fund, charge,
  refund, journal, outcomes — all per-tenant, same task, one event each.
  - Owns `$$tenants`.
  - ETL structure: one `<<batch` block implementing **ordered per-tenant
    batching** (next subsection). A tenant's commands of one microbatch
    are applied by ONE loop event per tenant, in depot append order, each
    command completely (including its outcome) before the next; the loop
    yields cooperatively between commands.

### Ordered per-tenant batching (ETL structure)

The README applies a tenant's commands in issue order, one at a time,
and the skill's cooperative-multitasking rule forbids unbounded
synchronous work on a task: one microbatch can carry thousands of
commands for a hot tenant. A `yield-if-overtime` inside plain per-record
processing would let the next same-tenant record start before the
previous one committed its balances (`dataflow.md` "Yielding and
ordering"). Resolution: make a tenant's whole batch of commands one
event, ordered by a durable ingress position, and yield only inside it.

```clojure
(<<sources mb
  (source> *commands :> %mb)
  (<<batch
    ;; pre-agg: runs on the depot partition task (hash of tenant-id) in
    ;; depot append order, synchronously, before any partitioner or yield
    (%mb :> *cmd)
    (get *cmd :tenant-id :> *t)
    (local-select> [(keypath *t :ingress-seq) (nil->val 0)] $$tenants :> *prev)
    (inc *prev :> *pos)
    (local-transform> [(keypath *t :ingress-seq) (termval *pos)] $$tenants)
    (vector *pos *cmd :> *pair)
    ;; agg: one row per tenant; +group-by hash-partitions by *t, the same
    ;; routing the PState uses for its top-level key
    (+group-by *t
      (aggs/+vec-agg *pair :> *pairs))
    ;; post-agg: one event per tenant per microbatch
    (sort-pairs-by-position *pairs :> *ordered)   ; defn sorting by *pos; +vec-agg order is NOT assumed
    (loop<- [*remaining *ordered :> *done]
      (<<if (empty? *remaining)
        (:> true)
       (else>)
        (first *remaining :> [*pos *cmd])
        ;; apply-command: the complete per-command procedure below,
        ;; including the request-record write; replay, conflict,
        ;; rejection, and acceptance branches all unify here, so exactly
        ;; one continue> runs per command
        (yield-if-overtime)
        (continue> (rest *remaining))))))
```

Properties relied on:

- `%mb` emits per task in depot append order and the pre-agg segment up
  to the `+group-by` partitioner is synchronous, so ingress positions are
  assigned in append order and are unique per tenant; reads inside the
  owning topology see the attempt's own writes (`microbatch.md` "Read
  visibility").
- `+group-by` needs no explicit partitioner and emits one row per key;
  post-agg has only `*t` and `*pairs` in scope and allows no partitioner
  (`batch.md`, `aggregators.md`). `+vec-agg` element order is not
  documented, hence the explicit sort by position.
- `yield-if-overtime` between commands lets other events on the task run
  (other tenants' loops, query topologies, foreign reads). None touches
  this tenant's uncommitted writes: the only writer of a tenant within a
  microbatch is this loop, external readers see committed state only,
  and the next microbatch starts only after this one completes on all
  tasks (`microbatch.md`). A retry rolls back and reapplies the ingress
  positions and the business writes together (exactly-once). Each
  command's own work is a constant number of reads and writes, so no
  yield is needed inside a command.
- `:ingress-seq` is a durable per-tenant Long written by field path
  only; it counts every command (replays, conflicts, rejections
  included), is independent of the journal `:next-seq`, and is never
  exposed.
- Batch bound: `(set-launch-depot-dynamic-option! setup "*commands"
  "depot.microbatch.max.records" 1000)` caps records per depot partition
  per microbatch, so a grouped vector holds at most one batch (≤ 1000
  records ≈ 450 KB per task), never lifetime history. Group memory is
  transient batch state, not a TaskGlobal and not a durable inbox.
- Cost: one `+group-by` hop per command (a network partitioner landing on
  the same task as the depot partition), plus one extra navigation of
  the tenant's top-level entry and one field write in pre-agg. The
  earlier "zero partitioners" claim is withdrawn.
- Limitation, stated plainly: the per-tenant loop is bounded by the
  batch size (≤ 1000 commands × constant work ≈ ≤ 2.5 s warm) and yields
  every ~5 ms, so no single microbatch attempt approaches
  `topology.microbatch.phase.timeout.seconds` under the stated bounds; no
  input cap beyond the README's is imposed.
- Build-time verifications: (a) `local-transform>` and `loop<-` are
  accepted in the post-agg of a microbatch `<<batch`; fallback is
  `(materialize> *t *ordered :> $$grouped)` and running the loop in the
  pre-agg of a second `<<batch` that reads `($$grouped :> *t *ordered)`.
  (b) `+group-by` routing lands on the PState partition of `*t` at 2 and
  4 tasks. (c) `sort-pairs-by-position` is a plain `defn`.

Per-command processing (`apply-command`, on the tenant's task, inside
the loop above):

1. `[(keypath *t :requests *rid)]` → replay (no-op) or conflict
   (`:conflicting-attempts` +1 via `termval` of the read record); stop
   (unify to the loop's `continue>`).
2. `[(keypath *t :currency)]` → nil ⇒ tenant absent. For fund/charge/
   refund also read `[(keypath *t :next-seq)]` (same top-level entry,
   no extra seek); every "`:next-seq`" write below is the field-path
   write `[(keypath *t :next-seq) (termval seq)]`.
   - `CreateTenant`: present → `:tenant-exists` (currency unchanged);
     else write the scalar fields only —
     `[(keypath *t :currency) (termval c)]`,
     `[(keypath *t :next-seq) (termval 0)]` — and
     `[(keypath *t :accounts "clearing") (termval {:kind :clearing :balance 0})]`.
     Never `termval` the tenant entry: `:requests` entries from
     pre-creation rejections and the `:ingress-seq` already live under
     this key and must survive.
   - `CreateAccount`: absent → `:no-such-tenant`; read
     `[(keypath *t :accounts a)]`; present → `:account-exists`; else
     write `{:kind k :balance 0}`.
   - `Fund`: absent tenant → `:no-such-tenant`; read account `a` → nil
     ⇒ `:no-such-account`; read `"clearing"`. Write `a.balance + amount`
     and `clearing.balance − amount` (`termval` of computed records),
     journal entry `seq = (inc next-seq)` with postings
     `[{:account-id "clearing" :delta −amount} {:account-id a :delta +amount}]`,
     `:next-seq`. `FundAccepted seq new-balance`.
   - `Charge`: tenant check; read customer and merchant records (2
     seeks); either nil → `:no-such-account`; kinds not
     `:customer`/`:merchant` respectively → `:wrong-account-kind`;
     `customer.balance < amount` → `:insufficient-funds`. Else write both
     balances, charge record `{... :refunded-total 0 :seq seq}`, journal
     entry with postings `[{customer −amount} {merchant +amount}]` and
     `:charge-id rid`, `:next-seq`. `ChargeAccepted rid seq customer-balance`.
   - `Refund`: tenant check; read `[(keypath *t :charges cid)]` → nil ⇒
     `:no-such-charge`; `refunded-total + amount > charge.amount` →
     `:refund-exceeds-charge`; read merchant record;
     `merchant.balance < amount` → `:insufficient-funds`. Else read
     customer record, write both balances, charge record with
     `refunded-total + amount` (`:seq` untouched), journal entry with
     postings `[{merchant −amount} {customer +amount}]` and
     `:charge-id cid`, `:next-seq`. `RefundAccepted rid cid seq total`.
3. Write the request record with `:conflicting-attempts 0`. Every branch
   of steps 1–3 ends here and unifies into the outer loop's single
   `continue>`.

I/O per command: fund = 4 seeks / 5 writes; charge = 4 seeks / 6 writes;
refund = 5 seeks / 6 writes; creates = 2–3 seeks / 2–4 writes (the
ingress position adds one navigation of the top-level entry, which every
command reads anyway, and one field write). Constant, independent of
tenant history.

Conservation: each accepted transaction applies exactly `−amount` and
`+amount` to two accounts in one event, so Σ balances stays 0 and every
balance equals the sum of its postings. Non-clearing balances never go
negative because the source check precedes the write.

## Query Topologies

None. Every read is a single path on one partition.

## Partitioning efficiency

Optimal placement: all reads and commands are scoped to one tenant and the
README requires per-tenant ordering and contiguous per-tenant seqs, so
`f(tenant-id) → one task`: `(hash-by :tenant-id)` on the depot, default
hash routing on `$$tenants`. Tenants are many (each merchant platform),
and a hot tenant is a serial stream by spec. Seeks/op = tasks touched = 1.

Dominant read: `get-balance` / `get-account`.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| existing account, small tenant | 0.50 | 1 | 0 |
| existing account, tenant with 10⁶ accounts and 10⁸ journal rows | 0.35 | 1 | 0 |
| `"clearing"` account | 0.05 | 1 | 0 |
| unknown account or tenant | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| existing account, small tenant | 0.50 | 1 | 0 |
| existing account, tenant with 10⁶ accounts and 10⁸ journal rows | 0.35 | 1 | 0 |
| `"clearing"` account | 0.05 | 1 | 0 |
| unknown account or tenant | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| existing account, small tenant | 0.50 | 1 | 0 |
| existing account, tenant with 10⁶ accounts and 10⁸ journal rows | 0.35 | 1 | 0 |
| `"clearing"` account | 0.05 | 1 | 0 |
| unknown account or tenant | 0.10 | 1 | 0 |
Weighted seeks = 1.0   |   Weighted iterator reads = 0

Flat across N. `get-journal` is 1 task, 1 seek, `limit` iterator reads at
every N.

## Design Decisions

- Subindexing: `:accounts`, `:charges`, `:journal`, `:requests` grow
  without bound per tenant → subindexed. `postings` (exactly 2) and the
  top-level record are not.
- Colocation: depot `(hash-by :tenant-id)` = PState key = `+group-by *t`
  routing. The only partitioner is the `+group-by` hop, which lands on
  the task that already holds the tenant.
- Ordering and yielding: ordered per-tenant batching (one loop event per
  tenant per microbatch, sorted by durable `:ingress-seq`), with
  `yield-if-overtime` between commands. `depot.microbatch.max.records`
  = 1000 bounds the per-microbatch group memory.
- Tenant entry creation: only scalar fields are written; a tenant's
  entry is never written whole, so outcomes of commands rejected before
  `create-tenant!` survive creation.
- Seq assignment: only accepted fund/charge/refund advance `:next-seq`,
  in processing order, so seqs are contiguous from 1; rejected commands
  never consume a seq and never write a journal entry.
- The charge's `:seq` is written once at acceptance and never touched by
  refunds; refunds write their own journal entry and `refunded-total`.
- Nothing is deleted; journal entries are never modified.

### Synchronization and client contract

`create-module` creates one `(atom 0)` in its closure, shared by all
`:wrap-client` instances; each command increments it before appending;
`wait-for-processing!` calls
`(rtest/wait-for-microbatch-processed-count ipc module-name "core" @cnt)`.
Module-level cumulative counting keeps this valid across a second client
and `update-module!`. The client holds no business state.

## State primitive selection

- `$$tenants` (PState): durable; ≤ 6 writes per command. `:ingress-seq`
  lives here because it must roll back with the attempt on retry; a
  TaskGlobal counter would not.
- Per-microbatch grouped vectors (`+vec-agg` output): transient batch
  state inside one microbatch attempt, bounded by
  `depot.microbatch.max.records`; not a TaskGlobal, not durable.
- No TaskGlobal.
- Client atom: sync bookkeeping only.

## Resource usage analysis

### Disk usage (PStates)
- Top-level entry: ≤ 128 B key + ~150 B.
- Account record: id ≤ 128 B + keyword + Long → ~180 B; 10⁶ accounts ≈
  180 MB per tenant, spread by tenant hash.
- Charge record: ~450 B. Journal entry: ~500 B. Request record: payload
  ≤ 450 B + outcome ≤ 200 B.
- Growth: per accepted transaction ≈ 1.2 KB (journal + request + charge
  when applicable); per rejected command ≈ 650 B.

### Memory usage (TaskGlobals)
None. Transient per-microbatch memory: the grouped `[pos cmd]` vectors,
≤ `depot.microbatch.max.records` (1000) records per task per microbatch
at ≤ ~450 B each ≈ 450 KB, released when the attempt commits.

### Minimization
- No duplicated data except the request payload, which the `=` replay
  contract requires verbatim.
- `:ingress-seq` is one Long per tenant; it cannot be folded into
  `:next-seq`, which must stay contiguous over accepted transactions
  only.

## Design difficulty log

- **Decision:** ordered per-tenant batching — pre-agg assigns a durable
  `:ingress-seq` position per command, `+group-by` tenant with
  `+vec-agg`, explicit sort by position, one `loop<-` per tenant per
  microbatch applying each command completely, `yield-if-overtime`
  between commands. **Basis:** README per-tenant issue ordering; skill
  cooperative-multitasking rule (a hot tenant can queue thousands of
  commands in one microbatch); `dataflow.md` states yielding gives up
  arrival ordering across events; `+vec-agg` ordering is undocumented.
  Alternatives: per-record processing without yield (unbounded
  synchronous stretch per task), per-record processing with yield
  (same-tenant commands interleave between balance reads and writes),
  grouping without a durable position (order unverifiable), a durable
  inbox or TaskGlobal queue (extra state; TaskGlobal does not roll back
  on retry). **Outcome:** ordering and cooperative yielding both hold at
  the cost of one `+group-by` hop and one top-level read and field write
  per command. Needs build-time verification of post-agg
  `local-transform>`/`loop<-` acceptance and `+group-by`-to-PState
  routing agreement at 2 and 4 tasks, plus a test that issues fund,
  charge, refund for one tenant in one microbatch and checks seqs 1, 2, 3
  and balances against a sequential oracle.
- **Decision:** `create-tenant!` writes only `:currency`, `:next-seq`,
  and the clearing account; the tenant entry is never written whole.
  **Basis:** `paths.md` (`termval` replaces the map), `pstate-schema.md`
  (orphaned subindexed children), and the README's requirement that a
  `:no-such-tenant` outcome stored before creation remains readable and
  replay-stable. **Outcome:** pre-creation outcomes and `:ingress-seq`
  survive creation. Verify with: `fund!` on an unknown tenant (rejected)
  → `create-tenant!` → original outcome still `:no-such-tenant`, replay
  still rejected, conflicting payload increments the counter, both when
  the commands share a microbatch and when a barrier separates them.
- **Decision:** per-tenant `:next-seq` over a global id generator.
  **Basis:** README requires contiguous per-tenant seqs from 1.
  **Outcome:** determined directly; no viable alternative.
- Point reads by `(tenant, id)`, one ordered command depot, and a
  microbatch topology are determined directly by the ordering,
  contiguity, conservation, and constant-read requirements.
