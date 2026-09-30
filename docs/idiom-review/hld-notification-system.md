# HLD notification system reference idiom review

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a`. Subject:
`challenges/hld-notification-system/test-resources/hld_notification_system/module.clj`
(cited as `module.clj`), checked against `README.md`, `protocol.clj`, both private test
namespaces, the reference's own design artifacts (`test-resources/PLAN.md`,
`PLAN_VALIDATION.md`), the `auction-module` and `chat-app` references, and
`plugins/rama-skill/skills/rama/SKILL.md`. This is a review only: the reference, README,
protocol, private tests, and skill are unchanged.

This revision replaces an earlier draft of this file (commit `d8dba7a`). That draft had
three substantive errors, corrected below:

- It called `$$task-pos` a partition-alignment bug. It is a deliberate task-local counter and
  is correct (§3).
- It suggested replacing the rank arbitration with a direct first-arrival claim at
  `hash(submission-id)`. The private tests reject that design (§2).
- It called `:ingress-seq` a Rama feature. It is an application-level field name from another
  challenge's plan, not a Rama API (§2).

It also misdescribed the comparison depots (§1) and treated the harness append lock as a module
throughput issue (§6).

## Summary

The reference is idiomatic. Its unusual parts (per-task rank, `+group-by`/`+limit`
arbitration, a materialized second batch block) each follow from a contract clause or a
documented Rama rule. The remaining items are small clarity improvements, not correctness or
I/O defects. No rewrite is warranted.

## 1. Multiplexed depot + microbatch

**Evidence.** A single depot `*events (hash-by :owner)` (`module.clj:102`) carries five record
types (`:10-14`). The wrapper sets `:owner` to the user ID for register/preference/submit and to
the submission ID for attempt/receipt (`:205-214`). One microbatch topology `core` consumes it
(`:103,126-127`). Blocks 1 and 3 each re-read `%mb` and select their records with
`filter>` + `instance?` predicates (`:92-99,129-130,163-164`).

**Assessment: sound.** The README ordering rule is per logical owner: a recipient user, or a
submission for attempts and receipts (`README.md:155-156`; `protocol.clj:11-12`). Hashing each
record by its owner gives each owner one depot partition, so per-owner invocation order holds.
All user-owned records (register, preference, submit) must go through the same partition and
the same block. Otherwise a submit's device/preference snapshot could observe a later
registration (`PLAN_VALIDATION.md:90-92`; exercised at `notification_test.clj:69-85`).

Microbatch is the right default (`SKILL.md:63`). Every write family is non-idempotent (generation
bump, `submit-seq`, `dl-seq`, `task-pos`, list appends), and the submit path spans three
partitions (user → submission → user). Microbatch's exactly-once replay covers both without
extra dedup state (`PLAN_VALIDATION.md:26-28,39-42`). No read requires stream-latency
visibility, because tests read only after `wait-for-processing!` (`README.md:150-151`).

**Comparison.** `chat-app` also multiplexes many record types on one owner-hashed depot
(`*user-actions-depot (hash-by :user-id)`, `chat_app/module.clj:26-30,160`) and dispatches with
`<<subsource` (`:200-201,280-282`). Registration claims get a separate depot
(`*register-depot (hash-by :handle)`, `:159`), but a differing key does not always mean a separate
depot: `RoomCreate` stays on the shared depot, carries its candidate room ID in `:user-id` only to
satisfy the partitioner, and immediately repartitions to `hash(room-name)` (`:47-52,212-213`).
`auction-module` partitions its bid depot by the listing owner, not the bid
(`hash-by nested-listing-user-id`, `auction_module/module.clj:66-71`), so each bid first lands where the listing state it updates
lives. The shared lesson is: partition each record by the owner whose order or local reads it
depends on; this reference already does that. A record with no such owner, like chat-app's room
creation, can ride a shared depot and repartition.

**Optional clarity alternative.** Split the depot into `*user-events (hash-by :user-id)` and
`*delivery-events (hash-by :submission-id)`, both consumed by the same `core` microbatch.

- This removes the synthetic `:owner` field that duplicates `:user-id` or `:submission-id`.
- Block 1 then sources only user events and block 3 only delivery events, so neither block
  filters the other's records.
- Dispatch within block 1 by type (`<<subsource` as in chat-app, or the current `<<cond`).
- Keep register, preference, and submit on one depot. Splitting those would break the
  same-owner ordering described above.
- Costs: the barrier must count appends to both depots, and the gain is readability plus
  skipping an in-memory type check. There is no I/O change.

## 2. Arbitration idioms: `:ingress-seq`, `+group-by`, `+vec-agg`, sort, `loop<-`, repartition

**What the reference uses.** For each `Submit` on the user's task, the code does the following
(`module.clj:134-144`):

- reads `[user :profile]` (one seek, `:132`);
- advances the per-user `submit-seq`;
- advances a per-task position in `$$task-pos`;
- builds the snapshot record;
- emits a candidate with `rank = task × 2^40 + pos` (`:89-90,143`).

Then `(+group-by *sid (aggs/+limit [1] … :+options {:sort *rank}))` keeps the minimum-rank
contender per submission ID, and `materialize>` saves it to `$$winners` (`:151-154`). Block 2
reads `$$winners` on `hash(sid)`, skips IDs already persisted, writes the record, and returns
with `|hash *user` to append `recent[seq]` (`:155-161`). The reference uses no `+vec-agg`,
explicit sort, `loop<-`, or ingress-sequence field.

**Why this design is needed.** Consider crossed submissions: client A submits `x` then `y` for
user `u`, while client B submits `y` then `x` for user `v`. Plain first-arrival at `hash(sid)`
lets `x→v` and `y→u` both win. No serial history explains that outcome, because it contradicts
both clients' invocation orders (`PLAN_VALIDATION.md:73-89,139-144`). The private test asserts
exactly this: owners `["v" "u"]` are excluded (`notification_test.clj:137-155`). The same-owner
case `repeat` requires the earlier payload to win without a barrier (`:156-160`).

`(task, pos)` is a total order that extends every per-owner depot order (`PLAN.md:49-65`). The
minimum rank per ID therefore always yields a serializable winner set. Any such order would do;
a per-task total order is not itself required for serializability. A direct point claim at
the submission owner would reintroduce the cross-owner race. That is only acceptable if the
contract is relaxed.

**`:ingress-seq`.** This is not a Rama API. It is an application PState field in another
challenge's plan (`challenges/hld-hotel-reservation/test-resources/PLAN.md:60,176-180,214-235`).
There, it gives a durable per-owner position so a whole batch of one owner's commands can be
grouped and applied in order. Here `$$task-pos` plays the analogous role. A per-user
sequence (the existing `submit-seq`) would also respect each user's order: with `u` and `v` on the
same task, the crossed case gives `x: min(u1, v2) = u` and `y: min(u2, v1) = v`, owners `["u" "v"]`,
which the test permits (`notification_test.clj:147`) via the serial history x(u), y(v), y(u),
x(v). The reason for `$$task-pos` is encoding: it makes the rank a unique `Long`. Per-user
sequence values collide across users, and a tie-breaker by the `String` user ID would need a
vector sort key, whose support in `+limit` `:sort` is undocumented (`PLAN_VALIDATION.md:53-54`).

**`+limit [1]` vs `+vec-agg` + sort vs `loop<-`.**

- `+limit [1]` with `:sort` is the minimal batch-only top-1-per-group reducer
  (`references/aggregators.md:108-113,263-269`). It keeps one row per group.
- `+vec-agg` + sort would move every contender to the group task and sort there. It has the same
  semantics and was the plan's documented fallback (`PLAN.md:59-61`), but it keeps more
  transient data.
- `loop<-` over sorted contenders is the right tool only when each contender's outcome depends
  on applying the previous ones to state (sequential command application). Here only the winner
  matters and losers have no effects, so `loop<-` would only add iterations. `SKILL.md:26` also
  flags long loops as a task-blocking risk.
- Keep `+limit`.

**Repartitions.** A submit takes exactly three hops: user task (snapshot plus rank), `+group-by`
to `hash(sid)`, then `|hash *user` for `recent`. That return hop cannot move into block 1's
post-agg, because partitioners are not allowed in post-agg (`references/batch.md:48,219`).
Hence `materialize>` plus a second `<<batch`, which is the documented pattern
(`references/batch.md:184-199`; `references/microbatch.md:44-52`).

Writing `recent` on hop 1 would save the return hop. But contest losers would then appear in the
recipient's list, violating first-wins (`protocol.clj:57-59,130-133`; `PLAN_VALIDATION.md:53`).
The delivery path does fixed work at `hash(sid)`, with one conditional hop to `hash(user)` only
for invalidations and dead letters (`module.clj:165-188`). No hop is removable.

## 3. `$$task-pos` layout (correction of the prior draft's "misalignment" finding)

**Evidence.** `$$task-pos {Long Long}` (`module.clj:124`) is read and written only at the key
`(ops/current-task-id)`, with `local-select>`/`local-transform>` on the current task
(`:137-140`).

**Assessment: correct.** Partition alignment (`SKILL.md:61`) matters when a key's owning
partition is chosen by a partitioner, so that other code (a later hop, a foreign select) can
find it. Here each task accesses only its own key and nothing else ever routes to it. Each
partition therefore holds exactly one entry, its own counter, and every access is local by
construction (`PLAN.md:151-152`; `PLAN_VALIDATION.md:11-12`). It is a PState, so microbatch
retry restores it and replay reassigns identical ranks (`PLAN.md:52-54`).

**Caveat (clarity only).** The key does not follow the usual `hash(key)` placement. A client
`foreign-select` of `$$task-pos` by task ID would route by hash and read the wrong partition.
Nothing does this. The design comment in `PLAN.md:151-152` explains the invariant but is
missing from `module.clj:124`, and adding it would prevent the misreading the prior draft made.
The cost is one small point read plus one write per submit, against a hot single-key partition.

## 4. Growing single-PState collections

**Evidence.**

- `$$users` holds each recipient's inline `:profile` together with two per-user collections
  that grow for the recipient's lifetime: `:recent` (seq → sid) and `:dead-letters`
  (seq → entry). Both are `{:subindex? true}` (`module.clj:104-115`).
- Page reads are `sorted-map-range-to-end 100` (one seek plus ≤100 iterations), reversed on the
  client (`:221-226`).
- `:devices` (≤8, enforced at `:23`), `:prefs` (≤16 by workload, `README.md:92`), and each
  submission's `:deliveries` (≤8, `module.clj:120-123`) are inline.
- All topology reads target `[user :profile]` (`:132,178`) or a single dead-letter-seq field
  (`:182`). None reads the top-level user value, which would pull the subindex handles.

**Assessment: correct sizing.** The README permits 100,000 submissions and 100,000 dead letters
per recipient (`README.md:90-91`) and requires page work independent of history
(`:104-105`). Subindexing gives exactly that (`SKILL.md:59`). Inlining the ≤8/≤16 maps avoids
subindex overhead where it would buy nothing. The independent cost test checks this empirically
by comparing reads, iterators, and writes at 240 vs 1040 history entries
(`independent_test.clj:23-30,58-87`).

**Retention.** Subindexing bounds read work, not storage. `:recent`, `:dead-letters`, and
`$$submissions` grow without bound. That is required: `get-submission` must answer for any
past ID, and the protocol never authorizes discarding history (`protocol.clj:117-142`;
`SKILL.md:34`). Storage balance comes from partitioning. User data is hashed over 100M users,
submissions over unbounded IDs. The only skew is one heavy recipient's subindexed lists, which
the workload caps at 100,000 entries each. Do not cap pages by deleting old entries unless a
retention contract is added.

## 5. Client-side distributed queries

**Evidence.** Each read method is one `foreign-select-one`/`foreign-select` on a single keyed
path, and the wrapper does no cross-partition stitching (`module.clj:215-226`):

- `get-devices` / `get-preferences`: `[user :profile :devices|:prefs]`
- `get-submission`: `[sid]`
- page reads: a bounded tail range under `[user :recent|:dead-letters]`

**Assessment: correct.** Each read is one network round trip and one seek (plus ≤100
iterations for pages). A query topology would add an invocation hop without removing a round
trip. `SKILL.md:39` recommends query topologies to collapse multiple client round trips, and
there are none to collapse.

Compare `chat-app`. It uses query topologies where one read fans out across partitions:

- `room-page` joins message → profile (`chat_app/module.clj:417-433`);
- `unread-counts` joins user rooms → room seq/cursor (`:468-478`).

Its single-key lookups stay direct `foreign-select-one` (`:546-553`). Its `get-online-members`
is a genuine client-side distributed loop: chunked member reads alternating with an
`online-filter` query (`:564-580`). That is the pattern to avoid or justify. This reference has
nothing like it.

Minor: `(or (foreign-select-one [… (nil->val {})] users) {})` (`module.clj:216,218`) applies the
default twice. Either the navigator default or the `or` alone suffices.

## 6. Client wrapper barrier (harness-only)

`counter` is one atom per `create-module` result, shared by every wrapper built from it
(`module.clj:191`), so the tests' cross-wrapper barriers are covered
(`notification_test.clj:14-15`; `independent_test.clj:62-63`).

`append` holds `(locking counter …)` around `foreign-append!` + increment (`:199-202`).
`wait-for-processing!` holds the same monitor while blocking in
`wait-for-microbatch-processed-count` (`:228-230`). So a barrier call stalls all other wrappers'
appends until processing catches up. This is conservative but sound for the harness, because
no uncounted append can be in flight while the target count is sampled.

The comparison wrappers use an unlocked atom (`auction_module/module.clj:175,208-212`;
`chat_app/module.clj:514-519,596-597`). This is test-synchronization machinery, not module
design. The README's 400,000/s workload is served by the module, not by one in-process wrapper,
so the lock is not a production throughput finding.

## Disposition

Doc-only. The reference is correct and idiomatic on every audited axis. The optional items (depot
split with `<<subsource`, a `$$task-pos` invariant comment, the redundant `or`) are clarity
edits, not safety or I/O fixes. They do not justify rewriting a validated reference.

Test status for this review: the reference was not changed, so the private suite was not re-run
for this doc-only revision. Recorded historical results in the repo, not re-verified here:

- `test-private/hld_notification_system/independent_full.log:19-20`: 12 tests, 188 assertions,
  0 failures, 0 errors.
- `test-resources/VERIFICATION.log:8-9`: 8 tests, 90 assertions, 0 failures, 0 errors.

`scripts/test_reference_packages.py` does not list this challenge (`PACKAGES`, lines 11-13), so
it does not apply. No `bb run-challenges` or solver run was made.
