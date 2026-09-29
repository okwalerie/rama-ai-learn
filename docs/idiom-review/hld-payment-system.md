# Idiom review: HLD payment system

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a` (`orb/idiom-hld-payment-system`).

Paths without a challenge prefix are relative to `challenges/hld-payment-system/`;
`module.clj` alone means `test-resources/hld_payment_system/module.clj`.

## Scope and decision

Reviewed the challenge contract and protocol, every private test and reference
module, the auction and chat reference implementations, and
`plugins/rama-skill/skills/rama/SKILL.md`. This is a review of the reference
implementation, not a claim that every listed design choice is defective.

**Decision: doc-only. Do not rewrite the reference in this change.** The
reference is already built around per-tenant ordering and atomic state changes;
changing that layout or the microbatch processing path safely requires a
focused redesign and validating both functional and storage-growth behavior
with the full private suite. The existing tests are substantial, and the
growth test explicitly cannot observe bytes deserialized or rewritten inside
opaque values (`test-private/hld_payment_system/private_challenge_test.clj:220-221`).
The challenge's private review also reports no concrete reference defect and
documents prior mutation checks against the functional and growth tests
(`test-private/REVIEW.md:7-8, 36-40`). The one localized improvement found
(finding 3) is a constant-factor I/O reduction inside the ledger's core loop,
so it is recorded here rather than applied without the full validation that
loop requires. No challenge contract, private test, reference implementation,
or Rama skill file was changed.

## Findings

### 1. Single tenant-keyed PState: deliberate colocation, with hot-tenant limits

`module.clj:110-116` stores each tenant's currency, ingress and journal
sequence counters, accounts, charges, journal, and idempotency requests under
one `$$tenants` top-level key. Depot `*commands` is partitioned by tenant
(`:107`), so a tenant's command stream and all state needed for funding,
charging, refunds, replay detection, sequence allocation, and journal append
are colocated on one task. This is a strong fit for the README's per-tenant
ordering, idempotency, and balanced-ledger invariants (`README.md:94-108,
246-258`): every accepted command's reads and writes happen in one event on one
task and are therefore atomic (`SKILL.md:24`). The nested maps are subindexed
(`:113-116`), so a point access does not deserialize the tenant's whole
history.

The tradeoff is that one very busy tenant remains a single partition's
serialization and storage hotspot; adding more tasks spreads different
tenants but cannot parallelize one tenant's ordered ledger writes. The current
tests cover two and four tasks (`private_challenge_test.clj:145-149,
232-236`) but do not establish behavior under a single extremely hot tenant.

**Idiomatic alternatives:** Keep the current partition key if atomic
same-tenant command handling is the priority. A more decomposed shape could
use separate per-record PStates — tenant metadata, accounts, charges,
requests, and journal rows — each keyed by tenant with a subindexed inner map
(or keyed by a composite such as `[tenant-id account-id]` and routed by
tenant). As long as every one is partitioned by tenant, the accepted-command
writes stay colocated and atomic. This may clarify schemas, but it does not
remove the hot-tenant limit and does not by itself reduce I/O. Do not
repartition ledger records by account or charge without solving how a single
accepted command atomically updates both accounts, charge/refund state,
request outcome, sequence, and journal.

### 2. Ingress sequence, group aggregation, sort, and loop: ordering depends on emission order and task alignment

The microbatch source assigns a per-tenant `:ingress-seq` (`:120-125`), groups
records by tenant and collects each group with `+vec-agg` (`:126-127`), sorts
the collected pairs (`ordered`, defined at `:103-104`, applied at `:128`), then
processes commands serially with `loop<-` (`:129-181`). `yield-if-overtime`
at `:180` is appropriate for a potentially long tenant batch (`SKILL.md:26`).

What this mechanism does and does not guarantee:

- **It preserves emission order; it does not create order.** `:ingress-seq`
  is assigned in the order `%mb` emits records on the task (`:120-124`). The
  sort therefore only undoes any reordering introduced by `+group-by` /
  `+vec-agg`; it cannot repair records that `%mb` already emitted out of depot
  order. The README's per-client ordering (`README.md:106-108`) is satisfied
  because the client appends with `:append-ack` synchronously (`:183-186`), so
  one client's commands land in the tenant's depot partition in issue order,
  and the design then relies on the microbatch emitting a partition's records
  in offset order. Any claim about this reference's ordering correctness rests
  on that emission-order property, not on the counter.
- **Its locality depends on two partitioners agreeing.** The `:ingress-seq`
  read and write (`:122-124`) run before any partitioner, on the task where
  `%mb` emitted the record. They are aligned only because `hash-by :tenant-id`
  on the depot and `$$tenants`' top-level key `*t` hash to the same task. The
  later `+group-by *t` (`:126`) routes each group to the task for `*t` as
  well, so the loop's `$$tenants` accesses are aligned by the group-by
  regardless of the depot partitioner. If the depot partitioner were ever
  changed (for example to route by request ID), the pre-group-by
  `:ingress-seq` accesses would silently touch the wrong partition
  (`SKILL.md:61`) while the loop would still appear correct.

Costs: one `:ingress-seq` read and one write per command, a vector of all of a
tenant's commands in the batch, and an O(n log n) sort before the loop. The
`depot.microbatch.max.records` option of 1000 (`:108`) bounds a microbatch
but is not by itself proof that one tenant's vector is small.

**Idiomatic alternative:** If per-partition emission order is a documented
guarantee for the source, a reviewer could evaluate processing each record
in that order without the persisted counter, the group vector, and the sort.
Because the existing counter already depends on that same property, removing
it would not weaken the ordering assumption — but that must be confirmed
against Rama's documentation, and a same-task grouping without a persisted
counter still needs some way to keep records in order through aggregation.
Any simplification must test same-tenant multi-client appends,
cross-command idempotency, contiguous journal sequence numbers, and stable
ordering at multiple task counts. The chat reference illustrates choosing a
stream topology for low-latency acknowledged writes
(`challenges/chat-app/test-resources/chat_app/module.clj:164-251`) and a
microbatch for derived views (`:253-390`); it is not evidence that changing
this ledger's topology is semantics-free.

### 3. Repeated `:currency` / `:next-seq` reads: optional constant-factor I/O reduction

For every non-replayed command the loop reads `:currency` and `:next-seq`
separately (`module.clj:143-144`), and writes `:next-seq` on every accepted
journal-producing command (`:174`). Each is a navigation through the tenant's
top-level key, which the skill counts as a storage read or write
(`SKILL.md:65-68`). Both values are constant per command, so the private
growth checks pass, and this is not a contract defect.

**Idiomatic alternative:** Within one tenant group both values change only
through this loop: `:currency` on an accepted `:create-tenant` (`:155-157`)
and `:next-seq` on each journal row (`:173-174`). They could be read once
before the loop, carried as `loop<-` variables and updated from `*result`, and
`:next-seq` written once after the loop. That turns two reads per command into
two reads per tenant group and removes per-command `:next-seq` writes. A
smaller step is to fetch both fields in one `local-select>` (for example with
`submap`), which should first be checked not to navigate into the subindexed
siblings. The saving is real under the skill's rule not to trade I/O for
simplicity (`SKILL.md:30`), but it is a constant-factor change inside the
ledger's correctness-critical loop: a missed update on `:create-tenant` or a
rejected command would corrupt currency checks or journal contiguity.
Apply it only together with the full private ledger and growth suite, and
compare the growth metrics before and after.

### 4. Subindexed growth is sound; `Object` value schemas are partly avoidable

All four tenant collections use `map-schema ... {:subindex? true}`
(`module.clj:113-116`). Point reads for request replay, account balances,
charges, and journal pages navigate keyed paths; `get-journal` uses a bounded
sorted range (`:230-238`). This matches the contract's constant-work point
reads and limit-bounded journal reads (`README.md:260-275`) and the skill's
advice to subindex large collections (`SKILL.md:59`). Preserve subindexing:
replacing these maps with a single growing value would violate the resource
guarantees, and the growth checks could not detect it
(`private_challenge_test.clj:220-221`).

The value schemas are all `Object`. The comparable references type their
record values: auction uses `fixed-keys-schema` values for listings and the
top bid, `Long` for bid amounts, and a result interface for notifications
(`challenges/auction-module/test-resources/auction_module/module.clj:78-93,
118`); chat uses `fixed-keys-schema` for messages, profiles, and thread
metadata (`challenges/chat-app/test-resources/chat_app/module.clj:170-178,
264-265`). Here, the account (`{:kind :balance}`, `module.clj:48, 54`) and
charge (`{:customer-id :merchant-id :amount :refunded-total :seq}`, `:80-81`)
records have fixed shapes and are good `fixed-keys-schema` candidates.
Journal rows are also fixed-shape but carry a nullable `:charge-id` and a
nested postings vector (`:64-66, 99-101`), so their typing gains less.
`Object` is defensible for request records: they store the original
`Command` record as `:payload` and an outcome map whose keys vary by command
type (`:31-32, 177-178`). Typing is a schema-clarity improvement, not a
demonstrated performance fix, and must be validated against the actual
PState operations and module update behavior before being applied.

### 5. Direct client PState reads are appropriate here; no multi-read fanout found

The client uses direct `foreign-select-one` for tenant, account, charge, and
outcome point reads, and a single bounded `foreign-select` for the journal
page (`module.clj:212-238`). Each API call is one client-to-cluster roundtrip,
and the journal request is explicitly limited by `limit`. No client method
loops over distributed PState reads to assemble one result, so there is no
client-side fanout to replace.

**Idiomatic alternative when a query grows:** Use a query topology to combine
reads, repartition between related PStates, and return one result when an API
would otherwise make multiple client roundtrips (`SKILL.md:39`). The chat
reference's query topologies are at
`challenges/chat-app/test-resources/chat_app/module.clj:392-494`; its
paginated and multi-part reads (`:417-494`) are each wrapped as one
`foreign-invoke-query` call (`:581-590`). Do not add a query topology merely
to wrap a single direct point read; that adds complexity without reducing
roundtrips or storage work.

### 6. Auction and chat patterns support focused state, but do not transfer wholesale

The auction reference uses separate PStates for user listings, listing
bidders, top bid, and user bids (`auction_module/module.clj:77-93`), with
subindexed maps where collections grow. Its bid depot is partitioned by the
listing owner's user ID (`:71`), so `$$listing-bidders` and
`$$listing-top-bid` — keyed by listing ID — are colocated with the owner
rather than partitioned by their own key; the client reads them with
`{:pkey (:user-id listing-id)}` (`:189-199`), and the topology repartitions
with `|hash *bidder-id` only for the per-bidder view (`:112-113`). That is a
useful contrast to the payment reference's nested per-tenant collections, and
shows how separate PStates can still share one partition key. It is not proof
that splitting payment state is better: the payment challenge requires
several state mutations to remain consistent for each tenant command.

The chat reference also uses direct client point reads for genuinely
single-key lookups (`chat_app/module.clj:546-563`). Its `get-online-members`
loop (`:564-580`) makes two roundtrips per chunk (a member-range read and an
`online-filter` query) because presence is an in-memory task-global value
filtered in chunks; that is a workload-specific tradeoff, not a pattern to
copy for bounded payment journal pages.

## Verification boundary

No source code was rewritten, so the private test suite and
`scripts/test_reference_packages.py` were not run. No `bb run-challenges` or
solver run was performed. The private suite has four top-level tests: ledger
and bounded-growth coverage each at two and four tasks
(`private_challenge_test.clj:145-149, 232-236`). Any future rewrite
(including finding 3) should run the challenge's `:test-private-harness`
alias, then the applicable reference package test script, and inspect the
growth metrics as well as the functional assertions before replacing this
reference.
