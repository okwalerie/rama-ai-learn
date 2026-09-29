# Idiom review: HLD ticketing system

**Scope.** This review compares the ticketing reference with the auction and
chat references and `plugins/rama-skill/skills/rama/SKILL.md`. It evaluates
the design requested for review; it does not change the challenge README,
protocol, tests, skill, or reference implementation.

## Findings

### 1. One multiplexed command depot is appropriate for this contract

`challenges/hld-ticketing-system/test-resources/hld_ticketing_system/module.clj:166-170`
declares one `*commands` depot partitioned by `:event-id`. The six command
record types share that event key, and the README requires (a) ordering across
all command types for one event, and (b) one request-id namespace across
command types. Those are reasons to retain one ordered command lane; splitting
commands into depots by operation would make the ordering and replay/conflict
decision cross-depot coordination problems.

The comparison references show when multiple depots are useful, not a rule to
split every command type. Auction has distinct listing and bid keys and thus
separate depot partitioners (`challenges/auction-module/test-resources/auction_module/module.clj:69-72`).
Chat has a separate registration depot keyed by handle and a shared actions
depot keyed by actor (`challenges/chat-app/test-resources/chat_app/module.clj:158-160`);
that shared action depot uses record dispatch in `<<subsource` while keeping
the common user-local registration gate local (`.../module.clj:196-205`).
Here, the event id and cross-command sequencing already align, so one
multiplexed depot is the simpler idiom.

### 2. The ordering bridge is correct-shaped but carries real overhead

The microbatch pipeline stamps each command with durable `:ingress-seq`,
groups by event, collects with `+vec-agg`, sorts, then applies each command in
a `loop<-` (`.../module.clj:201-208`). This makes order explicit instead of
assuming aggregator order, and lets each event's commands see earlier
commands in the loop. It also adds an ingress-sequence PState read and write
for every command, a `+group-by` aggregation phase (keyed by the same event
id as the depot partitioner), per-event buffering, a sort, and loop
machinery. The stamp is correctly separate from the
compensation sequence because those counters have different semantics.

**Alternative:** a stream topology consuming the same event-partitioned
command depot can apply each event as it arrives, removing the explicit
stamp/group/vector/sort bridge and reducing batching latency. This is the
simplest alternative to evaluate if latency or the bridge's per-command I/O
becomes material. It is not a drop-in recommendation: verify that a long
1000-seat add remains cooperatively schedulable and that any yielding cannot
interleave a competing command between a hold's availability check and all
seat writes. The current microbatch path makes that transaction sequencing
explicit and has a 200-record batch bound (`.../module.clj:167-170`).

The `+vec-agg` + explicit sort itself is justified if retaining the current
microbatch shape: do not remove the sort unless the replacement establishes
and verifies an ordering guarantee. The skill's guidance is to select stream
for low-latency/ack needs and microbatch for the remaining exactly-once,
higher-throughput work (`plugins/rama-skill/skills/rama/SKILL.md:63`). The
challenge specifies durable acceptance and explicit barriers, not a strict
low-latency target, so the current microbatch choice is defensible.

### 3. “One PState” does not mean unbounded event blobs here

`$$events` is a single PState keyed by event, but its `:seats`, `:holds`,
`:compensations`, and `:requests` maps are each declared subindexed
(`.../module.clj:171-195`). Mutations and reads navigate to affected keys:
seat commands read only their requested IDs and write affected seat records
(`.../module.clj:222-250`); hold/request lookups are by id; compensation
pages use a bounded sorted range (`.../module.clj:251-263, 326-335`). The
query topology similarly uses a requested-seat `submap` rather than loading
all seats (`.../module.clj:267-288`). This addresses the README's own-history
bound; the growing collections are not stored as one unindexed serialized
value. The record's `:seat-ids` vector is bounded by the protocol (at most
eight for a hold), and the request payload is bounded by command input.
The retained evidence agrees: `test-resources/SUCCESSOR_HARNESS.log` records
identical read/write counts for all six measured operations at 256 and 1,280
records of own history (2 and 4 tasks), and `SUCCESSOR_SCAN_CONTROL.log`
records that a full compensation-scan control failed only the page-read
growth assertions.

**Alternative:** separate PStates for seats, holds, outcomes, and
compensations could make ownership/schema boundaries more visible and permit
independent evolution. Keep each keyed by `event-id` (or by a composite key
whose partitioner still colocates the event's atomic command path), and retain
subindexing. It would not by itself improve the current bounds; it expands
the schema and must preserve same-task atomic writes and event-level query
placement. No evidence in the inspected tests calls for that tradeoff.

### 4. Client reads use a sensible direct-select/query-topology split

`make-client` uses one direct `foreign-select-one` for outcome and clock,
one bounded direct range select for compensation pages, and one query call
for each multi-seat or derived hold view (`.../module.clj:316-335`). It does
not fan a `get-seats` request out into a client-side select per seat; the
`seats` query routes once by event and selects the requested keys together.
This matches the skill's advice to use query topologies instead of multiple
client network roundtrips (`plugins/rama-skill/skills/rama/SKILL.md:36-41`).
Direct client-side reads are appropriate for the simple single-partition
lookup/range cases and are not a distributed-query defect in this
implementation.

**Alternative:** route outcome, clock, and compensation reads through query
topologies for a uniform API boundary only if that boundary adds required
validation, composition, or routing behavior. In the current code they would
still be one remote operation apiece and add query declarations without
reducing roundtrips or storage work.

### 5. Keep bounded work and cooperative yielding distinct

The command-side seat read intentionally omits `{:allow-yield? true}` while
the query-side read has it (`.../module.clj:222-227, 274-276`). The retained
reference diagnostics document an observed same-microbatch visibility
difference when the ETL read yielded; the cause was not established. The
write and command loops still call `yield-if-overtime` (`.../module.clj:243-265`).
Do not mechanically add yielding to every bounded read: first establish
correct same-batch visibility and atomicity with the ordering diagnostic.
Conversely, keep the query read's yield option because the query is read-only
and its input may contain up to 64 keys.

## Overall assessment

The most complex section is the ingress-order bridge, not the depot or the
PState layout. That complexity implements a specific combination of ordered
cross-command processing and cooperative bounded work. The obvious simpler
stream alternative deserves a latency/throughput comparison before adoption,
but changing it without a production-safe ordering/yield proof would be a
riskier result than preserving the verified reference. The nested growing
collections and client query split are idiomatic for the inspected access
patterns, so this review records the rationale rather than recommending a
rewrite.

## Verification and disposition

This is a documentation-only change. No Rama implementation or challenge
contract files were edited, and no challenge test command was rerun for this
review. The results below are historical, recorded in the challenge's
`test-resources/`, not fresh results from this branch:

- **Full private harness, most recent:** 9 tests, 214 assertions, 0 failures,
  0 errors (`clojure -J-Xmx1600m -X:test-private-harness`,
  `SUCCESSOR_HARNESS.log`, summarized in `VALIDATION.md` under the
  September 23, 2026 successor acceptance). `VALIDATION.md` records that run
  against reference SHA256 `44ec6142…5760`; the reference in this checkout
  hashes to the same value, and the test file contains the paired
  `bounded-own-history-work` test that the run exercised.
- **Earlier full run:** 9 tests, 176 assertions, 0 failures/errors
  (`HARNESS_RUN.log`). Its log has no paired-work output; it predates the
  paired history test that replaced absolute operation caps, so it is
  superseded, not the current count.
- **Reference-only ordering diagnostic:** 1 test, 16 assertions, 0
  failures/errors at 2 and 4 tasks (`DIAGNOSTIC_RUN.log`,
  `PARENT_DIAGNOSTIC.log`). `VALIDATION.md` states that this was a parent
  run and was not rerun by the successor.
