# Idiom review: `hld-enterprise-rag`

## Scope and recommendation

Reviewed at baseline `43abd3fccff8777d8995f8a29658f3c4c97df01a` on
`orb/idiom-hld-enterprise-rag`. Inputs were the challenge README and protocol,
the complete private contract test, the full reference module, the auction and
chat-app reference modules, and `plugins/rama-skill/skills/rama/SKILL.md`.

**Recommendation: keep the reference implementation unchanged for this task.**
It has a deliberately maintained revision/ACL/indexing contract and the
private suite includes multi-task, cross-client, module-update, and RocksDB
growth checks. The concerns below are optimization/design follow-ups, not a
demonstrated correctness failure. A topology/query rewrite should be isolated
and proven against the complete private suite and cost checks before replacing
this reference.

## Findings

### 1. One multiplexed depot and one microbatch topology

- `challenges/hld-enterprise-rag/test-resources/hld_enterprise_rag/module.clj:35-37`
  declares one `*writes` depot partitioned by `:key` and one `entities`
  microbatch topology.
- Lines 52-89 dispatch by `:kind` to membership, ACL, and content handling.
  This makes same-document content/ACL events share the `DocKey` partition,
  and same-user membership events share a `UserKey` partition. The private
  test's interleaved writes and cross-client synchronization exercise those
  guarantees (`test-private/hld_enterprise_rag/contract_test.clj:15-79,
  83-145, 147-205`).
- This is a sensible default for this contract: a shared dispatcher is compact
  and key routing preserves each entity's required invocation order. Separate
  depots/topologies could clarify ownership or isolate throughput, but add
  synchronization and ordering concerns and are not an automatic improvement.
  `auction-module` uses separate listing and bid depots for distinct routing
  keys and a separate expiration microbatch (`auction-module/module.clj:69-77,
  115-121`); `chat-app` separates registration claims from user actions because
  handle/name uniqueness and actor routing are different concerns
  (`chat-app/module.clj:69-75`). Those are domain-driven splits, not a rule to
  split every event type.
- No `:ingress-seq` appears in this HLD module. The visible contract requires
  per-entity invocation order, which its `hash-by :key` design addresses; adding
  a global sequencing mechanism would be unnecessary unless a stronger
  cross-entity ordering requirement is introduced.

### 2. Content replacement and token-index maintenance

- Lines 18-33 materialize each document's bounded chunks as a map, then compute
  the set difference of old and new `(token, chunk-ref)` pairs.
- Lines 72-89 replace the document's chunks and update only changed posting
  entries. This is a good match for the README's per-document write bound and
  the `unrelated-corpus-growth` private test (`contract_test.clj:207-276`).
- `$$chunks` at lines 43-44 holds at most 32 chunks under each `DocKey`, so its
  per-document collection is explicitly bounded. `$$docs` and `$$users` are
  entity-keyed PStates, not one in-memory collection containing every record.
  The posting bucket under a `TokenKey` can, however, grow with the number of
  matching chunks. The `$$postings` inner map at lines 48-51 disables size
  tracking but does not visibly request subindexing. Verify the intended
  `map-schema` indexing option against the Rama version before treating a
  posting bucket as an efficiently navigable growing collection; use a
  subindexed map when its access pattern and schema support that option.
- Avoid replacing the differential update with a full-tenant rebuild. The
  current event work is proportional to old/new chunks of one document, as
  required by the contract.

### 3. Query fan-out, client-side PState reads, and sorting

- Lines 150-167 implement `query` in the client. It does one foreign read for
  membership, one posting-list `foreign-select` per distinct query token
  (lines 157-159), and then one document-metadata `foreign-select-one` per
  distinct candidate document (lines 160-166). Those operations are
  distributed lookups from client code, so query latency includes multiple
  network roundtrips and the metadata fan-out grows with candidate count.
- Lines 91-107 score, authorize, sort, and truncate after collecting those
  postings. Authorization is correctly applied before top-k, and the candidate
  index plus private cost test prevent scanning unrelated documents/chunks;
  preserve both properties in any redesign.
- Prefer evaluating this fan-out inside one Rama query topology: explode the
  distinct query tokens, partition to their token posting keys, collect
  candidate references, route document/user authorization lookups to their
  owning partitions, then return authorized candidates for deterministic
  ranking. This moves the work behind one client query invocation and avoids
  client-issued per-token/per-document calls. It does not make the underlying
  distributed work free: compare partition hops and total RocksDB reads, and
  ensure any candidate aggregation does not materialize an unbounded vector at
  the origin. A bounded partial-top-k aggregation is a possible optimization
  only after proving it preserves global ordering and pre-truncation ACL
  filtering.
- Chat-app demonstrates the mechanics, not a drop-in query: query topologies
  repartition to PState owners and gather tuples with `aggs/+vec-agg` before
  formatting (`chat-app/module.clj:417-449, 451-466, 480-494`); the client
  invokes those query topologies with one `foreign-invoke-query`
  (`chat-app/module.clj:581-590`). For HLD, `+vec-agg` is appropriate only if
  result cardinality is acceptable; consider bounded aggregation for large
  posting fan-out.
- The HLD module uses `sort-by` at line 105, but neither `:ingress-seq`,
  `group-by`, nor Rama's `loop<-` is present in it. Chat-app sorts its bounded
  page tuples in ordinary Clojure helpers (`chat-app/module.clj:134-156`) and
  uses a client-side `loop` to page through room members (`565-580`); that loop
  is not `loop<-`. No reason to introduce `loop<-` or `group-by` is established
  by this implementation. Use a dataflow loop only if a measured topology
  operation requires iterative PState work, and follow the skill's yielding
  guidance for long iterations.

### 4. Direct client-side reads

- `get-document` and `get-user-groups` each perform a single keyed lookup
  (`module.clj:138-149`). This is materially different from `query`'s
  multi-roundtrip fan-out and is a reasonable simple-read pattern.
- `auction-module` also uses direct foreign PState reads for simple keyed or
  owner-scoped reads (`auction-module/module.clj:184-204`). `chat-app` uses
  query topologies for paginated reads that join data across partitions
  (`chat-app/module.clj:417-494, 581-590`). Choose by query shape: direct foreign
  reads for one bounded lookup; a query topology for distributed joins/fan-out
  or when avoiding repeated client network roundtrips matters.

## Implementation and verification decision

This change is documentation-only. No README, protocol, private test, skill, or
reference module was modified. The conditional full private suite and
`scripts/test_reference_packages.py` were not run because no reference package
was rewritten; the review itself does not claim a test-suite result.
