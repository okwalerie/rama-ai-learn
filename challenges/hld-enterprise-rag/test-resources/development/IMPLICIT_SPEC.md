# Implicit Spec

<!-- Phase 0 artifact for hld-enterprise-rag. Requirements only — no
PState, depot, or topology design. Inputs: README.md,
src/hld_enterprise_rag/protocol.clj, lib/harness Synchronizable
docstring, .agents/skills/rama/SKILL.md. -->

Before designing any Rama-specific implementation, write out the implicit
assumptions and expectations that a senior engineer would bring to this
system based on the requirements and the domain.

## Derived domain facts

These follow from the stated rules; every later phase may rely on them.

- **Every identifier is scoped by tenant, compared by exact string value.**
  `["acme" "d1"]` and `["globex" "d1"]` are unrelated documents;
  `["acme" "alice"]` and `["globex" "alice"]` are unrelated users; group
  `"eng"` in `"acme"` and `"eng"` in `"globex"` are unrelated groups. A
  user's groups can only ever intersect ACLs of documents in the same
  tenant. There is no "create tenant" step; a tenant exists only in the
  sense that some document or user under it has been written.
- **A document has two independent facets: content and ACL.** The content
  facet is `(content-revision, live?, chunks)`; the ACL facet is
  `(acl-revision, groups)`. Each facet has its own revision counter and
  its own writers. Revisions of the two facets are unrelated numbers
  (content-revision 7 with acl-revision 1 is normal). A document "exists"
  for `get-document` iff at least one facet has ever been written.
- **The content facet has three states: never written, live, tombstoned.**
  Live means the most recent accepted content write was a `put-document!`
  (so it has 1..32 chunks). Tombstoned means the most recent accepted
  content write was a `delete-document!` (zero chunks). Both live and
  tombstoned carry an integer content-revision; never-written carries nil.
- **The ACL facet has two states: unset, set.** Set carries an integer
  acl-revision and a group set which may be `#{}`. Unset means nil for
  both. An ACL, once set, is never unset again; it can only be replaced.
- **A user has two states: unknown, known.** Known carries an integer
  membership-revision and a group set which may be `#{}`. A user, once
  known, is never unknown again.
- **"Strictly greater wins" makes the stored revision order-independent
  but the stored content order-dependent.** Whatever the delivery order of
  writes on one stream, the stored revision ends at the maximum written.
  But two writes with the same revision and different payloads keep
  whichever was applied first, forever. That is why per-document and
  per-user application order must match client invocation order.
- **A stale write is rejected in full.** An equal-or-lower revision changes
  nothing on its stream: not the revision, not the chunks, not the
  tombstone flag, not the groups. There is no merge or partial update.
- **A duplicated or retried write is harmless only if the original was
  applied atomically.** Re-delivery of an accepted write hits the
  equal-revision case and is a no-op. Consequently the revision fence and
  the payload replacement of one write must never be observable as
  separated: a state where the revision has advanced but old chunks remain
  (or new chunks are missing) is a permanent corruption, because the retry
  will be rejected as stale and never repair it.
- **Streams are commutative with each other, not within themselves.** The
  final state of a document after any interleaving of content writes and
  ACL writes is the same as long as each stream's own order is preserved:
  content writes never read or modify the ACL facet; ACL writes never read
  or modify the content facet. Membership writes touch neither.
- **Content revision is shared by puts and tombstones.** A `delete` at 5
  followed by a `put` at 5 is a no-op put; a `put` at 5 followed by a
  `delete` at 5 is a no-op delete. A tombstone at 5 on a never-written
  document is a real state (revision 5, not live) that `get-document`
  reports and that fences out puts at ≤ 5.
- **Chunks are not independently addressable.** A new content revision
  replaces the whole chunk set. Chunk IDs are unique only within one
  document revision; the same chunk-id in the next revision is a different
  chunk with possibly different text and tokens, and a chunk-id absent from
  the new vector ceases to exist. Chunk text is opaque and returned
  verbatim as the citation.
- **Eligibility is evaluated against current state at query time.** The
  efficiency contract forbids document- or chunk-proportional work on
  `put-document-acl!` and `put-user-groups!`, so the authorization decision
  for a chunk must reflect the ACL and membership as they stand when the
  query runs, not as they stood when the chunk was indexed. The same query
  by the same user returns different results before and after an ACL or
  membership change with no intervening content write.
- **Eligibility is per document, scoring is per chunk.** All chunks of a
  document share one eligibility decision (live, ACL set, ACL ∩ user groups
  non-empty). Each chunk scores independently and each candidate chunk is
  a separate result; there is no per-document cap or grouping in results.
- **Score is `|Q ∩ chunk-token-set|`.** `Q` is the distinct set of query
  tokens; the chunk's tokens are treated as a set (duplicates in the chunk
  count once, worked example `["rama" "rama" "query"]` scores 2 for
  `Q = #{"rama" "depot" "query"}`). Score is bounded by
  `min(|Q|, 32)`. Score 0 chunks are not candidates even if eligible.
- **Token comparison is exact, case-sensitive string equality.** `"Rama"`
  and `"rama"` are unrelated tokens. No stemming, trimming, or
  normalization. Group IDs, doc-ids, chunk-ids, user-ids, and tenants are
  compared the same way.
- **Ordering is total and deterministic.** Score descending, then doc-id
  ascending, then chunk-id ascending, all by plain string comparison
  (`"c10"` sorts before `"c2"`; `"B"` sorts before `"a"`). Two results can
  never tie on all three keys because `[doc-id chunk-id]` is unique among
  live chunks. The result for a given state, user, tokens, and `k` is the
  same on every client, at every task count, on every invocation.
- **Truncation is the last step.** The first `k` of the fully sorted
  candidate list are returned. Ineligible chunks and score-0 chunks are
  never in that list, so they never displace an eligible chunk even at
  `k = 1`. When candidates number fewer than `k`, all are returned.
- **Result maps have exactly six keys.** `:doc-id :chunk-id :score :text
  :content-revision :acl-revision`. Because a candidate's document is live
  and its ACL is set, both revisions are integers, never nil. Tests compare
  whole maps with `=`; extra or missing keys fail.
- **`get-document` maps have exactly six keys.** `:doc-id
  :content-revision :live? :chunk-count :acl-revision :groups`. `:groups`
  is `#{}` (not nil) when an empty ACL was set, and nil only when the ACL
  is unset. `:chunk-count` is the chunk vector length of the current live
  revision, 0 otherwise. `:live?` is a boolean, never nil.
- **`get-user-groups` maps have exactly two keys.** `:membership-revision`
  and `:groups`, with `:groups` `#{}` when set empty. nil only for an
  unknown user.
- **Superseded data need not be retained, current data must never be
  dropped.** Only the current revision of each facet is observable. But
  nothing about a document, user, or ACL is ever removed by the system on
  its own: a tombstone keeps its revision and ACL forever; an ACL-only
  document persists indefinitely; a user with `#{}` groups is still known.

## Operations

### `put-document!` (write, content stream)

- **Latency.** Ingestion path (CDC / connector). Effects visible within
  hundreds of milliseconds to low seconds is acceptable. After the caller's
  `wait-for-processing!` returns, every read on every client must reflect
  it.
- **Throughput.** Driven by document churn across all tenants: initial
  crawls (bursty, many documents per tenant), then steady edits. Each call
  carries up to 32 chunks × 32 tokens plus text. Work must be proportional
  to old chunk count + new chunk count of this one document only; it must
  never touch other documents or the tenant's vocabulary beyond this
  document's own tokens.
- **Invariants.**
  - Applied iff `content-revision` > current content-revision or current is
    nil; otherwise total no-op.
  - When applied: content-revision := given; live? := true; chunk set :=
    exactly the given vector (old chunks gone, including same-id chunks);
    ACL facet untouched (revision and groups unchanged, unset stays unset).
  - The revision advance and the chunk replacement are one atomic unit with
    respect to retries and to reads after synchronization: no read may
    observe a superseded chunk together with the new revision, or a new
    chunk with the old revision, once the writer has synchronized.
  - Applying on a tombstoned document revives it under its existing ACL
    facet, whatever that is (unset, `#{}`, or non-empty).
  - Re-delivery of the same call is a no-op (equal revision).
- **Data growth.** Documents per tenant unbounded; tenants unbounded.
  Chunks per document ≤ 32, tokens per chunk ≤ 32, so a document's index
  footprint is bounded by ~1024 token-occurrences plus text. The
  tenant-wide set of distinct tokens is unbounded, and the number of chunks
  containing one common token is unbounded (a stop-word-like token may be
  present in most chunks of the tenant).
- **Concurrency.** Two puts for the same document from one client apply in
  invocation order. Writes to different documents have no ordering
  relation. A put and an ACL write on the same document apply in invocation
  order but do not affect each other's outcome. Repeated processing of the
  same append must converge to the single-application state.
- **Edge cases.** Revision equal to a tombstone's revision → no-op.
  Revision skipping (1 then 7) is normal. Chunk vector length 1 and 32 are
  both normal. A chunk with an empty `:tokens` vector (allowed: "at most
  32") is stored, counted in `:chunk-count`, and never scores. Same
  chunk-id reused across revisions with different text must return the
  new text. New vector shorter than old must drop the surplus old
  chunk-ids. Very long revision integers (longs) must compare correctly.
  Same doc-id in another tenant is unaffected.

### `delete-document!` (write, content stream)

- **Latency.** Same as `put-document!`.
- **Throughput.** Driven by document deletions and permission-driven
  purges; typically far lower than puts. Work proportional to the old chunk
  count of this one document.
- **Invariants.**
  - Applied iff `content-revision` > current content-revision or current is
    nil; otherwise no-op.
  - When applied: content-revision := given; live? := false; chunk set :=
    empty (no chunk of any earlier revision may be returned by `query`
    afterwards); ACL facet untouched.
  - Deleting a never-written document creates a tombstone record: after it,
    `get-document` is non-nil with `:content-revision` = given,
    `:live? false`, `:chunk-count 0`, and ACL fields as they were (nil/nil
    if unset).
  - Deleting an already-tombstoned document with a higher revision advances
    the revision and remains tombstoned.
  - Re-delivery is a no-op.
- **Data growth.** Tombstones persist forever; a tenant may accumulate an
  unbounded number of tombstoned documents. Tombstoned documents must not
  contribute to `query` work.
- **Concurrency.** Ordered with other content and ACL writes to the same
  document by invocation order; independent of all other documents.
- **Edge cases.** Delete then put at the same revision → put is a no-op;
  document stays tombstoned. Delete on an ACL-only document → tombstone
  with ACL and acl-revision preserved. Delete at revision lower than the
  live revision → no-op, document stays live with all chunks.

### `put-document-acl!` (write, ACL stream)

- **Latency.** Permission changes must take effect quickly relative to
  human expectations (revocation is security-relevant): visible within
  hundreds of milliseconds to low seconds; after synchronization, every
  query on every client must honor the new ACL.
- **Throughput.** Driven by permission changes and initial ACL population
  (one call per document at crawl time, so bursts comparable to puts).
  Work must be independent of the number of documents and chunks in the
  tenant, and independent of this document's own chunk count.
- **Invariants.**
  - Applied iff `acl-revision` > current acl-revision or current is nil;
    otherwise no-op.
  - When applied: acl-revision := given; groups := given set (possibly
    `#{}`); content facet untouched (revision, live?, chunks all
    unchanged). Setting an ACL on a tombstoned document leaves it
    tombstoned; on a never-written document it creates an ACL-only record.
  - Narrowing or emptying the ACL immediately makes previously eligible
    chunks ineligible for users outside the new set; widening immediately
    makes live chunks eligible for newly included groups. No content write
    is needed for either.
  - Re-delivery is a no-op.
- **Data growth.** One ACL per document; group sets are small (tens), not
  unbounded per document. ACL-only documents persist forever.
- **Concurrency.** Ordered with other writes to the same document by
  invocation order; outcome independent of content writes.
- **Edge cases.** `groups = #{}` is a set ACL (revision advances,
  `get-document` shows `:groups #{}`), and denies everyone. ACL on unknown
  document → `get-document` returns `{... :content-revision nil :live?
  false :chunk-count 0 :acl-revision r :groups g}`. Equal revision with a
  different group set → no-op, first set kept. Group IDs present in the ACL
  that no user holds are fine and simply never match.

### `put-user-groups!` (write, membership stream)

- **Latency.** IdP sync path; same visibility bound as ACL writes.
  Revocation (removing a group) must be honored by the next synchronized
  query.
- **Throughput.** Driven by IdP sync: bursts of many users at once
  (full-tenant resync), plus steady trickle. Work must be independent of
  the number of documents and chunks in the tenant. Changing a user's
  groups must not reindex, rescan, or rewrite any document or chunk.
- **Invariants.**
  - Applied iff `membership-revision` > current membership-revision or
    current is nil; otherwise no-op.
  - When applied: membership-revision := given; groups := given set
    (possibly `#{}`). No document state changes.
  - The user's eligibility for every document in the tenant changes
    immediately and only through this write; there is no per-document
    membership state.
  - Re-delivery is a no-op.
- **Data growth.** Users per tenant unbounded; groups per user small
  (tens to hundreds). Known users persist forever.
- **Concurrency.** Writes for the same user apply in invocation order;
  writes for different users are unordered. Independent of every document
  write.
- **Edge cases.** `groups = #{}` → user known with no groups, sees
  nothing, `get-user-groups` returns `{:membership-revision r :groups
  #{}}`. Equal revision with different groups → no-op. A group named in
  membership that appears in no ACL is fine. Same user-id in another tenant
  unaffected.

### `get-document` (read)

- **Latency.** Single point lookup; tens of milliseconds is fine, it is an
  admin/debug read rather than the hot path.
- **Throughput.** Low; driven by admin tooling and tests. Must read one
  entity only — no chunk-proportional or tenant-proportional work.
- **Invariants.**
  - nil iff neither facet has ever been written (no content write, no
    tombstone, no ACL write) in this tenant.
  - Otherwise exactly the six keys with values derived from the current
    state of both facets as described in the domain facts.
  - `:chunk-count` equals the number of chunks that `query` could return
    from this document (given a fully matching query and an eligible user).
  - Reflects every write the calling client, or any other client, has
    synchronized.
- **Edge cases.** Tombstone of an unknown doc → non-nil with nil ACL
  fields. ACL-only → non-nil with nil content-revision, `:live? false`,
  `:chunk-count 0`. Unknown tenant → nil. Chunk count 1 and 32 both
  possible.

### `get-user-groups` (read)

- **Latency / throughput.** Same as `get-document`: one entity read.
- **Invariants.** nil iff no `put-user-groups!` has ever been applied for
  `[tenant user-id]`. The first write for a user always applies, so "never
  written" and "never applied" coincide. Otherwise
  `{:membership-revision r :groups g}` with `g` the set from the
  highest-revision applied write. Reflects every synchronized write from
  any client.
- **Edge cases.** Empty set groups → `#{}` not nil. Unknown tenant → nil.

### `query` (read)

- **Latency.** Hot path: a RAG chat turn blocks on retrieval. Target is
  low tens of milliseconds end to end for typical queries (a handful of
  distinct tokens, `k` ≤ ~50). Latency must not degrade with the number of
  documents in the tenant that contain none of the query tokens, nor with
  the number of tombstoned or ACL-only documents, nor with data of other
  tenants.
- **Throughput.** Dominant operation by volume: every chat turn issues at
  least one query, typically far more queries than writes. Aggregate work
  per query is bounded by the number of (query token, chunk) matches in the
  tenant plus the candidate set produced; a query whose tokens match
  nothing must do work bounded by `|Q|` lookups.
- **Invariants.**
  - `Q` = distinct tokens of `query-tokens`; duplicates have no effect on
    scores or results.
  - Candidate ⇔ document live ∧ ACL set ∧ (ACL ∩ user groups) ≠ ∅ ∧
    score > 0, using the state current at query time.
  - Authorization precedes truncation. The returned vector equals the first
    `k` of the complete sorted candidate list; it is never "top-k of all
    matches, then filter".
  - Sort: score desc, doc-id asc, chunk-id asc, string comparison.
  - Each result carries the document's current `:content-revision` and
    `:acl-revision` (both integers) and the chunk's verbatim `:text`.
  - Deterministic across clients and task counts.
  - Reads nothing belonging to another tenant; results never include
    another tenant's documents even when doc-ids, tokens, and group names
    coincide.
  - Never mutates state.
- **Data growth.** The number of chunks matching a common token in a large
  tenant is unbounded; a query on such a token must still be correct (it
  may examine all matches, per the efficiency contract) and must not load
  data for non-matching chunks. `k` may exceed the candidate count. `|Q|`
  is uncapped by the spec but typically small.
- **Edge cases.**
  - `k = 0` → `[]` (must still be a vector, not nil), regardless of state.
  - `query-tokens` empty → `Q = #{}` → `[]`.
  - No token present anywhere in the tenant → `[]`.
  - Unknown user, or known user with `#{}` groups → `[]` even when
    matching live documents exist.
  - Unknown tenant → `[]`.
  - Document with set but empty ACL → its chunks never appear.
  - Document live with ACL unset → never appears (worked example `d3`).
  - Document tombstoned → never appears even if its ACL matches.
  - Multiple chunks of one document all matching → each is its own result;
    a single document may fill all `k` slots.
  - Ties on score broken by doc-id then chunk-id; `"d10"` before `"d2"`.
  - `k` greater than the candidate count → all candidates.
  - Score capped by `|Q|`; a chunk containing every query token scores
    `|Q|`.
  - A revoked user (membership narrowed) or narrowed ACL takes effect on
    the next synchronized query with no content write.
  - After a document is recreated at a new revision, results show the new
    revision number and only new chunks; results never show a chunk-id that
    was dropped by the newer revision.

### Synchronization (`wait-for-processing!`, harness protocol)

- After it returns on the client that issued writes, all writes issued
  through that client before the call are reflected in every read on every
  client of the same deployed module.
- Calling it on a client that has issued no writes must return promptly.
- It must be correct whether the module is deployed with 2 or 4 tasks.
- Business state lives only in the module; the only client-local state
  permitted is transient synchronization bookkeeping. A second
  `wrap-client` over the same cluster must return identical results to the
  first for every read without any writes of its own.

## Entity State × Write Matrix

Reads considered for every document row: `get-document` for that doc, and
`query` by (a) a user whose groups intersect the document's ACL ("member
user") and (b) a user whose groups do not, or an unknown user ("non-member
user"), with tokens that match the document's chunks. Reads for every user
row: `get-user-groups` and `query` by that user.

Document states (content facet × ACL facet):

| # | State | content-revision | live? | acl-revision / groups |
|---|---|---|---|---|
| D0 | Unknown | nil | false | nil / nil |
| D1 | ACL-only | nil | false | a / g |
| D2 | Live, ACL unset | c | true | nil / nil |
| D3 | Live, ACL set | c | true | a / g |
| D4 | Tombstoned, ACL unset | c | false | nil / nil |
| D5 | Tombstoned, ACL set | c | false | a / g |

### Document × `put-document!` at revision r

**D0 Unknown × put r (always applies, r ≥ 1)**
  - get-document: `{:doc-id d :content-revision r :live? true :chunk-count n :acl-revision nil :groups nil}` — first content write, ACL untouched.
  - query (member user): impossible to be a member; ACL unset → no results from this doc.
  - query (non-member user): `[]` from this doc — ACL unset means deny.
  → state D2.

**D1 ACL-only × put r (always applies)**
  - get-document: `{... :content-revision r :live? true :chunk-count n :acl-revision a :groups g}` — ACL retained at its revision.
  - query (member user): the doc's matching chunks appear with `:content-revision r :acl-revision a` — became live under existing ACL.
  - query (non-member user): nothing from this doc.
  → state D3.

**D2 Live, ACL unset × put r > c**
  - get-document: `:content-revision r :chunk-count n'` (new count), `:live? true`, ACL still nil/nil.
  - query (any user): still nothing from this doc — ACL unset. Old chunks are gone regardless.
  → D2.

**D2 × put r ≤ c**
  - get-document: unchanged (`c`, old count).
  - query: unchanged, still nothing (ACL unset).
  → D2.

**D3 Live, ACL set × put r > c**
  - get-document: `:content-revision r :chunk-count n'`, ACL `a / g` unchanged.
  - query (member user): only the new chunks, each with `:content-revision r :acl-revision a`; no chunk-id from revision c survives, even if reused with new text (new text shown).
  - query (non-member user): nothing from this doc.
  → D3.

**D3 × put r ≤ c**
  - get-document: unchanged.
  - query (member user): old chunks, unchanged, `:content-revision c`.
  - query (non-member user): nothing.
  → D3.

**D4 Tombstoned, ACL unset × put r > c**
  - get-document: `:content-revision r :live? true :chunk-count n`, ACL nil/nil.
  - query (any user): nothing — ACL unset even though live again.
  → D2.

**D4 × put r ≤ c**
  - get-document: unchanged `:live? false :chunk-count 0`, revision c.
  - query: nothing.
  → D4. (This is the "delete unknown d at 5, put at 4 or 5 cannot revive" case.)

**D5 Tombstoned, ACL set × put r > c**
  - get-document: `:content-revision r :live? true :chunk-count n :acl-revision a :groups g` — retained ACL, unchanged ACL revision.
  - query (member user): new chunks with `:content-revision r :acl-revision a` (worked example step 9).
  - query (non-member user): nothing.
  → D3.

**D5 × put r ≤ c**
  - get-document: unchanged, still tombstoned at c with ACL a/g.
  - query: nothing from this doc.
  → D5.

### Document × `delete-document!` at revision r

**D0 Unknown × delete r (always applies)**
  - get-document: `{:doc-id d :content-revision r :live? false :chunk-count 0 :acl-revision nil :groups nil}` — tombstone record created, non-nil.
  - query (any user): nothing (no chunks, no ACL).
  → D4.

**D1 ACL-only × delete r (always applies)**
  - get-document: `:content-revision r :live? false :chunk-count 0 :acl-revision a :groups g` — ACL preserved.
  - query (any user): nothing (no chunks).
  → D5.

**D2 Live, ACL unset × delete r > c**
  - get-document: `:content-revision r :live? false :chunk-count 0`, ACL nil/nil.
  - query: nothing (was already nothing; now no chunks either).
  → D4.

**D2 × delete r ≤ c**
  - get-document: unchanged, live at c.
  - query: nothing (ACL unset).
  → D2.

**D3 Live, ACL set × delete r > c**
  - get-document: `:content-revision r :live? false :chunk-count 0 :acl-revision a :groups g` (worked example step 6).
  - query (member user): this doc's chunks disappear from results; other docs unaffected.
  - query (non-member user): nothing, as before.
  → D5.

**D3 × delete r ≤ c**
  - get-document: unchanged, live at c.
  - query (member user): all chunks still returned with `:content-revision c`.
  - query (non-member user): nothing.
  → D3.

**D4 Tombstoned, ACL unset × delete r > c**
  - get-document: `:content-revision r`, still `:live? false :chunk-count 0`, ACL nil/nil.
  - query: nothing.
  → D4.

**D4 × delete r ≤ c**
  - get-document: unchanged.
  - query: nothing.
  → D4.

**D5 Tombstoned, ACL set × delete r > c**
  - get-document: `:content-revision r`, tombstoned, ACL a/g preserved.
  - query: nothing.
  → D5.

**D5 × delete r ≤ c**
  - get-document / query: unchanged.
  → D5.

### Document × `put-document-acl!` at revision r with groups g'

**D0 Unknown × acl r g' (always applies)**
  - get-document: `{:doc-id d :content-revision nil :live? false :chunk-count 0 :acl-revision r :groups g'}` — ACL-only record; content untouched.
  - query (user in g'): nothing — not live, no chunks.
  - query (user not in g'): nothing.
  → D1.

**D1 ACL-only × acl r > a**
  - get-document: `:acl-revision r :groups g'`, content nil / false / 0.
  - query (any user): nothing (no chunks).
  → D1.

**D1 × acl r ≤ a**
  - get-document / query: unchanged.
  → D1.

**D2 Live, ACL unset × acl r g' (always applies)**
  - get-document: `:content-revision c :live? true :chunk-count n :acl-revision r :groups g'`.
  - query (user in g'): this doc's matching chunks now appear with `:content-revision c :acl-revision r`, ranked by score among other candidates (worked example step 11: a score-3 chunk moves to first).
  - query (user not in g', or g' = `#{}`): nothing from this doc.
  → D3.

**D3 Live, ACL set × acl r > a**
  - get-document: `:acl-revision r :groups g'`, content unchanged.
  - query (user in g' but not in old g): now sees the doc's chunks with `:acl-revision r` — widened.
  - query (user in old g but not in g'): no longer sees them — narrowed/revoked; no content write needed.
  - query (user in both): sees them, now with `:acl-revision r`.
  - query (user in neither): nothing.
  → D3.

**D3 × acl r ≤ a**
  - get-document: unchanged (old g, a).
  - query: unchanged; membership decided by old g.
  → D3.

**D4 Tombstoned, ACL unset × acl r g' (always applies)**
  - get-document: `:content-revision c :live? false :chunk-count 0 :acl-revision r :groups g'`.
  - query (any user): nothing — tombstoned; ACL does not resurrect.
  → D5.

**D5 Tombstoned, ACL set × acl r > a**
  - get-document: `:acl-revision r :groups g'`, still tombstoned at c (worked example step 7).
  - query (any user): nothing. A later `put-document!` with revision > c will revive under g' at revision r.
  → D5.

**D5 × acl r ≤ a**
  - get-document / query: unchanged.
  → D5.

### Document × `put-user-groups!` (any user)

No document row changes under a membership write: `get-document` is
identical before and after for every document. Only `query` outcomes
change, covered in the user matrix below.

### User × `put-user-groups!` at revision r with groups g'

User states: U0 unknown (nil); U1 known with non-empty groups `(m, g)`;
U2 known with empty groups `(m, #{})`.

**U0 Unknown × membership r g' (always applies)**
  - get-user-groups: `{:membership-revision r :groups g'}`.
  - query: user now sees every live, ACL-set document whose ACL intersects g', scored and ranked normally; if g' = `#{}`, still `[]`.
  → U1 if g' non-empty, U2 if empty.

**U1 Known (m, g) × membership r > m, g' non-empty**
  - get-user-groups: `{:membership-revision r :groups g'}`.
  - query: eligibility recomputed against g': documents whose ACL intersects g' but not g become visible; documents whose ACL intersects g but not g' disappear. `get-document` for all docs unchanged. No document revision changes.
  → U1.

**U1 × membership r > m, g' = `#{}`**
  - get-user-groups: `{:membership-revision r :groups #{}}`.
  - query: `[]` for every query by this user (worked example step 10).
  → U2.

**U1 × membership r ≤ m**
  - get-user-groups: unchanged `(m, g)`.
  - query: unchanged.
  → U1.

**U2 Known (m, #{}) × membership r > m, g' non-empty**
  - get-user-groups: `{:membership-revision r :groups g'}`.
  - query: results appear per g'.
  → U1.

**U2 × membership r > m, g' = `#{}`**
  - get-user-groups: revision r, groups `#{}`.
  - query: `[]`.
  → U2.

**U2 × membership r ≤ m**
  - get-user-groups / query: unchanged.
  → U2.

### User × document writes

A user row never changes under `put-document!`, `delete-document!`, or
`put-document-acl!`: `get-user-groups` is identical before and after. The
effect on that user's `query` is exactly the per-document effect listed in
the document matrix.

### Cross-tenant rows

Every row above is unchanged for the same doc-id or user-id in a different
tenant: writes in tenant A leave `get-document`, `get-user-groups`, and
`query` in tenant B byte-for-byte identical.
