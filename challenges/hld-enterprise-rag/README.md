# HLD Enterprise RAG Challenge

Build the retrieval and authorization core of a multi-tenant RAG platform:
revisioned document content with tombstones, independently revisioned
document ACLs and user group memberships, and a permission-aware keyword
query that authorizes every candidate before ranking and truncation.

## Attribution

This challenge is adapted from the case study
["Design an Enterprise RAG System"](https://hld.handbook.academy/curriculum/case-studies/enterprise-rag/)
by The HLD Handbook contributors
([handbook-academy/engineering-handbook](https://github.com/handbook-academy/engineering-handbook)),
licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
The prose in this README is adapted from that case study and is likewise
offered under CC BY-SA 4.0.

### What was changed

- Retrieval is reduced to a sparse keyword model over pre-tokenized chunks:
  score is the count of distinct query tokens present in the chunk. This
  stands in for BM25 and dense retrieval; there is no fusion or reranking.
- The case study's central rule, "pre-filter ACLs before nearest-neighbour
  search, never post-filter", is kept as a hard contract: authorization
  happens before top-k truncation and ineligible chunks never occupy result
  slots.
- Change-data-capture and content-hash diffing become explicit, monotonic
  content revisions shared by puts and tombstones. IdP group sync becomes
  explicit, revisioned user membership writes. Document ACLs have their own
  revision stream.
- Chunks are capped at 32 per document and 32 tokens per chunk. Documents,
  users, and groups are all tenant-scoped.

### What was excluded

Connectors and parsers, embeddings and vector indexes, contextual retrieval,
reciprocal rank fusion, cross-encoder reranking, LLM generation and
streaming, citation validation, semantic caching, admin consoles, data
residency, and all capacity numbers. Results carry the chunk text as the
citation payload; no answer is generated.

## Domain model

**Tenant scope.** Every method takes a `tenant`. `doc-id`, `user-id`, and
`group-id` values are only meaningful within a tenant; the same string in
two tenants refers to two different things.

**Three revision streams.** Each is monotonic under "strictly greater wins;
equal or lower is a no-op":

| Stream | Writers | Effect |
|---|---|---|
| content | `put-document!`, `delete-document!` | replaces chunks / tombstones |
| ACL | `put-document-acl!` | replaces the document's group set |
| membership | `put-user-groups!` | replaces the user's group set |

They never interact:

- Content writes never modify the ACL. A document created before its ACL is
  set is not eligible until the ACL is set.
- ACL writes never resurrect content. Setting or widening an ACL on a
  tombstoned document leaves it tombstoned.
- A document recreated by a newer `put-document!` after a tombstone keeps
  the ACL it already had, at its existing ACL revision.
- An unknown ACL (never set) means deny. An unknown user (never set) has no
  groups and therefore sees nothing.

A deletion before any content exists establishes its revision fence. For
example, deleting unknown document `d` at revision 5 produces
`{:doc-id "d" :content-revision 5 :live? false :chunk-count 0
  :acl-revision nil :groups nil}`. A put at revision 4 or 5 cannot revive it.
Deleting an ACL-only document preserves that ACL and its revision.

**Chunks.** `put-document!` supplies a vector of 1 to 32 chunk maps
`{:chunk-id :text :tokens}`. Chunk IDs are distinct within the document.
`:tokens` is a vector of at most 32 non-empty strings, already tokenized;
comparison is exact string equality with no normalization. A newer content
revision fully replaces the chunk set.

**Eligibility.** A chunk is eligible for a user iff its document is live
(has chunks, not tombstoned) and the document's ACL group set intersects the
user's current group set.

**Scoring and ordering.** For a query, distinct query tokens form a set `Q`.
A chunk's score is the number of tokens in `Q` that occur in the chunk's
token vector (duplicate chunk tokens count once). Only eligible chunks with
score `> 0` are candidates. Candidates are sorted by score descending, then
`doc-id` ascending, then `chunk-id` ascending, and the first `k` are
returned. Authorization is applied **before** truncation: an ineligible
chunk with a high score does not consume a slot. `k = 0` returns `[]`.

**Result shape.** Each result carries `:doc-id`, `:chunk-id`, `:score`, the
chunk `:text` as the citation, and the document's current
`:content-revision` and `:acl-revision`.

## Worked example

Tenant `"acme"`.

```clojure
(put-user-groups!   c "acme" "alice" 1 #{"eng"})
(put-user-groups!   c "acme" "bob"   1 #{"sales"})

(put-document-acl!  c "acme" "d1" 1 #{"eng"})
(put-document!      c "acme" "d1" 1
  [{:chunk-id "c1" :text "Depots feed PStates."   :tokens ["rama" "depot" "pstate"]}
   {:chunk-id "c2" :text "Query topologies read." :tokens ["rama" "rama" "query"]}])

(put-document-acl!  c "acme" "d2" 1 #{"eng" "sales"})
(put-document!      c "acme" "d2" 1
  [{:chunk-id "c1" :text "Pricing depends on query volume." :tokens ["depot" "query" "pricing"]}])

(put-document!      c "acme" "d3" 1                       ; no ACL ever set
  [{:chunk-id "c1" :text "Unrestricted draft." :tokens ["rama" "depot" "query"]}])
```

1. `query "acme" "alice" ["rama" "depot" "query" "query"] 10` → `Q = #{"rama" "depot" "query"}`.
   Scores: `d1/c1` = 2 (`rama`, `depot`), `d1/c2` = 2 (`rama`, `query`),
   `d2/c1` = 2 (`depot`, `query`), `d3/c1` = 3 but its ACL is unset → denied.
   Result, all tied at 2, ordered by doc-id then chunk-id:
   ```clojure
   [{:doc-id "d1" :chunk-id "c1" :score 2 :text "Depots feed PStates."   :content-revision 1 :acl-revision 1}
    {:doc-id "d1" :chunk-id "c2" :score 2 :text "Query topologies read." :content-revision 1 :acl-revision 1}
    {:doc-id "d2" :chunk-id "c1" :score 2 :text "Pricing depends on query volume." :content-revision 1 :acl-revision 1}]
   ```
2. Same query with `k = 2` → the first two of those. `k = 0` → `[]`.
3. `query "acme" "bob" ["rama" "depot" "query"] 10` → only `d2/c1` (score 2).
   `d1` is `eng`-only; `d3` has no ACL. Authorization before top-k means
   `d1`'s two chunks never displace `d2/c1` even at `k = 1`.
4. `query "acme" "alice" ["pricing"] 10` → `[{:doc-id "d2" :chunk-id "c1" :score 1 ...}]`.
5. `query "acme" "alice" ["nothing"] 10` → `[]`. `query "acme" "carol" ["rama"] 10` → `[]`
   (unknown user).
6. `delete-document! "acme" "d1" 2` → `d1` tombstoned.
   `get-document "acme" "d1"` → `{:doc-id "d1" :content-revision 2 :live? false :chunk-count 0 :acl-revision 1 :groups #{"eng"}}`.
   Alice's query from step 1 now returns only `d2/c1`.
7. `put-document-acl! "acme" "d1" 2 #{"eng" "sales"}` → ACL widened; `d1`
   stays tombstoned. Bob still gets only `d2/c1`.
8. `put-document! "acme" "d1" 2 [...]` → no-op (equal revision).
   `put-document! "acme" "d1" 1 [...]` → no-op (lower).
9. `put-document! "acme" "d1" 3 [{:chunk-id "c9" :text "Recreated." :tokens ["rama"]}]`
   → `d1` is live again with one chunk, under the retained ACL from step 7.
   `query "acme" "bob" ["rama"] 10` →
   `[{:doc-id "d1" :chunk-id "c9" :score 1 :text "Recreated." :content-revision 3 :acl-revision 2}]`.
   Chunks `c1` and `c2` from revision 1 are gone.
10. `put-user-groups! "acme" "bob" 1 #{"eng"}` → no-op (equal revision).
    `put-user-groups! "acme" "bob" 2 #{}` → Bob now has no groups and every
    query for Bob returns `[]`.
11. `put-document-acl! "acme" "d3" 1 #{"eng"}` → `d3` becomes eligible for
    Alice; her step 1 query now ranks `d3/c1` (score 3) first.
12. `query "globex" "alice" ["rama"] 10` → `[]`; tenant `"globex"` has no data.

## Efficiency contract

Bounds concern application-level records examined or updated, allowing
input/output-size costs and ordinary lookup and ranking overhead.

- `query` may examine query-token/chunk matches within the tenant and
  the matching candidate chunks. It must not
  scan documents or chunks that contain none of the query tokens, and it
  must never read another tenant's data.
- `put-document!` and `delete-document!` may do work proportional to the
  chunks of that one document (old and new). They must not touch other
  documents.
- `put-document-acl!` and `put-user-groups!` must do work independent of
  the number of documents and chunks in the tenant. Changing a user's groups
  must not perform document- or chunk-proportional work.
- `get-document` and `get-user-groups` read one entity.

## Input assumptions

- `tenant`, `doc-id`, `user-id`, `chunk-id`, group IDs, tokens, and chunk
  text are non-empty strings.
- Revisions are positive signed 64-bit integers; `k` is a nonnegative
  signed 32-bit integer.
- Chunk vectors have 1 to 32 chunks with distinct chunk IDs; each chunk has
  at most 32 tokens.
- Tests supply valid input except for the explicitly specified cases: stale
  or equal revisions on any of the three streams, ACL/content interactions
  (tombstone then ACL change, recreation), and unknown users or ACLs.

## Write ordering and synchronization

All `!` methods are asynchronous writes. Tests call
`(harness/wait-for-processing! client)` after a group of writes and before
any read. Writes addressed to the same document (content, tombstone, ACL)
must take effect in the order the client invoked them, and writes for the
same user likewise. No ordering is required between different documents or
users.

All authoritative business state must be durable Rama state (depots and
PStates); transient synchronization counters are allowed. The module runs
with both 2 and 4 tasks in private validation. Multiple clients wrapping
the same deployed module must observe the same business state after the
writing client synchronizes. Tests alternate synchronized client phases;
no cross-client concurrent ordering or snapshot isolation is required.

## Protocol

Your implementation must satisfy the `EnterpriseRag` protocol defined in
`src/hld_enterprise_rag/protocol.clj`. The docstrings there are part of the
contract.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <EnterpriseRag implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified `EnterpriseRag` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `rama-challenges.harness/Synchronizable`. See
the docstring on that protocol for implementation requirements. Tests call
`(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `hld-enterprise-rag.module`.

## File Location

Write your solution to:
```
implementations/hld-enterprise-rag/src/hld_enterprise_rag/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
