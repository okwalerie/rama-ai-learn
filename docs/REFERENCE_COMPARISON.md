# Reference comparison: agent code against Nathan Marz's references

This report compares the Rama backends that coding agents wrote in the October
2026 sweep with the reference implementations Nathan Marz wrote. It treats each
reference as one more contestant and scores both against the same rubric,
[`docs/judge-criteria.md`](judge-criteria.md).

## Summary

- On the design judges we could run (J6 and J7, code checks only), passing agent
  code and the references look the same. Of 44 passing runs, none fails J6 or
  J7. One reference fails J6: the content-moderation module from Marz's book.
- The clearest differences are not design judges. They are contract edges. On
  bank-transfer and auction, the references accept inputs that a production
  backend must reject, and every agent solution we probed rejects most of them.
- Agent code is longer (1.0 to 1.5 times the lines in the 3 hand-read
  challenges) and puts test-only synchronisation state inside the production
  module. The references do not.
- Agents use paths correctly and with more navigators than the references.
  Only 2 of 44 put a condition inside a path, and none uses `selected?` or
  `pred<`. They read, branch and write instead.
- The references are teaching code. They are compact, idiomatic and correct on
  the happy path. They skip validation, and two of them keep dead or lossy
  state. Treat them as a design baseline, not as a correctness baseline.

Every claim in the section on whether the references are good is labelled
measured, inferred or guess.

## What we compared

### Inputs

References are in `challenges/<c>/test-resources/`. For the 3 book challenges
and profile-module we judged the unchanged upstream file, not the wrapper.

Agent solutions come from the run directories on yoga
(`/var/tank/rama/data/challenge-runs/<run>/solver/implementations/`) and waler
(`/mnt/service-data/rama/data/challenge-runs/<run>/...`). We copied them
read-only and changed nothing on either host. We used the final solver tree,
not a checkpoint.

The dashboard snapshot taken on 2 November 2026 lists 50 runs with score 100
across the 8 challenges, not 57. The 7-run gap is unexplained; the snapshot may
have changed since the count of 57 was made. Of the 50:

- 44 were copied and judged
- 6 are from the static "laptop" batch (`dashboard/extra/laptop.json`) and their
  code is not on yoga, waler or this laptop

The missing 6 include the only passing who-to-follow run. So who-to-follow has a
reference row and no agent rows.

Run ids ending in `-relaxed` or `-relaxedb` and ids containing `build-<model>`
are named as the sweep named them. In `X-build-Y` runs, model X planned and
model Y wrote the code.

### Method

1. Hand-read the reference and 3 passing solutions for fanout, bank-transfer
   and auction, with the Rama skill loaded.
2. Wrote contract-edge probes for bank-transfer and auction, and ran them in an
   in-process cluster against each reference and the same 3 solutions.
3. Built code-only detectors for J6 and J7, and ran them on all 44 solutions
   and all 8 references.

The probes are in [`scripts/reference_probes/`](../scripts/reference_probes/).
The detectors are [`scripts/static_judges.bb`](../scripts/static_judges.bb),
with tests in `scripts/static_judges_test.bb`.

## Side by side: bank-transfer-module

The contract: deposits, transfers between users, balances, and full incoming
and outgoing transfer history. It must never double-process an append. It may
assume a transfer id is never appended twice.

| | Reference | sonnet medium | haiku medium (relaxedb) | opus low |
|---|---|---|---|---|
| Lines (non-blank) | 86 | 90 | 113 | 109 |
| Depots | 2: deposits by user, transfers by sender | 1: events by owner | 1: events by user | 1: events by owner |
| PStates | `$$funds {String Long}`; outgoing and incoming as `{String (map String record, subindexed)}` | one `$$accounts` record: balance, outgoing and incoming as subindexed vectors | one `$$accounts` record: `BigInt` balance, subindexed maps | one `$$users` record: balance, subindexed maps |
| Topologies | 1 microbatch | 1 microbatch | 1 microbatch | 1 microbatch |
| Partitioners | `|hash` to recipient | same | same | same |
| Query topologies | none | none | none | none |
| Input validation | none | rejects negative transfers in the topology | rejects non-positive amounts and self-transfers in the topology | rejects negative amounts on the client |

Design notes:

- all 4 use one microbatch topology, so J5 and J6 pass by construction
  (reference `module.clj:18`)
- all 4 return the whole transfer history in one `foreign-select`. The contract
  asks for the full history, so J3 does not apply
- opus says why it uses one depot: a sender's deposits and transfers then
  process in append order on one task (`opus-low module.clj:16-22`). The
  reference uses 2 depots. The probe found no ordering failure (40 of 40
  deposit-then-transfer pairs succeeded), so this is a style difference here

### One PState or three

The PState split is not just style. The reference keeps 3 PStates keyed by
user id on the same partitioning: `$$funds`, `$$outgoing-transfers` and
`$$incoming-transfers`. The Rama skill says to merge data that shares a key and
a partitioner into one PState with a `fixed-keys-schema`, because every PState
partition costs memory on every task (`references/phase-1-plan.md`). The 3
agent solutions follow that rule with one accounts record. The reference does
not. We think Marz split them for teaching clarity, one PState per client read
(guess).

What each design gains or loses:

- reads cost the same. In one record the histories are subindexed fields,
  stored apart from the balance, so reading the balance does not load them
  (inferred)
- atomicity is the same. Both write in one microbatch, so all 3 PStates commit
  together (inferred from Rama semantics)
- the split costs some memory on every task and buys nothing in this contract
  (inferred, not measured)
- the split lets different topologies own different pieces. A PState has one
  owning topology, so balances could stay in a microbatch topology while
  history is written by a stream topology. One record forces one owner. This
  contract does not need that, but a larger system might (inferred)
- separate PStates can be migrated or dropped on their own. A change to one
  record's schema touches the whole value (inferred)

### Swapping one implementation for another

All 4 implementations provide the same protocol through `create-module` and
`:wrap-client`. The test suites already swap them, so a caller that only uses
the protocol can switch implementation (measured). Behaviour still differs at
the edges: the reference accepts a −50 transfer and most agent solutions
reject it (measured, see the probes below).

Inside Rama they do not swap (inferred):

- another module would read them through mirrors, which name specific depots
  and PStates. A module that mirrors the reference's `$$funds` or
  `*transfer-depot` breaks against an agent module with `$$accounts` and one
  events depot
- depot records differ in shape: a `Deposit` record in the reference, event
  maps in the agent solutions
- moving live data means a migration. A new module's PStates start empty, so
  it would need to rebuild state from the old depots' history through an
  adapter for the old record shapes. That assumes the depots still hold their
  full history. We have not checked how to start a topology from the beginning
  of a mirrored depot

To keep implementations swappable, make the protocol the only point of
contact. Anything that mirrors a module's internals is tied to that
implementation.

## Side by side: auction-module

The contract: listings, bids that only replace a bidder's lower bid, expiry
driven by simulated time, and sale, no-sale, won and lost notifications.

| | Reference | sonnet plan, haiku build | haiku low (relaxed) | opus low |
|---|---|---|---|---|
| Lines (non-blank) | 197 | 254 | 253 | 235 |
| Depots | listings by seller, bids by listing's seller, expiry tick | listings and bids by listing id, expiry tick | listings by seller, bids by listing id, tick, test trigger | listings and bids by listing id, tick, test trigger |
| PStates | 4 in stream (user-listings, listing-bidders, listing-top-bid, user-bids); 2 in microbatch (finished, notifications) and scheduler | 2 in stream (listings, seller-listings); 2 in microbatch (notifications, settle-progress) and scheduler | 4 in stream (seller-listings, listings, expiry-index, notifications) and scheduler | 2 in stream (listings, seller-listings); 3 in microbatch (finalized, notifications, triggers) and scheduler |
| Topologies | stream and microbatch | stream and microbatch | stream only | stream and microbatch |
| Query topologies | none | 1 (`settle-progress`, test sync) | none | none |

Design notes:

- the reference writes a bidder's amount with no "only if higher" check
  (`auction_module/module.clj:106`), but updates the top bid only when higher
  (`:109`). The two PStates can disagree, which the probe shows
- the reference writes `$$user-bids` on the bidder's task (`:113`), and no
  client method reads it. It is dead state that costs a partitioner hop and a
  write on every bid
- the reference checks only `$$finished-listings` before accepting a bid
  (`:104`). It never compares the bid time with the expiry time, so a bid can
  win after the auction should have closed
- haiku low writes `$$expiry-index` keyed by expiry timestamp on the listing's
  task (`haiku-low module.clj:84`). No code reads it, and its key does not match
  its partition, so a foreign read would miss it
- haiku low copies the 45-line expiry handler twice, once for the tick and once
  for the test trigger (`:118-163` and `:166-206`)
- notifications go to every bidder in all 4. The contract asks for this, so J1
  does not apply

## Side by side: fanout

The contract: profiles, posts, and a 20-entry timeline page with fixed read
work. Fanout must not write durable state per follower, must be balanced and
fair, and must survive loss of in-memory state.

| | Reference | sonnet medium | sonnet plan, haiku build | opus low |
|---|---|---|---|---|
| Lines (non-blank) | 315 | 346 | 479 | 369 |
| Depots | profiles, posts, tick | profiles, posts, internal fanout depot | profiles, posts, internal fanout depot | one accounts depot, tick |
| PStates | profiles, user-posts (subindexed), pending-fanouts | accounts (posts subindexed), fanout-state | profiles, posts (subindexed), timeline-boot, fanout-pending | accounts, pending (subindexed), fanout-progress, task-epoch |
| In-memory timeline | ring buffer per user, only for users who have read | sorted array per follower | per follower, with ownership token | per follower, capped at 50,000 users per task |
| Topologies | stream for writes, microbatch for fanout | stream and microbatch | stream only (fanout runs in a `loop<-`) | stream and microbatch |
| Query topologies | 4 | 3, one for test sync | 5, one for test sync | 3, one for test sync |
| Chunking | 1,000 followers per social-graph task per microbatch | 500 per chunk, re-appended to depot | 500 per chunk inside one stream event | chunked through `$$pending` |
| Recovery read | at most 300 followees times 10 posts (`fanout/module.clj:280-287`) | every followee, up to 800 posts each (`:303-318`) | every followee, up to 800 posts each (`:460-470`) | every followee, up to 800 posts each (`:318-334`) |

Design notes:

- all 4 meet the "no durable write per follower" rule. Durable writes per post
  grow with followers divided by chunk size, in every design, so J1 should pass
  for all 4 once calibrated
- the reference bounds recovery work and accepts a lossy timeline. A user with
  more than 300 followees loses some of them after a restart, and a followee who
  posted 50 times shows at most 10. The agents read every followee. Their
  recovered timelines are complete, but recovery work grows with followee count.
  The contract relaxes latency during recovery, so neither choice breaks it
- the reference keeps a timeline only for users who have read one. The agents
  keep one for every follower who receives a post. Opus caps this at 50,000
  users per task and evicts an arbitrary entry (`opus-low module.clj:139`). The
  reference and sonnet do not cap it
- the sonnet-plan, haiku-build solution delivers a whole post inside one stream
  event, looping over follower chunks (`:405-430`). A post from an account with
  millions of followers is then one long event. It passed the private fairness
  test, but we have not measured how it behaves at that scale
- all 3 agents add test-only progress state and a polling `wait-for-processing!`
  to the module (sonnet `:247-300`, `:370-380`; sonnet-haiku `:453-458`;
  opus `:224`, `:335-346`). The reference waits on its microbatch count

## Contract-edge probes

We wrote these probes after reading the code. Each probe is a short scenario
run against a 4-task in-process cluster. Results were the same in the single
run we made of each; we did not repeat them.

### Bank-transfer

| Probe | Correct result | Reference | sonnet medium | haiku medium | opus low |
|---|---|---|---|---|---|
| Transfer of −50 from a user holding 100 | rejected; sender 100, recipient 0 | accepted; sender 150, recipient −50 | rejected | rejected | rejected by client |
| Deposit of −100 | rejected | accepted; balance −100 | accepted; balance −100 | ignored; balance 0 | rejected by client |
| Deposit then transfer appended back to back, 40 pairs | 40 succeed | 40 | 40 | 40 | 40 |

A negative transfer in the reference moves money from the recipient to the
sender, and can leave the recipient negative.

### Auction

| Probe | Correct result | Reference | sonnet plan, haiku build | haiku low | opus low |
|---|---|---|---|---|---|
| Bid after expiry time, before the expiry tick | rejected; no sale | accepted; bidder wins | rejected | rejected | rejected |
| Bid on a listing that does not exist | rejected | accepted; shows in bids and highest bid | rejected | rejected | rejected |
| Same bidder bids 20, then 10 | bids show 20 | bids show 10, highest shows 20 | 20 | 20 | 20 |
| Seller bids on own listing | not stated in contract | accepted | accepted | rejected | rejected |
| Bid of −5 | rejected | accepted | rejected | rejected | rejected |

## Judge results

The detectors implement the code half of J6 and J7 only. Neither is
calibrated, so every verdict is uncalibrated detector output. The rules in
`judge-criteria.md` say a judge reports `Unjudged` until it meets its
acceptance rule. Read Pass and Fail below as "the code check says", not as a
calibrated judgment. J1 to J5 and J8 are not implemented and stay `Unjudged`.

How the detectors decide:

- J6 lists non-idempotent writes (`+sum`, `+count`, `+vec-agg`, `AFTER-ELEM`,
  `(term inc)` and similar) and the topology each sits in. It fails a write in a
  stream topology with no PState read before it. It reports `Unjudged` for a
  write it cannot classify (a custom function, a depot re-append) or one that
  follows a read that might be a dedup check
- J7 lists `{:global? true}` PStates and `|global` hops. It fails `|global` on
  per-event work outside a batch block. It reports `Unjudged` for a global PState
  with a collection schema, because key cardinality needs a human declaration

Why we did not build J3: an event-hook J3 needs a human-declared workload per
challenge (which read, which data to grow). The hand reading found only one J3
candidate, the fanout recovery read, and the contract relaxes latency there.

The per-run vector, with no aggregation:

| Run | J1 | J2 | J3 | J4 | J5 | J6 | J7 | J8 | Code behind Fail or Unjudged |
|---|---|---|---|---|---|---|---|---|---|
| `REF:fanout` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `REF:bank-transfer-module` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `REF:auction-module` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `REF:time-series-module-hard` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `REF:content-moderation` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Fail | Pass | Unjudged | J6: line 27, local-transform> AFTER-ELEM |
| `REF:family-tree` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `REF:who-to-follow` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `REF:profile-module` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `auction-module-haiku-low-20261028-relaxed` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `auction-module-opus-high-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `auction-module-opus-low-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `auction-module-opus-medium-build-haiku-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `auction-module-opus-medium-build-sonnet-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `auction-module-pi-glm-5_3-flash-low-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Unjudged | J6: line 139, local-transform> (term %bids-max) |
| `auction-module-pi-muse-spark-1_3-contributor-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `auction-module-sonnet-high-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `auction-module-sonnet-low-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `auction-module-sonnet-medium-build-haiku-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Unjudged | J6: line 114, local-transform> (term (partial max-bid *amount)) |
| `bank-transfer-module-haiku-medium-20261028-relaxedb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `bank-transfer-module-opus-low-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `bank-transfer-module-opus-medium-build-sonnet-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `bank-transfer-module-pi-glm-5_3-flash-20261028c` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `bank-transfer-module-sonnet-high-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `bank-transfer-module-sonnet-medium-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `bank-transfer-module-sonnet-medium-build-haiku-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `bank-transfer-module-sonnet-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `content-moderation-haiku-medium-20261028-relaxedb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `content-moderation-opus-low-20261028b` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `content-moderation-sonnet-medium-20261028b` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `content-moderation-sonnet-medium-20261028-relaxed` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `family-tree-haiku-medium-20261028-relaxedb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `family-tree-opus-low-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `family-tree-opus-low-20261028-relaxed` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `fanout-gpt-6-astra-medium-20261028-relaxed` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `fanout-opus-low-20261028b` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `fanout-opus-low-20261028-relaxed` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `fanout-opus-medium-20261028c` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `fanout-opus-medium-build-haiku-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `fanout-opus-medium-build-sonnet-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `fanout-sonnet-medium-20261028b` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `fanout-sonnet-medium-build-haiku-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Unjudged | J6: line 390, depot-partition-append! (re-append on retry); line 433, local-transform> (term (complete-item *p)) |
| `profile-module-gpt-6_1-sol-medium-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Unjudged | J6: line 55, depot-partition-append! (re-append on retry) |
| `profile-module-gpt-6-astra-low-20261028-relaxed` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `profile-module-haiku-medium-20261028b` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `profile-module-haiku-medium-20261028-relaxed` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `profile-module-opus-low-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | N/A | Pass | Unjudged |  |
| `profile-module-sonnet-medium-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Unjudged | J6: line 85, local-transform> (term (partial merge-fields *fields)) |
| `time-series-module-hard-haiku-medium-20261028-relaxedb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `time-series-module-hard-opus-high-20261028` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `time-series-module-hard-opus-medium-build-sonnet-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `time-series-module-hard-sonnet-medium-20261028-relaxed` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |
| `time-series-module-hard-sonnet-medium-build-haiku-medium-orb` | Unjudged | Unjudged | Unjudged | Unjudged | Unjudged | Pass | Pass | Unjudged |  |

Reading the `Unjudged` J6 rows by hand: `max-bid`, `%bids-max` and
`merge-fields` are idempotent, so a human would label those 3 Pass. The 2 depot
re-appends feed idempotent inserts downstream. The detector is conservative by
design: it says `Unjudged` rather than guess.

Known detector gaps:

- code inside a `defmacro` syntax-quote is not attributed to a topology (opus
  low auction keeps its expiry handler in a macro; those writes are in a
  microbatch topology)
- it judges one file per run, the `module.clj`

### The one J6 failure

The content-moderation reference appends each post to a subindexed vector in a
stream topology, with no dedup (`upstream/nlb/content_moderation.clj:27`).
Stream topologies retry from the failure point, so a retry can append the same
post twice. All 4 passing agent solutions write posts in a microbatch
topology, or as keyed writes. This code is unchanged from the book.

## Path usage

Paths work the same way in all 3 places a module uses them: on plain Clojure
data, on PStates in dataflow (`local-select>`, `local-transform>`), and on
PStates from a client (`foreign-select`). So we treat path usage as one
question. We counted the navigators in every context, in all 44 passing agent
modules and the 8 references, with `scripts/path_usage.bb`. Then we read the
outliers by hand.

The agents use paths correctly and more broadly than the references. We
found no path that gives a wrong result. The gap is in style: agents rarely
put a decision inside a path.

### Navigator adoption

Files that use each navigator at least once (measured):

| Navigator | Agent modules (44) | References (8) |
|---|---|---|
| `nil->val` | 19 | 2 |
| `multi-path` | 16 | 2 |
| `view` | 16 | 3 |
| `term` | 16 | 0 |
| `subselect` | 12 | 1 |
| `NONE-ELEM` | 10 | 3 |
| `sorted-set-range-from` | 9 | 1 |
| `sorted-map-range` | 8 | 1 |
| `submap` | 8 | 0 |
| `AFTER-ELEM` | 6 | 2 |
| `if-path` | 2 | 0 |
| `selected?` | 0 | 1 |
| `pred<` | 0 | 1 |
| `filterer` | 0 | 0 |
| `{:allow-yield? true}` | 24 | 2 |

Most of the extra breadth follows from the agents' larger designs, such as
range reads over the progress state they add for tests. It is not evidence of
better path design on its own.

### What the agents do well

- every module reads through `keypath` to the value it needs. None loads a
  whole PState value and searches it in Clojure (measured by count, checked by
  reading the outliers)
- counters use `(term inc)` with no separate read, which is the cheaper write
  (measured: 6 sites)
- range queries use the sorted range navigators, as the references do. All 6
  time-series solutions read the same way as the reference (measured)
- about half of agent modules mark large reads `{:allow-yield? true}`, against
  2 of 8 references (measured)

### Where the agents fall short

No agent module uses `selected?`, `pred<` or `filterer`, and only 2 use
`if-path` (measured). Agents read a value, branch with `<<if`, then write.
The auction reference keeps the highest bid with one transform:
`(selected? :amount (nil->val 0) (pred< *amount))`. The agent pattern costs an
extra navigation and more code. We do not think it changes results (inferred).

5 of 6 profile-module agents read the whole profile, merge the edits in
Clojure and write the whole map back (measured). The reference writes each
field with `(keypath *user-id *field)`. The profile is one non-subindexed
value, so both cost one read and one write (inferred). The cost is code: agent
modules are 79 to 175 lines against the reference's 40.

Some `(view count)` calls count test-plumbing state. Two scan a whole
partition: `fanout-opus-medium-build-haiku-medium-orb` line 542 and
`fanout-opus-low-20261028-relaxed` line 360 (measured). That work is off the
request path, but it is the same plumbing noted earlier.

A static judge for this would look for a read, an `<<if` and a write to the
same key, where one transform with a path condition would do. We have not
built it.

## Are the references good?

Labels: measured means we ran something and saw it. Inferred means we read the
code and the conclusion follows from Rama semantics, but we did not run it.
Guess means a judgment we have not checked.

### Where the references are good

- They use the right topology types and partitioning. On J6 and J7 the
  references match the best agent code, except content-moderation (measured, by
  the static detectors)
- They are short. Bank-transfer is 86 lines against 90 to 113; auction is 197
  against 235 to 254; fanout is 315 against 346 to 479 (measured)
- They keep test plumbing out of the module. Each reference syncs tests on its
  microbatch count. All 6 agent fanout and auction solutions we read add
  progress PStates, trigger depots or polling queries to the production module
  (measured, by reading)
- The fanout reference bounds the cost of recovery, and keeps in-memory
  timelines only for users who read (inferred)

### Where the references are teaching code

- The bank-transfer reference accepts negative transfers and negative deposits.
  A negative transfer moves money from the recipient to the sender (measured)
- The auction reference accepts bids after the expiry time, bids on listings
  that do not exist, and negative bids. A late bid can win (measured)
- The auction reference lets a lower re-bid replace a higher one in the bid
  list, while the highest-bid PState keeps the old amount (measured)
- The auction reference writes `$$user-bids` and never reads it (measured, by
  reading)
- The content-moderation reference can duplicate a post on stream retry
  (inferred from code and Rama retry semantics; not reproduced)
- The fanout reference's recovered timeline drops followees beyond 300 and
  keeps at most 10 posts per followee (inferred)
- The fanout reference's in-memory cache has no cap or eviction (inferred)
- None of the references validates types or ranges at the client (measured, by
  reading)

### What this means for the comparison

- Use the references as a baseline for design: topology choice, partitioning,
  PState shape and size of code. They hold up there (inferred)
- Do not use them as a baseline for correctness at the contract edges. On every
  edge we probed, the agent solutions did as well or better (measured, for 2
  challenges and 3 solutions each)
- The private test suites did not catch any of the reference gaps above, since
  the references pass them. So "passes the private suite" says nothing about
  input validation (inferred)
- A rubric of 8 design judges misses most of what separates these codebases. A
  contract-edge probe set and a "test plumbing in the module" check would catch
  more (guess)

## Limits

- Hand comparison covers 3 challenges and 3 solutions each. Other models and
  challenges were seen only through the static detectors
- Each probe ran once
- 6 passing runs, including the only who-to-follow pass, were not available
- The detectors are uncalibrated. No labelled set exists yet
- twitter-scale-mastodon, the only Marz codebase proven at scale, was out of
  scope

## Reproduce

Run the detector tests, from the repo root:

```bash
bb scripts/static_judges_test.bb
```

Judge a module:

```bash
bb scripts/static_judges.bb REF:bank=challenges/bank-transfer-module/test-resources/bank_transfer_module/module.clj
```

Count path navigators, or summarise adoption across files whose labels
start `REF` and the rest:

```bash
bb scripts/path_usage.bb --adoption REF:bank=challenges/bank-transfer-module/test-resources/bank_transfer_module/module.clj
```

Run the bank-transfer probes against the reference:

```bash
scripts/reference_probes/run.sh bank-transfer-module challenges/bank-transfer-module/test-resources probe.bank
```
