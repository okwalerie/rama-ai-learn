# Idiom review: `hld-enterprise-rag`

## Scope and recommendation

Reviewed on `orb/idiom-hld-enterprise-rag` at baseline
`43abd3fccff8777d8995f8a29658f3c4c97df01a` (plus the earlier review commit
`2fd46ec`, which only added this file). Inputs were the challenge
`README.md`, `src/hld_enterprise_rag/protocol.clj`, the only private test
file `test-private/hld_enterprise_rag/contract_test.clj`, the reference
`test-resources/hld_enterprise_rag/module.clj`, the auction-module and
chat-app reference modules, and `plugins/rama-skill/skills/rama/SKILL.md`,
plus the skill references it points to for depots, schemas, and
aggregators.

Paths below are relative to `challenges/hld-enterprise-rag/` unless they
start with `challenges/` or `plugins/`. `module.clj:N` means
`test-resources/hld_enterprise_rag/module.clj`, and `contract_test.clj:N`
means `test-private/hld_enterprise_rag/contract_test.clj`.

**Recommendation: keep the reference unchanged for now (doc-only).** The
reference correctly implements the three revision streams, tombstone
fences, pre-truncation authorization, and the write-cost bounds. Two idiom
problems justify a later rewrite:

1. `query` is a client-side fan-out of `foreign-select` calls. SKILL.md:39
   tells authors to use query topologies instead.
2. The posting-index maintenance relies on an ordering assumption that no
   private test exercises (finding 2b).

This review does not rewrite the reference. The unchanged reference passes
the full private suite (see the last section). Any rewrite must also pass the
full `:test-private-harness` suite at 2 and 4 tasks before it replaces the
reference.

## Findings

### 1. One multiplexed depot and one microbatch topology

- `module.clj:36-37` declares a single `*writes` depot with `(hash-by :key)`
  and a single `entities` microbatch topology. `:key` is a `DocKey` record
  for content, tombstone, and ACL events, and a `UserKey` record for
  membership events (`module.clj:127-137`). So every write to one document
  lands in one depot partition, and every write for one user lands in one
  depot partition. That gives the README's per-document and per-user
  ordering requirement (README:184-188). Sharing a depot for
  ordering-sensitive events on one entity matches the skill's depot-boundary
  rule (`plugins/rama-skill/skills/rama/references/depot-design.md:15-17`).
- Dispatch is not idiomatic. Events are plain maps with a `:kind` keyword,
  and `module.clj:53-89` routes them with nested `<<if` branches on `:kind`.
  The skill says to "Use `<<subsource` to dispatch distinct event types"
  (`depot-design.md:16-17`). chat-app shows the pattern: a single
  multiplexed depot of defrecords (`chat-app/.../module.clj:53-58,160`)
  consumed with `(<<subsource *action (case> ProfileEvent ...) ...)` in both
  its stream topology (`:200-251`) and its microbatch topology (`:280-390`).
  Using `PutDoc`/`DeleteDoc`/`PutAcl`/`PutMembership` records with
  `<<subsource` would flatten the three-deep `<<if` nest. It would also give
  each branch only its own fields. Today `module.clj:55-58` reads `:kind`,
  `:key`, `:revision`, and `:groups` from every event before dispatching, so
  `:groups` is read (as `nil`) even for put and delete events. `:chunks` is
  read only inside the put branch (`module.clj:73-74`).
- Separate depots are not required here. auction-module splits
  `*listing-depot` (hash by seller) from `*bid-depot` (hash by listing
  owner), then adds a separate `expirations` microbatch for its scheduler
  (`challenges/auction-module/test-resources/auction_module/module.clj:70-75,
  115-158`). chat-app splits `*register-depot` (hash by `:handle`) from
  `*user-actions-depot` (hash by `:user-id`), because a registration claim
  must be routed by the unique handle (`chat-app/.../module.clj:26-30,
  159-160, 186-194`). Both splits come from different routing keys. In this
  module, document and ACL events share `DocKey` and must stay ordered with
  each other, so they must not be split. Membership events could get their
  own depot because no ordering relates them to document events. That would
  add a second source and synchronization count and wouldn't simplify
  anything.
- Nothing in the reference uses `:ingress-seq`. Some other HLD references
  in this repo use it (hotel-reservation, stock-exchange, payment-system,
  ticketing-system, file-sync). This contract needs only per-entity order,
  and key-hashed depot partitions already provide it, so adding it would
  not help.
- A microbatch topology is the right choice (SKILL.md:63). Writes are
  acknowledged with `:append-ack` (`module.clj:120`), and reads are gated by
  `wait-for-microbatch-processed-count` (`module.clj:124-125`). Microbatch
  is also required for retry safety (SKILL.md:32). One accepted content
  write updates `$$docs` and `$$chunks` on the document's task
  (`module.clj:80-82`), then `|hash` sends its posting ops to other tasks
  (`module.clj:85-89`). In a stream topology each partitioner ends a
  transaction
  (`plugins/rama-skill/skills/rama/references/core-concepts.md:65`). If
  processing failed after the document-side commit, the retried event would
  be rejected because its revision is no longer strictly newer
  (`module.clj:15-16, 72`), and any posting ops that were lost would never
  be re-sent. A microbatch is one cross-partition transaction with
  exactly-once PState updates (`core-concepts.md:11, 65`; SKILL.md:63), so
  the document, chunk, and posting writes commit or retry together.

### 2. Content replacement and posting-index maintenance

**2a. What the diff actually does.** `chunks-map` (`module.clj:18-20`)
converts each chunk's token vector to a set. `posting-diff`
(`module.clj:28-33`) removes `(TokenKey, ChunkRef)` pairs that are in the
old chunks but not the new ones. It then **re-adds every new pair with the
full chunk payload**, including pairs that did not change. The earlier
version of this review said it updates "only changed posting entries",
which is wrong. The re-add is required by the current design, because each
posting stores a denormalized `{:text :tokens}` copy of its chunk. A new
revision can change a chunk's text without changing its tokens.
`contract_test.clj:92-98` checks exactly that: `keep` goes from `"old"` to
`"new"` and still matches `"match"`. Each accepted content write therefore
costs one `$$docs` write, one `$$chunks` read and write, and at most
32×32 = 1024 posting removes plus 1024 posting writes. That is proportional
to the old and new chunks of one document, which README:161-163 allows.

**2b. Ordering after `|hash` (not covered by tests).** Posting updates
leave the document partition through `(|hash *token-key)` (`module.clj:85`).
Nothing in the posting value records which content revision produced it.
The ops are plain `termval`/`NONE>` writes (`module.clj:86-89`), so the
final posting state depends on the ops for one `(token, chunk)` pair
arriving in content-revision order.

Here is the risky case. Two accepted content writes for the same document
land in the same microbatch, such as a delete followed by a recreate, or
two puts that change the same chunk. If the second event's posting ops
arrive before the first event's ops for the same pair, you get:
- a stale posting whose payload no longer matches `$$chunks`, or
- a missing posting for a live chunk.

The skill's depot reference warns that a `|hash` repartition "breaks local
ordering" (`depot-design.md:142`).

The private suite never puts two *accepted* content writes for one document
in the same synchronization window. Same-window pairs are always an
accepted write plus a rejected one: `contract_test.clj:44-45, 52-54, 83-85,
130-131, 147-148`. So the suite can neither confirm nor rule out this case.

Rama may deliver messages between one pair of tasks in FIFO order, which
would make this safe. That guarantee was not established here. Either way,
the reference should not depend on it silently. A robust fix is to fence
each posting on the document's content revision:
- store `:content-revision` in the posting value;
- apply an add only if the stored revision is lower;
- apply a remove only if the stored revision is lower than the remover's.

This makes posting ops for one pair commutative, keeps the per-document
write bound, and adds no cross-document work. Pair the fix with a private
test that sends put → put (changing one chunk's text) and delete → put for
one document inside one synchronization window.

**2c. Growing collections.**

- **`$$postings` is subindexed.** The earlier version of this review said
  the inner map does "not visibly request subindexing", which is wrong.
  `{:subindex-options {:track-size? false}}` (`module.clj:51`) is the
  documented way to turn subindexing on with size tracking disabled
  (`plugins/rama-skill/skills/rama/references/pstate-schema-clojure-api.md:21,
  34-41`; `.../pstate-schema.md:127-139`). The same form appears in several
  other HLD references in this repo. Posting buckets can grow with the
  tenant's corpus, so subindexing them is correct (SKILL.md:59). Nothing
  calls `count` on them, so turning size tracking off is also correct.
- **`$$chunks`** (`module.clj:43-44`) is a plain map per `DocKey`, limited
  to 32 chunks by the input contract (README:175-176). Leaving it
  non-subindexed matches SKILL.md:59 ("Don't subindex small collections").
  The comment at `module.clj:42` explains why content is kept out of
  `$$docs`: ACL writes and metadata reads never load chunk payloads, which
  is what keeps `acl-cost` in `contract_test.clj:214-216, 242-253` flat.
- **`$$docs` / `$$users`** are keyed by entity. Their `:groups` sets are
  plain `set-schema`, which the contract does not bound. They are replaced
  whole on each ACL or membership write, and read whole for the
  intersection test. That is an input-size cost (README:154-155), so plain
  sets are acceptable. Subindexing them would make each whole-set
  replacement more expensive.
- Storing the payload in each posting multiplies chunk storage by up to 32
  (one copy per distinct token in the chunk). In exchange, `query` needs no
  per-chunk `$$chunks` lookup. This follows the skill's
  write-amortization principle (SKILL.md:41). The development plan
  originally sketched `ChunkRef → Boolean` postings
  (`test-resources/development/PLAN-revisioned-entities.md:137`). The
  denormalized payload is a deliberate change from that plan, not an
  accident. ACL groups cannot be denormalized into postings the same way,
  because ACL writes must not do chunk-proportional work (README:164-166).
  That is why a per-candidate-document authorization lookup can't be
  avoided.

### 3. `query`: client-side distributed fan-out

- `module.clj:150-167` runs `query` entirely in the client:
  - one `foreign-select-one` for the user's membership (`:153`), which
    returns `[]` early when the user has no groups (`:155-156`);
  - one `foreign-select [(keypath TokenKey) ALL]` per distinct query token
    (`:157-159`);
  - one `foreign-select-one` per distinct candidate document (`:161-166`);
  - scoring, authorization, sorting, and truncation to `k` in
    `query-results` (`module.clj:91-107`, `sort-by` at `:105`).

  Each call is a separate network roundtrip (SKILL.md:39). Posting reads
  run one after another (`mapcat`), and the metadata lookups run one after
  another too (`into {}` over `map`). The number of roundtrips grows with
  the number of candidate documents. Every matching posting, payload
  included, is shipped to the client before authorization.
- These properties are correct and must survive any redesign:
  - Authorization happens before `take k` (`module.clj:98-106`).
  - Duplicate refs across tokens collapse through `(into {} postings)`
    (`:93`), and score is computed from the stored token set, so a chunk
    that matches several tokens is scored once.
  - Only postings for the query tokens are read. `unrelated-corpus-growth`
    (`contract_test.clj:190-253`) checks that query RocksDB reads stay
    within +16 as unrelated documents grow 256 → 2048 and the target
    document grows 1 → 32 chunks.

  The contract (README:157-160) allows query work proportional to
  token/chunk matches, so the problem is idiom and latency, not a contract
  violation.
- **Idiomatic alternative: one `<<query-topology`** invoked with a single
  `foreign-invoke-query`, as chat-app does
  (`chat-app/.../module.clj:417-494` for the topologies, `:506-513, 581-590`
  for the client). Sketch:
  1. `|hash` to the `UserKey` and read `:groups`. Stop if there are none.
  2. `ops/explode` the distinct tokens, then `|hash` each `TokenKey`.
  3. Scan the subindexed posting bucket with `{:allow-yield? true}`
     (SKILL.md:26), since a hot token's bucket can be large.
  4. Emit a candidate only from the partition of its smallest matching
     query token. This deduplicates without an aggregation step and avoids
     repeated document lookups for chunks that match several tokens.
  5. `|hash` to the `DocKey`, read the ACL and revisions, and filter by
     group intersection.
  6. `|origin`, aggregate, then sort and take `k`.

  The document hop is still one hop per candidate. It could be reduced by
  grouping candidates per document before that hop.
- **Aggregation choice.** chat-app uses `aggs/+vec-agg` then sorts in a
  plain helper (`chat-app/.../module.clj:134-156, 432-433`). That is safe
  there because each page is bounded by `PAGE-SIZE`. Here the candidate set
  is bounded only by matches, so `+vec-agg` would collect every authorized
  candidate at the origin before truncation. The same amount of data
  reaches the client today, so this is no regression, but it is not
  bounded by `k`. A bounded top-k needs care:
  - `+top-monotonic` expects a literal bound. A logvar `[n]` such as `*k`
    can fail (`plugins/rama-skill/skills/rama/references/aggregators.md:279`).
  - Its order must be re-sorted by `(-score, doc-id, chunk-id)` afterwards.
  - `+limit` is batch-only (`aggregators.md:108-113, 263-269`). Query
    topologies are batch context (`aggregators.md:145-147`), so it may fit,
    but the three-key tie-break sort must be proven.

  Either way, authorization must stay before the aggregator.
- **Idiom audit summary:**
  - `sort-by` is the only sort, and it is client-side (`module.clj:105`).
  - There is no `group-by`/`+group-by`, `+vec-agg`, or `loop<-` in the
    module, and no `:ingress-seq` (see finding 1).
  - `ops/explode` is used once, to fan out the posting diff
    (`module.clj:84`).
  - chat-app's `get-online-members` uses a client-side Clojure `loop` over
    bounded `sorted-set-range-from` chunks (`chat-app/.../module.clj:564-580`).
    That is ordinary `loop`, not dataflow `loop<-`.
  - Nothing here needs `loop<-`. If a query topology scans a large posting
    bucket, use `:allow-yield?` rather than a hand-written loop.

### 4. Single-entity client reads

- `get-document` (`module.clj:138-147`) and `get-user-groups` (`:148-149`)
  each do one keyed `foreign-select-one`, as README:167 requires.
  auction-module does the same for simple keyed reads
  (`challenges/auction-module/test-resources/auction_module/module.clj:184-204`),
  and so does chat-app (`chat-app/.../module.clj:546-563`).
- Rule of thumb from these references: use a direct foreign read for one
  bounded keyed lookup. Use a query topology when a read joins across
  partitions or would otherwise need several client roundtrips.

### 5. Client synchronization

- `create-module` keeps a single `counts` atom keyed by `ipc`
  (`module.clj:110, 119-121`). All `wrap-client` instances from one
  `create-module` call share it, so the reader client in the cross-client
  tests waits for the writer's appends too. README:190-191 explicitly
  allows transient synchronization counters. This matches auction-module's
  `mb-cnt` (`auction-module/.../module.clj:175, 210-212`) and chat-app's
  `mb-cnt` (`chat-app/.../module.clj:514-519, 596-597`), except those are
  per client.

## Corrections to the earlier version of this review

- Private-test ranges were wrong; the old ones included 207-276, but the
  file is 254 lines. Correct ranges:
  - `revision-and-authorization`: `contract_test.clj:20-65`
  - `first-delete-recreate-and-current-payload`: `:67-110`
  - `fences-denial-and-tenant-isolation`: `:112-157`
  - `durable-after-module-update`: `:159-176`
  - `rocks-cost` (helper `defn`, not a test): `:178-188`
  - `unrelated-corpus-growth`: `:190-253`
- `chat-app/module.clj:69-75` is the `PresenceStore` deftype, not the depot
  split. The depots are at `:159-160`, and their rationale is at `:26-30`.
- The claim that postings are not subindexed was wrong (finding 2c).
- The claim that the diff updates "only changed posting entries" was wrong
  (finding 2a).
- The earlier version missed the `<<subsource` dispatch idiom (finding 1)
  and the ordering assumption on posting ops (finding 2b).
- Confirmed as accurate: the `auction-module` citations `69-77`, `115-121`,
  and `184-204`; the `chat-app` query-topology, sort-helper, and client-loop
  citations; and the observation that none of `:ingress-seq`, `group-by`,
  or `loop<-` appears in this module.

## Implementation and verification decision

This change touches only this document. The challenge README, protocol,
private test, skill, and reference module were not modified.

`scripts/test_reference_packages.py` does not cover this challenge: its
`PACKAGES` tuple (`scripts/test_reference_packages.py:11-13`) does not list
`hld-enterprise-rag`.

The full private suite was run against the unchanged reference with
`clojure -X:test-private-harness` from `challenges/hld-enterprise-rag/`:
`Ran 5 tests containing 86 assertions. 0 failures, 0 errors.` Each test ran
at both 2 and 4 tasks. The `unrelated-corpus-growth` cost output was:

| Tasks | Query reads | ACL writes (reads/writes) |
|---|---|---|
| 2 | 5 / 5 / 5 | 1/1 each |
| 4 | 4 / 4 / 4 | 1/1 each |

The three columns are 256 documents, 2048 documents, and 2048 documents with
a 32-chunk target. The costs stay flat, which confirms the query and ACL cost
claims in findings 2c and 3. The logged `LeaderNotFoundException` and Kafka
index-recovery `ERROR` lines come from the IPC cluster and the module update
test; no test failed.
