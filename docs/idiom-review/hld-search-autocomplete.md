# Idiom review: HLD search autocomplete

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a`. This review covers the
challenge contract, private tests, reference module, the auction/chat reference
modules, and `plugins/rama-skill/skills/rama/SKILL.md`.

Provenance: first drafted in `6b5e5c2` (Amp). Two later revisions added the
construct audit, findings 1a, 1b, 6, 7, and 8, and the de-funnelling argument.
No revision was trusted. The final pass re-checked every claim and citation
against the tree and ran the private suite (see "Rewrite decision and
validation"). It made these corrections:

- Finding 1's de-funnelling counter-example was unobservable. A dropped
  old-generation search is erased by the next publish anyway. It now uses a
  future-generation search that must be ignored.
- Finding 1a now treats task-to-task FIFO as a documented guarantee
  (`references/stream.md:37`, `references/formal-model.md:592`), not an open
  question.
- Finding 7 now accounts for the `{:pkey …}` foreign-read idiom that auction
  uses. It also explains why that idiom cannot repair this layout.
- Finding 8 now notes that this module shares one barrier counter across
  wrappers. Chat and auction keep a counter per client. The recommended fix
  keeps the shared counter.
- Finding 2 now covers microbatch retry semantics and per-task commit
  visibility, and what each means for this module's reads.
- Numeric bounds now cite the README directly. Several test and reference
  line ranges were narrowed.

## Executive summary

Keep the current read-side architecture: `suggest` does a bounded sorted-set
range read from a precomputed prefix index, while phrase and generation lookups
are point reads. The exact-session deduplication requirement also justifies a
subindexed set of session IDs; a counter alone cannot distinguish duplicate
events. The largest design concern is write-side concentration at the locale
owner and retained state for every historical generation. Addressing either
requires preserving generation ordering, retry safety, and bounded snapshot
publication, so this review recommends **documentation only**, not a rewrite.
Two smaller idiom issues are cheap to fix but still undone:

- Four PStates are keyed by locale but placed by `[locale prefix]`
  (finding 7).
- The client wrapper holds a global lock around every append (finding 8).

Neither affects correctness today.

Several constructs named in the requested audit are not present in this
reference. It has no `:ingress-seq`, `+group-by`, `+vec-agg`, `loop<-`, or
query-time sort. The observed flow instead uses `hash-by :locale`, a microbatch
source, explicit key repartitioning, and precomputed sorted prefix indexes.
Those absent constructs are not findings against this module.

### Claimed-construct audit (verified against the current reference)

Paths below are relative to
`challenges/hld-search-autocomplete/test-resources/hld_search_autocomplete/`.

| Claimed construct | Present? | Evidence |
|---|---|---|
| Single multiplexed depot | Yes | `module.clj:36` declares only `*events (hash-by :locale)`. All four writes append plain maps tagged with `:op` (`module.clj:204-212`). Dispatch uses `<<cond`/`case>` on `*op` (`module.clj:68-88`) and again on `*work-op` (`module.clj:96-140`). |
| Microbatch topology | Yes, one | `module.clj:37` (`"core"`). The barrier is `wait-for-microbatch-processed-count` (`module.clj:220-222`). |
| `:ingress-seq` | No | `grep -nE 'ingress-seq\|\+group-by\|\+vec-agg\|loop<-\|sort'` on `module.clj` matches only `sorted-set-range-from-start` (`module.clj:168`). |
| `+group-by` / `+vec-agg` | No | Same grep. No aggregators appear in the module. |
| Query-time `sort` | No | Order is encoded in `rank-key` (`module.clj:15-16`) and read by a bounded range (`module.clj:167-168`). |
| `loop<-` repartition | No | The only repartitions are `\|hash *pk` (`module.clj:93`), `\|hash *short-pk` (`module.clj:153`), `\|all` (`module.clj:74`), and `\|hash`/`\|origin` in queries (`module.clj:164,172,177,185`). |
| Growing single-PState collections | Yes, all subindexed | Per-phrase session sets can reach 1M entries (`module.clj:49-54`; `README.md:78-79`). Per-prefix rank sets can reach every matching phrase: up to 10,000 snapshot plus 100,000 novel phrases (`module.clj:55-60`; `README.md:63,80`). Per-locale block sets (up to 10,000, `README.md:83`) are split across placement tasks (`module.clj:42,94-95,129-137`), because the top-level key is not the partition key (finding 7). Superseded generations are never removed (finding 4). |
| Client-side distributed queries | No | `suggest` and `get-phrase` each make one `foreign-invoke-query` (`module.clj:213-216`). `get-generation` makes one point `foreign-select-one` (`module.clj:217-218`). No client-side loops, fan-out, or joins. (By contrast, chat's `get-online-members` pages in a client loop: `challenges/chat-app/test-resources/chat_app/module.clj:564-580`.) |

## Findings and alternatives

### 1. A locale-keyed ingress is a useful ordering boundary, but a possible hot-key funnel

- `challenges/hld-search-autocomplete/test-resources/hld_search_autocomplete/module.clj:35-37`
  declares one `*events` depot partitioned by locale and one `core` microbatch
  topology.
- `.../module.clj:62-95` accepts/publishes against `$$owner` while processing
  events on the locale partition. Accepted phrase operations then repartition
  at lines 89-95 and 151-153.
- This gives each locale an ordering/acceptance point, which is valuable for
  the contract that a client's sequential writes to one locale are ordered.
  But all events for a very hot locale pass through one owner before spreading
  across phrase/prefix partitions. Depot placement distributes locales, not
  necessarily a skewed locale's processing. The skill's first implementation
  goal asks for balanced computation and warns against funnelling
  high-throughput writes (`SKILL.md:53`). This funnel is per locale, not
  global, but a hot locale reproduces it on one task.

**Idiomatic alternative:** Preserve a narrow per-locale generation/ordering
gate, then fan out accepted phrase work to key-aligned partitions; keep bulk
snapshot rows and independent policy operations on paths that avoid unrelated
work where that can be done without violating the publish boundary. The
auction reference demonstrates separate depots keyed by distinct owners
(`challenges/auction-module/test-resources/auction_module/module.clj:69-71`),
and chat uses a separate registration depot plus an actions depot partitioned
by acting user (`challenges/chat-app/test-resources/chat_app/module.clj:158-160`).
These are patterns to evaluate, not drop-in replacements: this challenge must
also reject stale-generation events and ensure a new snapshot's reset is
ordered with events. Do not remove the locale gate unless the alternative
proves that invariant and retry-safe publication.

The obvious de-funnelling rewrite is concrete but not viable here. It would
partition search events by `placement(locale, phrase)` and check them against
the already-replicated `$$generation` (`module.clj:73-76`). Phase ordering
could come from two `<<batch` blocks: publishes in the first, searches in the
second. That breaks the README ordering rule (`README.md:147-148`). Suppose
`en` is at generation 2, and one client sends `record-search! en 3 …` and then
`publish-snapshot! en 3 …`, both in one microbatch. The search names a
generation that is not yet current, so it must be ignored (`README.md:44-45`).
The private test sends such a future-generation search
(`private_test.clj:79`). Batch phasing applies the publish first, so the
search is wrongly counted. Without phasing, the reverse sequence fails:
after `publish-snapshot! en 3` then `record-search! en 3`, the search can
reach its phrase task before the `|all` generation update. It is then wrongly
dropped. The per-locale `$$owner` read (`module.clj:65`) resolves both cases
because it runs in depot append order (skill
`references/microbatch.md:13`). That is why the gate stays.

### 1a. Phrase-task correctness depends on task-to-task FIFO; that guarantee is documented, and the path must stay non-yielding

- One microbatch can carry a publish followed by searches or blocks for the
  same locale. The private test sends such sequences back to back
  (`private_test.clj:74-82,112-116`), though landing in one microbatch is not
  guaranteed. The owner task then sends an `:init` work item and later a
  `:count` item for the same phrase to the same placement task
  (`module.clj:77-93`).
- `:init` overwrites the record with `{:base … :sessions 0}`
  (`module.clj:97-100`). Each `:count` and `:block`/`:unblock` computes
  `*old-rank` from the record state it reads (`module.clj:104-140`). Applied
  out of order, these items could erase a count or leave a stale rank in
  `$$prefixes`.
- Order holds because of two facts:
  - Every work item for a `(locale, phrase)` starts on the single task that
    owns `hash(locale)` (`module.clj:36,65`), in depot append order
    (`references/microbatch.md:13`).
  - The skill documents per-task-pair FIFO after a partitioner for all
    topology types: "if A sends events e₁, e₂, e₃ to B, then B processes them
    in order" (`references/stream.md:37`, `references/formal-model.md:592`).

  The later hop to the 1-char prefix task (`module.clj:153`) is again
  a single task pair per phrase. Rank strings from different phrases are
  distinct, so their adds and removes commute.
- The same skill warns that yielding gives up that order
  (`references/dataflow.md:156`). The module has no `:allow-yield?` or
  `yield-if-overtime`, so the invariant holds today.

**Idiomatic alternative:** No change is needed. Keep every write for a
`(locale, phrase)` originating from one task. Do not add yielding on the
owner→placement→short-prefix path. A comment at `module.clj:65-67` could name
this dependency. One optional hardening would make order matter less: `:init`
could set only `:base` (`module.clj:99-100`), because a fresh generation key
already starts `:sessions` at 0. Rank maintenance would still depend on order,
so this is not required.

### 1b. Two-character placement concentrates hot prefixes, and rank fan-out multiplies storage

- `placement` keys work on `[locale (first two chars)]` (`module.clj:9-10`).
  A phrase and all its prefixes of length ≥2 therefore share one task
  (`module.clj:92-93,143-150`), which keeps `suggest` a single-task read. The
  cost: every phrase under a common two-letter start for a large locale
  (e.g. `"th"`) shares one task. So do all 1-char rank entries for that letter
  (`module.clj:151-159`).
- Each phrase stores its rank in every prefix set, `len(phrase)` of them, up to
  64 (`README.md:64`; `module.clj:12-13,143-159`). Each counted event
  removes and re-adds one rank per prefix. That is within the README's "bounded by phrase length"
  contract (`README.md:93-94`). Storage is about L× the candidate count.
- The README asks that "work and storage are balanced across tasks"
  (`README.md:99-100`). No test asserts balance. The independent test only
  prints which tasks read the depot, and labels it "diagnostic only"
  (`independent_test.clj:85-98`).

**Idiomatic alternative:** Accept the per-prefix fan-out. It is write-path
work paid once per event so that every `suggest` is a single bounded range
read (`SKILL.md:41`). A bounded top-N cache per prefix does not remove the
need for full sets. When a cached entry is blocked, the next candidate must
come from somewhere. Filtering a shorter prefix's set to find it is a scan
proportional to the match count, which the contract forbids
(`README.md:90-92`). The block-refill test guards exactly this
(`private_test.clj:127-138`).

The balance concern is better handled by placement. The placement key could
include more of the phrase, which spreads long-prefix sets. The 1- and
2-character prefix sets would then need their own placement: already
`[locale c]` for 1 character, and `[locale cc]` for 2 characters. The phrase
record's placement would then no longer colocate with its 2-character set, so
each event would do one more partitioner hop. Not rewritten: this changes the
colocation argument, and the tests do not measure skew.

### 2. Microbatch is consistent with the contract; do not replace it merely for latency

- The single `core` topology is microbatch-based (`module.clj:37,62`). Writes
  become observable through a processed-count barrier (`module.clj:197-222`),
  matching the challenge's explicit `wait-for-processing!` contract.
- A single topology simplifies ordering across generation ownership, phrase
  records, session membership, and prefix ranks. That consistency is more
  relevant here than an unrequested stream-visibility target.
- Microbatch PState updates are exactly-once across retries
  (`references/microbatch.md:126`). That is what makes the owner acceptance
  write and the `:init` resets retry-safe without any idempotence keys.
- Visibility is per task, not per microbatch: during commit, "external
  readers can observe two tasks on different microbatches"
  (`references/microbatch.md:128`). Each read here touches one task's state.
  `suggest` and `get-phrase` read the replicated `$$generation` next to the
  placement data (`module.clj:163-168,176-181`), so each query is
  self-consistent. `get-generation` reads `$$owner` on a different task.
  Before the barrier, it can briefly report a generation whose phrase data is
  not yet visible. The contract only promises visibility after
  `wait-for-processing!` (`README.md:142-143`), so this is allowed.
- The auction reference uses stream processing for its low-latency bid/listing
  path and a separate microbatch for expiration work
  (`challenges/auction-module/test-resources/auction_module/module.clj:77,115`);
  chat likewise distinguishes its stream core from derived microbatch state
  (`challenges/chat-app/test-resources/chat_app/module.clj:4-17,164-165,254`).

**Idiomatic alternative:** Split stream and microbatch paths only if the
product contract requires different visibility/ack semantics and the resulting
cross-topology consistency boundary is made explicit. For this challenge,
retain microbatch unless measurements or a changed contract justify that
additional complexity.

### 3. The precomputed prefix index is the right shape for bounded `suggest`

- `module.clj:43-60` declares nested, subindexed phrase/session/prefix state.
- `module.clj:12-19` encodes score-descending/phrase-ascending order in a
  lexically sortable rank key. Lines 141-159 update ranks as phrases change.
- `module.clj:161-172` uses `sorted-set-range-from-start *k`; it neither scans
  all prefix matches nor sorts them at read time. This directly satisfies the
  bounded-work contract for a hot prefix.
- This differs beneficially from a query-side `+vec-agg` followed by sorting:
  that pattern gathers and sorts all matching rows, so its read work grows with
  the match count. Chat's `room-page` query uses `+vec-agg` only after its
  single range read is capped at `PAGE-SIZE`
  (`challenges/chat-app/test-resources/chat_app/module.clj:417-433`). That
  bounded page is a suitable use, unlike an autocomplete prefix with
  thousands of matches.

**Idiomatic alternative:** Keep materializing ordered prefix candidates when
the query contract requires top-k independent of match cardinality. A
differently partitioned prefix index could be considered if the update
fan-out (all phrase prefixes) becomes the bottleneck. A top-k cache alone
cannot replace the full sets (finding 1b). Any alternative must prove exact
tie-breaking, refill after blocking the current top results, and bounded
query work. The private functional test explicitly checks refill after
blocking twelve leading candidates
(`test-private/hld_search_autocomplete/private_test.clj:127-138`).

### 4. Session deduplication is intrinsically stateful; historical generations are the avoidable growth

- `module.clj:49-54` stores a set of session IDs under locale, generation, and
  phrase. Subindexing each map/set is appropriate for a phrase with up to one
  million unique sessions; the event path uses membership lookup before
  incrementing (`module.clj:104-119`). Replacing that set with only a count
  would break duplicate suppression.
- `module.clj:43-60` also nests `$$phrases` and `$$prefixes` under generation.
  A newer publish changes the current generation and populates the new one
  (`module.clj:68-79`), but does not remove old generations. Thus old phrase,
  session, and rank data remains durable even though reads select only the
  current generation (`module.clj:165-168,178-182`). Retained state grows with
  the number of publications, not just the current corpus and trend overlay.
- The independent test checks that publication work does not traverse old
  trend data (`test-private/hld_search_autocomplete/independent_test.clj:70-83`).
  A synchronous cleanup scan on publish would violate that constraint.

**Idiomatic alternative:** Keep per-event session membership and its
subindexing; separately design bounded reclamation of superseded generations
that does not make publication work proportional to old trend data. For
example, use a generation-scoped storage boundary that can be retired in
bounded background work after the current-generation pointer switches. Prove
that old reads are no longer valid before reclaiming, and that retries or an
interrupted cleanup cannot remove the active generation. The README requires
the new snapshot to replace the old corpus and reset trend counts, while the
blocklist explicitly persists, so those states have different lifetimes.
Do not eagerly iterate every old phrase/session during the publish operation.

### 5. Client reads are already divided along the right boundary

- `module.clj:213-216` sends `suggest` and `get-phrase` through query
  topologies, so their local PState reads and derived data stay inside Rama.
- `module.clj:217-218` uses one direct `foreign-select-one` for
  `get-generation`. This is a fixed point lookup, not a client-side fan-out or
  join. The foreign-client reference says `foreign-select-one` "routes to a
  single partition determined by the first key in the path", while
  `foreign-invoke-query` "can access 1..N partitions"
  (`references/foreign-client.md:32`). The skill's rule is to minimize
  roundtrips "by using query topologies instead of multiple client-side
  foreign selects" (`SKILL.md:39`). One point read satisfies both. `$$owner`
  is keyed by locale and is written on `hash(locale)` (`module.clj:36,71`), so
  the foreign route matches where the data lives.
- Chat similarly uses query topologies for composed pages
  (`chat-app/module.clj:417-433`) and direct foreign point/range selections for
  simple reads (`chat-app/module.clj:546-563`). Auction shows direct foreign
  reads for simple PState results (`auction_module/module.clj:184-204`).

**Idiomatic alternative:** Do not replace the generation point read with a
query topology solely for uniformity. If `get-generation` later needs to join
other state or perform distributed work, then move it to a query topology.
Avoid making several client-side PState calls to assemble one suggestion;
keep that composition in a single query topology.

### 6. The multiplexed depot uses keyword-tagged maps instead of typed records with `<<subsource`

- All four write kinds share one depot for a sound reason: one ordered
  per-locale stream (finding 1). They are encoded as plain maps with an `:op`
  keyword (`module.clj:204-212`) and destructured into one shared set of vars.
  Vars that don't apply to an op are `nil` (`module.clj:64`). A second map
  then carries the work across the partitioner (`module.clj:78-91`).
- Chat multiplexes one depot with one `defrecord` per action
  (`challenges/chat-app/test-resources/chat_app/module.clj:53-58`) and
  dispatches with `<<subsource` / `case>` on the record type (`chat_app/module.clj:200-203`).
  Each branch binds only its own fields, and the depot schema is
  self-describing.

**Idiomatic alternative:** Use `defrecord`s (`Publish`, `Search`, `Block`,
`Unblock`) and `<<subsource`. This change is only for readability and type
safety; it has no effect on correctness or bounds. Not worth a rewrite on its
own.

### 7. Four PStates use `locale` as the top-level key but are partitioned by `[locale first-two-chars]`

- `$$blocked`, `$$phrases`, `$$sessions`, and `$$prefixes` all declare a
  top-level `String` locale key (`module.clj:42-60`). Every write goes to them
  only after `|hash *pk` or `|hash *short-pk`, where the key is
  `[locale (subs s 0 2)]` (`module.clj:9-10,92-93,151-153`). Every read comes
  after the same partitioner (`module.clj:163-164,176-177`).
- So the top-level key does not decide where the data lives. The same `"en"`
  key exists on every task that owns some `[en xx]` placement, and each copy
  holds a different slice. Topology code is correct because every access is
  preceded by the matching partitioner. The layout is still misleading,
  though. A future `foreign-select`, or a query that partitions with
  `|hash *locale`, would route to `hash(locale)` and silently read one slice.
  This is the silent misalignment named in `SKILL.md:61`. Only `$$owner`
  (depot-aligned) and `$$generation` (replicated via `|all`) are keyed the way
  they are placed.
- A top-level key that differs from the partition key is not wrong by
  itself. Auction keys `$$listing-bidders` and `$$listing-top-bid` by listing
  UUID but places them by seller
  (`challenges/auction-module/test-resources/auction_module/module.clj:71,84,88`).
  Its client routes reads with `{:pkey (:user-id listing-id)}`
  (`auction_module/module.clj:190-192,197-199`), which the foreign-client
  reference supports (`references/foreign-client.md:34`). That works because
  each listing UUID lives on exactly one task. Here, `"en"` is not unique to
  one placement. Several `[en xx]` placements can hash to the same task and
  merge under one `"en"` entry. So even a correct `:pkey` yields a mixed
  slice, and whole-locale reads such as `(keypath locale)` on `$$blocked`
  return an arbitrary subset.

**Idiomatic alternative:** Make the top-level key the partition key. One
option is a composite string such as `(str locale "|" two-char-prefix)`,
built by `placement`. For example, use
`{String (map-schema Long (map-schema String …))}` and replace
`(keypath *work-locale *work-generation …)` with
`(keypath *pk *work-generation …)`. Then `|hash *pk` and the PState's own key
agree, foreign reads with that key route correctly, and each top-level entry
holds only one placement's data. Seek counts on reads and writes do not
change. Not rewritten: this changes four PState schemas and every path that
uses them (see "Rewrite decision and validation").

### 8. The client wrapper serializes every append behind a JVM-wide lock

- `append!` wraps `foreign-append!` and the barrier counter in
  `(locking append-count …)` (`module.clj:197-201`). The atom is created once
  per `create-module` (`module.clj:189`), so all wrappers share it. Concurrent
  writers on any wrapper therefore wait in turn for each other's full
  append roundtrip.
- The barrier only needs to count appends that have returned
  (`README.md:149-151`). Chat and auction both use a plain `swap!` with no lock
  (`challenges/chat-app/test-resources/chat_app/module.clj:514-519`,
  `challenges/auction-module/test-resources/auction_module/module.clj:175-180`).
- The shared counter itself is correct and is better than the chat and
  auction pattern. Their counters live inside each client
  (`chat_app/module.clj:514`, `auction_module/module.clj:175`), so a barrier
  on one client does not count another client's appends. This module's
  cross-client test depends on sharing: the first client writes, then the
  second client runs the barrier (`private_test.clj:72-83`).

**Idiomatic alternative:** Keep the per-`create-module` atom. Call
`(foreign-append! depot event)`, then `(swap! append-count inc)`, with no
lock. Incrementing after the append returns keeps one property of the current
code: a failed append never raises the target, so it cannot hang the barrier.
Chat and auction increment before appending and lack that property. This
affects only the test-harness wrapper, not module semantics.

## Rewrite decision and validation

**Decision: documentation only; the reference is unchanged.** The prefix index
and session-deduplication path directly serve explicit contract bounds.
Findings 1, 1b, and 4 need a new design for publication ordering, retry
safety, and bounded cleanup. Finding 1a rests on a documented Rama guarantee
and needs no change. Finding 6 is cosmetic. Findings 7 and 8 are small, but 7
changes four PState schemas. The private suite now runs (below), but it
cannot validate the benefit of either change. No test measures placement
skew, top-level-key routing, or append concurrency. A rewrite would also make
the committed validation and mutant logs under `test-private/` stale. The
suite could show only that nothing broke, not that anything improved. The
reference therefore stays as committed.

Validation status for this revision:

- **Verified by reading:** every file:line citation above was re-checked
  against the current tree:
  - the reference module, README, and protocol;
  - both private test namespaces;
  - the auction and chat references;
  - `SKILL.md` and the skill references `microbatch.md`, `stream.md`,
    `formal-model.md`, `dataflow.md`, and `foreign-client.md`.

  The construct audit's `grep` was re-run, extended to check for yield,
  partitioner, and `locking` calls.
- **Run:** `clojure -X:test-private-harness` from
  `challenges/hld-search-autocomplete`, with no `implementations/` directory
  present, so the reference module was the only candidate on the classpath.
  Both namespaces (`private-test`, `independent-test`) at 2 and 4 tasks ran
  4 tests with 356 assertions: 0 failures, 0 errors, exit 0. This matches the
  committed `test-private/independent_run.log`. Rama logged Kafka
  segment-recovery and module-assignment `ERROR` lines during the
  module-update scenario; they are not test failures.
- **Not run:** `bb run-challenges` and solver runs, intentionally.
  `scripts/test_reference_packages.py` does not apply: its `PACKAGES` list
  does not include `hld-search-autocomplete`.

No challenge README, protocol, private tests, reference module, or skill
files were changed.
