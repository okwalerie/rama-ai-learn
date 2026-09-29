# Idiom review: HLD search autocomplete

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a`. This review covers the
challenge contract, private tests, reference module, the auction/chat reference
modules, and `plugins/rama-skill/skills/rama/SKILL.md`.

## Executive summary

Keep the current read-side architecture: `suggest` does a bounded sorted-set
range read from a precomputed prefix index, while phrase and generation lookups
are point reads. The exact-session deduplication requirement also justifies a
subindexed set of session IDs; a counter alone cannot distinguish duplicate
events. The largest design concern is write-side concentration at the locale
owner and retained state for every historical generation. Addressing either
requires preserving generation ordering, retry safety, and bounded snapshot
publication, so this review recommends **documentation only**, not a rewrite.

Several constructs named in the requested audit are not present in this
reference. It has no `:ingress-seq`, `+group-by`, `+vec-agg`, `loop<-`, or
query-time sort. The observed flow instead uses `hash-by :locale`, a microbatch
source, explicit key repartitioning, and precomputed sorted prefix indexes.
Those absent constructs are not findings against this module.

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
  necessarily a skewed locale's processing.

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

### 2. Microbatch is consistent with the contract; do not replace it merely for latency

- The single `core` topology is microbatch-based (`module.clj:37,62`). Writes
  become observable through a processed-count barrier (`module.clj:197-222`),
  matching the challenge's explicit `wait-for-processing!` contract.
- A single topology simplifies ordering across generation ownership, phrase
  records, session membership, and prefix ranks. That consistency is more
  relevant here than an unrequested stream-visibility target.
- The auction reference uses stream processing for its low-latency bid/listing
  path and a separate microbatch for expiration work
  (`challenges/auction-module/test-resources/auction_module/module.clj:77,115`);
  chat likewise distinguishes its stream core from derived microbatch state
  (`challenges/chat-app/test-resources/chat_app/module.clj:13-17,164-165,254`).

**Idiomatic alternative:** Split stream and microbatch paths only if the
product contract requires different visibility/ack semantics and the resulting
cross-topology consistency boundary is made explicit. For this challenge,
retain microbatch unless measurements or a changed contract justify that
additional complexity.

### 3. The precomputed prefix index is the right shape for bounded `suggest`

- `module.clj:49-60` declares nested, subindexed phrase/session/prefix state.
- `module.clj:12-19` encodes score-descending/phrase-ascending order in a
  lexically sortable rank key. Lines 141-159 update ranks as phrases change.
- `module.clj:161-172` uses `sorted-set-range-from-start *k`; it neither scans
  all prefix matches nor sorts them at read time. This directly satisfies the
  bounded-work contract for a hot prefix.
- This differs beneficially from a query-side `+vec-agg` followed by sorting:
  that pattern gathers and sorts all matching rows, so its read work grows with
  the match count. Chat uses `+vec-agg` in page queries after limiting each
  local range to `PAGE-SIZE` (`challenges/chat-app/test-resources/chat_app/module.clj:417-433`);
  that bounded page is a suitable use, unlike an autocomplete prefix with
  thousands of matches.

**Idiomatic alternative:** Keep materializing ordered prefix candidates when
the query contract requires top-k independent of match cardinality. A
partitioned prefix index or an equivalent bounded top-k structure could be
considered if the update fan-out (all phrase prefixes) becomes the bottleneck,
but any alternative must prove exact tie-breaking, refill after blocking the
current top results, and bounded query work. The private functional test
explicitly checks refill after blocking twelve leading candidates
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

**Idiomatic alternative:** Keep per-event session membership and make its
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
  join; the foreign-client reference says direct point reads are the right use
  of `foreign-select-one` and reserves `foreign-invoke-query` for cross-task
  reads/joins/computation.
- Chat similarly uses query topologies for composed pages
  (`chat-app/module.clj:417-433`) and direct foreign point/range selections for
  simple reads (`chat-app/module.clj:546-563`). Auction shows direct foreign
  reads for simple PState results (`auction_module/module.clj:184-204`).

**Idiomatic alternative:** Do not replace the generation point read with a
query topology solely for uniformity. If `get-generation` later needs to join
other state or perform distributed work, then move it to a query topology.
Avoid making several client-side PState calls to assemble one suggestion;
keep that composition in a single query topology.

## Rewrite decision and validation

No implementation rewrite was made. The current prefix index and
session-deduplication path directly serve explicit contract bounds, while
partition changes or state reclamation need a separate design for publication
ordering, retry safety, and bounded cleanup. The reference was not modified;
therefore the private suite and `scripts/test_reference_packages.py` were not
run as rewrite validation. No challenge README, protocol, private tests, or
skill files were changed.
