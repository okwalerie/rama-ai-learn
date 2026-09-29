# Idiom review: HLD hotel reservation

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a`. This audits the
reference implementation at that commit. **Outcome: documentation only.**
The reference module, README, protocol, private tests, and skill are
unchanged (see "Verification" for why no rewrite was attempted).

Paths below are relative to `challenges/hld-hotel-reservation/` unless
they start with `challenges/` or `plugins/`. `module.clj` means
`test-resources/hld_hotel_reservation/module.clj`. Bare `SKILL.md` and
`references/...` paths are under `plugins/rama-skill/skills/rama/`.

## Contract that constrains the design

The README requires one durable outcome per command, property-scoped
idempotency, and conflict counting (`README.md:43-104`). Commands from one
client against one property must run in issue order (`README.md:106-108`).
Reservations must be all-or-nothing over up to 30 nights
(`README.md:152-173`), and journal seqs must be contiguous per property
(`README.md:234-255`). Every read and write must be bounded independently
of the property's own history (`README.md:257-275`). Together these
require a serial, transactional state machine per property. Splitting
commands across independently processed depots or topologies would change
behavior unless a new ordering and transaction design came with it.

## Findings

### 1. One multiplexed command depot + one microbatch topology — keep

**Observed.** `module.clj:60` declares one `*commands` depot with
`(hash-by :property-id)`. `module.clj:61` caps
`depot.microbatch.max.records` at 1000. `module.clj:62-249` sends every
command kind through the single microbatch topology `"core"`. Dispatch is
a `<<cond` on the `:op` keyword of a generic `Command` record
(`module.clj:9`, `115-244`).

**Assessment: idiomatic in its essentials.** The skill says to put events
in the same depot when they need local ordering on the same entity. It
also says to partition the depot by the key used for PState lookups
(`plugins/rama-skill/skills/rama/references/depot-design.md:15-18,112-117`;
`SKILL.md:53`). Hashing by property colocates all state that one command
touches. Microbatch makes the up-to-30 night updates, booking, journal
event, `:next-seq`, and outcome commit as one attempt, and makes them
exactly-once under retry (`references/microbatch.md:116-130`). Nothing in
the contract needs stream-only ack coordination (`SKILL.md:63`).

**Idiomatic alternative (readability only).** Replace the generic
`Command{:op :args}` record and positional `(nth *args i)` destructuring
(`module.clj:137-138,153,168-169,212`) with one defrecord per command
type. Dispatch those records with `<<subsource` and `case>`, keeping the
same single depot and topology. `chat-app` uses this shape: one
`*user-actions-depot` hashed by user feeds typed records into
`(%mb :> *action) (<<subsource *action (case> MembershipEvent ...) ...)`
(`challenges/chat-app/test-resources/chat_app/module.clj:160,280-282`).
Two constraints apply:

- Payload equality for replay/conflict detection must still compare the
  command type plus all non-request-id arguments. Distinct record types
  satisfy this under `=`.
- The client must still validate structure before appending
  (`README.md:83-91`).

Do **not** split commands into separate depots or topologies. That would
lose the per-property total order that journal seqs and the paused-queue
test depend on (`test-resources/hld_hotel_reservation/reference_test.clj:25-57`).
`auction-module` does use separate depots and topologies
(`challenges/auction-module/test-resources/auction_module/module.clj:70-71,77-158`),
but its listing, bid, and expiration flows have no shared serial
journal. It is not a precedent for this contract.

### 2. `:ingress-seq` + `+group-by` / `+vec-agg` + sort + outer `loop<-` — unnecessary scaffold (main finding)

**Observed.** `module.clj:91-100,248-249`, for each emitted command:

1. reads and increments the persisted `:ingress-seq` counter
   (`module.clj:94-96`);
2. pairs the command with that position (`module.clj:97`);
3. aggregates the pairs per property with
   `(+group-by *p (aggs/+vec-agg *pair :> *pairs))` (`module.clj:98`);
4. sorts them back into position order with `sorted-commands`
   (`module.clj:42-43,99`);
5. walks the sorted vector with an outer `loop<-`, calling
   `yield-if-overtime` after every command (`module.clj:100-249`).

**Why it exists.** Aggregation in a `<<batch` block is a
declarative boundary. The skill says Rama decides execution order there
(`references/batch.md:3`). It does not promise that `+vec-agg` keeps
emission order. `+group-by` also adds a hash partition on `*p`. That
lands on the same task, because the depot is hashed by the same key, but
it is still an aggregation and materialization boundary. Given that
design, the persisted counter plus the sort is a correct way to restore
order. The design is the problem.

**Why the design is unneeded.** `%mb` emits each task's depot-partition
records in append order (`references/microbatch.md:13`). All commands for
one property land in one depot partition. The per-command body
(`module.clj:104-247`) contains no partitioner or other async boundary:
it is only `local-select>` / `local-transform>` on `*p`'s task. So direct
per-record processing, with no `<<batch` and no aggregation, already runs
a property's commands in append order. Microbatches run strictly one
after another (`references/microbatch.md:28-30`), so order also holds
across batches, including the 1000-record boundary that the paused-queue
test crosses. Nothing in this topology needs cross-record aggregation,
materialization, or several passes over the batch. Those are the stated
reasons to use `<<batch` (`references/microbatch.md:68-84`).

**Cost of the scaffold.**

- One extra PState read and write per command (`:ingress-seq`), plus a
  counter kept forever in every property's root record that no API ever
  reads.
- Each property's whole in-batch command vector (up to 1000 records) is
  materialized and sorted before any business logic runs.
- A per-batch outer `loop<-` and a `yield-if-overtime` that exist only
  to walk that vector.

All of these are bounded by the batch cap, not by history, so the work
test still passes. They are pure overhead, and they make the ordering
invariant harder to see.

**Idiomatic alternative.** Emit and process directly, as the
`auction-module` expiration topology does
(`challenges/auction-module/test-resources/auction_module/module.clj:122-123`)
and as `chat-app` does (`chat_app/module.clj:280-282`):

```clojure
(<<sources mb
  (source> *commands :> %mb)
  (%mb :> {:keys [*property-id *request-id] :as *command})
  (local-select> [(keypath *property-id :requests *request-id)] $$properties :> *prior)
  ;; ... existing replay/conflict check and per-command <<cond/<<subsource body ...
  )
```

Then delete `:ingress-seq` from the schema, along with `sorted-commands`,
`+group-by` / `+vec-agg`, and the outer `loop<-`. The inner per-night
`loop<-` in reserve and cancel (`module.clj:192-198,230-236`) stays. It
is bounded by the 30-night stay and is the right tool there.

Removing the scaffold would change the reference, so it has to pass the
full private suite and `reference_test.clj` at 2 and 4 tasks before it
lands. That was not possible in this session (see "Verification").

### 3. One property-keyed PState with nested, growing subindexed maps — acceptable

**Observed.** `module.clj:63-88` declares one `$$properties` PState keyed
by property id. Its root is a `fixed-keys-schema` holding `:next-seq`,
`:ingress-seq`, and four nested maps that grow with history: room types
→ nights, `:bookings`, `:events`, and `:requests`. Every map that can grow
is `{:subindex? true}`, including room types and each room type's nights.

**Assessment.** Keeping everything in one PState is not a defect here.
Every unbounded collection is subindexed, which follows `SKILL.md:59`,
and every access touches a bounded slice:

- point `keypath` reads for requests, bookings, and single nights
  (`module.clj:108,126,140,213,303,306,312`);
- `sorted-map-range` over at most 30 nights (`module.clj:172-173,227-228,254-255`);
- `sorted-map-range-from ... {:max-amt limit}` for the journal, with
  `limit ≤ 500` (`module.clj:320-321`).

The layout keeps everything one reservation touches on one partition,
which is what makes the atomic single-task transaction possible. The
private work test grows one property's nights, bookings, events, and
requests to 1201 entries and checks read, iterator, and write hook counts
(`test-private/hld_hotel_reservation/work_test.clj:19-25,53-81`). In the
checked-in log, all counts are identical at 240 and 1201 entries. For
example, a one-night reservation costs 13 reads, 2 iterations, and 6
writes at both sizes (`test-resources/validation/parent-harness.log`).

**Minor schema notes.**

- `:requests` stores the full `Command` record as `:payload`
  (`module.clj:86,246`), so property-id and request-id are duplicated in
  their own key path. Storing only the payload (op + args) would match the
  README's definition of payload.
- `:outcome` is typed as a bare `IPersistentMap` (`module.clj:86`),
  unlike the precise `fixed-keys-schema` used everywhere else.

Neither affects correctness or bounds.

**Alternative/tradeoff.** Splitting bookings, events, or requests into
separate top-level PStates would not reduce I/O, because each access is
already a keyed or ranged subindex read. It could only help as part of a
design that relaxes same-property atomicity, and the contract does not
allow that. Keep one PState. Keep subindexes on every unbounded nested
map. Never select a whole growing map as a value.

### 4. Query topology vs. client-side foreign selects — appropriate

**Observed.** `get-availability` calls the `"availability"` query
topology (`module.clj:250-259,268,307-309`). That topology hashes to the
property, checks that the room type exists, and range-reads ≤30 nights.
`get-outcome`, `get-night`, and `get-booking` are single
`foreign-select-one` point reads (`module.clj:301-313`).
`get-booking-events` is one bounded `foreign-select` range with
`{:max-amt limit}` (`module.clj:314-321`).

**Assessment.** None of these is a client-side scatter or loop. Each
client API call makes exactly one network round trip:

- The point reads touch one key each.
- `get-availability` puts the existence check and the range read, which
  are two dependent reads, into one query invocation. The skill advises
  exactly this: prefer one query topology over several client-side
  selects (`SKILL.md:39`).
- The journal page is one ranged read bounded by `limit`.

A query topology adds nothing for a single bounded path, so the direct
foreign selects are the right choice. If a future API needs joins or
fan-out across partitions, put that dataflow in one query topology
partitioned by the primary key, as `chat-app`'s page queries do
(`challenges/chat-app/test-resources/chat_app/module.clj:417-433`). Do
not loop client-side over foreign selects.

`wait-for-processing!` calls `rtest/wait-for-microbatch-processed-count`
from the client wrapper (`module.clj:274-280`). The skill limits that
function to tests (`references/microbatch.md:173`). Here it is required
by the test-only `harness/Synchronizable` protocol, and no business read
depends on it, so it is acceptable.

## Conclusion

The reference is idiomatic where it matters:

- one property-hashed depot feeding one microbatch topology;
- colocated, subindexed per-property state;
- per-command atomic transactions;
- bounded point and range reads, with one round trip per API call.

The one clear non-idiomatic element is the ordering scaffold at
`module.clj:94-100` and its outer loop (`module.clj:100-249`). It
recovers an order that direct `%mb` emission already guarantees, and it
adds a persisted counter, per-batch materialization, and a sort. The
recommended change is to emit directly and delete that scaffold. Moving
from `:op` + `<<cond` to typed records + `<<subsource` is an optional
readability improvement. Do not split depots, topologies, or PStates:
that would give up the per-property serial order and single-task
atomicity the contract requires.

## Verification

- Branch `orb/idiom-hld-hotel-reservation` is based exactly on
  `43abd3f`. The only change on the branch is this document.
- Every cited line range was re-checked against `module.clj`,
  `README.md`, the private tests, `reference_test.clj`, the
  `auction-module` and `chat-app` references, and the Rama skill.
- **No tests were run for this review.** An attempt to run
  `clojure -X:test-private-harness` in this session needed an approval
  that was not granted. That is why the reference rewrite in finding 2
  was not made.
- The pass counts quoted here come from existing checked-in logs, not
  from a new run:
  - `test-resources/validation/parent-harness.log`: 5 tests, 160
    assertions, 0 failures;
  - `test-resources/validation/parent-reference.log`: 2 tests, 20
    assertions, 0 failures.
- `scripts/test_reference_packages.py` does not cover this challenge:
  `hld-hotel-reservation` is not in its `PACKAGES` list.
