# Plan — subsystem `revisioned-entities`

<!-- Phase 1 artifact for hld-enterprise-rag, subsystem 1 of 2 (DECOMPOSITION.json).
Authority: README.md + src/hld_enterprise_rag/protocol.clj docstrings + harness
`Synchronizable` docstring. IMPLICIT_SPEC.md is derived guidance: its latency, throughput,
skew, and "dominant operation" notes are NOT requirements and justify nothing below.
Subsystem 2 (authorized-query) is a black box: nothing here assumes its mechanisms; what
the README states about `query` (efficiency bullet, eligibility) binds the state delivered. -->

## Scope

Owned: `put-document!`, `delete-document!`, `put-document-acl!`, `put-user-groups!`,
`get-document`, `get-user-groups`, and the module-wide `wait-for-processing!` contract.
Delivered durable state (README "Efficiency contract", query bullet): per-tenant document
records (content facet + ACL facet), per-tenant user membership records, and a per-tenant
token → chunk index maintained only by content writes, so that a read about one token
touches only chunks containing that token and never another tenant's data.

`query` is not designed here; the build session stubs it on the reified protocol.

Identity records (defrecords, used as depot fields, `|hash` inputs, and PState keys):
`DocKey [tenant doc-id]`, `UserKey [tenant user-id]`, `TokenKey [tenant token]`,
`ChunkRef [doc-id chunk-id]` (tenant implied by the enclosing `TokenKey`).

## Reads

| Read | Access | Path | Partition | Cost |
|---|---|---|---|---|
| `get-document t d` | `foreign-select-one` on `$$docs` | `[(keypath (->DocKey t d)) (submap [:content-revision :chunk-count :acl-revision :groups])]` | `hash(DocKey)` | 1 seek, 0 iterations, 1 roundtrip |
| `get-user-groups t u` | `foreign-select-one` on `$$users` | `[(keypath (->UserKey t u))]` | `hash(UserKey)` | 1 seek, 0 iterations, 1 roundtrip |

`get-document` client mapping: if both `:content-revision` and `:acl-revision` are nil the
document was never written → `nil` (every accepted write sets one of them). Otherwise
`{:doc-id d :content-revision cr :live? (pos? chunk-count) :chunk-count (or cc 0)
:acl-revision ar :groups g}` — `:groups` stays `nil` when unset and `#{}` when set empty,
because the stored field is the accepted set verbatim. `submap` keeps the chunk payload off
the wire. `get-user-groups`: `nil` when absent, else `{:membership-revision r :groups g}`
read straight from the record.

Neither read needs a query topology: each is one path on one PState on one partition.

Reads the delivered state supports (properties of the state, not a design of subsystem 2):
one token of one tenant → `[(keypath (->TokenKey t tok)) ALL]` on `$$postings` is 1 seek +
one iteration per chunk containing that token, nothing else; one candidate document →
`(keypath (->DocKey t d))` on `$$docs` is 1 seek returning current content revision, chunk
texts, ACL revision and groups together, so eligibility and the result payload come from the
state current at read time (README "Eligibility", "Result shape").

## Writes

All four `!` methods append one record to `*writes` with `:append-ack`, after incrementing
the shared sync counter. Nothing is returned; the README makes writes asynchronous with
visibility deferred to `wait-for-processing!`. Every record is guarded in dataflow (malformed
→ dropped, never thrown: a throwing record retries forever and blocks the topology).

| Write | Depot record | Effect on the key's task |
|---|---|---|
| `put-document! t d r chunks` | `PutDocument [key=DocKey r chunks]` | read `$$docs[key]` (1 seek). Accept iff stored `:content-revision` nil or `r` > it. On accept: whole-record `termval` with `:content-revision r`, `:chunk-count n`, `:chunks` = new map, ACL fields copied from the loaded record (no read). Then fan out posting deletes for `(old-refs − new-refs)` and posting adds for `(new-refs − old-refs)`, each `|hash TokenKey` then a no-read element write. Else no-op. |
| `delete-document! t d r` | `DeleteDocument [key r]` | same read and fence. On accept: `termval` record with `:content-revision r`, `:chunk-count 0`, `:chunks {}`, ACL fields copied. Fan out deletes for all old refs. Else no-op. |
| `put-document-acl! t d r groups` | `PutDocumentAcl [key r groups]` | read record (1 seek). Accept iff stored `:acl-revision` nil or `r` > it. On accept: `termval` record with `:acl-revision r`, `:groups groups`, content fields copied. No posting work. Else no-op. |
| `put-user-groups! t u r groups` | `PutUserGroups [key=UserKey r groups]` | read `$$users[key]` (1 seek). Accept iff nil or `r` > stored. On accept: `termval {:membership-revision r :groups groups}`. Else no-op. |

"refs" of a record = `#{[TokenKey ChunkRef] …}` over its chunks' distinct tokens (≤ 32 × 32).
A chunk-id reused across revisions is a different chunk; its refs are recomputed from the new
token set, so surplus tokens are deleted and new ones added — the presence-only posting has
nothing else to update.

Guard (`well-formed?`, plain Clojure fn, at least as strict as the schema): revisions are
positive integers (coerced with `long`); tenant/doc-id/user-id non-empty strings; `chunks`
a vector of 1..32 maps with non-empty string `:chunk-id` (distinct) and `:text`, `:tokens` a
vector of ≤ 32 non-empty strings; `groups` a set (coerced with `set`) of non-empty strings.
Normalization: `:tokens` stored as the distinct set (duplicates count once and nothing returns
the vector).

## PState Design

### `$$docs` — one record per document, both facets, chunk payload inline

```
$$docs: {DocKey fixed-keys{:content-revision Long                 ;; nil until first content write
                          :chunk-count      Long                 ;; 0 when tombstoned
                          :chunks           {String<chunk-id> fixed-keys{:text   String
                                                                         :tokens Set<String>}}  ;; not subindexed, ≤32 by guard
                          :acl-revision     Long                 ;; nil until first ACL write
                          :groups           Set<String>}}        ;; nil until set; #{} legal
```

- **Key = `DocKey`.** Every content and ACL bound is per document within a tenant; same-key
  ordering is per document; both owned reads are one entity. One key = one record = one seek.
- **Both facets in one record.** Same key, same partitioner → the skill forbids two PStates.
  Each accepted write loads the record once (the fence needs it) and replaces it whole with
  the untouched facet copied from the loaded value: content writes cannot alter ACL fields and
  vice versa by construction, and no write does a second read.
- **`:chunk-count` stored** so `get-document` returns without shipping chunk payloads, and
  `:live?` is `(pos? chunk-count)` (protocol: "true iff it currently has chunks").
- **`:chunks` not subindexed.** Bounded at 32 by the README ("Chunks are capped at 32 per
  document") and enforced by the guard. Costed against subindexing below.
- **`:groups` / `:tokens` as `set-schema String`, not subindexed.** Every operation reads or
  replaces the whole set (`get-document` returns `:groups` whole; ACL writes replace it;
  content writes replace the chunk map). No bound is stated for groups; see difficulty log.

Alternatives costed (read = the delivered per-document read, 1 seek in the chosen design;
put = accepted `put-document!`; acl = accepted `put-document-acl!`; sizes: 32 chunks × ~1–2 KB
text ≈ 30–60 KB worst-case record):
- **Option B — `:chunks` subindexed (`map-schema … {:subindex? true}`), header small.**
  Read of a document with `c` matching chunks: 1 seek (header) + `c` point seeks, or 1 + 1
  seek + ≤ 32 iterations as a range scan: ≥ 1.0 ms vs ≈ 0.5 ms + ~0.1 ms deserialization.
  Put: 1 + 1 seek + 32 iterations to recover old tokens, delete the old map, 32 element
  writes, vs 1 seek + 1 write. ACL: 1 small read/write (≈ 0.5 ms) vs 1 read/write of the blob
  (≈ 0.6 ms). Rejected: the per-document read costs ≥ 2× per candidate document, and the ACL
  saving is ~0.1 ms on a bounded blob.
- **Option C — split `$$content` and `$$acl` PStates.** Same key and partitioner: forbidden by
  the skill (two partitions per task for one key), and `get-document` would need 2 seeks or a
  query topology. Rejected.
- **Option D — chunk text duplicated into postings** (so a token read yields text directly).
  Storage and write volume ×(distinct tokens per chunk, ≤ 32): a put writes up to 1024 posting
  entries carrying ~1–2 KB each (≈ 1–2 MB) vs 1024 × ~60 B (≈ 60 KB), and every posting
  iteration drags text for chunks later found ineligible. The per-document read is still
  required for eligibility and current revisions, so nothing is saved. Rejected.

Chosen: A.

### `$$users` — one record per user

```
$$users: {UserKey fixed-keys{:membership-revision Long
                             :groups              Set<String>}}   ;; not subindexed; #{} legal
```

Obvious: one entity, one key, whole-record read and replace by contract. Separate from
`$$docs` because the key structure differs (a user and a document with the same string id in
one tenant are different entities); both partition by their own key.

### `$$postings` — per-tenant token → chunks containing it

```
$$postings: {TokenKey (map-schema ChunkRef Boolean {:subindex-options {:track-size? false}})}
```

- **Key = `TokenKey [tenant token]`.** The README query bullet requires reading only
  token/chunk matches within the tenant and never another tenant's data; tenant inside the key
  gives tenant isolation by construction and a large keyspace (tenants × vocabulary).
- **Value = subindexed map of `ChunkRef` → presence.** The number of chunks containing one
  token is unbounded (a stop-word may be in most chunks of a tenant), so it must be
  subindexed; reading all matches is one seek + one iteration per match. Presence only: text,
  revisions and ACL are read from `$$docs` at read time, because ACL and membership writes may
  not do chunk-proportional work (README) — the eligibility inputs cannot be denormalized here.
  Size tracking off: nothing reads the size, and it would add a read to every element write.
- **Flat `ChunkRef` key, not `{doc-id {chunk-id …}}`.** Nested subindexed maps cost one seek
  per document when iterating a token (D seeks) instead of one seek + M iterations.

Alternatives costed:
- **Option P2 — postings colocated with the document (`{DocKey {token #{chunk-id}}}`) and
  token reads fanned with `|all`.** Put/delete become single-partition, but one token read
  costs N seeks (one per task, empty slices included): N = 16 → 16, N = 128 → 128 seeks per
  token vs 1. Cost grows with cluster size. Rejected.
- **Option P3 — spread a hot token over `span(t)` tasks with stored placement
  (`$$token-meta {TokenKey Long}` on `hash(t)`; postings on `hash(t)+i`).** Token read: 1 seek
  (span) + span seeks + the same M iterations; put: each of ≤ 1024 refs needs a span read (1
  seek) before routing, i.e. up to 1024 extra seeks per put; growing a span re-places existing
  postings. Aggregate work per token read is never lower (same iterations, more seeks); the
  only gain is per-task latency and storage balance for stop-word tokens, which no README
  requirement states. Rejected on total cost: +|Q| seeks per token read, +≤ 1024 per put.

Chosen: flat `|hash` on `TokenKey`.

## Depots

- `*writes`: `(hash-by :key)`, client-appendable. Records (defrecords, all with a `:key`
  field): `PutDocument [key content-revision chunks]`, `DeleteDocument [key content-revision]`,
  `PutDocumentAcl [key acl-revision groups]` with `key = DocKey`; `PutUserGroups [key
  membership-revision groups]` with `key = UserKey`. Dispatch with `<<subsource`.

One depot: README requires per-document order across content, tombstone and ACL writes and
per-user order for membership writes — one log partition per key gives both. Document and
user streams have no ordering relation, so sharing the log costs nothing; `hash-by :key`
places every record on the task holding `$$docs[key]` or `$$users[key]`, so the fence
read and record write need no partitioner hop. A second depot for user writes was costed:
it adds a declaration and a second processed-count to wait on and removes nothing.

## Topologies and PStates

- **`entities`: microbatch.** Why: default microbatch. Neither stream reason applies — the
  README makes every write asynchronous with visibility deferred to `wait-for-processing!`,
  and no write returns a value. Microbatch adds what the content writes need: exactly-once
  PState updates across retries and cross-partition atomicity between the `$$docs` record on
  the document's task and the posting writes on token tasks (a settled attempt never leaves a
  new revision with old postings or vice versa).
  - Concerns: (1) revision-fenced record replace for documents and users — microbatch
    sufficient; (2) posting diff fan-out — microbatch sufficient and preferred (multi-partition
    write).
  - PStates owned: `$$docs`, `$$users`, `$$postings` (schemas above).
  - Dataflow shape (design sketch, not implementation):
    ```
    (source> *writes :> %mb)
    (%mb :> *rec)  (well-formed? *rec :> *ok?)  (filter> *ok?)
    (<<subsource *rec
      (case> PutDocument :> {*key :key *r :content-revision *chunks :chunks})
      (local-select> [(keypath *key)] $$docs :> *old)                       ;; 1 seek, nil if absent
      (<<if (content-accept? *old *r)                                      ;; nil or strictly greater
        (new-content-record *old *r *chunks :> *new)                        ;; ACL fields copied
        (local-transform> [(keypath *key) (termval *new)] $$docs)           ;; no-read write
        (posting-diff *key *old *new :> *ops)                               ;; [[TokenKey ChunkRef :add|:del] …]
        (ops/explode *ops :> [*tk *ref *op])
        (|hash *tk)
        (<<if (= *op :add)
          (local-transform> [(keypath *tk *ref) (termval true)] $$postings) ;; no-read write
         (else>)
          (local-transform> [(keypath *tk *ref) NONE>] $$postings)))        ;; no-read delete
      (case> DeleteDocument …)      ;; same fence; tombstone record; all old refs → :del
      (case> PutDocumentAcl …)      ;; fence on :acl-revision; content fields copied; no fan-out
      (case> PutUserGroups :> {*key :key *r :membership-revision *g :groups})
      (local-select> [(keypath *key :membership-revision)] $$users :> *cur)
      (<<if (accept? *cur *r)
        (local-transform> [(keypath *key) (termval {:membership-revision *r :groups *g})] $$users)))
    ```
- No stream topology. No second microbatch topology: every concern is a sub-millisecond
  record read/replace plus ≤ 2048 no-read element writes; one latency class.
- No internal depots.

Same-key ordering: `%mb` emits each task's depot partition in append order; a record's
document-task work (fence read + record write) runs before its first partitioner, so
same-document records apply in invocation order. Posting messages from a document task to a
token task are delivered in send order, so revision r's adds precede revision r+1's deletes
of the same refs; and r+1's diff is computed from the record r wrote (visible inside the
topology). Same-user records never hop.

## Query Topologies

None in this subsystem. Both owned reads are one path on one partition.

## Partitioning efficiency

**Optimal placement.** Owned reads and every write are scoped to one document or one user,
so their placement is `f(DocKey) → one task`, `f(UserKey) → one task`, keys spread evenly:
`|hash` on the identity record is exactly that `f` (keyspaces = all documents / all users
across all tenants, large; one small record per key). The delivered token read is scoped to
one `(tenant, token)` and must touch only that token's matches: `f(TokenKey) → one task`,
implemented by `|hash` on `TokenKey`; the P2/P3 alternatives above cost more in total.
Hashing on `(tenant, id)` rather than `tenant` keeps variance over the large keyspace and
prevents one tenant from landing on one task.

Table: the dominant read on the delivered state is "resolve one token of one tenant to its
candidate chunks, then read each candidate document once" — the only read whose cost
depends on data shape. Seeks/op are cluster totals. Per-token rows: 1 seek for the posting
list + D seeks for D distinct candidate documents; iterator reads = M matches. Owned point
reads (`get-document`, `get-user-groups`) are 1 seek, 0 iterations at every N and every
category. Nothing depends on N; the private validation runs at 2 and 4 tasks.

### N = 1 task (single-task baseline)
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| token absent from tenant | 0.20 | 1 | 0 |
| typical token: M = 20 matches over D = 10 documents | 0.60 | 11 | 20 |
| common token: M = 10,000 over D = 5,000 documents | 0.20 | 5,001 | 10,000 |
Weighted seeks = 0.2×1 + 0.6×11 + 0.2×5001 = 1,007.0   |   Weighted iterator reads = 0 + 12 + 2,000 = 2,012

### N = 16 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| token absent from tenant | 0.20 | 1 | 0 |
| typical token: M = 20, D = 10 | 0.60 | 11 | 20 |
| common token: M = 10,000, D = 5,000 | 0.20 | 5,001 | 10,000 |
Weighted seeks = 1,007.0   |   Weighted iterator reads = 2,012

### N = 128 tasks
| Data category | Frequency proportion | Seeks/op | Iterator reads/op |
|---|---|---|---|
| token absent from tenant | 0.20 | 1 | 0 |
| typical token: M = 20, D = 10 | 0.60 | 11 | 20 |
| common token: M = 10,000, D = 5,000 | 0.20 | 5,001 | 10,000 |
Weighted seeks = 1,007.0   |   Weighted iterator reads = 2,012

Flat from N = 1 to N = 128. The common-token row is the README's allowed cost ("may examine
query-token/chunk matches within the tenant and the matching candidate chunks"); tombstoned,
ACL-only and non-matching documents contribute nothing because their refs are absent. Writes:
1 seek per well-formed record (fence read), 0 for malformed; accepted content writes add
`|old-refs − new-refs| + |new-refs − old-refs|` ≤ 2048 no-read element writes on token tasks.
Storage spreads by document, user and token.

## Design Decisions

- **Subindexing:** `$$postings` inner map only (unbounded per token). `:chunks` (≤ 32,
  guard-enforced), `:tokens` (≤ 32, guard-enforced) and `:groups` are whole-value by contract.
- **Colocation:** depot `hash-by :key` = `$$docs`/`$$users` key = routing of the foreign reads;
  the topology hops only for postings, whose key is a different entity by necessity.
- **Revision fence in the topology, not the client:** the depot is the single authority for
  acceptance, identical for every client wrapping the module; a client-side read-then-append
  would race between clients.
- **Whole-record `termval` after the fence read:** the record is already loaded for the fence,
  so the replacement costs no second read and cannot leave a blend of old and new fields; the
  untouched facet is copied from the loaded value, which is what "never touches the ACL /
  never touches content" means operationally.
- **Posting diff, not delete-all-then-add-all:** write volume is `|Δ|` instead of `old + new`;
  identical outcome, and presence-only values make the unchanged refs need no write.
- **Retry safety:** microbatch PState writes are exactly-once. Independently every write is
  idempotent: re-delivery of an accepted record hits the equal-revision case; posting writes
  are `termval`/`NONE>`.
- **Malformed input dropped in dataflow**, never thrown; tests send valid input, but the guard
  protects production and a second client not written by us.
- **Never deletes business data:** tombstones and ACL-only records persist; a token whose
  posting map empties keeps its (empty) map.

## Synchronization (`Synchronizable`)

`wait-for-processing!` calls `(rtest/wait-for-microbatch-processed-count ipc module-name
"entities" n)` where `n` is the cumulative number of records appended to `*writes` by every
client on this cluster: one `(atom {})` created inside `create-module` and closed over by
`:wrap-client`, keyed by the IPC instance and incremented by each wrapper before every
`foreign-append!` (same pattern as the existing hld-metrics-pipeline reference module; scoped
to the `create-module` result rather than a process-wide `defonce` so closed IPC instances are
released with it and wrappers of different IPCs built from one result keep independent
counts). Shared across all wrappers of one cluster, not per-client: the processed count
is module-cumulative, so a per-client count could be satisfied by another client's records
while this client's own writes are unprocessed, breaking "Multiple clients wrapping the same
deployed module must observe the same business state after the writing client synchronizes".
Rejected and malformed records are depot records too and are counted. A client that never
wrote waits on the current total and returns promptly. Correct at 2 and 4 tasks. README
explicitly permits transient synchronization counters; no business state lives in the client.

## State primitive selection

- `$$docs` (PState): source of truth for both facets. Per-source-event write volume O(1)
  records of size O(supplied chunks). Durable.
- `$$users` (PState): source of truth for membership. O(1) per event. Durable.
- `$$postings` (PState): derived index, per-source-event write volume ≤ 2 × 32 × 32 element
  writes, bounded by inputs the README caps. Durable — it must survive restarts (worker restart
  does not replay depots) and be current after every synchronized write.
- `append-counts` (`create-module`-scoped atom, keyed by IPC): transient sync counter, permitted by README.
- No TaskGlobals. No external systems.

## Resource usage analysis

### Disk usage (PStates)
- `$$docs` entry: key ≈ 40–80 B; value ≈ 60 B scalars + per chunk (text ~0.5–2 KB + ≤ 32
  tokens × ~10 B + ~30 B) → ≈ 20–70 KB for a full 32-chunk document; tombstone/ACL-only
  ≈ 100–300 B (groups tens of strings). Growth: one entry per distinct document, replaced in
  place; superseded revisions retain nothing. Per task: documents × entry / N.
- `$$users` entry: key ≈ 40–60 B; value ≈ 30 B + ~10 B per group. One per user.
- `$$postings`: per (tenant, token) one map; per element key ≈ `ChunkRef` 30–60 B + 1 B value.
  Growth: ≤ 1024 elements per live document (distinct tokens × chunks), deleted on tombstone
  or replacement. Per task ≈ live documents × ~500 avg × ~50 B / N.
- `*writes` depot: one record per call ≈ record size (put ≈ chunk payload). Default retention;
  the topology never reads history back.

### Memory usage (TaskGlobals)
None.

### Minimization
- `$$docs` holds exactly what a read must return (`get-document` fields; texts, revisions and
  groups for the delivered per-document read) plus `:tokens`, kept so a later content write
  can find its old postings with work proportional to old chunks (README put/delete bullet)
  instead of a tenant-wide scan. `:chunk-count` is a Long duplicating `(count chunks)` to keep
  `get-document` payload-free; `:live?` is derived, not stored.
- `$$postings` values are the smallest possible (`true`); the only duplication is the
  `doc-id`/`chunk-id` strings inside `ChunkRef`, which is the index itself.
- Boxed `Long`s inside persistent maps are dictated by the map shapes the protocol fixes.

## Design difficulty log

- **Chunk payload inline vs subindexed `:chunks` (Option B).** Genuinely contested. Inline
  makes ACL writes and `get-document` load a bounded blob that they do not need (the README
  bound is "independent of the number of documents and chunks in the tenant", which a 32-chunk
  constant satisfies), while subindexing makes the delivered per-document read ≥ 2× the seeks.
  Costing settled it: the per-candidate document read is the operation repeated per candidate,
  the ACL/get-document penalty is ~0.1 ms on a bounded value. Build-phase verification item:
  `submap` on the nested fixed-keys record returns the scalar fields without materializing the
  chunk map on the client.
- **Hot tokens under `|hash TokenKey` (Option P3).** The skill's `|hash` indicator flags
  per-key skew, and stop-word tokens are skewed. I constructed the stored-span spreading and
  costed it: more seeks on every token read and every put for a latency/balance benefit the
  README never states. Settled by the "maximize throughput, no stated latency target" rule.
- **One depot vs two.** Close call on taste, not cost; one depot gives one processed-count and
  one dispatch, and the README's ordering requirements are per key either way.
- **Non-subindexed `:groups`.** The checklist wants an enforced bound; the README gives none
  for group sets. Every operation reads or replaces the set whole by contract, so subindexing
  turns 1 seek into 1 seek + G iterations on reads and G element writes plus a structure
  delete on every ACL/membership write, and breaks whole-value `foreign-select-one`. Inventing
  a cap would reject spec-legal writes, so I did not. Logged honestly.
- **`ChunkRef` record as a subindexed map key.** Records work as top-level keys in existing
  reference code; as an inner subindexed key the sort is by serialized form, which is
  irrelevant here (no range reads on postings). Build-phase verification item; fallback is a
  string key `doc-id` + nested chunk map, which was costed above as D seeks per token and is
  only a fallback if records are rejected as inner keys.
- **Everything else was forced:** identity records as keys, `|hash` on them, whole-record
  replace after the fence read, microbatch, one topology, no query topology, posting diff.

## Self-validation (Phase 1 Step 6, checklist applied; no PLAN_VALIDATION.md produced)

- Query topologies: none → no wasted-read check applies.
- PState grouping: `$$docs` (DocKey), `$$users` (UserKey), `$$postings` (TokenKey) — three
  distinct key structures, one PState each; both facets of a document share one PState. No
  `Object`. Uniform records → fixed-keys; no polymorphic position. Non-subindexed collections:
  `:chunks`/`:tokens` guard-enforced ≤ 32; `:groups` justified above.
- Partitioning: `|hash` on identity records — large keyspaces; per-key skew on hot tokens
  costed against stored-placement spreading with numbers. Table filled for N = 1/16/128,
  proportions sum to 1, seeks are cluster totals, flat. Justifications cite only README
  requirements; no assumption about subsystem 2's mechanisms.
- Topologies: microbatch only; no README write demands millisecond visibility; no
  test-synchronization reasoning influenced any choice.
- Production readiness: concurrent clients on one key serialize on its task in append order
  (max revision wins, first-applied wins on ties, never a blend); client restart loses only
  the transient counter; worker restart resumes from the committed microbatch, exactly-once,
  posting fan-out atomic with the record write; unbounded documents/users/tokens spread by
  hash, unbounded per-token postings subindexed; no stream topology, so no partial-failure
  duplication; the only multi-partition write is inside a microbatch attempt.
- Internal depots / cross-topology flows / stream commit boundaries: none.
- Minimality: simplest sketch = one depot, one microbatch topology, a document record, a user
  record, a token index — which is this plan. `:chunk-count` is the only extra field; deleting
  it forces `get-document` to ship chunk payloads. Posting diff replaces nothing that a cruder
  delete-all/add-all would need.
- Throughput: 1 seek per write, 1 seek per owned read, 1 seek + M iterations per token and
  1 seek per candidate document; the per-document read cannot be removed because ACL and
  membership writes may not touch chunk-proportional state (README ACL/membership bullet).
- Spec coverage (owned items), traced against the IMPLICIT_SPEC state × write matrix: D0–D5 ×
  put/delete/acl at r > current, r = current, r < current, including delete-then-put at equal
  revision (stays tombstoned), tombstone of an unknown document (record with nil ACL fields),
  ACL on tombstone (stays tombstoned; later put at higher revision revives under that ACL at
  its revision — worked example steps 6–9), `groups #{}` set and returned as `#{}`,
  chunk-id reuse with new text (old refs removed, new stored), shorter new vector (surplus
  refs removed), U0–U2 × membership writes, same ids in another tenant untouched (keys carry
  tenant), duplicate delivery → equal-revision no-op, two clients → shared counter, identical
  at 2 and 4 tasks.
