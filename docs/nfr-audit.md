# NFR test coverage audit — 26 challenges

Benchmark: auction, bank-transfer, fanout, social-graph-and-fanout, chat-app private performance tests. Probe scripts were throwaway and are not committed.

---

# NFR test coverage audit, group A

Challenges: `time-series-module-hard`, `top-users-module`, `who-to-follow`, `timed-notifications`, `unbalanced-social-graph`.

Method: I read each challenge's README, protocol, every file under `test-private/`, the reference implementation and upstream source in `test-resources/`, and `PROVENANCE.md` where present. None of the five has a challenge-level `test/` directory, so every test is private. For the reference implementations I also ran throwaway probes from the scratchpad, without editing the repo. Each probe launched the reference with 4 tasks and counted `:rocks-read`, `:rocks-iterator-read`, `:rocks-commit` and `:task-id` through `rtest/with-event-hook`, the same mechanism the gold-standard tests use. Probe scripts were throwaway and are not committed.

Gold-standard baseline (`auction-module`, `chat-app`):
- Counting hooks around the heavy operations, with bounds that do not depend on input size (for example, a post to a 300-member room must make fewer than 40 writes: `chat-app/.../performance_test_support.clj:64-73`).
- A check on the topology type (stream vs microbatch): `auction-module/.../performance_test_support.clj:56-74`.
- Stream retries injected through `:streaming-complete -> :fail`, then a check for exactly-once effects: `auction .../performance_test_support.clj:21-29,76-99`.
- An `:allow-yield?` check and a check that iterators are used for subindexed reads: `auction :101-125`.
- `update-module!` to wipe in-memory state: `chat-app :183+`.

---

## 1. time-series-module-hard

**Stated NFRs**
- "the implementation must support efficient large-range queries" (`README.md:12`).
- "`get-stats-for-range` must be efficient for all range sizes — from a single minute to multiple years. Queries should not do more disk reads than necessary." (`README.md:18`).

**Implied NFRs** (not in the README)
- Ingestion is exactly-once, because stats are aggregates that cannot be made idempotent. The tests turn this into a microbatch requirement.
- Write amplification per record is bounded (a constant number of granularity rollups).
- Hot-URL skew: all points for one URL land on one task. This is inherent to the domain and acceptable.

**Tested NFRs** (all private, `test-private/time_series_module_hard/performance_test_support.clj`)
| NFR | Mechanism | Lines |
|---|---|---|
| Large-range read cost | 400 points spread over about 42 days. Asserts `:rocks-read < 10`, `:rocks-iterator < 10`, `:rocks-iterator-read < n/4 (=100)` and `pos? n-iterators`, which forces subindexing | 206-241 |
| Served by a query topology | `:topology-event :type :query` present | 231-234 |
| Small-range cost | Exactly 1 `:local-select` for a 1-minute range | 243-249 |
| Exactly-once ingestion | Topology types seen `= #{:microbatch}` | 251-259 |
| Partition balance | `rtest/gen-hashing-index-keys` over 10 URLs × 4. `:depot-read` counts per `:task-id` must all be equal | 261-276 |
| Varied parallelism | `with-module` picks 2 or 4 tasks at random (`lib/harness/src/rama_challenges/harness.clj:66-70`) | — |

**Gaps: wrong designs that pass**
- **Minute + hour + day rollups only, with no month or 30-day level.** The large-range test spans about 42 days (`:208-209`, buckets 63 to 60543). Day buckets therefore give about 42 iterator reads, which is under the threshold of 100. Over the "multiple years" range the README promises, this design reads about 1,000 day buckets per query. It passes.
- A design with a leading-edge prefix-sum or cache bug that only shows up when a range crosses a year boundary. No test has a range longer than about 42 days.

**Test weaknesses**
- The large-range test has only one range size, so there is no check that the bound is independent of size.
- `(= 1 n-selects)` (`:248`) prescribes the *shape* of the query rather than its cost. A design that always issues one `local-select` per granularity, most of them empty, is efficient but fails. Bound disk reads instead.
- `(apply = counts)` on `:depot-read` (`:275`) requires the depot partitioner itself to hash by URL. A `:random` depot followed by `|hash url` produces balanced PState state but fails. Separately, `num-tasks 4` is hard-coded while the launch may use 2 tasks. That is harmless here, because hashing mod 2 stays balanced, but it is fragile.
- Exactly-once is checked by topology type rather than behaviour. This is acceptable for microbatch, since no microbatch fault hook is used.

**Reference-implementation concern**: none. `test-resources/time_series_module_hard/module.clj:51-59,71-93` has four granularity levels (m/h/d/td) and a minimal decomposition.

**Recommended tests**
1. **Multi-year range**: 400 points over about 3 years, each in a distinct day (a step of about 2,600 minutes). Assert `:rocks-iterator-read < n/4` and `< 10` iterators. The m/h/d-only design reads about 400 and fails. The reference reads about 36 months plus edge days. No README change is needed, because `README.md:18` already says "multiple years".
2. **Size-independence**: run the same query-cost capture at 42 days and at 3 years, and assert that the iterator reads differ by less than 2×.
3. Replace `(= 1 n-selects)` with `(<= (+ rocks-read iterator-read) 5)` for a single-minute query.
4. Relax the balance check to per-task `:rocks-commit` over the PState, allowing max/mean ≤ 1.5. Or keep the depot check but bind the key count to `harness/*task-count*`.

**Verdict: ADEQUATE.** It is the only one of the five with gold-style cost, topology-type and balance tests. The main gap is that the stated "multiple years" bound is never exercised, and one assertion over-specifies the shape of the query.

---

## 2. top-users-module

**Stated NFRs**: explicitly none. "They do not measure latency, throughput, or recovery behavior." (`README.md:25-27`; also `test-resources/PROVENANCE.md:12-14`: "No efficiency, throughput, or recovery measurement was made.") Semantics that touch NFRs: purchases "are not deduplicated. Re-appending a purchase counts again" (`README.md:12-13`). That makes each append count **exactly once**, including under internal retry.

**Implied NFRs** (from the upstream gallery module, `test-resources/rama/gallery/top_users_module.clj:124-129,178-181,207-216`)
- Incremental top-N: only the users updated in a microbatch flow to the global partition, through `+top-monotonic`.
- Per-purchase cost is O(1) in the total number of users.
- The `top-users` read is O(500), not a scan of all users.
- Exactly-once sums, which is the reason the source chose microbatch.
- User totals are hash-partitioned; only the 500-entry list is global.

**Tested NFRs** (`test-private/top_users_module/functional_challenge_test.clj`)
- Both 2 and 4 tasks are run (`:76-77`). This is a partitioning sanity check, not a cost check.
- `update-module!` preserves state (`:49-51`). This is lifecycle only.
- There are no event hooks anywhere.

**Gaps: wrong designs that pass**
- **Top-N at query time**: keep only per-user totals, and make `top-users` a query topology that runs `|all`, scans every total and sorts. Each read is O(users).
- **Full re-rank in every microbatch**: rescan all of `$$user-total-spend` in each batch to rebuild the top 500.
- **All totals in one global map** `{Long Long}`: one hot partition, with every purchase routed to one task.
- **Stream topology with `+sum`**: retries double-count. No fault injection is present.

**Test weaknesses**: this is a correctness-only suite. `:threads 2` is fixed.

**Reference-implementation concern**: none. The probe (`tu.clj`) found that processing one purchase costs `{:r 1 :w 1}` at both 500 and 3,000 existing users. The global-list `foreign-select-one` read emitted no rocks events.

**Recommended tests** (all need the README disclaimer at `README.md:25-27` replaced by a minimal NFR statement, for example "`top-users` and per-purchase processing cost must not grow with the number of users; each purchase counts exactly once even if processing is retried")
1. **Per-purchase processing cost**: seed 3,000 users, then capture reads and writes around `purchase!` plus `wait-for-processing!`. Assert fewer than 20 each, and less than a 2× difference between 500 and 3,000 users. This catches the full re-rank.
2. **Read cost**: seed 3,000 users, then capture `top-users`. Assert `rocks-read + iterator-read < 50`. This catches top-N at query time, which does at least 3,000 iterator reads.
3. **Exactly-once under retry**: `failed-streaming` around `purchase!` (the auction pattern), then assert that the total equals the sum. This catches a stream `+sum`. It is a no-op for microbatch implementations, so the test does not prescribe microbatch.
4. **Totals are partitioned**: `:rocks-commit` per `:task-id` over 400 distinct users at 4 tasks. No single task may hold more than 60% of the writes. This catches the single global map.

**Verdict: ABSENT.** NFRs are explicitly disclaimed, and every plausible non-scalable top-N design passes.

---

## 3. who-to-follow

**Stated NFRs**
- "For N accounts with outgoing follows, all accounts must be revisited within ceil(N / 15) + 2 completed refresh calls … computing more per call is allowed." (`README.md:25-27`). This is a *liveness* lower bound, not a cost bound.
- "A user's recommendation list is empty until a refresh computes it. New follow data is reflected by a subsequent refresh, not synchronously" (`README.md:19-21`). This implies precomputed, O(1) reads.
- Repeated follows are idempotent (`README.md:10-11`).

**Implied NFRs** (NLB recommendation-engine post; `test-resources/nlb/who_to_follow.clj`)
- **Bounded work per refresh tick**: a 15-key page per task (`:191-200`), with `yield-if-overtime` (`:223`). This is the core point of the source design.
- Reads are a single keypath lookup.
- Refresh cost does not grow with graph size.
- Hot spots from high out-degree followees.

**Tested NFRs** (`test-private/who_to_follow/functional_challenge_test.clj`)
- Liveness: 12 refreshes for about 137 accounts must reach both account 1 and account 1127 (`:73-80,92`). A cursor wrap-around after a new edge is required (`:98-104`).
- Duplicate follow edge (`:72`).
- `update-module!` state survival (`:81-84`).
- Runs at 2 and 4 tasks (`:106-107`).
- There are no event hooks.

**Gaps: wrong designs that pass**
- **Recommendations computed at read time**: a query topology does the 2-hop fan-in, top 1000 and filter on every `recommendations` call, while `refresh!` is a no-op microbatch. The first assertion happens only *after* 12 refreshes (`:78-84`), and `(= [] (p/recommendations b 999))` (`:91`) is for an account with no follows. The stated "empty until a refresh" behaviour is therefore never checked. The cost is O(Σ followee out-degree) on every read.
- **Full recompute on every refresh**: `|all`, scan all keys, recompute everyone. The README explicitly permits this ("computing more per call is allowed"). Cost per tick is O(E·d) with no bound.
- **Non-subindexed `{Long #{Long}}` follow sets**: the whole set is rewritten on each follow and read in full for the `contains?` checks. Nothing measures this.

**Test weaknesses**
- `:93-97` ("reaches accounts beyond its first 15-key page") is redundant: account 1127 already contains 1 after the first 12 refreshes (`:92`).
- `:threads 2` is fixed.
- There is no check that recommendations are stale *before* a refresh.

**Reference-implementation concern**
- A per-refresh cost bound is fine. The probe (`wtf.clj`) measured one refresh at `{:ir 2224 :r 900 :w 60}` at both N=150 and N=600, so it is size-independent. `recommendations` costs `{:r 1}`.
- There is a latent unbounded read: `[(keypath *following-id) ALL]` (`nlb/who_to_follow.clj:204`) reads each followee's entire out-set, and `+compound` (`:208`) aggregates all candidates in memory. A test with a followee of very large out-degree would push per-refresh cost to O(out-degree). A cap on out-degree is absent from the contract.

**Recommended tests**
1. **Stale before refresh** (correctness of a stated property; no README change): follow edges, call `recommendations` before any `refresh!` and assert `[]`. Then add an edge after a refresh and assert the old list until the next refresh. This catches read-time computation.
2. **Read cost**: capture `recommendations` for an account following 50 accounts with 50 follows each. Assert `reads < 10`.
3. **Per-refresh cost independent of N**: capture one `refresh!` at N=150 and at N=600 (5 follows each). Assert the ratio is below 1.5. The reference passes. This catches full recompute, but it **requires a README change**, because "computing more per call is allowed" (`README.md:27`) currently permits full recompute.
4. Optionally, subindexing: `pos?` iterator reads during a refresh.

**Verdict: PARTIAL.** The progress (liveness) bound is tested. Precomputed reads and bounded per-tick work, the NFRs the source is built around, are untested, and the README permits the unbounded design.

---

## 4. timed-notifications

**Stated NFRs**
- "A not-yet-due item is not delivered early" (`README.md:15-16`).
- Timer-driven production (`README.md:18-19`).
- Explicit disclaimer: "They do not measure latency or prove worker restart, replay, or exactly-once delivery behavior." (`README.md:25-26`; `test-resources/PROVENANCE.md:116-117`).

**Implied NFRs** (the source post is titled *Fault-tolerant timed notifications*; `test-resources/nlb/timed_notifications.clj` uses `TopologyScheduler`)
- **Exactly-once delivery under retry.** This is the headline property of the source.
- **Tick cost proportional to due items, not all pending items**, through a time-sorted index.
- Pending schedules are durable.
- Per-account partitioning.
- A stream-class latency of about 1 s.

**Tested NFRs** (`test-private/timed_notifications/functional_challenge_test.clj`)
- Boundary correctness under sim time (`:72-81`).
- **Durability of the pending schedule across `update-module!`**: "late" and "later" are scheduled before the update and delivered after it (`:82-96`). This kills designs that hold the schedule in memory, so it is a genuine NFR test.
- Repeated tick causes no redelivery (`:97-100`).
- Runs at 2 and 4 tasks.
- There are no event hooks.

**Gaps: wrong designs that pass**
- **A tick that does a full scan**: store `{account {time [posts]}}` and, on each tick, `|all` and iterate every pending item. That is O(total pending) per tick, with ticks every 1 s in production. It passes.
- **No idempotency under stream retry**: any stream design that appends to the feed without a dedupe key. The reference is one of them (see below).
- **Microbatch delivery**, which adds latency. Latency class is disclaimed and unchecked. Low priority.

**Test weaknesses**: every check is on the result; there is no measure of cost or of retry behaviour.

**Reference-implementation concern (confirmed by probe `tn/probe.clj`)**: injecting one `:streaming-complete -> :fail` around `schedule-post!` produced a feed of **`["x" "x"]`, a duplicate delivery**. The source runs `.scheduleItem` in a stream topology (`nlb/timed_notifications.clj:163-164`), keyed by a record with no identity (`ScheduledPost [id time-millis post]` at `:147`, where `id` is the account). On retry the item is scheduled twice. A retry injected on the tick path was fine (`["y"]`). So an exactly-once test would **fail the current reference**. Fixing it needs one of:
- A client-generated UUID in the depot record, deduplicated at schedule time.
- Scheduling from a microbatch.

Tick cost is fine: 2,000 far-future items gave no reads on an idle tick, and 20 due items gave `{:ir 40 :r 60 :w 60}`, so cost is proportional to due items.

**Recommended tests**
1. **Tick cost independent of pending volume**: schedule 2,000 items far in the future and capture one idle `tick!`. Assert `reads < 50`. Then make 20 items due and assert `reads < 200`. This catches the full-scan tick, and the reference passes. No README change strictly needed ("scheduled delivery"), but a one-line "tick work must be proportional to due items" statement would make it fair.
2. **Exactly-once under schedule retry**: `failed-streaming` around `schedule-post!`, advance time, tick, and assert that the feed has exactly one item. This **requires** removing the README disclaimer at `:25-26` **and fixing the reference** as described above.
3. **Exactly-once under tick retry**: `failed-streaming` around `tick!`, then assert no duplicates. The reference passes.

**Verdict: PARTIAL.** Durability across a module update is tested. Exactly-once delivery and per-tick cost, the source's defining NFRs, are untested, and the reference violates exactly-once under stream retry.

---

## 5. unbalanced-social-graph

**Stated NFRs** (the README is *entirely* NFR)
- "CPU usage across the cluster's tasks must be even, and the total disk work must be **near-optimal** — within a small constant factor of the least work any design could do … must hold for **every** distribution" (`README.md:47-52`).
- "Fanout runs across every task … must stay balanced no matter whose post it is processing" (`README.md:40-43`).
- Load figures: at most 5,000 followees per account, about 7k posts/s, about 100 follows/s (`README.md:54-55`).
- Idempotent follow and unfollow (`protocol.clj:108-111`).

**Implied NFRs**
- **Small accounts must *not* be scattered.** Near-optimal work for the 93.6% tail means that a post by an account with under 100 followers touches one task (`README.md:13`).
- Per-follow write cost is O(1) regardless of follower count.
- Follower reads are subindexed and yield (`:allow-yield?`).
- Unfollow cleans up.

**Tested NFRs** (`test-private/unbalanced_social_graph/functional_test_support.clj`)
- None. The file's own docstring says the tests "make no assumption about the partitioning strategy, so any correct implementation passes" (`:2-4`).
- The only tests are functional: both directions, idempotency, and unfollow (`:17-62`).
- The launch uses 2 or 4 random tasks (`:13-15`).
- `deps.edn` defines a `:test-harness` alias pointing at a `test-harness/` directory that does not exist.

**Gaps: wrong designs that pass**
- **Plain `{target #{follower}}` hashed by target.** The whole celebrity follower set sits on one task, and fanout is completely unbalanced. This is the naive design the challenge exists to reject, and it passes.
- **Scatter every account across all tasks** (for example `|hash [target follower]`). This is balanced, but every post by a tiny account touches every task: task-count × the work under Example A. It passes.
- **Non-subindexed follower set.** Each follow rewrites a million-element set. It passes.
- **Balance by a static threshold tuned to one distribution.** It passes; nothing varies the distribution.

**Test weaknesses**: there are no cost or balance tests at all. The graphs used are at most 5 followers.

**Reference-implementation concern**: none for balance. The probe (`usg.clj`, 4 tasks) gave:
- Celebrity with 4,400 followers: `get-followers` iterator reads per task were `{0 1106, 1 1106, 2 1106, 3 1106}`, perfectly even.
- A 30-follower account: iterator reads on task 0 only.
- One follow into the celebrity: about 16 reads and 6 writes, bounded.

Two further observations:
- Per-task write commits are skewed to the celebrity's home task (13,188 against about 4,400), because of the follower-task index and control. So test read distribution, not write distribution.
- Minor: unfollow never removes the `$$partitioned-follower-tasks` entry (`test-resources/.../module.clj:113-117`), so that index grows without bound. It is harmless for current tests.
- Seeding 4,400 follows with `:ack` took about 32 s.

**Recommended tests** (no README change needed; the property is already stated)
1. **Celebrity balance**: create `1100 × tasks` followers of one account. Capture `get-followers` and group `:rocks-iterator-read` by `:task-id`. Assert that every task has more than 0 and that max/mean ≤ 1.5. This catches the naive hash-by-target design.
2. **Small-account locality**: create a 30-follower account and capture `get-followers`. Assert that iterator reads touch exactly one `:task-id` and total ≤ 2 × 30. This catches scatter-everything.
3. **Per-follow cost is independent of follower count**: capture one `follow!` into the celebrity. Assert `reads < 40` and `writes < 20`. This catches non-subindexed sets.
4. **Yielding reads**: the `:local-select` for a large follower read has `:allow-yield? true` (the auction mechanism).

Caveat: the fanout consumer reads PStates directly (`README.md:57-59`), but tests can only go through the protocol, so `get-followers` read distribution is a proxy for fanout balance. It is the best signal available without fixing PState names, which the README deliberately leaves free.

**Verdict: ABSENT.** The README is a pure NFR specification, and none of it is tested. The naive single-partition design passes.

---

# NFR coverage audit: batch B (NLB and demo-gallery ports)

Challenges: collaborative-document-editor, content-moderation, family-tree,
music-catalog-migration, profile-module, rest-api-integration-module.

## Method

- Gold standard read: `challenges/auction-module/test-private/auction_module/performance_test_support.clj`
  (stream-type check via `:topology-event :type`, `:streaming-complete -> :fail`
  retry injection with exactly-once assertions, `:rocks-iterator-read` to prove
  subindexing, `:local-select :allow-yield?`, sim time) and
  `challenges/chat-app/test-private/chat_app/performance_test_support.clj`
  (write-batch-count bounds independent of room size, read bounds independent
  of history, `update-module!` durability).
- Read every file in each assigned challenge (README, protocol, all
  test-private files, EVIDENCE, PROVENANCE, adapter and upstream reference),
  plus `lib/harness/src/rama_challenges/harness.clj` and `docs/atlas/REFERENCES.md`.
- None of the six challenges has a public `test/` directory. The three
  gallery ports have only `src/*/module_presence_test.clj` (factory shape).
  Every behavioural assertion is private.
- The mutation controls in `docs/atlas/REFERENCE_EVIDENCE.md:29-37` for all six
  are functional (altered output). None is an NFR mutation.
- I ran short probes against each reference (scratch scripts in
  throwaway probe scripts, not committed). They used the challenge's
  `:test-private-harness` classpath, `rtest/with-event-hook`, and the auction
  `failed-streaming` pattern. The results are quoted below as "Probe".

Event-hook fact relevant to every recommendation: `:rocks-read` and
`:rocks-iterator-read` carry `{:name "$$pstate" :task-id :topology}` but no
byte size. A non-subindexed whole-value read therefore counts as one read. To
detect "loads the whole collection", use the auction mechanism: require
`pos?` iterator reads on the named PState, or bound reads on a structure that
must be subindexed. Raw read counts alone do not catch this.

---

## 1. collaborative-document-editor

**Stated NFRs**
- README:29-30: "No latency, maximum document size, or edit-history compaction
  guarantee is made: retained history and transformation work grow with edit
  count." This explicitly disclaims the NFRs.
- README:17: "Edits on distinct IDs are independent." This is a weak
  partitioning hint.
- README:34-35: writes are visible after `wait-for-processing!`. No
  visibility class is required.

**Implied NFRs (source blog is titled "Massively scalable collaborative text editor backend")**
- (implied) Real-time collaboration needs low-latency visibility (stream).
- (implied) Exactly-once edit application. An edit applied twice is document
  corruption.
- (implied) Transform cost is O(staleness), not O(history). The server reads
  only the missed edits (`srange version latest`) on a subindexed edit log.
- (implied) Per-document partitioning, so documents scale horizontally.

**Tested NFRs**
- None. `test-private/collaborative_document_editor/challenge_test.clj:8,32`
  forces tasks 2 and 4 with two document IDs. That is a functional
  multi-partition check, not a cost check.
- The upstream direct test launches 4 tasks
  (`test-resources/upstream/nlb/collaborative_document_editor_test.clj:91`).
  It is functional only.

**Gaps (plausible wrong designs that pass every current test)**
- **Non-subindexed history:** `{Long [Edit]}` as a plain vector. Every edit
  reads and rewrites the whole history, so it is O(history) per edit. The
  tests use at most 5 edits per document.
- **Doc recomputed on read:** `doc+version` replays every stored edit from
  scratch on each query, so a read costs O(history).
- **Microbatch:** passes, because the harness barrier hides latency. The
  README disclaims latency.
- **Global partition:** `|global` for all documents passes.
- **No retry idempotency:** any design, including the reference, passes.

**Test weakness**
- The histories are tiny (at most 5 operations), so no test is sensitive to
  cost. There is no event hook, no fault injection and no `update-module!`.

**Reference-impl concern (confirmed by probe)**
- The reference is **not idempotent under stream retry**.
  `test-resources/upstream/nlb/collaborative_document_editor.clj:103-121`
  reads `latest-version`, then appends to `$$edits` and overwrites `$$docs`.
  On retry, the edit's own first application is treated as a "missed" edit.
  Probe: `failed-streaming` around `(edit! (Edit 1 0 0 (AddText "abc")))`
  returned `{:doc "abcabc" :version 2}`.
- An exactly-once test would require fixing the reference. Options are
  microbatch, or storing an edit or client-op ID to dedupe. It would also
  require a README line on retry semantics.
- The reference cost is good. Probe after 300 edits: head edit
  `{:read 5 :writes 3}`, 5-stale edit `{:read 6 :iter 5 :writes 3}`, query
  `{:read 2}`.

**Recommended tests**
1. **Transform cost is O(staleness), not O(history).** Apply 300 edits to one
   document. Then submit an edit 5 versions stale, captured with
   `:rocks-iterator-read` filtered to the edit-log PState. Assert that
   iterator reads are `pos?` (the log is subindexed) and fewer than 20, and
   that total reads are under 30. This catches the plain-vector history and
   the full-history scan. It needs no README change (the reference passes).
2. **Read cost independent of history.** After 300 edits, `doc+version`
   total reads must be under 10. This catches recompute-on-read. The
   reference passes. The README disclaimer "transformation work grow[s] with
   edit count" would need softening to "grows with staleness".
3. **Exactly-once under stream retry.** Wrap one AddText in
   `failed-streaming` and assert the doc text appears once and the version
   is 1. This **requires reference and README changes**.
4. Optional: a `:topology-event :type :stream` check, if the README adopts a
   "collaborators see edits in milliseconds" requirement.

**Verdict: ABSENT.** The README disclaims every NFR. The tests have no cost,
retry or latency checks, and the reference would fail the most important one
(retry duplication).

---

## 2. content-moderation

**Stated NFRs**
- README:21-23: "Large mute sets and long runs of hidden posts increase query
  work; the reference has no fixed-cost page guarantee." This disclaims page
  cost.
- README:17-18: mute and unmute apply to old and new posts. This forces
  read-time filtering to be *correct*, but says nothing about cost.
- Protocol:10 says "reads never wait". This rules out read-time barriers, not
  cost.

**Implied NFRs (source blog: "Personalized content moderation in 60 LOC")**
- (implied) Post and mute writes are O(1). Muting must not rewrite or
  re-materialize the reader's timeline.
- (implied) Page cost is O(scanned entries). The feed is subindexed and read
  by range, and each mute check is a point lookup in a subindexed set, not a
  full mute-set load.
- (implied) Timelines are partitioned by recipient, and the query runs on the
  recipient's task.
- (implied) Exactly-once post delivery (no duplicate timeline entries).

**Tested NFRs**
- None. `test-private/content_moderation/test_support.clj:55-65` ("a long
  hidden run is traversed") checks *results* over a 12-post hidden run, not
  cost. Tasks are 2 and 4 with threads fixed at 2 (`:12`).

**Gaps (wrong designs that pass)**
- **Write-time filtered timeline:** keep a per-reader "visible feed" and
  rebuild it on every mute or unmute, which is O(history) per mute. It stays
  correct for old and new posts, and the tests have about 20 posts.
- **Non-subindexed feed or mute set:** `{Long [Post]}` or `{Long #{Long}}`.
  Each page loads the whole feed, and each post check loads the whole mute
  set.
- **Client-side filtering:** `get-posts` pulls the full feed with
  `foreign-select` and filters in the client.
- **No retry dedupe:** passes, and the reference has the same behaviour.

**Test weakness**
- Only results are checked. There are no cost bounds, no iterator-read
  proof, and no durability (`update-module!`) check.

**Reference-impl concern**
- Stream `AFTER-ELEM` append (`upstream/nlb/content_moderation.clj:27`) is
  **at-least-once**. Probe: one `post!` under `failed-streaming` produced the
  post twice in `get-posts`.
- The README (`:16-17`, "preserving duplicate appends") makes a retry
  duplicate indistinguishable from a legitimate duplicate append. An
  exactly-once test would need a README change and either a reference change
  (microbatch) or a dedupe key.
- Page cost is proportional to entries scanned, which is acceptable. Probe
  on 200 posts: a 10-post page with no hidden posts cost
  `{:read 33 :iter 57}`, and a page with 4 of 5 posts hidden cost
  `{:read 129 :iter 231}`. The upstream adapter must clamp `offset > count`
  because `srange` throws (`test-resources/content_moderation/module.clj:28-31`).
  That is a functional wart, not an NFR one.

**Recommended tests**
1. **Page read cost independent of feed length.** Use 300 posts to one
   reader with no mutes. Capture `get-posts reader 250 10` and assert
   `pos?` `:rocks-iterator-read` on the feed PState and total reads under 80
   (probe baseline is about 90 total for 10 posts over 200 posts, so tune to
   about 2x). This catches the non-subindexed feed and client-side scan. The
   reference passes.
2. **Mute is O(1).** With a 300-post feed, capture `mute!` plus
   `wait-for-processing!` and assert `write-batch-count` under 5. This
   catches write-time timeline rebuilds. The reference passes.
3. **Mute set subindexed.** Give the reader 200 mutes, then page. Assert
   reads stay bounded by the scanned count plus a constant, and that
   `:rocks-iterator-read` or `:rocks-read` counts on the mute PState are no
   more than the scanned entries (one point lookup each). The reference
   passes.
4. Optional: exactly-once under retry. This needs README and reference
   changes (see above).

**Verdict: ABSENT.** The README disclaims page cost, and the tests assert
only results. The central design choice (read-time filtering with bounded
write cost) is untested.

---

## 3. family-tree

**Stated NFRs**
- README:25-28: "descendant traversal expands paths, so a branching graph can
  cause work exponential in depth. This package makes no wall-clock latency
  or unbounded-depth guarantee." This disclaims descendants cost.
- README:13: "Repeated reachability is deduplicated." This is a *result*
  requirement for ancestors, not a work requirement.

**Implied NFRs (source: NLB "Graphs" blog)**
- (implied) Traversal runs inside the cluster as one distributed query
  (`|hash` per hop), not as N client round trips.
- (implied) Ancestor traversal work is bounded by *distinct* nodes. The
  visited set in `$$ancestors$$` prevents path explosion under pedigree
  collapse.
- (implied) The graph is partitioned by person ID, not held on one task.
- (implied) Child-link writes are idempotent under retry (`NONE-ELEM` into a
  set).

**Tested NFRs**
- **Durability across `update-module!`:**
  `test-private/family_tree/challenge_test.clj:40-49`. After the update, a
  fresh client reads the retained tree and writes succeed. This is the only
  NFR test in the batch that matches a gold-standard mechanism (chat-app
  fault tolerance).
- Multi-partition: tasks 2 and 4 (`:51-53`) with a 5-person graph whose IDs
  spread across tasks.

**Gaps (wrong designs that pass)**
- **No visited-set dedupe in ancestors:** the traversal explores every path
  and dedupes only in the final `+set-agg`. The test graph (a diamond:
  root <- p1, p2 <- child) has 2 paths, so exponential path blow-up is
  invisible because the result set is still correct.
- **Client-side traversal:** repeated `foreign-select` hops from the client
  pass.
- **Materialized ancestor sets at write time:** copying the parents'
  ancestor sets into each person costs O(ancestors) writes per add. This is
  arguably a valid trade-off, but it is untested either way.
- **Children stored as a vector (`AFTER-ELEM`) or a `+count` aggregate:**
  a stream retry double-counts descendants. This passes without fault
  injection.

**Test weakness**
- The graph is too small to distinguish path-work from node-work, and there
  is no retry injection. Threads are fixed at 2.

**Reference-impl concern**
- None for the recommended tests. Probe: a stream retry on `add-person!`
  gave the correct `{0 1, 1 0}` (idempotent). For a 12-generation
  pedigree-collapse ladder (4096 ancestor paths, 24 distinct ancestors),
  `ancestors` cost `{:read 13}`.
- `:children #{UUID}` is not subindexed
  (`upstream/nlb/family_tree.clj:22`). A person with many children rewrites
  the whole set per add. This is out of scope given the README's bounded
  family size.

**Recommended tests**
1. **Ancestor work is bounded by distinct nodes.** Build a pedigree-collapse
   ladder: each generation of 2 people are both children of the previous
   pair, 12 generations. That gives 24 distinct ancestors and 2^12 paths.
   Capture `ancestors leaf 20` and assert total reads under 100. This
   catches traversal without a visited set. The reference passes (13 reads).
2. **Stream retry idempotency.** Wrap `add-person!` in `failed-streaming`
   and assert `descendants-count parent 2 = {0 1, 1 0}`. This catches
   vector or counter children storage. The reference passes.
3. Optional: `:topology-event :type` includes `:query`, which proves
   server-side traversal. A client-side traversal emits no query-topology
   events (it would use only `foreign-select`).

**Verdict: PARTIAL.** Durability across a module update is tested. Traversal
cost and retry idempotency are not, and the test graph is too small to
expose path explosion.

---

## 4. music-catalog-migration

**Stated NFRs**
- README:4-6, 35: "Use one module lifecycle ... Keep the client protocol
  usable before and after the update ... State must survive the update."
- README:27: "Repeated updates must preserve migrated and newly written
  albums."
- The processing barrier must work for both generations (README:34).

**Implied NFRs (Rama migrations: `migrated` schema)**
- (implied) The update is **online and instant**. Old values are readable in
  the new shape immediately after `update-module!`, without waiting for a
  reprocessing pass. Rama migrates on read and in the background.
- (implied) The migration does not depend on replaying the depot. Depots may
  be trimmed, and replay costs O(all history).
- (implied) The migration function is idempotent (the first-element
  `string?` guard) and keyed by a stable migration ID.

**Tested NFRs**
- **State survives the update, and the client works across generations:**
  `test-private/music_catalog_migration/migration_test.clj:21-30`.
- **Old albums read migrated immediately after `update-module!`**, with no
  barrier before the reads at `:22-30`. This is a de facto online-migration
  check, but only for 2 albums.
- **Repeated update of the same generation preserves data:** `:49-55`.

**Gaps (wrong designs that pass)**
- **Depot replay:** module B adds a new PState and topology that re-consumes
  `*albums-depot` from the start and re-parses everything. With 2 albums
  the new topology may catch up before the first read, and the client's
  `wait-for-processing!` can wait on the new topology.
- **Parse on read:** module B keeps strings at rest and parses in a query
  topology or client read path, never migrating the stored data. This is
  functionally identical from the tests' view.
- **No `migrated`, lenient schema:** the songs schema is widened to
  `Object`, and parsing happens at read time only.

**Test weakness**
- The data size (2-3 albums) makes the "reads immediately after update"
  check race-prone rather than decisive. No cost or event-hook checks are
  made, and writes are not interleaved with the update.

**Reference-impl concern**
- None. The reference uses `migrated` with a guard (`rama/gallery/migrations_music_catalog_modules.clj:31-33`),
  so reads are migrated on access from the moment of update.

**Recommended tests**
1. **Online migration at scale.** Before the update, write 500 albums across
   50 artists. Call `update-module!`, then immediately (with no
   `wait-for-processing!`) read all 500 and assert every one is structured.
   A depot-replay design shows nil or string values, or is far slower. The
   reference passes. This needs no README change beyond the existing "state
   must survive"; optionally add "old albums are readable in the new shape
   as soon as the update completes".
2. **No reprocessing of history on update.** Capture `:topology-event`
   during and for a bounded window after `update-module!` (500 albums).
   Assert no microbatch or stream topology event processes more than the
   newly appended records. For example, count `:rocks-commit`
   write-batch-count on topologies other than the original `"albums"`
   (expected 0). This is inference: confirm which events Rama's background
   migration emits before fixing a bound.
3. Distinguishing parse-on-read from at-rest migration is hard black-box.
   Skip it unless the README requires the stored schema.

**Verdict: PARTIAL.** Durability and cross-generation readability are tested.
The online, non-replay nature of the migration (the point of the gallery
example) is only incidentally checked on 2 albums.

---

## 5. profile-module

**Stated NFRs**
- README:8-10: the first UUID for a username wins, and a different UUID gets
  nil. This is a uniqueness invariant.
- README:9-10 and protocol:3-4, 9-11: the same UUID "generates a new ID each
  time, so retrying registration does not return a stable ID". This
  **explicitly enshrines non-idempotent retry**.
- README:23-24: "Keep profile and registration state in the module."

**Implied NFRs**
- (implied) Username uniqueness is **linearizable under concurrency**. The
  check-and-claim must run on the task that owns the username (partition by
  username), not as a client check-then-write.
- (implied) Registration returns the ID synchronously. This needs a stream
  topology with `ack-return>`, not microbatch polling.
- (implied) The registration UUID is a retry marker. In the upstream
  gallery's intent, a lost-ack retry should succeed without creating a
  duplicate account.
- (implied) IDs come from a distributed unique generator
  (`ModuleUniqueIdPState`), not a global counter hot spot.

**Tested NFRs**
- Uniqueness is checked only sequentially:
  `test-private/profile_module/profile_test.clj:21`.
- `:22-28` **asserts `(not= id retry-id)`**. The private test fails the
  idempotent, fault-tolerant design, which returns the same ID for the same
  UUID.

**Gaps (wrong designs that pass)**
- **Client-side check-then-append:** `foreign-select` the username and, if
  it is absent, append. Two concurrent registrations can both win.
  Sequential tests cannot see this.
- **Registration depot partitioned by UUID or random,** with the check on a
  different task from the claim. The same race applies.
- **Global counter on one task (`|global`) for IDs:** passes.
- **Microbatch plus client polling for the ID:** passes, because the harness
  hides latency.

**Test weakness**
- There is no concurrency and no retry injection. The one retry-related
  assertion rewards the less fault-tolerant behaviour.

**Reference-impl concern**
- The reference generates a new ID on same-UUID retry and overwrites
  `$$username->registration` with it (`rama/gallery/profile_module.clj:26-37`).
  Probe: `register!` under `failed-streaming` returned ID 1 (not the
  generator's first), which is consistent with the first attempt's ID being
  consumed and orphaned. I did not verify the orphan profile directly.
- A test requiring a stable ID on retry would **fail the reference and
  contradict README:9-10**. The concurrency test does not have this problem.
  Probe: 20 concurrent `register!` calls for the same username with
  distinct UUIDs gave exactly 1 winner. Registration cost was
  `{:read 3 :writes 2}` on `:stream`.

**Recommended tests**
1. **Concurrent uniqueness.** Run 20 futures registering the same username
   with distinct UUIDs. Assert exactly one non-nil ID, and that
   `get-profile` of the winner has that username. This catches client-side
   check-then-write. The reference passes. No README change is needed,
   because README:8-9 already states "first UUID wins".
2. **Synchronous ID via stream.** Capture `:topology-event :type` during
   `register!` and assert `:stream`. This is optional: it needs a README line
   such as "register! returns the ID from the same call" (it already returns
   an ID, so microbatch-plus-poll is the only design this excludes).
3. **Decide the retry contract.** Either delete the `(not= id retry-id)`
   assertion (`profile_test.clj:26`) and the README sentence, or keep them.
   If the repository wants retry idempotency as an NFR, change the reference
   to return the existing `:user-id` when `curr-uuid = uuid` and add a
   `failed-streaming` test. As shipped, the test teaches the opposite of the
   Rama idiom the source demonstrates.

**Verdict: ABSENT.** There is no concurrency or retry test. The single
retry-related assertion enforces non-idempotency.

---

## 6. rest-api-integration-module

**Stated NFRs**
- README:4-5: "`fetch!` returns `nil` without waiting for the response."
- README:8: "Close HTTP resources when their owning client or module
  lifecycle ends."
- README:13-14: "Transport exceptions instead fail asynchronous processing
  and cause a retry."
- README:16: no timeout, retry limit, size or concurrency bound.

**Implied NFRs (demo-gallery: `completable-future>` + `TaskGlobalObject`)**
- (implied) HTTP I/O must not block the task thread. Other work on the task
  (PState reads, other topologies) proceeds while a request is in flight.
- (implied) One HTTP client per task, via a task-global created in
  `prepareForTask` and closed in `close`. There is no per-event client and no
  JVM-static client.
- (implied) Latest-completed-wins per URL (hash-by URL keeps it on one task).

**Tested NFRs**
- **Non-blocking `fetch!`:** `test-private/rest_api_integration_module/rest_test.clj:54-57`
  asserts `fetch!` on a 5 s endpoint returns in under 3000 ms. **This is
  satisfied client-side.** The reference adapter wraps `foreign-append!` in a
  `future` (`test-resources/rest_api_integration_module/module.clj:15-17`),
  so any module design passes, including a topology that blocks its task
  thread on the HTTP call.
- **Status versus transport:** a 503 body is stored (`:44-47`). This is
  functional.

**Gaps (wrong designs that pass)**
- **Blocking I/O on the task thread:** `(slurp url)` or `@(http-get ...)`
  inside the topology. The test issues only one slow request, and the
  client-side `future` hides the block.
- **Per-event `AsyncHttpClient` never closed, or a JVM-global client never
  closed:** no lifecycle assertion exists, despite README:8.
- **Transport failures swallowed** (store nil or error text, no retry):
  untested. PROVENANCE:10 says a transport failure was deliberately not
  induced.
- **Fixture caveat:** `HttpServer/create` without `.setExecutor`
  (`rest_test.clj:10`) handles requests on one dispatcher thread. Any future
  concurrency test must set an executor, or the fixture itself serializes
  requests (confirmed in my first probe).

**Test weakness**
- The only NFR assertion measures the adapter, not the module. Tasks are 2
  and 4 with threads 2. There is no thread or resource accounting.

**Reference-impl concern (confirmed by probe)**
- The reference keeps the task thread free. Probe, 1 task and 1 thread: a
  PState read during a pending 5 s fetch took about 52 ms.
- However, stream records on the same depot partition are processed in
  order. A fast URL appended after a slow URL on the same partition became
  visible only after the slow one completed (about 4.9 s). A
  "slow URL must not delay other URLs" test would therefore **fail the
  reference** on a shared partition. Scope such a test to task-thread
  responsiveness only.
- Lifecycle: `AsyncHttpClient*` threads went to 0 about 6 s after IPC close
  (netty graceful shutdown). A 1 s check showed lingering threads, so a
  lifecycle test must poll with a generous timeout.

**Recommended tests**
1. **Task thread is not blocked by in-flight HTTP.** Launch with tasks 1 and
   threads 1, using a fixture with a thread-pool executor. `fetch!` the 5 s
   URL, then time a `get-body` on another URL and assert under 1000 ms. This
   catches blocking I/O in the topology. The reference passes (52 ms). The
   README already says "without waiting"; add "HTTP I/O must not block
   module processing".
2. **Resource lifecycle.** Count live threads whose name matches
   `AsyncHttpClient` before launch, after 30 fetches (assert the count is
   bounded, for example no more than 4 per task, which catches a per-event
   client), and after IPC close (poll up to 20 s and assert a return to
   baseline, which catches a never-closed static client). The reference
   passes. This is keyed to the AsyncHttpClient dependency pinned in
   `deps.edn`, or generalize by diffing the thread set.
3. **Transport failure retries to success.** Add a fixture endpoint that
   closes the exchange without a response on the first call and serves 200
   on the second. `fetch!`, wait, then assert the body is stored. This is
   bounded because the second call succeeds. It catches swallowed transport
   errors. The reference passes by design (the `completable-future>` failure
   triggers a stream retry).

**Verdict: ABSENT.** Each stated NFR (non-blocking, resource lifecycle,
transport retry) is either untested or tested only through the adapter's
client-side `future`, so the module design is never exercised.

---

## Cross-cutting observations

1. **No challenge in this batch uses `rtest/with-event-hook`.** All
   NFR-relevant assertions are result-only. The only gold-standard mechanism
   present is `update-module!` durability (family-tree, music-catalog).
2. **The READMEs for the three NLB ports explicitly disclaim cost**
   (collab:29-30, content-moderation:21-23, family-tree:25-28). Adding cost
   tests requires deleting or softening those disclaimers. In each case the
   reference already meets a reasonable bound, so only the README changes.
3. **Stream at-least-once duplicates are latent in two references**
   (collab editor: corrupt text; content moderation: duplicate post). The
   profile-module test actively enforces non-idempotent retry. Any
   exactly-once test for these needs reference changes.
4. **Fixed `:threads 2`** everywhere, and task counts of exactly 2 and 4
   (hard-coded via `with-redefs rand-nth` or literal). This is fine for
   partitioning, but none of the tests exercises skew or hot keys.

---

# NFR test coverage audit: hld-ad-click-aggregation, hld-metrics-pipeline, hld-rate-limiter, hld-url-shortener, hld-search-autocomplete

Scope: I read each challenge's README, protocol, all private tests and test support, the reference implementation, and the development notes under `test-resources/`. I did not run any tests. All paths are relative to `challenges/`.

## Cross-cutting findings

Six findings apply to all five challenges:

1. **No public NFR tests.** None of the five has a `test/` directory; each `:test` alias points at `implementations/<name>/test`. Every NFR check is private, so the solver learns the NFRs only from the README. Three READMEs describe the bounds only in words: ad-click, metrics and autocomplete. The other two publish numeric budgets: rate-limiter and url-shortener.
2. **No fault injection.** No suite uses the gold-standard `:streaming-complete → :fail` hook (`auction-module/test-private/auction_module/performance_test_support.clj:21-29`). The development notes say "exactly-once under retries → not forceable from tests" (`hld-ad-click-aggregation/test-resources/development/TEST_VALIDATION.md:79,220`; `hld-metrics-pipeline/test-resources/development/TEST_VALIDATION.md:189`). That note is wrong for stream designs. The hook forces a stream retry, and it never fires for a microbatch design, so the test is **topology-neutral**. A microbatch design passes trivially. A stream design that is not retry-safe fails. This is the cheapest high-value addition for all five challenges.
3. **Topology type is deliberately not asserted.** The suites avoid the `:topology-event` checks that bank and auction use, and that fits these READMEs, which declare latency non-acceptance. Visibility class (stream vs microbatch) is therefore unconstrained everywhere. Only rate-limiter has a domain where that matters (see below).
4. **"Durable state survives restart" is weak.** The restart tests use `rtest/update-module!` in the same JVM. Examples: ad-click functional `:506`, metrics functional `:457-464`, autocomplete independent `:100-120`. State kept in a namespace-level atom would survive this, so the tests prove only that business state lives in PStates, and only weakly.
5. **Blobs are invisible.** The event hook reports operation counts, not bytes. So a whole-entity non-subindexed blob (the entire campaign, series, user history or alias history as one value) passes every read-cost test. The rate-limiter and url-shortener READMEs admit this (rate-limiter README `:132-136`, url README `:122-126`). The ad-click and metrics suites admit it in docstrings. No challenge closes the gap.
6. **Task balance is uneven.** Rate-limiter and url-shortener assert per-task balance. Ad-click and metrics do not check it at all. Autocomplete states it in the README (`:99-100`) but only prints a diagnostic.

---

## 1. hld-ad-click-aggregation

**Verdict: PARTIAL.** It has solid read-scaling checks, but no write-cost, task-balance or retry checks, and the read-time-aggregation design goes undetected.

### Stated NFRs
- Exactly-once via idempotence: "every click is recorded exactly once with a frozen disposition" (README `:3-4`); replays have "no effect at all" (`:66-68`; protocol `:28-31`).
- Efficiency contract (README `:113-125`): `record-click!` does "bounded work per click, independent of how many clicks, windows, or campaigns exist"; `get-request` reads nothing proportional to other requests; "`get-window` reads one window. `get-windows` must do work proportional to the windows and returned breakdown entries inside the requested range"; reads of one campaign must not read other campaigns' state.
- Durability: "All authoritative business state must be durable Rama state" (`:147-148`). It must also run with 2 and 4 tasks (`:149`).
- Per-campaign ordering (`:141-145`).

### Implied NFRs (source case study; not in README)
- *Implied:* **pre-aggregation**. Dashboard queries read rolled-up counters and never re-aggregate raw clicks. This is the heart of the case study.
- *Implied:* **hot-ad skew**. A viral campaign concentrates clicks. The case study partitions and salts hot ad keys.
- *Implied:* **exactly-once under processing failure**, not only under client replay. The source's checkpoint and transactional-sink chain exists for this.
- *Implied:* bounded write amplification per click.
- Retention and raw-log reconciliation are explicitly excluded (`:36-39`).

### Tested NFRs (all private)
- The read-side cost of `get-request`, `get-window`, counted, late and replay `record-click!` is compared small vs grown with `within-growth?` (`after <= 2*before+20`). The growth adds 150 windows, 100 late audits and 12 other campaigns (`efficiency_test_support.clj:130-194`). Mechanism: `with-event-hook` counting `:rocks-read/:rocks-iterator/:rocks-iterator-read` (`:39-50`).
- `get-windows` scaling: 700 → 2,900 out-of-range windows, with a fixed 3-window output (`:195-214`).
- Counted-write read cost as the target window's breakdown grows from 128 to 512 pairs (`:215-234`).
- Client-replay idempotence and cross-campaign identity (`independent_semantics_test.clj:9-50`; functional `:275`).
- Restart via `update-module!` (functional `:506-530`), with the weakness noted in cross-cutting finding 4.

### Gaps (with a plausible wrong design that passes today)
1. **Read-time aggregation of the target window.** *Wrong design:* store raw clicks under `[campaign window]` (subindexed) and compute totals and breakdown in `get-window` by iterating them. Every `get-window` measurement targets a window with 3 to 5 clicks (window 6060, `:131`, `:175`). The 512-pair window (`:215-234`) and the 200-click "hot" window (functional `:429-454`) are checked only for results, never for read cost. So cost ∝ clicks-in-window passes. This violates "work proportional to … returned breakdown entries".
2. **Write amplification is unmeasured.** `ROCKS-EVENTS` excludes `:rocks-commit` (`:39`). *Wrong design:* each click also rewrites a per-campaign "all windows" summary, or writes the audit record into several secondary indexes. Reads stay flat, writes grow, and nothing notices.
3. **Task balance.** *Wrong design:* route every event with `|global`, or hash on a constant, so everything lands on one task. Read counts are identical, and all tests pass.
4. **Exactly-once under stream retry.** *Wrong design:* a stream topology that writes the audit record at `hash(campaign, request-id)` and then hops to `hash(campaign, window)` to bump counters. A retry after the audit write sees "already recorded" and skips the counters, which loses the count. The reverse order double-counts. Neither is detected.
5. **Hot campaign.** Not tested. See the reference-impl concern below: the README ordering contract makes a per-campaign single partition legitimate.

### Test weaknesses
- The bounds are growth ratios, not absolute. The docstring admits "a sufficiently large fixed iterator page can still cross these finite fixture sizes" (`:12-14`). Unlike chat-app, there is no size-independent absolute ceiling of the form "< 40 for a 300-member room".
- `:threads 2` with `:tasks 4` (`:109`). Per-task attribution would be impossible even if a balance check were added. Rate-limiter uses `:threads tasks`.
- Cross-campaign isolation is exercised only jointly with other growth (docstring `:29-32`).
- `get-watermark` and `advance-watermark!` costs are unasserted (`:17-20`). A watermark advance that closes windows by rewriting them would be O(windows). The README gives no bound for this, so that is acceptable, but it is worth stating.

### Reference-impl concern
- Everything is keyed and partitioned by campaign (`module.clj:179`, `:182-193`). A hot-campaign balance test would fail the reference. But the README requires per-campaign ordering (`:141-145`), so any such test first needs a README change that allows splitting a campaign's windows across tasks. I do not recommend it.
- The reference would pass gaps 1 to 4 as written. It pre-aggregates with `+compound` (`:225-228`), writes about 3 entries per click, hashes by campaign and uses microbatch.

### Recommended tests
1. **Window read cost vs clicks-in-window.** Put 10 clicks into window W of campaign A, then 1,000 clicks into window W of campaign B, all on one `[geo device]` pair. Assert `cost(get-window B W) <= cost(get-window A W) + 10`, and a similar check for `get-windows` over W. This separates pre-aggregation from read-time aggregation. No README change is needed ("proportional to … returned breakdown entries").
2. **Write entries per click.** Add `:rocks-commit` `write-batch-count` to the capture. Assert that one counted click, including its barrier, writes ≤ 8 entries at both the small and the grown state. Assert that replay and late clicks write ≤ 4. The README would need a one-line numeric budget in the rate-limiter style to be fair.
3. **Retry injection.** Wrap one `record-click!` plus barrier in a `failed-streaming` hook. Then assert that the window totals equal exactly one click and that the audit record is unchanged. This is topology-neutral and needs no README change, because exactly-once is already stated.
4. **Balance.** Launch with `:threads tasks`. Send 400 campaigns with one click each. Assert that per-task write entries and read work fall within 0.5x to 1.5x of the mean, reusing the rate-limiter helper. The README already says "runs with both 2 and 4 tasks", but it would need the rate-limiter "balanced across tasks" sentence.

---

## 2. hld-metrics-pipeline

**Verdict: PARTIAL.** It has read-scaling checks, but no data is ever placed above the query range, and there is nothing for storage reclamation, write cost, task balance or retries.

### Stated NFRs
- Efficiency contract (README `:123-137`): queries do "work proportional to the queried series' data inside the requested range, not to other series, other tenants, or data outside the range". `ingest-sample!` does bounded work. `get-series-info` examines bounded state. "`advance-clock!` may do work proportional to the data it expires on that series. It must not touch other series."
- Retention semantics: raw data is retained while `ts+300 > clock`, and rollups while `start+width+7200 > clock` (`:63-77`). Durability and 2/4 tasks (`:161-163`).

### Implied NFRs
- *Implied:* **retention reclaims storage**. The case study's tiering and downsampling exist to bound storage. Hiding expired data from queries is not the same thing.
- *Implied:* ingest is the dominant write path and must not amplify per sample. Rollups must be pre-aggregated, never derived at query time. The 300-vs-7200 retention split already forces pre-aggregation for raw → 60, but not for 60 → 3600.
- *Implied:* per-series counters must be exact under processing retries.
- *Implied:* tenants and series are spread across tasks. Hot-tenant and cardinality concerns are excluded (`:30-33`).

### Tested NFRs (all private)
- The read cost of `query-raw`, `query-rollup` 60 and 3600, `get-series-info`, `ingest-sample!` and a non-expiring `advance-clock!` is compared small vs grown with `within-growth?` (`efficiency_test_support.clj:119-190`).
- One absolute bound: the rollup(60) over 3 buckets costs `< 50` with 100 out-of-range buckets (`:129-135`).
- A huge clock jump costs `< 8*(n-retained+110)` (`:196-210`), which is an upper bound only.
- Other series are untouched by the advance (`:211-213`).
- Restart via `update-module!` (functional `:457-464`).

### Gaps
1. **Lazy retention, where nothing is ever deleted.** *Wrong design:* `advance-clock!` only writes the clock, and queries clamp `lo` to `clock-299`. Expired raw samples and buckets stay in RocksDB forever. Every functional check passes, because queries filter by clock. The efficiency advance test passes too, because it has only an upper bound (`:202`).
2. **Data above the query range is never present.** The relevant raw range `[98990, 99000)` sits at the clock, and all growth goes below it (`:140`). The rollup range gets only one extra bucket above it (98940). *Wrong design:* `sorted-map-range` or `subselect ALL` with a filter from `lo` to the end of the map. Its cost ∝ data above `start` goes undetected.
3. **Deriving 3600-width rollups from 60-width buckets at read time.** The `hour-range` query (`:121`, `:168`) reads the same ~100 60-buckets before and after growth, so `within-growth?` passes. There is no absolute bound for it. (This may fail functionally near 60-bucket expiry, but no test targets that window.)
4. **Write amplification.** `:rocks-commit` is not captured (`:28`). *Wrong design:* rewrite the whole non-subindexed 60-bucket map, or the raw map, per sample. That is one read and one write, so it is invisible.
5. **Task balance** is absent. A `|global` design passes.
6. **Stream retry**: a non-idempotent `:accepted` increment double-counts under retry. It is untested.

### Test weaknesses
- The growth ratio (2x+20) with a small baseline, and a single absolute bound.
- `:threads 2` with `:tasks 4` (`:83`).
- The "advance proportional to what it expires" test (`:196-210`) cannot distinguish eager from lazy expiry.

### Reference-impl concern
- `query-raw` and the 60-width `query-rollup` use `sorted-map-range` on subindexed maps (`test-resources/hld_metrics_pipeline/module.clj:190`, `:207`). The sibling ad-click build **measured** that `sorted-map-range` on a subindexed map "iterates to the END of the map": 104 iterator reads for a 3-window range with 100 above it (`hld-ad-click-aggregation/test-resources/development/IMPLEMENTATION_VALIDATION.md:127-132,186-191`). If that holds, the metrics reference violates "not … data outside the range". A narrow `query-raw` near `clock-299` would read up to 300 samples, and a narrow rollup up to 121 buckets. Retention caps bound the damage, but gap 2's test would likely **fail the reference**, so it must be fixed first with `sorted-map-range-from` and `:max-amt` paging, as in ad-click `module.clj:236-245`. I did not re-measure this for metrics.
- The reference deletes eagerly (`module.clj:133-155`), so it would pass the retention test.

### Recommended tests
1. **Above-range data.** On one series with clock C, ingest 290 samples in `[C-289, C]`. Measure `query-raw [C-299, C-289)` before and after adding them, and assert cost ≤ 2x+20 over a baseline that has none above. Do the same for rollup(60) with 100 complete buckets above the range. This needs no README change, but the reference must be fixed first.
2. **Storage reclamation.** Around an `advance-clock!` that expires N ≥ 200 raw samples and M buckets, capture `:rocks-commit` `write-batch-count`. Assert that at least N entries are written, i.e. deleted. This needs a README sentence: "expired samples and buckets must be physically removed; storage is bounded by retention".
3. **Retry injection.** Wrap `ingest-sample!` plus barrier in `failed-streaming`. Assert that `:accepted` rose by exactly 1 and that the bucket count, sum, min and max reflect one sample. No README change is needed.
4. **Balance plus write budget.** Send 400 series with one ingest each and use `:threads tasks`. Assert per-task balance, and that total write entries are ≤ 8 × samples. This needs the rate-limiter-style balance and budget sentence.

---

## 3. hld-rate-limiter

**Verdict: ADEQUATE.** Its bounded-work, history-growth, write-budget and task-balance tests match the stated contract. The top gap is that nothing injects a stream retry against the debit-and-record pair.

### Stated NFRs
- Fixed work: "`check!` processing is fixed work per request, independent of how many decisions the user has accumulated", and fixed-work reads (README `:87-96`).
- Numeric budgets (`:98-130`): reads ≤4 point, ≤2 seeks, ≤8 iterator reads and 0 writes. `check!` ≤8/2/8/8. Growth from 32 to 512 decisions ≤ +2/+1/+4/+2. Across 400 users, total writes are 1x to 8x and per-task balance is within 0.5x to 1.5x.
- Atomic two-bucket debit (`:36`). Idempotent decisions (`:47-50`). Workload: a hot user may issue thousands of requests per second, with up to 1M decisions per user (`:80-85`).
- Latency is explicitly not tested (`:138-144`; protocol `:18-20`).

### Implied NFRs
- *Implied:* **inline, low-latency decisions**. A rate limiter's job is to answer before the request proceeds. The protocol's asynchronous `check!` returns nil and is read later, so the domain's main NFR is designed out. The reference and plan pick microbatch (`test-resources/PLAN.md:81,195`), which means hundreds of milliseconds to a decision.
- *Implied:* **retention of idempotency records**. Keeping 1M decisions per user forever is unbounded storage. Real limiters expire request-ID dedup after a TTL.
- *Implied:* hot-user throughput. Serialising one user on one task is required by the clock semantics (PLAN `:101`), so this is legitimately out of scope.

### Tested NFRs (all private)
- Reads at 32 vs 512 decisions: absolute budget plus bounded growth, on user-owned PStates only, with internal `$$__` PStates excluded (`performance_test_support.clj:137-173`).
- `check!` new, retried and denied: write budget and growth, with `:rocks-commit` counted (`:175-237`).
- Balance: 400 owners across two clients, `:threads tasks`, 0.5x to 1.5x per task for both writes and reads, 1x to 8x total writes, zero writes on reads (`:239-277`).
- Functional: two clients on one hot user, where debits sum exactly and tokens never go negative (`functional_test_support.clj:403-423`). Changed-body retries across config versions (`:275`).

### Gaps
1. **Stream retry double-debit.** *Wrong design:* a stream topology that writes `:limiter` (the debit) before `:decisions[rid]`. A retry between the two writes finds no decision and debits again. The `decisions[rid]` guard helps only if the decision is written first or both writes are atomic. This is untested.
2. **Opaque history blob.** Acknowledged at README `:132-136` and left to review.
3. **Decision-record retention.** Not stated, and the reference keeps decisions forever (`module.clj:115-122`). Any test would need a README TTL rule, so I recommend adding none.
4. The visibility class is not tested, by design.

### Test weaknesses
- The budgets are measured on a single configured user with refill 0 and 2 endpoints. The config and limiter value is small, so a config-proportional cost (up to 16 endpoints) would go unnoticed, but it is bounded by the grammar.
- The 1x–8x write total is loose. A design that writes 8 entries per check (for example a secondary index per decision) passes.

### Reference-impl concern
None under these tests. `$$users` stores the limiter as one fixed-keys value, bounded at ≤16 endpoints, with subindexed decisions (`module.clj:104-122`). A single microbatch path on `hash(user)` gives atomic both-or-neither debits.

### Recommended tests
1. **Retry injection on a debiting check.** Configure capacity 10 and refill 0. Run `failed-streaming` around `check! u r1 e 3 t` plus barrier. Assert that `get-status` available is 7 on both buckets, that the clock is t, and that the decision is recorded once. No README change is needed, because idempotence and atomic debit are stated.
2. (Optional) Repeat the history-growth measurement with a 16-endpoint config to cover config-sized values. No README change is needed.

---

## 4. hld-url-shortener

**Verdict: ADEQUATE.** The fixed-work resolve, count and outcome reads, the fixed-work click writes and the task-balance tests match the stated contract. The top gap is that nothing checks exactly-once click counting under a stream retry.

### Stated NFRs
- Fixed-work reads and `record-click!` independent of click and request history (README `:77-88`). Numeric budgets are the same shape as rate-limiter (`:90-120`). Balance across tasks.
- Workload: 100k resolves per second, a viral alias with thousands of resolves per second, 1M observations per alias, 1,000 create request IDs (`:68-75`).
- Exactly one `:created` among concurrent creates (protocol `:45-48`). Clicks deduplicated per `(alias, click-id)` (README `:48-53`).

### Implied NFRs
- *Implied:* **hot-alias read skew**. The case study answers with CDN and cache tiers, which are excluded (`:24-25`). Partition choice cannot spread one key's reads in Rama without replication, so this is reasonably out of scope.
- *Implied:* the redirect path must not be slowed by analytics writes (resolve never writes, which is tested), and click counting is asynchronous and exactly-once.
- *Implied:* the dedup set for 1M click IDs per alias is unbounded storage. The README accepts this.

### Tested NFRs (all private)
- Reads (`get-click-count`, `resolve-alias`, and the winning, last and missing outcome) at 32 vs 512 history: absolute budget plus growth (`performance_test_support.clj:127-158`).
- `record-click!` new and duplicate: write budget including `:rocks-commit` (`:160-188`).
- Balance: 400 aliases, `:threads tasks`, per-task writes and reads within 0.5x to 1.5x, and reads write nothing (`:190-224`).
- Functional: concurrent creates from two clients, exactly one created (`functional_test_support.clj:259`). Distinct concurrent observations from two clients (`:308`).

### Gaps
1. **Stream retry under- or over-count.** *Wrong design:* a stream topology that inserts the click-id into the dedup set and then increments `:clicks`, possibly at a different partition or in a later step. A retry after the insert sees "seen" and skips, which undercounts. The reverse order double-counts. This is untested.
2. **Opaque blob.** Acknowledged at README `:122-126`.
3. **Hot-alias click ingest.** The test drives one click at a time. There is no check that a burst of N clicks to one alias writes O(N) entries, not more. The per-click budget covers this indirectly.

### Test weaknesses
- The 1x–8x total write ceiling is loose (the same point as rate-limiter).
- `delete-link!`, `block-link!` and `unblock-link!` costs are unmeasured. They are point ops in any sane design, and the README states no bound.

### Reference-impl concern
None. Every op is point work on `hash(alias)` in microbatch (`module.clj:24-97`). Each click rewrites the `:link` record, which includes the URL of up to 2,048 characters, to bump `:clicks` (`:95-96`). That is a bytes-level amplification the hook cannot see, but it is fine under the count budget.

### Recommended tests
1. **Retry injection on a click.** Run `failed-streaming` around `record-click! a c1` plus barrier, then assert that the count rose by exactly 1. Run `failed-streaming` around a duplicate and assert that the count is unchanged. No README change is needed.
2. (Optional) **Hot-alias burst.** Send 500 distinct clicks to one alias in one phase. Assert that total write entries are ≤ 4 × 500 and the count is exact. This is within the existing README budget.

---

## 5. hld-search-autocomplete

**Verdict: PARTIAL.** The top-K precomputation is only weakly checked (one fixture where score order equals lexical order, k=1, a 4x ratio). Task balance is stated but only printed. Heavily blocked prefixes, storage reclamation and retries are untested, and the reference would fail a reclamation test.

### Stated NFRs
- "`suggest` read work is independent of the number of candidates matching the prefix and of corpus size. It must not scan matching phrases at query time." (README `:90-92`; protocol `:69-71`).
- `record-search!` does fixed work bounded by phrase length (`:93-94`). `publish-snapshot!` ∝ snapshot size, not the previous generation's trends (`:95-97`). Fixed-work `get-phrase` and `get-generation` (`:98`). "Work and storage are balanced across tasks" (`:99-100`).
- Workload: `suggest` at 500k/s dominates, a single-character prefix may match thousands of candidates, and the block list holds up to 10,000 (`:76-83`).
- Unlike rate-limiter and url, there is no numeric budget section.

### Implied NFRs
- *Implied:* **top-K precomputation per prefix**. This is stated, and it is the core of the case study.
- *Implied:* **policy filtering must not degrade top-K**. When many top candidates are blocked, a precomputed top-k cache must not fall back to scanning.
- *Implied:* **hot-prefix skew**. Short prefixes for a big locale are hot. Placement should spread them.
- *Implied:* **old-generation data is reclaimed**. Snapshots arrive a few times a day, and keeping every generation grows storage without bound.
- *Implied:* trend counts are exact under retries.

### Tested NFRs (all private)
- `suggest "ab" k=1` cost with a 300- vs 3,000-phrase corpus, `< 4 × small` (`independent_test.clj:41-50`, bound at `:24-31`).
- `get-phrase` read and `record-search!` write+read cost with 60 vs 600 sessions (`:52-68`), with `:rocks-commit` counted (`:8-19`).
- Publish cost after 20 vs 320 prior-generation novel trends (`:70-83`).
- Balance: `:depot-read` task set **printed only**, not asserted (`:85-98`).
- Restart via `update-module!` (`:100-120`).
- Functional: 600-phrase hot prefix with the top 12 blocked, which defeats a fixed top-10 cache (`private_test.clj:127-138`).

### Gaps
1. **Capped scan passes.** In both large fixtures, score decreases with n while phrase order increases with n, so score order equals lexical order (`independent_test.clj:41`; `private_test.clj:130`). *Wrong design:* iterate the prefix range in lexical order, capped at a fixed 1,000 entries (or "until k non-blocked found"), score the phrases and return the top k. Results are correct because the best-scored phrase is lexically first. The cost is 300 → 1,000, which is under 4 × 300. This is a scan, and it passes.
2. **Heavily blocked prefix.** *Wrong design:* keep a per-prefix ranked set that includes blocked phrases and skip them at query time. The cost is ∝ the blocked phrases ahead of the top k. Only 12 are blocked, and only for correctness (`private_test.clj:132`). The README allows 10,000 blocked phrases.
3. **Task balance.** *Wrong design:* hash everything by locale. The tests use one locale per measured scenario, and balance is only printed. This violates README `:99-100`, yet passes.
4. **Generation reclamation.** Old-generation phrases, sessions and prefixes are never deleted. This is untested, and the reference has the same leak (see below).
5. **Stream retry.** *Wrong design:* a stream topology that inserts `session-id` into the membership set, then updates ranks at the long-prefix partition and hops to the 1-character-prefix partition (the reference's own shape, `module.clj:104-159`). A retry after the membership insert filters out (`:109`) and loses the rank updates. `suggest` and `get-phrase` then disagree. This is untested.
6. `suggest` cost is measured only at k=1 and for a 2-character prefix. 1-character prefixes take a different placement path in the reference (`:151-159`).

### Test weaknesses
- The ratio is `< 4x` with no absolute ceiling, against a 10x size step. A fixed page or cap under 4 × baseline passes.
- The fixtures correlate score with lexical order (gap 1).
- The balance check is diagnostic only. `INDEPENDENT_VALIDATION.md:30-33` calls balance "a source-review limitation".
- The publish test's prior-generation growth is only 20 → 320 novel phrases.

### Reference-impl concern
- **Superseded generations are never reclaimed.** `$$phrases`, `$$sessions` and `$$prefixes` are keyed `[locale generation …]` (`module.clj:43-60`), and publish never deletes the old generation. `test-private/VALIDATION.md:35` confirms: "Superseded generation data remain durable and unreclaimed". A reclamation test would **fail the reference**. It also cannot be cheap. Deleting a 10,000-phrase × 64-prefix generation at publish time contradicts "publish must not depend on the size of the previous generation's trend data" (`:95-97`), unless deletion is deferred or amortised. Any such test therefore needs a README decision first.
- The reference removes blocked phrases from the prefix sets at block time (`:127-140`) and reads exactly k (`:167-168`), so it would pass gaps 1 and 2. Placement on `hash([locale, first-2-chars])` (`:9-10`) should pass a multi-prefix balance test.

### Recommended tests
1. **Anti-correlated hot prefix.** Build 3,000 `ab…` phrases whose score *increases* with lexical order, so the best is lexically last. Measure `suggest "ab" 10` and `suggest "a" 10` at 300 vs 3,000 phrases. Add an absolute ceiling, for example ≤ 4 seeks and ≤ 2k + 10 iterator reads. The README would need a numeric budget to justify the absolute part; the ratio part needs no change.
2. **Blocked-heavy prefix.** Using the corpus from test 1, block the top 500 phrases. Assert that `suggest` cost is within 2x+20 of the unblocked cost, plus correct results. No README change is needed ("must not scan matching phrases").
3. **Asserted balance.** Use one locale with 400 distinct 2-character prefixes, one search each, and `:threads tasks`. Assert per-task write entries and read work within 0.5x to 1.5x, reusing the rate-limiter helper. The README already states the requirement.
4. **Retry injection.** Run `failed-streaming` around `record-search!` plus barrier. Assert `get-phrase :sessions` = 1 and that `suggest` on the 1-character, 2-character and full prefixes shows the same score. No README change is needed.

---

## Summary table

| Challenge | Verdict | Top gap |
|---|---|---|
| hld-ad-click-aggregation | PARTIAL | `get-window` cost is measured only on a 3-to-5-click window, so read-time aggregation of raw clicks passes. There are no write counts, no balance check and no retry injection. |
| hld-metrics-pipeline | PARTIAL | Lazy retention (never deleting expired data) passes, and no data sits above a query range. The reference's `sorted-map-range` would likely fail an above-range test. |
| hld-rate-limiter | ADEQUATE | No stream-retry injection, so a debit-before-record design double-debits unseen. Inline decision latency is designed out by the protocol. |
| hld-url-shortener | ADEQUATE | No stream-retry injection, so a dedup-insert-then-count design under- or over-counts unseen. |
| hld-search-autocomplete | PARTIAL | The suggest fixture's score order equals lexical order, so a capped prefix scan passes. Balance is stated but only printed. The reference never reclaims old generations. |

---

# NFR test audit — transactional HLD challenges

Scope: `hld-payment-system`, `hld-stock-exchange`, `hld-ticketing-system`, `hld-hotel-reservation`, `hld-file-sync`.
The audit read every README, protocol, private test, reference module and validation note. It also ran two short probes against the payment reference, using scratch scripts only and changing no repository file.

Gold-standard mechanisms used for comparison:

- auction `performance_test_support.clj`: `:topology-event :type` must be `:stream`; `failed-streaming` returns `:fail` from `:streaming-complete` to force a retry, then checks exactly-once notifications; `:local-select :allow-yield?`; `(pos? iterator-reads)` as proof of subindexing.
- chat-app: absolute read and write bounds that do not depend on input size (for example, fewer than 40 writes for a post to a 300-member room).
- bank-transfer: `#{:microbatch}` is required for exactly-once; exact counts such as `{:rocks-read 1 :rocks-iterator 1 :rocks-iterator-read 50}` for a history page; even `:depot-read` counts across tasks using `gen-hashing-index-keys`; and **no `:partitioner` events**.

---

## Cross-cutting findings (apply to all five)

1. **No public tests.** None of the five has a `test/` directory. Every NFR assertion is in `test-private/`, so the solver sees only the README's "Resource guarantees" prose. The originals have the same private layout, so this matches them.
2. **All five share one template, and all five tests use one mechanism.** Each test grows the entity's own history (about 4–5×) and asserts that `large ≤ k·small + c` for `:rocks-read`, `:rocks-iterator(-read)` and `write-batch-count`. The test runs on 2 and 4 tasks. This mechanism is sound, and it catches full scans. It borrows only one of the gold-standard mechanisms.
3. **Nothing checks the latency or visibility class.** Every command is async: it returns `nil` and the result is read later with `get-outcome` after `wait-for-processing!`. So nothing requires `:stream` or `:microbatch`. A matching engine and a ticket-hold service are defined by their latency. Here that requirement has been designed out of the challenge rather than tested. Restoring it would need new README text.
4. **No fault injection on either stream or microbatch.** The README requires "exactly one durable outcome" and "a replayed accepted X never Xs twice". Tests exercise only *client* replays that reuse the same `request-id`. None exercises an *engine* retry. A stream design that does `depot-append!` into an internal depot before recording the request (the auction bug pattern) passes every test. Auction's `failed-streaming` helper would pass trivially on the microbatch references, because they emit no `:streaming-complete`. It could therefore be added without changing any reference.
5. **A whole collection stored as one value passes every count test.** Every validation note admits this (payment `test-private/REVIEW.md:20-23`, stock `TEST_VALIDATION.md` "Limitations", hotel `VALIDATION.md`, file-sync `performance_test_support.clj:9-11`). A probe of the hook payloads confirmed that `:rocks-read` and `:rocks-commit` carry no byte size. The available keys are `:task-id :module-name :name :topology :write-batch-count`. The only count-level proxy is the bank/auction pattern: **range queries must show `:rocks-iterator-read > 0`, bounded by about `limit`**. A non-subindexed sorted map gives 0 iterator reads. Point-keyed maps (accounts, orders, seats) cannot be told apart by counts.
6. **Partition balance is not checked.** A design that routes every command through one global partition passes, for example a depot with `hash-by (constantly "x")`, or a global depot "for ordering". File-sync collects `:depot-read` per task, but only as a diagnostic (`performance_test_support.clj:171-190`). A bank-style test would need a README line, such as "load for distinct tenants/symbols/... must spread across tasks".
7. **Reference concern shared by all five (verified on payment).** Every reference stamps each command with an `:ingress-seq` write before regrouping. It then regroups with `+group-by` / `+vec-agg`, sorts, and runs a `loop<-` (payment `module.clj:122-128`, stock `:106-111`, ticketing `:201-207`, hotel `:98`, file-sync `:290-295`). A probe of the payment reference at 4 tasks with 4 tenants recorded `:partitioner 4` events. **A bank-style "no partitioner calls" test would fail all five references.** The pattern also costs one extra write per command, and it holds a whole batch per entity in memory. That memory is bounded only because each reference sets `depot.microbatch.max.records` (payment `:108` to 1000, ticketing to 200, file-sync `:248` to 200). None of the originals uses this pattern. If you want the references to be idiomatic, this is the first thing to review. Any added "no repartition" test depends on that review happening first.
8. **Task and worker configuration.** Payment and hotel use `threads = tasks`. Ticketing fixes `:threads 2` (`ticketing_test.clj:15`). Only file-sync runs 4 tasks across `:workers 2` (`performance_test_support.clj:217`). None randomises tasks the way auction does (`rand-nth [2 4]`), but running both counts is stronger in any case.

---

## 1. hld-payment-system

**Stated NFRs**
- Resource guarantees (`README.md:260-277`). `fund!`, `charge!` and `refund!` must make "a constant number of storage reads and writes". Point queries make a constant number of reads, and "Balances must not be recomputed by scanning the journal". `get-journal` is "proportional to `limit`". No operation may "read, deserialize, or rewrite an unbounded per-tenant collection ... as one value" (`:264-266`). The guarantees must hold on 2 and 4 tasks (`:277`).
- Idempotency and replay (`:94-104`): "a replayed accepted charge never charges twice".
- Exactly one durable outcome per command (`:51-52`).
- Durability and immutability (`:110-112`).
- Per-client, per-tenant ordering (`:106-108`).
- The client holds no business state (`:279-284`).

**Implied NFRs (not in the README)**
- Exactly-once money movement under *engine* retry. This is the defining NFR of the Stripe-style source, and bank-transfer turns it into a `#{:microbatch}` requirement.
- An idempotency cache that stays bounded under replay storms (covered by the constant-cost rule).
- Tenant isolation: one hot tenant must not slow the others.
- Auditability: the journal is replayable (covered functionally).

**Tested NFRs (all private)**
- `private_challenge_test.clj:151-162`: a `storage-cost` hook sums reads (`:rocks-read`, `:rocks-iterator`, `:rocks-iterator-read`) and `write-batch-count`.
- `:164-230` (`run-growth`): grows the tenant's own history from 200 to 1,000 accounts and charges, while another tenant grows from 200 to 1,000 funds. It measures `get-journal` at a fixed deep cursor (`150`, limit 7), plus balance, account, charge, outcome and tenant. It also measures `fund!`, `charge!` and `refund!` including processing (`:191-196`). The bound is `hi ≤ 60 + 1.25·lo` for both reads and writes (`:229`). It asserts that reads are non-zero, and that command writes are non-zero (`:225-227`).
- Runs on 2 and 4 tasks (`:232-236`, `:145-149`).
- Client replays and conflicts, including cross-command request-id reuse (`:52-70`, `:120-143`).
- `update-module!` keeps state (`:85-93`).
- Mutant evidence: a `get-balance` journal scan was caught, with reads growing from 207 to 1,019 (`REVIEW.md:42-44`).

**Gaps and plausible wrong designs that pass**
- *No retry or exactly-once test.* Wrong design: a stream topology that appends journal rows to an internal `*journal` depot (or sends to a second topology) before it writes the request record. A forced retry then duplicates a journal row and breaks seq contiguity. No test fails.
- *Microbatch is not required.* Wrong design: a stream topology with a `select`-then-`transform` balance update split across a partitioner hop. The hotel, ticketing and payment tests never interleave two clients' charges on one customer.
- *Whole-collection value.* Wrong design: `{tenant {:accounts {...} :journal (sorted-map ...)}}` with no subindexing. Every read and write stays at about one Rocks operation, and the growth test passes. Admitted in `REVIEW.md:20-23`.
- *Global partition.* Wrong design: `(hash-by (constantly 0))` on the command depot. It is correct and passes, but it serialises all tenants.

**Test weaknesses**
- Only relative growth is measured, with no absolute ceiling like chat-app's. A command costing a constant 500 writes passes. The allowance of 60 + 1.25× over a 5× growth is fine against scans.
- `fund!`, `charge!` and `refund!` are measured once per stage from a single client, with no concurrency.

**Reference-impl concerns**
- Would fail a "no partitioner" test: `module.clj:126` (verified, 4 events).
- Would pass an iterator test: the probe of `get-journal` at limit 10 recorded `{:rocks-read 1 :rocks-iterator 1 :rocks-iterator-read 11}`.

**Recommended tests**
1. **Stream-retry exactly-once.** Wrap one `charge!` and one `refund!` in an auction-style `failed-streaming` helper. Then assert that the journal seqs are exactly `[1..n]`, that the balances are as expected, and that `:conflicting-attempts` is 0. This passes the microbatch reference unchanged and needs no README change.
2. **Subindex proof on the journal.** Assert `(< 0 iterator-reads (+ limit 3))` for `get-journal` at limit 10. This kills the sorted-map-blob design and needs no README change, because `:264-266` already forbids it.
3. **Optional, bank-style.** Require `:topology-event :type #{:microbatch}` for commands. This needs a README sentence ("commands must be processed with exactly-once semantics under worker failure"). The bank README carries the same requirement.
4. **Optional, needs a README line.** Even `:depot-read` counts across tenants generated with `gen-hashing-index-keys`.

**Verdict: PARTIAL.** Every stated resource bound is tested with growth pairs on 2 and 4 tasks. The domain's central NFR, exactly-once money movement under engine retry, is never exercised, and blob storage is undetectable.

---

## 2. hld-stock-exchange

**Stated NFRs**
- `README.md:239-261`. `submit-limit-order!` must be "proportional to the number of trades it produces plus a constant, not to the number of resting orders or levels". `cancel`, `get-order` and `get-outcome` take a constant number of operations. `get-depth` is proportional to `levels`, and "a level with ten thousand orders costs the same as a level with one". `get-trades` is proportional to `limit`. "The book of a symbol must not be held as a single value that is read and rewritten whole on every command" (`:258-259`).
- Deterministic processing, one command at a time, applied atomically (`:103-106`, `:117`).
- A replay "never creates a second order or a fresh position in the queue" (`:96-98`).

**Implied NFRs**
- Low, predictable matching latency. This is the headline NFR of the source case study. The latency class is designed out, because commands are async.
- A deterministic sequencer that is safe to replay (covered functionally).
- Hot-symbol skew: one symbol is a single-writer book by design, and a very large sweep must not stall the partition (cooperative yielding).
- A durable, gap-free trade feed (covered functionally).

**Tested NFRs (all private)**
- `private_test.clj:20-38` defines `rocks-work` and `stable-work!` with the bound `large ≤ 2·small + 12` for read, iterator seek, iterator read and writes.
- `:143-202` (`bounded-work-and-deep-page`) grows the book from 320 to 1,280 in symbol H (one order per price level) and symbol Q (all orders at one level). It measures:
  - `get-depth` for 3 levels (`:155-158`);
  - a 1,280-order single level (`:159-160`, which covers the "ten thousand orders" clause);
  - `get-order` and `get-outcome` (`:161-162`);
  - a non-crossing submit (`:163-164`);
  - cancel (`:165-167`);
  - a fixed 3-trade match (`:168-175`);
  - a deep 5-row trade page after 320 versus 960 trades from one sweep (`:187-202`).
- `:247-267`: 1,040 commands without a barrier exceed the reference's batch cap, and ordering is still checked.
- 2 and 4 tasks in every test.
- Mutants were caught: a depth scan grew iterator reads from 323 to 1,288 (`INDEPENDENT_REVIEW.md`).

**Gaps and plausible wrong designs that pass**
- *The stated whole-book rule (`:258`) is untested.* Wrong design: `$$books {sym {:bids (sorted-map ...) :asks (sorted-map ...)}}` stored as one non-subindexed value per symbol. `get-depth` then costs 1 `:rocks-read` and 0 iterator reads at both sizes, and each submit costs about one write-batch entry. `stable-work!` passes.
- *No latency or visibility class.* Wrong design: microbatch with a large batch cap, or even a scheduled tick. Both are fully correct. If the challenge is meant to teach "matching engine means stream plus idempotent sequencing", nothing currently enforces it (the README would need changing).
- *No retry injection.* Wrong design: a stream topology that assigns the trade seq from a counter on another partition, or appends trades to an internal depot. A retry duplicates trades or leaves a gap in trade seqs.
- *No check that a big sweep yields.* A 960-trade sweep runs (`:193`), but no `:allow-yield?` or yield check exists. The reference raises `topology.microbatch.phase.timeout.seconds` to 3,600 (`module.clj:79`) so its own long microbatch phases don't time out.

**Test weaknesses**
- There is no independent check that the cost of `submit` scales with the number of trades rather than the book size, beyond the fixed 3-trade probe. For example, nothing asserts that a 10-trade sweep costs about 10/3 of a 3-trade sweep. This is acceptable.
- Relative bounds only. A "2× + 12" bound over 4× growth would admit a sublinear (√n) scan.

**Reference-impl concerns**
- Would fail a "no partitioner" test: `+group-by` at `module.clj:110`.
- Fully filled or cancelled queue entries are removed with `NONE>` (`:155`, `:198`). A level's head read is `sorted-map-range-from-start 1` (`:143`), so on a level with heavy churn RocksDB skips tombstones. That cost is invisible to hooks. It will not fail any count test and is noted only as a real-world concern.

**Recommended tests**
1. **Iterator-read proof** for `get-depth` and `get-trades`: `(< 0 iterator-reads (+ (* 2 levels) 4))`. This kills the book-as-one-value design and needs no README change, because `:258` already states the rule.
2. **Stream-retry helper** around a crossing submit. Assert trade seqs `[1..k]`, a single `:trade-count`, and depth unchanged relative to the expected value. This passes the reference trivially.
3. **Optional.** If low latency is to be represented, add a README line (for example, "a submit's outcome must be visible within stream latency" or a synchronous submit result). Then add an auction-style `:topology-event #{:stream}` test together with test 2. As written today this would force the reference to be rewritten as a stream topology.

**Verdict: PARTIAL.** It has the most thorough per-operation growth coverage of the five, but its explicitly stated whole-book rule and the domain's defining latency and exactly-once NFRs are untested.

---

## 3. hld-ticketing-system

**Stated NFRs**
- `README.md:296-317`. `hold-seats!`, `confirm-hold!` and `release-hold!` must be "proportional to the number of seats in the hold (at most 8), not to the number of seats or holds in the event". `add-seats!` is proportional to the number of seats added. `get-seats` is proportional to the ids requested. `get-hold`, `get-clock` and `get-outcome` take constant reads. `get-compensations` is proportional to `limit`. "Expiry must not require any per-hold background work; an event with a million expired holds costs nothing until one of its seats is touched" (`:314-315`).
- "Exactly one compensation record" per qualifying rejection (`:292-293`).
- A replay produces no second record (`:209-210`).

**Implied NFRs**
- **No double-selling under concurrent contention.** This is the core Ticketmaster NFR: a flash sale where many buyers race for the same seats.
- Hot-event skew: all traffic for one on-sale event lands on one partition.
- Exactly-once compensation, which stands in for the payment reversal, under engine retry.
- Low-latency hold feedback to the buyer (designed out by the async API).

**Tested NFRs (all private)**
- `ticketing_test.clj:261-272` defines `rocks-cost`.
- `:274-314` (`bounded-own-history-work`) grows seats, holds, requests and compensation records from 256 to 1,280 (including 1,280 expired "noise" holds plus late confirms). It measures `get-seats` for 1 seat, `get-compensations` at a deep cursor with limit 1, `get-hold`, `get-outcome`, `get-clock`, and **`advance-clock!`** (`:295-302`). The bound is `≤ 24 + 2·small` (`:312-313`).
- The `advance-clock!` write bound over 1,280 newly expired holds is exactly the "million expired holds" clause.
- 2 and 4 tasks. `:threads` is fixed at 2 (`:15`).
- 1,000-seat `add-seats!` and 8-seat holds are exercised for correctness only (`:141-168`).
- Mutant evidence: a full compensation scan grew from 258 to 1,294 reads (`VALIDATION.md:6-12`).

**Gaps and plausible wrong designs that pass**
- *The per-seat bound on `hold-seats!`, `confirm-hold!` and `release-hold!` is untested.* This is the stated NFR for the challenge's main commands. Wrong designs:
  - the seat record keeps a growing `:holds [...]` vector of every hold that ever covered it, and availability is decided by scanning that vector;
  - `hold-seats!` scans `$$holds` for the event to find active holds covering the requested seats;
  - `release-hold!` rewrites a per-user list of holds.
  All pass, because only queries and `advance-clock!` are measured. The `get-seats` probe reads the "target" seat, which has exactly one historical hold.
- *`add-seats!` proportionality is untested.* Wrong design: `add-seats!` checks `:seat-exists` by reading the whole seats map of the event.
- *No concurrent race.* Every contention test is sequential from one client (`:60-114`). Wrong design: hash by seat-id with check-then-act on each seat's partition, plus a compensation pass for multi-seat atomicity. Under sequential single-client traffic it produces the right settled outcomes. Under two clients racing on overlapping seat sets, both holds can be accepted, or an intermediate partial hold can be visible.
- *No retry injection.* Wrong design: a stream topology that `depot-append!`s the compensation record to an internal depot consumed by another topology before it records the request. A retry then produces two compensation records, which is exactly the auction duplicate-notification bug.

**Test weaknesses**
- Of the 6 stated per-operation bounds, only the query bounds and `advance-clock!` are measured. All 4 mutating seat commands are unmeasured.
- `:threads 2` is fixed at 4 tasks.

**Reference-impl concerns**
- Would fail a "no partitioner" test: `+group-by` at `module.clj:205`.
- `VALIDATION.md:74-94` records that a *yielding* `submap` read inside the ordered microbatch loop missed earlier same-batch seat writes, so the reference switched to a synchronous read of up to 1,000 keys (`module.clj:~220-226`). This does not break an NFR, but it shows the group-and-sort-and-loop template is fragile. It also means an auction-style `:allow-yield?` requirement on the ETL would conflict with the reference.

**Recommended tests**
1. **Extend `bounded-own-history-work`** so it measures one fresh `hold-seats!` of 8 seats, then `confirm-hold!` and `release-hold!`, each followed by `wait-for-processing!`, at 256 and 1,280 history. The "target" seat should first be re-held and expire K times (K = 5 versus 50), so that per-seat churn grows too. Use the same `2·small + 24` bound. No README change is needed.
2. **`add-seats!` of 10 seats** at both sizes, with the same growth bound.
3. **Concurrent race.** Using 4 clients on `future`s, have each hold the same 3 seats in a different order, then wait. Assert exactly one hold is `:accepted`, the others are `:seat-unavailable`, and `get-seats` shows a single owner. The README's atomic "all seats or none" (`:154-156`) already implies this. Optionally state "commands from different clients against the same event are applied atomically, one at a time".
4. **Stream-retry helper** around a late `confirm-hold!`. Assert that `get-compensations` returns exactly one record.

**Verdict: PARTIAL.** The expiry-without-background-work NFR is well tested. The stated per-seat cost bounds on all four mutating commands and the domain's defining concurrent no-double-sell property are not.

---

## 4. hld-hotel-reservation

**Stated NFRs**
- `README.md:257-275`. `reserve!` and `cancel-booking!` must be "proportional to the number of nights in the stay (at most 30), not to the number of bookings or configured nights". `get-night`, `get-booking` and `get-outcome` take constant reads, and "Availability must not be recomputed by scanning bookings". `get-availability` is proportional to the nights requested. `get-booking-events` is proportional to `limit`.
- No overbooking (`:15-16`, `:244-246`).
- "Cancellation restores ... exactly once" (`:252-253`).
- A replayed accepted reservation "never reserves twice" (`:99-101`).

**Implied NFRs**
- No overbooking under **concurrent** reservations for the same hot dates. The source case study accepts controlled overbooking; this README forbids it, which makes concurrency *more* central.
- Hot-property and hot-date skew.
- Read-heavy availability search, which is out of scope here.
- Exactly-once under engine retry.

**Tested NFRs (all private)**
- `work_test.clj:7-17` defines a `work` hook with reads, iterator reads and writes. It does not count `:rocks-iterator` seeks.
- `:19-81` grows nights, bookings, events and requests from 240 to 1,200 in property "p", plus 20 other properties. It measures `get-night`, `get-availability` for 1 night, `get-booking`, `get-outcome`, a first page and a deep page of `get-booking-events`, and a fresh **one-night** `reserve!` (`:48-49`). The bound is `≤ 2·before + 24` (`:80`).
- 2 and 4 tasks.
- A 30-night, 100-room stay is exercised for correctness only (`contract_test.clj:98-130`).
- Reference-only: a pre-append failure and a paused 1,004-command queue (`test-resources/.../reference_test.clj:10-57`). These are not in the solver-facing suite.
- Mutant evidence: an event scan read 1,209 entries (`VALIDATION.md`).

**Gaps and plausible wrong designs that pass**
- *`cancel-booking!` cost is untested.* Wrong design: `cancel-booking!` recomputes each stay night's `:available` as `capacity − Σ` over the property's confirmed bookings (a booking scan), or finds the booking's `:reserved` event by scanning the journal. Both pass. `get-booking` must stay cheap, but cancel is never measured.
- *Stay-length proportionality is untested.* Only a 1-night reserve is measured. Wrong design: `reserve!` reads every configured night of the room type (for example `(keypath p :room-types rt :nights)` with no range) and filters to the stay. At 1,200 nights that would grow and be caught. However, a design that reads a whole nights map per room type stored **as one value** passes, because it costs 1 read.
- *`set-rate!` and `init-night!` are unmeasured.* Wrong design: `init-night!` checks `:night-exists` by deserialising the room type's whole nights map, stored as one value.
- *No concurrent race.* Wrong design: partition by `(property, room-type, night)` with per-night check-then-decrement plus compensating increments for a failed multi-night stay. Sequential tests see the correct settled state. Two clients racing for the last room on overlapping 3-night stays can both get `:accepted`, or can observe a transient over-decrement.
- *No retry injection in the solver suite.* The reference-only `reference_test.clj` covers only a failure before append.

**Test weaknesses**
- The hook ignores `:rocks-iterator` seeks.
- The measured stay is always 1 night, so "proportional to stay length" has no data point.

**Reference-impl concerns**
- Would fail a "no partitioner" test: `+group-by` at `module.clj:98`.
- Otherwise the reference uses bounded range reads `sorted-map-range *ci *co` (`:173`, `:228`, `:255`). It would pass all the recommended tests.

**Recommended tests**
1. **Measure `cancel-booking!`** of a fresh 1-night booking at both sizes, and a **30-night** reserve plus cancel on a separate room type, at both sizes. This uses the existing growth bound, and optionally an absolute bound of `reads ≤ 4·30 + c`. No README change is needed.
2. **Iterator proof on `get-booking-events`** and on a 30-night `get-availability`: `(<= 1 iterator-reads (+ nights 3))`. This kills the whole-nights-map design.
3. **Concurrent last-room race.** Capacity is 1 and 4 clients reserve overlapping multi-night stays in parallel. Assert that at most one stay covering any given night is accepted, and that `available ≥ 0`. No README change is needed, because atomicity and no overbooking are already stated.
4. **Stream-retry helper** around `reserve!` and `cancel-booking!`. Assert contiguous event seqs, and that `available` is restored exactly once.

**Verdict: PARTIAL.** Query and journal bounds are tested. The stated cost bound for `cancel-booking!`, stay-length proportionality, and the concurrency behind "no overbooking" are untested.

---

## 5. hld-file-sync

**Stated NFRs**
- `README.md:265-283`. `commit-file!` and `register-blocks!` must be "proportional to the length of the submitted list". Point queries take constant reads "independent of how many versions, files, or blocks". `get-changes` is proportional to `limit`. No operation may use "an unbounded per-namespace collection (all files, a file's whole version history, the block index, or the journal) as one value".
- `wait-for-processing!` covers every client of one module (`:288-291`).
- A replay "creates no second version and no second conflict copy" (`:114-115`).

**Implied NFRs**
- Change notification and long-poll latency: out of scope (`:13-14`).
- Block deduplication that stays cheap as the index grows (covered).
- Cursor paging that never misses or repeats entries (covered functionally).
- Exactly-once versioning under engine retry.
- A large shared namespace as a hot key.

**Tested NFRs (all private)**
- `performance_test_support.clj:18-30` counts all four hook metrics.
- The fixture has a small namespace (10 blocks, 5 versions, 3 files) and a big one (1,500 blocks, 1,200 versions, 300 files) (`:63-75`). A 150× growth ratio is far larger than the other four challenges use.
- `register-blocks!` of 5 blocks: absolute limits of reads < 80 and writes < 60, plus reads growth `≤ 30 + 2·s` (`:77-87`).
- A head `commit-file!`: reads < 100, writes < 60, plus growth (`:89-104`).
- Conflict-copy, rejected `:need-blocks` and replay commits have absolute bounds (`:106-125`).
- Point reads: `get-file`, `get-file-version` at version 600 of 1,201, `get-outcome`, `get-block-size`, and a missing file, each with reads < 25 and iterator reads < 10 (`:127-141`).
- `get-changes` shallow versus deep, a 500-row page, and empty tails in small and big namespaces at limit 50 and limit 500 (`:143-169`).
- A 1,024-entry register and commit bounded by `100 + 3·1024` reads and `100 + 2·1024` writes, which is proportional to the input (`:192-211`).
- Per-task depot-read distribution is **printed, not asserted** (`:171-190`).
- Runs at `{:tasks 2 :threads 2}` and `{:tasks 4 :threads 2 :workers 2}` (`:216-217`). This is the only one of the five that runs on multiple workers.

**Gaps and plausible wrong designs that pass**
- *A file's version history stored as one value.* The README forbids it (`:272`), but the tests cannot see it. Wrong design: `{:files {fid {:versions [v1 v2 ...]}}}` with a plain vector. `get-file-version` then costs 1 read, `commit-file!` costs 1 write, and every bound passes. Admitted at `:9-11`. Unlike the journal, there is no range query on versions, so an iterator proof does not apply either.
- *No retry injection.* Wrong design: a stream topology that allocates the journal seq and version, then appends a conflict copy through a second depot. A retry creates two `~rid` copies or skips a seq.
- *Partition balance is diagnostic only.* A global-partition design passes.

**Test weaknesses**
- Mostly absolute ceilings (in the chat-app style) plus growth. This is the strongest shape of the five.
- The conflict-copy, rejection and replay probes lack a small-namespace pair, but their absolute bounds are well under the big-history size, so this is fine.

**Reference-impl concerns**
- Would fail a "no partitioner" test: `+group-by` at `module.clj:295`.
- Its own notes (`IMPLEMENTATION_VALIDATION.md` ":allow-yield?") deliberately use non-yielding point reads inside the loop. This is fine.

**Recommended tests**
1. **Iterator proof on `get-changes`**: `(<= 1 iterator-reads (+ limit 3))` for limit 50. This kills a journal-as-sorted-map-value design.
2. **Stream-retry helper** around a stale commit. Assert exactly one `~rid` copy, contiguous seqs, and that the head version is unchanged.
3. **Optional, needs a README line.** Turn the depot-read diagnostic at `:171-190` into an evenness assertion over `gen-hashing-index-keys` namespaces.
4. **Optional, weak.** Detect a version history stored as one value by timing. Compare median `get-file-version` latency over 50 calls at 5 versus 5,000 versions, allowing less than 3× growth. This is fragile in IPC and should be offered only as a diagnostic.

**Verdict: ADEQUATE.** Every stated resource bullet is tested, with both absolute and growth bounds, input-size proportionality, and runs on multiple workers. What remains is the blob blind spot every challenge shares, plus the missing engine-retry test.

---

## Summary table

| Challenge | Verdict | Top gap (a wrong design that passes today) |
|---|---|---|
| hld-payment-system | PARTIAL | No engine-retry or exactly-once test: a stream design that appends journal rows via an internal depot duplicates them on retry. The journal as one sorted-map value is also undetected. |
| hld-stock-exchange | PARTIAL | The README's own "book must not be one value" rule (`:258`) is untested: a whole-book value passes `stable-work!` with 0 iterator reads. The latency class is absent. |
| hld-ticketing-system | PARTIAL | `hold-seats!`, `confirm-hold!`, `release-hold!` and `add-seats!` costs are never measured: scanning the event's holds to decide availability passes. There is no concurrent no-double-sell race. |
| hld-hotel-reservation | PARTIAL | `cancel-booking!` and multi-night stay costs are unmeasured: a cancel that recomputes availability by scanning bookings passes. There is no concurrent last-room race. |
| hld-file-sync | ADEQUATE | A file's version history as one value (plain vector) passes every count bound. There is no retry injection. |

The cheapest additions, needing no README or reference change:

1. An auction-style `failed-streaming` retry test in all five.
2. `(pos? :rocks-iterator-read)`, bounded by `limit`, on every paginated query in all five.
3. Measuring the unmeasured mutating commands in ticketing and hotel.
4. A concurrent multi-client race in ticketing and hotel.

A bank-style "no partitioner" or `#{:microbatch}` test would first require changing the README, and changing the `+group-by` template in the references as well.

---

# NFR coverage audit: hld-job-scheduler, hld-notification-system, hld-web-crawler, hld-feature-flag-service, hld-enterprise-rag

Scope: static reading of README, protocol, all of `test-private/`, and the reference in `test-resources/`. No tests were run. None of the five challenges has a public `test/` directory: the `:test` alias points at `implementations/<name>/test`, so **every NFR assertion below is private**.

## Cross-cutting findings (apply to all five)

These patterns hold across all five challenges, so they are listed once here. Each challenge section refers back to them.

**X1. Blob blindness.** Every cost test counts RocksDB *operations* (`:rocks-read`, `:rocks-iterator-read`, `:rocks-commit` `:write-batch-count`). A design that keeps a growing collection as one non-subindexed PState value costs 1 read and 1 write, whatever its size. So "growth" tests (small population vs large population, compared) cannot detect it. Examples of such blobs: all claims of an execution, all pending URLs of a host, all recent submission IDs of a user, or a token's whole posting list. The gold standard closes this gap with `(pos? (capture-iterator-reads ...))` on reads that must be subindexed (auction `performance_test_support.clj` lines 107-113). None of the five uses a "must use an iterator" check.

**X2. No fault injection.** None of the five forces `:streaming-complete` to `:fail`. Only auction-module does this in the repo (`failed-streaming`, auction perf support lines 21-29). All five references use a single microbatch topology, so they are exactly-once by construction. Nothing requires microbatch, though, and nothing tests stream-retry idempotency. In every challenge the natural write path has two or more sequential PState writes guarded by a "has this already happened?" check. Under a stream retry, that guard sees the first write and skips the rest. The result is lost secondary writes or wrong decisions (concrete cases per challenge below).

**X3. No topology-type assertion.** No test inspects `:topology-event :type`. None of the five READMEs states a visibility class, so this only matters where X2 matters.

**X4. Stated "balanced across tasks" is never tested.** Notification (README:106-107) and crawler (README:128-129) state it outright. All five launch 2 and 4 tasks. In every case, though, a `|global` or single-partition design passes. RocksDB events carry `:task-id`: sibling HLD challenges already bucket ops per task (`hld-rate-limiter/.../performance_test_support.clj:45-55`), and bank-transfer uses `rtest/gen-hashing-index-keys` plus `:depot-read`/`:partitioner` (bank perf support lines 23-44).

**X5. Only a subset of operations is measured**, and often only the no-op or denial path of a write. Details per challenge.

**X6. Thresholds are relative (small vs large), not absolute.** This is deliberate, because layout is free. Relative bounds do catch O(history) scans. They cannot catch a constant but large per-event cost, such as rewriting all 8 devices or all 32 nodes on every write. That is acceptable here because those entities are bounded.

**X7. Fixed `threads = tasks`** (feature-flag: `:threads 2`). The gold standard randomises `tasks ∈ {2,4}` and `threads ∈ [2, tasks]`. This is minor.

---

## 1. hld-job-scheduler

### Stated NFRs
- README:148-158 "Efficiency contract": work may be proportional to the one DAG (at most 32 nodes). "No operation may do work proportional to the number of executions, claims in other executions, or workers." "Reads of one execution must not read state belonging to any other execution."
- README:180-182: all business state durable, and the module runs on 2 and 4 tasks.
- README:175-178: per-execution write order.
- Claim-ID idempotency and fencing (README:68-99; protocol:44-61). These are correctness rules, but they are the fault-tolerance contract of the domain.
- Explicitly excluded (README:38-41): external exactly-once.

### Implied NFRs (from the case study; not in the README)
- *Implied:* no double-grant under retry or crash. The source's core promise is "at most one live lease per task, and fencing tokens survive failover." In Rama this becomes idempotency of `claim!` under stream retry (X2).
- *Implied:* a claim or advance costs O(DAG), not O(claim history of the execution). A long-running execution accumulates many claim decisions (retry storms). The README only forbids growth with claims *in other executions*. The test does grow same-execution claims (see below), but only on some paths.
- *Implied:* low dispatch latency (the source aims for sub-second scheduling). Not in the README, and not needed given the explicit logical clock.

### Tested NFRs (all private)
| NFR | Mechanism | Location |
|---|---|---|
| Point, iterator and write ops do not grow from 256 to 1024 unrelated executions **and** 256 to 1024 same-execution denial decisions | `with-event-hook` summing reads, iterators, iterator-reads and commit batch counts; `after <= max(12, 8 + 3*before)` for 8 probes | `independent_test_support.clj:7-17, 19-32, 34-38, 61-66` |
| Durable state across `update-module!` | `rtest/update-module!` then re-read | `functional_test_support.clj:96-110, 149-151` |
| 2 and 4 tasks, two clients | `doseq [tasks [2 4]]` | `functional_test_support.clj:14, 113`; `independent_test_support.clj:41` |
| Claim-ID replay idempotency (semantic) | replay with different args | `functional_test_support.clj:33, 81-93` |

### Gaps, each with a plausible wrong design that passes every test
1. **Effective `advance-clock!` cost is never measured.** The probe calls `(p/advance-clock! client "target" 0)` (`independent_test_support.clj:29`) while the target clock is already 0, so the write is a no-op. *Wrong design:* on every effective clock advance, iterate over the execution's claim decisions (or a per-execution lease index built from them) to find expired leases. This is O(claim history) and invisible to the tests.
2. **Granted-claim and effective-completion paths are unmeasured.** `:denial` measures a `:dependencies-incomplete` denial and `:completion` uses token 99 (`independent_test_support.clj:25, 31`), so both take early-exit paths. *Wrong design:* on grant, write a per-worker "active leases" record and scan it, or recompute `:attempts` by counting granted decisions in the claims map. Neither shows up.
3. **Blob blindness (X1).** *Wrong design:* `{exec-id {:state … :claims {claim-id decision}}}` with `:claims` **not** subindexed. Every claim then rewrites the full decision map (O(n) bytes, 1 write). `get-claim` loads all decisions (1 read). The 256-to-1024 growth test passes, because op counts are flat.
4. **Stream-retry double-decision (X2).** *Wrong design:* a stream topology that writes the node lease/token, then separately writes the claim decision. If a retry lands between the two writes, the replay finds no decision for the claim-id, re-decides against the now-leased node, and records `:lease-held`. The worker holds token N with no granted decision, and `:attempts` has been bumped. No test injects failure.
5. **"Work proportional to workers" is not exercised.** Every test uses at most a handful of worker IDs. *Wrong design:* a global `$$worker-leases` partitioned by worker, read on every claim, would not be stressed.

### Test weaknesses
- The bound `max(12, 8 + 3*before)` gives 3x headroom against a 4x population increase. A partial scan of about a quarter of the history (for example, scanning only granted decisions) could slip under it.
- All measured paths are denials or no-ops (gaps 1 and 2).
- Correctness under `update-module!` is checked, but that is a clean restart, not a mid-batch failure.

### Reference-impl concern
None would fail a reasonable NFR test. The reference is microbatch (`module.clj:81`), and its claims are subindexed per execution (`module.clj:83-85`). `ExecutionState` is an opaque record blob containing the whole DAG and node map (`module.clj:13, 84`). That blob is bounded at 32 nodes, so it is acceptable, but agents will copy the "whole-record `termval`" idiom.

### Recommended tests
1. **Effective-path probes:** measure `claim!` → grant, `advance-clock!` to `clock+11` (expires the lease), and a matching `complete!`, at 256 and 1024 same-execution decisions. Use the same growth bound. This catches gaps 1 and 2. No README change is needed.
2. **Subindex check:** `(pos? iterator-reads)` is not applicable to point reads. Instead, assert that `:rocks-commit` `:write-batch-count` for one claim is at most a constant (for example, 6). Also run a 5k-decision execution with a wall-clock guard. Alternatively, require iterator use on a new paged read. The cleaner option is not to add any README requirement and accept X1 as a documented limitation.
3. **Stream-retry injection:** wrap one `claim!` in `failed-streaming`. Then assert that `get-claim` shows `:granted? true :token 1` and `:attempts` is 1. This is harmless for microbatch designs and catches gap 4. No README change is needed, because the README already requires the idempotent single decision.
4. **Balance (X4):** 2×tasks executions chosen with `gen-hashing-index-keys`. Assert that per-task `:rocks-commit` counts are non-zero on every task. This needs README text only if balance is to be a hard requirement; today the README only says "runs with 2 and 4 tasks".

### Verdict
**PARTIAL.** A real growth harness exists, but it measures only the no-op and denial paths, and there is no retry or fault injection for a domain whose whole point is fencing under failure.

---

## 2. hld-notification-system

### Stated NFRs
- README:94-107 "Bounded-work contracts (enforced)":
  - `submit!` is fixed work bounded by 8 devices and independent of history.
  - Attempt and receipt are fixed per delivery.
  - Point reads are fixed.
  - Pages are bounded by 100 entries.
  - "Work and storage are balanced across tasks" (README:106).
- README:97: "enforce the work bounds … by construction (large histories, hot keys)".
- README:87-92 workload: up to 100,000 submissions and 100,000 dead letters per recipient.
- README:109-116: latency figures are explicitly *not* thresholds.
- Idempotent first-wins `submission-id` (README:42-45; protocol:56-70). Guarded attempts (protocol:72-95).

### Implied NFRs
- *Implied:* exactly-once dead-lettering and delivery-state transitions under retry. The case study's defining concern is "at-least-once provider calls, and dedupe so users are not double-notified". Here that becomes: a retried report must not add a duplicate DLQ entry or lose one.
- *Implied:* global uniqueness of `submission-id` without a global bottleneck. At 100k submissions per second, a single-partition dedupe is the classic wrong answer.
- *Implied (excluded by README):* priority-lane isolation (transactional vs bulk), campaign fanout, and provider rate limits. These are untestable without API changes. Not a gap.

### Tested NFRs (all private)
| NFR | Mechanism | Location |
|---|---|---|
| Point, recent-page, DLQ-page, submit, attempt and receipt costs do not grow from 240 to 1040 history on one 8-device user | hook: reads, iterators, commit batch counts; `large <= small + 150` per metric | `independent_test.clj:10-30, 32-56, 58-90` |
| Instrumentation sanity | `pos?` total | `independent_test.clj:26-27` |
| Durability of pending-retry state across `update-module!` | re-read `:next-attempt-at` and `:attempts` | `independent_test.clj:92-128` |
| 2 and 4 tasks, second client | per-deftest | `notification_test.clj` (whole file) |
| First-wins and guards (semantic idempotency) | replayed and stale reports | `notification_test.clj:21-47, 157-168` (file-relative) |

### Gaps and wrong designs that pass
1. **Balance is stated but untested (X4, README:106).** *Wrong design:* dedupe `submission-id` by routing every submit through `|global` (a `$$seen-submissions` on task 0), then `|hash user`. All tests pass on 2 and 4 tasks, but every submission serialises on one task.
2. **Blob blindness on per-user history (X1).** *Wrong design:* `{user {:recent [sid …]}}` as a plain growing vector, or `:dead-letters` as a non-subindexed sorted map. `get-recent-submissions` does 1 read and `(take 100 (rseq v))`. Submit does 1 read and 1 write. The 240-to-1040 comparison is flat. With 100k entries (README:90), each submit rewrites megabytes.
3. **Lost or duplicated dead letters under stream retry (X2).** *Wrong design:* a stream topology. It writes the delivery `:failed` on the submission partition, then `|hash user` and appends a DLQ entry with a `dl-seq` counter. If a retry happens after the first write, the replay sees `:failed`, the guard (protocol:77) drops it, and the **DLQ entry is lost**. If the failure lands after the counter increment but before the append, the entry is duplicated at a new seq. The reference layout shows the same shape: `module.clj:167-188`. It is safe only because it is microbatch.
4. **Device invalidation across partitions under retry** has the same shape as gap 3: the delivery is set to `:invalid-token` and then the profile invalidation happens on the user partition (`module.clj:176-180`).
5. **`get-devices` and `get-preferences` cost is not measured.** Both are bounded (8 devices, 16 categories), so this is low risk.

### Test weaknesses
- `+150` absolute headroom per metric (`independent_test.clj:29`) against a +800-entry history. That is fine for full scans, but a design that scans *the last 250 entries* per page passes.
- The "hot key" is only 8 devices plus 1040 history on one user. The per-task skew of a hot user is never examined (it is inherent, which is fine), and neither is the non-hot path's spread (gap 1).
- Update-module is a clean restart, not failure injection.

### Reference-impl concern
Nothing would fail a reasonable NFR test. Submits hash by user. First-wins arbitration uses `+group-by sid` inside the batch plus `$$task-pos` ranks (`module.clj:124, 137-154`). That is balanced, but it is unusual and heavy: it adds a second batch phase and a per-task position PState to get deterministic first-wins. A simpler idiomatic form would route `Submit` by `submission-id` first (a check-and-set on the sid partition), then `|hash user`. If a balance test is added, the reference should pass it. `$$task-pos` is written once per submit per task, which is fine.

### Recommended tests
1. **Balance:** submit to users from `gen-hashing-index-keys` across all tasks, capture per-task `:rocks-commit`, and assert every task saw writes and that max/min ≤ 3. This catches gap 1. The README already states the requirement.
2. **Subindex check:** `(pos? (capture-iterator-reads #(p/get-recent-submissions c user)))` and the same for `get-dead-letters`, following the gold-standard pattern. This catches gap 2 without a README change, because the README already says "read work bounded by the 100-entry page".
3. **Retry injection:** wrap `report-attempt! … :permanent-failure` and a `:invalid-token` report each in `failed-streaming`. Then assert exactly one DLQ entry for that (sid, device) and that the device is invalidated. This catches gaps 3 and 4.

### Verdict
**PARTIAL.** Page and history growth are tested well. Stated balance is untested, blob storage passes, and there is no retry injection for a system whose core promise is exactly-once notification.

---

## 3. hld-web-crawler

### Stated NFRs
- README:113-129 "Bounded-work contracts (enforced)":
  - `discover!` is fixed per URL, independent of queue size.
  - `claim!` is fixed plus amortized per blocked URL, "must not scan the host's queue".
  - `complete!` is fixed.
  - `get-claim`, `get-url` and `get-host` are fixed. `:queued` "must not be computed by scanning".
  - `list-pending` is bounded by `limit`.
  - Balanced across tasks.
- README:104-111 workload: up to 1M pending URLs per host, and up to 1,000 consecutive blocked head URLs.
- Idempotent claim-id and fencing (protocol:72-102).

### Implied NFRs
- *Implied:* the frontier survives worker and processing failures without double-leasing a URL or losing the `:queued` count. The case study stresses "at-least-once fetch with dedup, no URL lost on crash".
- *Implied:* global exact dedup without a global chokepoint. Dedup here is per host, because canonicalization pins host, so hash-by-host is the natural answer.
- *Implied:* bounded per-host memory during bursty discovery (a hot host at 50k URLs per second).

### Tested NFRs (all private, in `frontier_test.clj`)
| NFR | Mechanism | Location |
|---|---|---|
| Claim cost (10 skips) does not grow from 220 to 1000 retired, 220 to 1000 claim-history, 520 to 1300 queued | `capture-work` sums reads, iterators, iterator-reads, `:local-select`, `:local-transform`; `large <= small + 100` | `:19-30, 263-301` |
| `list-pending` limit 1/20/100 cost does not grow with population | same | `:280-285, 298-301` |
| Blocked-skip correctness up to 1000 skips (cost only checked `pos?`) | `capture-work` + `pos?` | `:166-190` (`:181`) |
| Pagination cost observed | `pos?` only | `:252-253` |
| Durability across `update-module!` | re-read | `:303-338` |
| 2 and 4 tasks, second client | `with-frontier` | `:7-14` |

### Gaps and wrong designs that pass
1. **`discover!` cost is never measured** (the README states it is fixed per URL, independent of queue size). *Wrong design:* on each discover, read the host's whole pending set to recompute `:queued` as `(count pending)`, or to re-sort the queue. No test captures a `discover!`.
2. **Writes are never counted.** `capture-work` omits `:rocks-commit` (`:23-25`). *Wrong design:* on each claim, rewrite every blocked or retired URL's status record, or re-persist the whole pending set. Write amplification is invisible even on the one measured claim.
3. **`get-host` (`:queued` "not by scanning"), `get-url`, `get-claim` and `complete!` are unmeasured.** *Wrong design:* `get-host` computes `:queued` via `(foreign-select-one [(keypath h :pending) (view count)])` over a non-size-tracked set, or over a count of `:urls` whose status is `:queued`. That is O(queue) per read and passes.
4. **Blob blindness (X1).** *Wrong design:* `{host {:pending (sorted-set …)}}` stored non-subindexed, which is a very natural Clojure-shaped choice. Claim and list are then 1 read each, and discover is 1 read and 1 write. Every growth bound passes, yet each discover rewrites up to 1M URLs.
5. **Balance (X4, README:128) is untested.** *Wrong design:* a global canonical-URL dedup set on `|global` before `|hash host`.
6. **Stream retry (X2).** *Wrong design:* a stream topology whose discover writes the URL record, then the pending entry, then the `:queued` counter. After a mid-event retry, the "already seen" guard skips the URL, so `:queued` is **under-counted** permanently. For claims, a retry after the lease and fence are persisted but before the claim record exists re-decides to `:busy`, which burns a fence and strands the lease until expiry.

### Test weaknesses
- `capture-work` mixes `:local-select` and `:local-transform` (topology op counts) with storage counts, and drops commits. The `+100` bound on the mixed sum is loose for list-pending at limit 1 (a design reading 100 extra entries passes).
- The 1000-skip scenario checks only `pos?` (`:181`), so "amortized fixed per blocked URL" is not bounded. A design that re-reads the whole blocked prefix on each chunk (O(skips²)) passes.
- Measured claim history is all `:empty` denials at tick 2 (`:275`), so the growth test grows the claims map but never grows lease or fence churn.

### Reference-impl concern
- `+ordered-commands` accumulates **all** commands for a host within one microbatch into an in-memory vector before processing (`module.clj:38-43, 68-70`). For a hot host under burst discovery this is unbounded memory per microbatch, which conflicts with the implied bounded-memory NFR. A per-partition `:>` loop in depot order (the depot already hashes by host, `module.clj:46`) would avoid the `+group-by` shuffle and the buffer. An NFR test that bounds memory is hard to write in IPC, but the idiom is questionable.
- If a `discover!` write bound were added, the reference would pass: it does a point read and conditional writes per URL, then one info write (`module.clj:77-96`).
- `get-host` is O(1) because `:queued` is a maintained counter (`module.clj:53, 225-227`), so the reference would pass gap-3 tests.

### Recommended tests
1. **Discover and complete growth:** measure one 100-URL `discover!` + barrier and one `complete!` + barrier at 520 vs 1300 queued. Include `:rocks-commit`. Bound: `large <= small + 20` per metric. This catches gaps 1 and 2.
2. **Point-read probes:** `get-host`, `get-url` and `get-claim` at both sizes. Bound: `large <= small + 4`. This catches gap 3. Add `(pos? iterator-reads)` for `list-pending` to catch gap 4 (the README already says "bounded by limit").
3. **Amortized skip bound:** for skipped ∈ {64, 1000}, assert that claim reads plus iterator-reads ≤ c·skipped + k (for example, 3·skipped + 50). Assert writes ≤ 3·skipped + 10.
4. **Retry injection plus balance:** `failed-streaming` around a discover batch and a granting claim. Then assert that `:queued` equals the true count, and that the claim is `:granted` with fence 1. Separately, run per-task commit counts over `gen-hashing-index-keys` hosts.

### Verdict
**PARTIAL.** Claim and list growth are tested, but 5 of the 8 operations with stated bounds are never measured, writes are never counted, and the stated balance is untested.

---

## 4. hld-feature-flag-service

### Stated NFRs
- README:147-157:
  - `evaluate` and `get-flag-config` "must read only the one flag's configuration. Work must not grow with the number of flags, envs, or tenants."
  - `put-flag-config!` is proportional to its supplied config.
  - `compute-bucket` must not read stored state.
- README:170-178: per-flag ordering. README:175-177: durable state, 2 and 4 tasks.
- Excluded (README:33-36): SDK distribution, SSE and propagation, capacity.

### Implied NFRs
- *Implied (excluded):* evaluation at sub-millisecond latency in the SDK, and kill-switch propagation within seconds. The README says evaluation is "a read against durable state; where it runs … is your choice", so this is out of scope.
- *Implied:* a hot flag (every request evaluates it) is a read hot-key. Mitigation lives in SDK caching, which is excluded. It is not testable meaningfully in IPC.
- *Implied:* idempotent writes. The revision guard makes puts naturally idempotent under retry, so no fault-injection gap of consequence exists.

### Tested NFRs (all private, `private_test_support.clj`)
| NFR | Mechanism | Location |
|---|---|---|
| get, evaluate and put reads/writes do not grow from 240 to 1240 unrelated flags across 17 tenants and 11 envs | hook, `large <= 12 + 3*small` | `:25-36, 174-213` |
| `compute-bucket` does zero stored-state work | `(= {:reads 0 :writes 0} bucket-work)` | `:188-199` |
| Durable across `update-module!` | re-read after update | `:222-230` |
| Ordering of equal revisions in one unsynchronized group | semantic | `:165-172` |

### Gaps and wrong designs that pass
1. **Blob blindness (X1).** *Wrong design:* store one map per `[tenant env]` (a "config bundle", which is plausible because the source case study distributes env bundles to SDKs). `get-flag-config` then reads the bundle and picks the flag: 1 read. Put does a read-modify-write of the bundle: 1 read and 1 write. This violates "read only the one flag's configuration", but the 240-to-1240 test cannot see it. Its 1240 flags are spread over 187 env buckets anyway, so each bundle holds only about 7 flags. Even byte growth would be small.
2. **Balance (X4).** *Wrong design:* `(hash-by (constantly "all"))` or `|global` for every flag. Every test passes. The README does not state balance, so this is implied only.

### Test weaknesses
- The population is spread across 187 (tenant, env) pairs, so even a per-env scan grows only from about 1.3 to 6.6 flags per env. That is well inside `12 + 3*small` (`:211`). *Wrong design:* `evaluate` does `foreign-select [(keypath tenant env) MAP-VALS]` over a per-env subindexed map and filters by flag key. This passes. To distinguish it, concentrate the 1000 extra flags in the **same** `acme/prod` env as the probed flag.
- `:threads 2` is fixed (`:42`).

### Reference-impl concern
None. It uses one PState keyed by `FlagId` with a revision guard (`module.clj:31-51`), and evaluation is a client-side point read (`module.clj:102-104`). This is the minimal idiomatic answer.

### Recommended tests
1. **Same-env growth:** repeat the growth sample with all extra flags under `acme/prod`. Bound: `large <= small + 4`. This catches the per-env scan and makes an unsubindexed per-env bundle at least visible via a size-dependent write. No README change is needed.
2. Optionally, a per-task commit spread over `gen-hashing-index-keys` flag IDs. This would need a README line on balance.

### Verdict
**ADEQUATE (borderline).** The stated contract is narrow and mostly tested, and puts are idempotent by construction. The one real weakness is that the unrelated population lives in other envs rather than in the probed flag's own env.

---

## 5. hld-enterprise-rag

### Stated NFRs
- README:152-167 "Efficiency contract":
  - `query` may examine token/chunk matches within the tenant, "must not scan documents or chunks that contain none of the query tokens, and it must never read another tenant's data".
  - Put and delete are proportional to one document's chunks and "must not touch other documents".
  - ACL and membership writes are independent of document and chunk counts. "Changing a user's groups must not perform document- or chunk-proportional work."
  - Gets read one entity.
- README:22-26: authorize before top-k. This is semantic, and it is tested.

### Implied NFRs
- *Implied:* ACL revocation takes effect promptly and is never lost. The source's security posture is that a stale index must not leak revoked documents. In Rama this means ACL and membership writes must not be lost under retry, and must be exactly-once when denormalized.
- *Implied:* index-maintenance idempotency. A re-ingested (retried) put must not leave orphaned or missing postings.
- *Implied:* hot-token skew. Common tokens concentrate on one posting partition. This is inherent to an inverted index and fine to leave untested.

### Tested NFRs (all private, `contract_test.clj`)
| NFR | Mechanism | Location |
|---|---|---|
| Query reads do not grow from 256 to 2048 unrelated documents (half in tenant `t` with non-matching tokens; half in tenant `other` **containing** the query token) | `rocks-cost` reads; `large <= small + 16` | `:178-188, 199-221, 236-238` (`:205` puts "target" in other tenant) |
| Query reads do not grow with 31 non-matching chunks on the matching document | same | `:223-241` |
| ACL write reads and writes do not grow with the unrelated corpus or the target document's 32 chunks | same | `:214-216, 242-253` |
| Durable across `update-module!` | re-read | `:159-176` |
| Tenant isolation (semantic) | same IDs in two tenants | `:112-157` |

### Gaps and wrong designs that pass
1. **`put-user-groups!` cost is never measured**, despite the explicit "must not perform document- or chunk-proportional work" (README:165-166). *Wrong design:* this is exactly what the source's "pre-filter" invites: a per-user materialized eligible-chunk (or per-user posting) index, rebuilt on membership change by scanning the tenant's documents. Queries become cheap and all tests pass.
2. **`put-document!` and `delete-document!` cost are never measured** ("must not touch other documents"). *Wrong design:* the posting list per token is a non-subindexed set, and each put does a read-modify-write of each touched token's whole list. That is 1 read and 1 write per token regardless of the list's size, and the query-side growth test is blind to it too, because a blob read counts as 1 (X1). The test token "target" only ever has one posting in tenant `t`, so even byte growth would not appear.
3. **`get-document` and `get-user-groups` are unmeasured.** These are low risk.
4. **Stream retry loses postings (X2).** *Wrong design:* a stream topology that writes `$$docs` content-revision first, then diffs and writes postings (the same order as the reference, `module.clj:78-89`). On retry, `accepted?` sees the revision already stored and skips, so **postings are never written or removed**. The document is live, but unsearchable or still searchable after tombstone. In the reference this is safe only because it is microbatch (`module.clj:37`).

### Test weaknesses
- Growth is checked on reads only for query. Query writes should be 0; that is not asserted, though it is minor.
- The query probe uses `k = 1` and one matching chunk. It does not distinguish "reads all postings for the token" from "reads k", which is fine given the contract allows examining all matches.

### Reference-impl concerns (idiom, not NFR failure)
- The query runs client-side as N+1 `foreign-select-one` calls: one per token, then one per candidate document (`module.clj:150-166`). That is a round trip per candidate document. It fits the contract (op counts), but the idiomatic Rama shape is a query topology that fans out to token partitions, batch-looks-up docs, and aggregates top-k. If a round-trip or latency NFR were ever added, the reference would fail it.
- Each posting entry stores the **full chunk payload** (text + tokens) (`module.clj:28-33, 48-51`). A 32-chunk, 32-token document writes up to 1024 copies of chunk text. That is within "proportional to that one document's chunks", but it is heavy write amplification that agents will copy.
- A test for gap 1 or gap 2 would pass on the reference: membership is a single write (`module.clj:59-64`), and postings are subindexed.

### Recommended tests
1. **Membership cost:** measure `put-user-groups!` + barrier at 256 vs 2048 documents in the tenant. Bound: reads and writes `<= small + 4`. This catches gap 1. The README already states it.
2. **Put and delete cost:** measure a 1-chunk `put-document!` (sharing a common token with all 2048 noise documents), then a `delete-document!`, at both sizes. Bound: reads, iterator-reads and writes `<= small + 16`. Add `(pos? iterator-reads)` for the query on that common token to force subindexed postings. This catches gap 2 and X1.
3. **Retry injection:** `failed-streaming` around one `put-document!`, then assert that the query finds it. Then `failed-streaming` around `delete-document!`, then assert that the query no longer finds it. This catches gap 4.

### Verdict
**PARTIAL.** Query and ACL growth, including cross-tenant isolation, are well designed. Membership, put and delete costs, which are explicitly stated, are never measured, and posting maintenance is not tested under retry.

---

## Summary table

| Challenge | Verdict | Top gap |
|---|---|---|
| hld-job-scheduler | PARTIAL | Growth probes measure only the no-op and denial paths (`advance-clock!` to 0, `complete!` with token 99); effective grant, advance and complete are unmeasured, and there is no stream-retry test of claim idempotency |
| hld-notification-system | PARTIAL | Stated "balanced across tasks" is untested (a `|global` submission dedupe passes); a non-subindexed per-user history blob passes; no retry test for DLQ exactly-once |
| hld-web-crawler | PARTIAL | `discover!`, `complete!`, `get-host` (`:queued` "not by scanning") and writes (`:rocks-commit`) are never measured; a non-subindexed pending-set blob passes |
| hld-feature-flag-service | ADEQUATE (borderline) | Unrelated flags sit in other envs, so a per-env scan or bundle blob passes; concentrate the population in the probed env |
| hld-enterprise-rag | PARTIAL | `put-user-groups!`, put and delete costs are explicitly bounded in the README but unmeasured (a per-user materialized-eligibility design passes); no retry test for posting maintenance |

---

# NFR test coverage audit: benchmark modules

These five are the gold-standard benchmark challenges. Each has a private `performance_test_support.clj` alongside functional tests. Each test launches on 2 or 4 tasks, chosen at random.

## 1. auction-module

**Stated NFRs**
- None beyond "use simulated time" for expiry checks. The README states no cost, latency or fault-tolerance rule.

**Tested NFRs (all private)**
- Topology type: a `:topology-event` hook requires `:stream` for `list-item!` and `bid!` (the test message cites "millisecond visibility").
- Exactly-once under retry: `failed-streaming` fails the first `:streaming-complete` of `list-item!` and of two `process-expirations!` calls. Seller, winner and loser must each get exactly one notification.
- Bounded expiry work: a `:local-select` hook must see `:allow-yield?` true during expiry processing.
- Subindexing: `get-listings`, `get-bids` and `get-notifications` must each record a non-zero `:rocks-iterator-read` count.
- Unique listing IDs across two clients.

**Gaps (with a wrong design that passes)**
- Every tested NFR is unstated. A solver must infer stream topology, retry safety and subindexing from the domain. This is a README gap, not a test gap.
- Read cost is checked only as "iterator used", never bounded. Wrong design: `get-bids` pages the whole subindexed bid map then sorts on the client. It passes with 3 bids.
- No concurrent-bid race. Wrong design: `get-highest-bid` read on the bidder partition and compared after a hop. It passes because bids arrive from one thread.
- No growth pair on expiry. Wrong design: expiry scans every listing with `:allow-yield? true`. It passes the flag check.

**Verdict: PARTIAL.** The retry and topology tests are strong and reusable. The README states none of what they enforce, and read cost is proven only by "iterator used".

## 2. bank-transfer-module

**Stated NFRs**
- Fault tolerant: "never double process or fail to process" deposits or transfers.
- Transfer IDs are unique, so no client dedupe is needed.

**Tested NFRs (all private)**
- Exactly-once proxy: every topology event during deposits and 49 transfers must be `#{:microbatch}`.
- Write and read cost: a deposit is exactly `{:rocks-read 1 :rocks-writes 1}`. A transfer is at most 8 reads and 6 writes.
- Subindexed history: `get-incoming-transfers` and `get-outgoing-transfers` over 50 transfers are exactly 1 read, 1 iterator and 50 iterator reads.
- Balance: deposits to keys from `gen-hashing-index-keys` must give equal `:depot-read` counts per task, with no `:partitioner` events.

**Gaps (with a wrong design that passes)**
- No retry injection. Microbatch type is a proxy. Wrong design: a microbatch that appends the credit leg to a second internal depot, consumed by another microbatch. A retry duplicates the append; every test passes.
- No concurrent-transfer race. Transfers come from one client in sequence, so an overdraft race is never exercised. Microbatch makes this safe, but the test would not catch a stream fallback that also passed the type check.
- Exact op counts are brittle: an equally good design with one extra read fails.

**Verdict: ADEQUATE.** The stated NFR (exactly once) is enforced by topology type, and cost, subindexing and balance are pinned tightly.

## 3. chat-app

**Stated NFRs**
- Workload: 10M users, 2,000 messages per second, rooms up to 50,000 members, 100,000 heartbeats per second.
- Visibility within 5 milliseconds for register, profile, room creation and posts.
- Fixed read work for `get-room-page`, `get-recent-threads` and `get-mentions-page`. `get-unread-counts` costs O(1) per room.
- Presence is online if a heartbeat arrived in the last 120 seconds.

**Tested NFRs (all private)**
- Write volume: heartbeats write fewer than 10 records. A post to a 300-member room writes fewer than 40. `mark-room-read!` writes fewer than 15 and reads fewer than 60.
- Read cost: page reads over a 250-message room stay under 250 reads. Unread counts over 10 rooms stay under 120. Recent threads with 10 replies each stay under 200. Mentions stay under 250. Online members stay under 400.
- Fault tolerance: `update-module!` keeps every durable view exact, resets presence to offline, and keeps processing writes.

**Gaps (with a wrong design that passes)**
- The 5 ms visibility bound is untested. Wrong design: every write goes through a microbatch topology. It passes all tests.
- No retry injection. Wrong design: a stream topology that bumps unread counters and appends derived records through an internal depot. A retry double-counts unread; no test fails.
- Mention and thread-participant fanout cost is not measured. Wrong design: a reply that rewrites every participant's recent-threads list as one value. It passes with 10 replies.

**Verdict: PARTIAL.** Write and read cost bounds are the best in the repo. The stated latency bound and retry safety of derived counters are not tested.

## 4. fanout

**Stated NFRs**
- No durable write per follower during fanout; per-follower state is in memory only, and must survive restart by another route.
- Fanout is balanced across tasks and fair: one post's delay on another must not scale with follower count.
- No permanent backlog. `post!` is visible on the user timeline within 5 ms; fanout completes within about a second.

**Tested NFRs (all private)**
- Write amplification: a post to 500 followers must write fewer than 20 RocksDB records, and all 500 must see it.
- Fault tolerance: after `update-module!`, a reader's timeline over 30 round-robin posts is rebuilt exactly, in strict order, with profiles attached, and new fanout still works.

**Gaps (with a wrong design that passes)**
- Balance is untested. Wrong design: fan out every follower on the poster's task. With one poster of 500 followers it passes.
- Fairness is untested. Wrong design: one microbatch processes a whole post's follower list before the next post. No test mixes a large and a small poster.
- Latency and backlog are untested. Wrong design: fanout in a slow tick with no chunk bound. It passes because tests wait for processing.

**Verdict: PARTIAL.** The per-follower write ban and restart recovery are tested well. Balance and fairness, two of the four numbered constraints, are not tested.

## 5. social-graph-and-fanout

**Stated NFRs**
- Near-optimal disk work and even CPU across tasks for any follower distribution (three example distributions given).
- The same five fanout constraints as fanout: no per-follower durable writes, balance, fairness, no backlog, one post per 5 seconds.
- Workload: 7,000 posts per second, 100 follows per second, at most 5,000 followees.

**Tested NFRs (all private)**
- Write amplification: a post to 500 followers writes fewer than 20 RocksDB records, and all 500 see it.
- Fault tolerance: the same restart-and-rebuild test as fanout.

**Gaps (with a wrong design that passes)**
- The headline property is untested. Wrong design: followers stored as one subindexed set per account on one task. A celebrity's fanout runs on one task; it passes.
- No growth or distribution test. Wrong design: tuned to a heavy tail, with a per-post full follower scan. No test compares distributions.
- `follow!`, `unfollow!` and `get-followers` cost is not measured. Wrong design: a follower list stored as one value. It passes with 500 followers.

**Verdict: PARTIAL.** Only the per-follower write ban and restart are tested. The distribution-independent balance and near-optimal work claims that define the challenge have no test.

## Summary table

| Challenge | Verdict | Top gap |
|---|---|---|
| auction-module | PARTIAL | Tests enforce stream topology, retry safety and subindexing that the README never states; read cost is checked only as "iterator used" |
| bank-transfer-module | ADEQUATE | No retry injection: a microbatch that appends a transfer leg to a second internal depot duplicates it on retry and passes |
| chat-app | PARTIAL | The 5 ms visibility bound is untested (all-microbatch passes); no retry test for derived unread counters |
| fanout | PARTIAL | Stated balance and fairness are untested: fanning out all followers on the poster's task passes |
| social-graph-and-fanout | PARTIAL | The distribution-independent balance and near-optimal work property is untested: one task per celebrity passes |
