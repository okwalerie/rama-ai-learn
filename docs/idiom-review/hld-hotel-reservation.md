# Idiom review: HLD hotel reservation

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a` (`master` at the
requested baseline). This is an audit of the reference implementation at
that commit, not a proposed compatibility-preserving patch. The reference
was left unchanged; only this review document is added.

## Contract that constrains the design

The README requires durable outcomes and idempotency for every command,
ordered commands from one client on a property, all-or-nothing inventory
updates across up to 30 nights, contiguous per-property journal sequence
numbers, and reads/work bounded independently of property history
(`challenges/hld-hotel-reservation/README.md`, especially “Command
conventions”, “Ordering”, “Invariants”, and “Resource guarantees”). These
are design constraints, not optional micro-optimizations. In particular,
moving commands to independent depots/topologies without a sequencing and
transaction design would change behavior.

## Findings and alternatives

### 1. One multiplexed command depot and a single microbatch topology

**Observed.** `test-resources/hld_hotel_reservation/module.clj:60-62`
declares one `*commands` depot hashed by `:property-id`, sets its maximum
microbatch records to 1,000, and sends all command kinds through one
`core` microbatch topology. The topology's `<<cond` dispatch at lines
115-244 handles create, rate, reserve, and cancel operations against one
property-scoped state record.

**Why this is defensible.** Hashing by property colocates the inventory,
booking, request-id, and journal state needed for one command. The
microbatch provides retry-safe PState updates and allows the reservation's
up-to-30-night inventory changes, booking, journal entry, sequence, and
outcome to commit together. A single ordered input also avoids inventing
ordering across several depots. This is a coherent correctness-first
default for the supplied contract.

**Idiomatic alternative.** Keep a single property-partitioned command
depot and microbatch while commands need shared serial order and atomic
state transitions. Simplify the internal dispatch by extracting small
operation-specific dataflow functions only if that improves readability;
do not split commands into independently processed topologies merely to
make the module appear more like a production service. If future load data
proves that one property is a hot partition, first decide which contract
can be relaxed or introduce an explicit coordination/transaction design;
sharding one property's inventory is not a mechanical repartitioning.

**Comparison.** `auction-module` uses separate depots/topologies for
distinct listing/bid and expiration flows (`challenges/auction-module/
test-resources/auction_module/module.clj:69-77,115-158`). `chat-app`
splits a low-latency stream path from derived microbatch views
(`challenges/chat-app/test-resources/chat_app/module.clj:158-165,253-280`),
but that split has a deliberate consistency boundary and does not offer a
precedent for splitting this contract's atomic reservation command.

### 2. `:ingress-seq`, `+group-by`, `+vec-agg`, sort, and `loop<-`

**Observed.** The topology emits each microbatch command at lines 89-93;
lines 94-99 increment and persist `:ingress-seq`, build `[position,
command]` pairs, group by property with `+group-by` / `+vec-agg`, and sort
each property's complete in-batch vector. Lines 100-249 then run a
`loop<-` over that ordered vector, perform the idempotency/business logic,
and call `yield-if-overtime` between commands (line 248).

**Cost and risk.** The ordering scaffold adds a PState read and write per
input command, materializes and sorts an intermediate vector per property
per batch, and serializes the command state machine into one loop. These
costs are bounded by the configured microbatch record cap rather than by
all-time history, but they remain per-batch work. The loop's cooperative
yield is good practice for a long sequence; it does not remove the vector
materialization/sort or make the entire microbatch's latency independent
of a busy property. The 1,000-record cap also becomes a correctness/perf
coupling to document if it is changed.

**Idiomatic alternative.** Microbatch source emission preserves append
order within each depot partition (`.agents/skills/rama/references/
microbatch.md`, “Source binding and emitting records”). Since all commands
for a property hash to the same depot partition, investigate whether
processing each record directly in source order can safely replace the
sequence/group/vector/sort scaffold. Do not remove it solely on this
observation: prove the relevant order survives the exact aggregation
boundary and across batches/restarts, and preserve the test's paused-queue
cross-batch contract (`test-resources/hld_hotel_reservation/
reference_test.clj:25-57`). If the aggregation is needed, keep its
materialization bounded and make the partition/order invariant explicit.
The per-command `loop<-` is bounded by the same batch cap and its body
already yields; a direct per-record dispatch is simpler only if its
ordering and cross-record dependencies remain equivalent.

### 3. One property-keyed PState root with nested, growing maps

**Observed.** `module.clj:63-88` stores all property-owned state in
`$$properties`, with the property id as the root key. Nested room types,
nights, bookings, events, and requests are declared as subindexed maps.
Availability/reservation range reads use the night subindex (lines
172-174, 250-259); the client journal page uses a sorted range with a
maximum amount (lines 314-321). Point reads address one booking, request,
or night.

**Assessment.** A “single PState” is not itself a defect: all maps that
grow with property history are subindexed, keeping reads from materializing
the entire property value. The layout colocates state required for atomic
reservation and cancellation. The work test explicitly grows the same
property's nights/bookings/events/requests and checks read/write hook
counts at two history sizes (`test-private/hld_hotel_reservation/
work_test.clj:19-25,53-81`).

**Alternative/tradeoff.** Splitting state into separately partitioned
PStates may distribute a single property's history or hot-key load, but
would add routing and potentially cross-task transaction/read work to
reservation, cancellation, and journal sequencing. It should be justified
by measured storage/throughput pressure and the transaction design, not by
the number of PState declarations. Keep subindexes on any unbounded nested
map; avoid selecting a whole growing map as a value. Consider separate
top-level PStates only when it reduces measured I/O without breaking the
same-property atomicity and ordering requirements.

### 4. Distributed query work and client-side PState reads

**Observed.** The `availability` query topology hashes by property,
selects the room-type's bounded night range, and returns the requested
vector (`module.clj:250-259`); its client wrapper uses
`foreign-invoke-query` (`module.clj:266-268,307-309`). The client also
does direct `foreign-select-one` point lookups for outcomes, nights, and
bookings (`module.clj:301-313`), and a bounded sorted journal-range
`foreign-select` for `get-booking-events` (`module.clj:314-321`).

**Assessment.** These are not unbounded client-side distributed scans:
the point calls select one known key, the event page has `:max-amt limit`
where `limit` is structurally capped at 500, and availability is handled
server-side in one query invocation with a stay cap of 30. A query topology
is useful when it packages distributed dataflow as one operation; it is
not automatically better than a single bounded foreign PState select.

**Idiomatic alternative.** Retain direct client `foreign-select-one` for
independent point reads and the bounded foreign journal page; these each
avoid extra query-topology machinery and already satisfy the fixed-work
contract. Keep `get-availability` as one server-side query rather than
issuing one client network roundtrip per night. If another API needs
multiple PState reads, joins, or fan-out, put that dataflow in one query
topology and partition by the primary key, as chat-app's page queries do
(`challenges/chat-app/test-resources/chat_app/module.clj:417-433`). Avoid
client loops that call distributed selects once per result item.

## Recommendation

Preserve the existing atomic property-local layout and microbatch contract
until profiling demonstrates a concrete bottleneck. The clearest audit
candidate for a future focused experiment is the ordering scaffold at
`module.clj:94-100`: verify whether source order within the property-hash
partition is sufficient, then compare command-order tests and measured
work before and after any simplification. Do not trade away retry-safe
PState updates, per-property order, history-bounded reads, or the
microbatch transaction semantics to remove code without an equivalent
proof.

## Review scope and verification notes

Inspected the challenge README, protocol, private contract/work tests,
reference module and reference-specific tests, plus auction-module and
chat-app references and the Rama skill. No implementation or challenge
contract files were changed. Existing checked-in validation logs report
5 tests / 160 assertions for the contract-and-work harness and 2 tests /
20 assertions for the reference-specific suite (`test-resources/
validation/parent-harness.log`, `parent-reference.log`); these are prior
run artifacts, not a test execution performed for this documentation-only
change.
