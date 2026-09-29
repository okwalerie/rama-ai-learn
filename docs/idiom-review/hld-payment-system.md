# Idiom review: HLD payment system

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a` (`orb/idiom-hld-payment-system`).

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
opaque values (`test-private/hld_payment_system/private_challenge_test.clj:220-230`).
The challenge's private review also reports no concrete reference defect and
documents prior mutation checks against the functional and growth tests
(`test-private/REVIEW.md`). There is no clear, localized rewrite whose safety
can be established within this review. No challenge contract, private test,
reference implementation, or Rama skill file was changed.

## Findings

### 1. Single tenant-keyed PState: deliberate colocation, with hot-tenant limits

`test-resources/hld_payment_system/module.clj:106-116` stores each tenant's
currency, sequence counter, accounts, charges, journal, and idempotency
requests under one `$$tenants` top-level key. Depot `*commands` is partitioned
by tenant (`:107`), so a tenant's command stream and all state needed for
funding, charging, refunds, replay detection, sequence allocation, and journal
append are colocated. This is a strong fit for the README's per-tenant command
ordering and balanced-ledger invariants (`README.md:94-112, 246-258`). The
nested maps are subindexed (`:113-116`), so this is not automatically the same
as deserializing the entire tenant history for each point access.

The tradeoff is that one very busy tenant remains a single partition's
serialization and storage hotspot; adding more tasks spreads different
tenants but cannot parallelize one tenant's ordered ledger writes. The current
tests cover two and four tasks (`private_challenge_test.clj:145-149,
232-236`) but do not establish behavior under a single extremely hot tenant.

**Idiomatic alternatives:** Keep the current partition key if atomic
same-tenant command handling is the priority. A more decomposed shape could
use typed per-record PStates such as tenant metadata, accounts keyed by
`[tenant-id account-id]`, charges by `[tenant-id charge-id]`, requests by
`[tenant-id request-id]`, and journal rows by `[tenant-id seq]`, all still
partitioned by tenant. This may clarify schemas and avoid nesting distinct
record families beneath one tenant value, but does not remove the hot-tenant
limit and may add path/coordination complexity. Do not repartition ledger
records by account or charge without solving how a single accepted command
atomically updates both accounts, charge/refund state, request outcome,
sequence, and journal.

### 2. Ingress sequence, group aggregation, sort, and loop preserve semantics but add batch work

The microbatch source assigns a per-tenant `:ingress-seq` (`:117-125`), groups
records by tenant and collects each group with `+vec-agg` (`:126-128`), sorts
the collected pairs (`ordered`, defined at `:103-104`), then processes
commands serially with `loop<-` (`:129-181`). The ordering mechanism is
explicit and supports command-order correctness; `yield-if-overtime` at `:180`
is also appropriate for a potentially long tenant batch.

Its costs are an ingress-sequence PState read/write per command, materializing
all commands for a tenant in a batch, and sorting those commands before the
loop. Large batches for one tenant therefore require memory and sorting work
proportional to that batch and continue to serialize on that tenant. The
`depot.microbatch.max.records` option is set to 1000 (`:108`), which bounds a
microbatch but is not itself proof that one tenant's vector is small.

**Idiomatic alternative:** First establish whether the source's per-partition
record order is a documented guarantee for this topology and depot. If it is,
process each tenant's records in that order without a second sequence counter,
group vector, and sort. If it is not, retain an explicit sequencing strategy;
do not remove the sequence/sort just because the depot is hash-partitioned.
Any simplification must test same-tenant multi-client appends, cross-command
idempotency, contiguous journal sequence numbers, and stable ordering at
multiple task counts. The chat reference illustrates choosing streams for
low-latency acknowledged writes and microbatches for derived views
(`challenges/chat-app/test-resources/chat_app/module.clj:164-200, 392-494`);
it is not evidence that changing this ledger's topology is semantics-free.

### 3. Subindexed growth and `Object` schemas need to be judged separately

All four tenant collections use `map-schema ... {:subindex? true}`
(`module.clj:113-116`). Point reads for request replay, account balances,
charges, and journal pages navigate keyed paths; `get-journal` uses a bounded
sorted range (`:230-238`). This is a sound direction for the contract's
constant-work point reads and limit-bounded journal reads (`README.md:260-275`)
and is consistent with the skill's advice to subindex large collections
(`SKILL.md:59`). The `Object` value schemas (`:113-116`), however, leave the
shape of stored records unchecked at the PState schema boundary.

**Idiomatic alternative:** Consider typed fixed-key schemas for account,
charge, journal-row, and request records, provided Rama's schema supports the
needed value types and the change is validated against the actual PState
operations. This improves schema clarity, but is not a demonstrated
performance fix. Preserve subindexing: replacing these maps with a single
growing Clojure map/vector value would contradict the explicit resource
guarantees. The private growth checks compare observed RocksDB event counts
between history sizes (`private_challenge_test.clj:164-230`); they are useful
but the test itself notes that opaque bytes read or rewritten are invisible.

### 4. Direct client PState reads are mostly appropriate here; no multi-read fanout found

The client uses direct `foreign-select-one` for tenant, account, charge, and
outcome point reads, and a single bounded `foreign-select` for the journal
page (`module.clj:212-238`). Each is one client-to-cluster read path, and the
journal request is explicitly limited by `limit`. I found no client method
that loops over distributed PState reads to assemble one result. Therefore,
there is no existing client-side distributed-query fanout to replace in this
reference.

**Idiomatic alternative when a query grows:** Use a query topology to combine
reads, repartition between related PStates, and return one result when an API
would otherwise make multiple client roundtrips. The chat reference shows
query topologies for paginated pages and multi-part reads (`chat-app/module.clj:417-494`)
and wraps them as one `foreign-invoke-query` call (`:581-590`). Do not add a
query topology merely to wrap a single direct point read; that can add
complexity without reducing roundtrips or storage work. Direct foreign PState
reads are used similarly in the auction reference (`auction-module/module.clj:180-196`).

### 5. Auction reference patterns support focused state, but do not transfer wholesale

The auction reference uses separate PStates for user listings, listing bids,
top bid, and user bids (`challenges/auction-module/test-resources/auction_module/module.clj:77-93`),
with subindexed maps where collections grow (`:78-93`). It demonstrates
matching depot and PState partitioning to lookup keys (`:69-71, 95-114`).
This is a useful contrast to the payment reference's nested per-tenant
collections, not proof that splitting payment state is better: the payment
challenge requires multiple state mutations to remain consistent for each
tenant command.

The chat reference also demonstrates direct client point reads where they
are genuinely single-key lookups (`chat-app/module.clj:546-563`) and query
topologies for composite results (`:417-494`). Its `get-online-members`
client loop makes repeated bounded requests (`:564-580`) because it scans
members in chunks; that is a workload-specific tradeoff, not a pattern to
copy for bounded payment journal pages.

## Verification boundary

No source code was rewritten, so the private test suite and
`scripts/test_reference_packages.py` were not run. No `bb run-challenges` or
solver run was performed. The private suite has four top-level tests: ledger
and bounded-growth coverage each at two and four tasks
(`private_challenge_test.clj:145-149, 232-236`). Any future rewrite should run
the challenge's `:test-private-harness` alias, then the applicable reference
package test script, and inspect the growth metrics as well as the functional
assertions before replacing this reference.
