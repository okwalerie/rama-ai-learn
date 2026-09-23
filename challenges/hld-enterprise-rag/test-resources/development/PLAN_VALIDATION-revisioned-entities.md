# Plan Validation — subsystem `revisioned-entities`

<!-- Phase 2 artifact for hld-enterprise-rag, subsystem 1 of 2. Inputs: README.md,
src/hld_enterprise_rag/protocol.clj, harness Synchronizable docstring, IMPLICIT_SPEC.md,
PLAN-revisioned-entities.md, skill SKILL.md + phase-2-plan-validate.md + template.
Authority order: README/protocol > IMPLICIT_SPEC (derived). Each item: Decision / Basis /
Outcome. Rama facts cited from skill references where the plan depends on them. -->

Verdict: **minor-fail** → one localized edit applied to `PLAN-revisioned-entities.md`
(Synchronization registry scope, see "Synchronization"). Every other check passes.

## Query topology
- None in this subsystem; both owned reads are one `keypath` on one PState partition.
  Wasted-read check not applicable. PASS.

## PState schemas
- **Grouping by (key type, partitioner):** `$$docs` (`DocKey`, hash), `$$users` (`UserKey`,
  hash), `$$postings` (`TokenKey`, hash). Three distinct key structures → three PStates, no
  group of two. Both document facets share the one `$$docs` record. PASS.
- **Object type:** none. PASS.
- **Uniform records → fixed-keys:** `$$docs` value, chunk value, `$$users` value are all
  fixed-keys. `:content-revision`/`:acl-revision`/`:groups` are nullable fields on a uniform
  shape (permitted by the template; nil is allowed at any location, pstate-schema.md line 21).
  No polymorphic position. PASS.
- **Inner collections > 100 elements subindexed:**
  - `$$postings` inner map: unbounded per token (stop-word case) → `map-schema ChunkRef Boolean`
    subindexed with size tracking off. PASS.
  - `:chunks` ≤ 32, `:tokens` ≤ 32: README "Chunks are capped at 32 per document" / "at most 32
    tokens", enforced by the plan's `well-formed?` guard which drops violators. PASS.
  - `:groups` (ACL and membership): no README bound; not subindexed. Basis for accepting:
    every operation the contract defines reads or replaces the set whole (`get-document`
    returns it, ACL/membership writes replace it, the delivered per-document read needs it
    whole for intersection). Constructed alternative: subindexed set at G = 5,000 groups →
    reading it is 1 seek + 5,000 iterations ≈ 25 ms and replacing it is a structure delete +
    5,000 element writes; inline is 1 seek loading ≈ 50 KB ≈ 0.6 ms and one blob write. Inline
    is cheaper at every G and never loads more than the operation must return. PASS.

## Partitioning
- `*writes` `hash-by :key`, `$$docs`/`$$users` by identity record: keyspace = all documents /
  users across tenants (large); one small record per key, no hot key. PASS.
- `$$postings` `|hash TokenKey`: large keyspace (tenants × vocabulary) but a stop-word key is
  hot in storage and reads. Plan constructed stored-placement spreading (P3) and costed it:
  +1 seek per token read for the span lookup, +span seeks, same iterations; +≤ 1,024 span
  reads per accepted put. Aggregate work never lower; the README states no per-task latency
  or balance target. Rejection is by computed total cost, not "complexity". PASS.
- No `|all`. PASS.
- **Partitioning efficiency table:** filled for N = 1/16/128; rows absent/typical/common
  token, proportions 0.2 + 0.6 + 0.2 = 1; weighted seeks recomputed: 0.2×1 + 0.6×11 +
  0.2×5001 = 1,007.0 at every N; iterator reads 2,012 at every N. Seeks are cluster totals
  (a token read is 1 seek on one task + D document seeks; no fan-out row). Flat with N. PASS.
- Justifications cite README "Efficiency contract" (query bullet) and "Domain model" only; the
  table describes a property of the delivered state and assumes no subsystem-2 mechanism. PASS.

## Topologies
- One microbatch topology `entities`. README: "All `!` methods are asynchronous writes"; no
  write returns a value; visibility is deferred to `wait-for-processing!`. No single-digit-ms
  visibility requirement exists in the README. Stream not needed. PASS.
- No stream topology → no per-concern stream check, no non-idempotent-write list, no
  commit-boundary check. PASS.
- Topology choice reasoning cites exactly-once and cross-partition atomicity (microbatch.md
  lines 3, 118, 126), not test synchronization. PASS.

## Production readiness
- **Concurrent clients:** all records for one `DocKey`/`UserKey` land on one depot partition
  (`hash-by :key`) and are applied in append order on a single-threaded task; strictly-greater
  fence means the max revision wins and on equal revisions the first-appended payload is kept
  whole. README requires no cross-client ordering. PASS.
- **Client restart:** the only client state is the transient append counter, permitted by
  README ("transient synchronization counters are allowed"). Business state is all PStates.
  PASS.
- **Worker restart during processing:** microbatch attempt is reset and retried; PState writes
  are exactly-once; `$$docs` record write and `$$postings` fan-out are one attempt, so no
  settled state has a new revision with old postings (microbatch.md line 118). No
  TaskGlobals. PASS.
- **Scale:** documents/users/tokens spread by hash; unbounded per-token postings subindexed;
  per-document payload bounded at 32 × 32. PASS.

## Internal depots / cross-topology / stream commit boundaries
- None. PASS.

## In-memory state efficiency
- No TaskGlobals or caches. PASS.

## Minimality — adversarial simplification
Simplest sketch: one client depot, one microbatch topology, one document record PState, one
user record PState, one token → chunk index. The plan is that sketch plus `:chunk-count` and
the posting diff.
- **`*writes` (single depot):** delete → no ordered log; README requires same-document and
  same-user order. Merge: already one depot. PASS.
- **`entities` topology:** delete → no fence, no state. PASS.
- **`$$docs`:** delete → `get-document` impossible. Merge with `$$users`: different key
  structure. PASS.
- **`$$users`:** delete → `get-user-groups` and membership eligibility impossible. PASS.
- **`$$postings`:** delete → a token read must scan documents, violating README "must not scan
  documents or chunks that contain none of the query tokens". PASS.
- **`:chunk-count` field:** delete → `get-document` must ship the chunk map (up to ~60 KB) to
  count it; the field costs 8 bytes written once per content write. PASS.
- **Posting diff (vs delete-all/add-all):** constructed alternative writes `old + new` ≤ 2,048
  elements per put; diff writes `|Δ|`, identical outcome. Not extra mechanism: same code path
  with a set difference. PASS.
- **`well-formed?` guard:** delete → a malformed record throws and retries forever, blocking
  every write in the module (skill: design for production). Kept; it is one predicate. PASS.
- **`ChunkRef`/`DocKey`/`UserKey`/`TokenKey` records:** delete → tuple or string keys; records
  are the same data with a schema-visible class. Neutral, kept. PASS.

## Throughput — adversarial
- Writes: 1 seek (fence) + 1 no-read record write + ≤ 2,048 no-read element writes. The fence
  read is unavoidable (strictly-greater rule), and the loaded record is reused for the
  untouched-facet copy and old refs, so no second read. No cheaper design constructed. PASS.
- Owned reads: 1 seek, 0 iterations, 1 roundtrip each. Minimum. PASS.
- Delivered per-token read: 1 seek + M iterations; per-document read 1 seek returning
  revision, texts, ACL together. Alternatives B (subindexed chunks, ≥ 2 seeks per document)
  and D (text in postings, ~1–2 MB per put, still needs the document read for eligibility)
  costed in the plan and lose on totals. PASS.

## Spec coverage — operations

### `put-document!`
- **Source:** "Iff `content-revision` is strictly greater than the document's current content
  revision (or the document has no content revision yet), replace the document's chunks ...
  Old chunks, including any chunk-ids absent from the new vector, disappear. Otherwise no-op.
  Never touches the document's ACL."
- **Trace:** `d1` in state D3 (`content-revision 1`, chunks c1{rama depot pstate},
  c2{rama query}, ACL 1 #{eng}). `put-document! "acme" "d1" 3 [c9{rama}]`: record lands on
  `hash(DocKey acme d1)`; fence read gives 1 < 3 → accept; new record
  `{cr 3, chunk-count 1, chunks {c9}, acl-revision 1, groups #{eng}}` written by `termval`;
  diff: old refs {rama/c1 depot/c1 pstate/c1 rama/c2 query/c2}, new {rama/c9} → 5 deletes,
  1 add, each `|hash TokenKey` then no-read write. `get-document` → cr 3, live? true,
  chunk-count 1, acl 1 #{eng}. Worked example step 9 matches. Equal (2 on a tombstone at 2)
  and lower (1) → fence rejects, nothing written (steps 8). D0 → accepts, ACL fields nil.
  D5 → accepts, copies ACL a/g (revives under retained ACL).
- **Fault tolerance:** worker restart → attempt retried, exactly-once; the record write and
  all posting writes settle together or not at all. Client re-append → equal revision → no-op.
- **Race:** two clients same doc → serialized on the key's partition. Out-of-order across
  partitioner hops: document-task work runs before the first hop; posting messages from one
  document task to one token task are processed in send order (formal-model.md line 592:
  partition ordering holds for all topology types), so rev r's add precedes rev r+1's delete
  of the same ref.
- **Flaws:** none found. **PASS.**

### `delete-document!`
- **Source:** "tombstone the document: it has no live chunks and its content revision becomes
  `content-revision`. Otherwise no-op. Never touches the document's ACL."
- **Trace:** D0 `d` delete 5 → fence nil → record `{cr 5, chunk-count 0, chunks {}}`, no ACL
  fields, zero posting deletes; `get-document` →
  `{:doc-id "d" :content-revision 5 :live? false :chunk-count 0 :acl-revision nil :groups nil}`
  (README example verbatim). Then put 4 or 5 → rejected. D3 `d1` delete 2 (step 6) → record
  cr 2, chunk-count 0, ACL 1 #{eng} copied; 5 posting deletes. D1 ACL-only delete → tombstone
  with ACL preserved.
- **Fault tolerance / race:** identical mechanism to put. **Flaws:** none found. **PASS.**

### `put-document-acl!`
- **Source:** "Iff `acl-revision` is strictly greater than the document's current ACL revision
  (or none is set), replace the document's ACL ... Never touches content or tombstone state;
  setting an ACL on a tombstoned document does not make it live."
- **Trace:** D5 `d1` (cr 2, tombstoned, ACL 1 #{eng}); `put-document-acl! 2 #{eng sales}` →
  fence 1 < 2 → record `{cr 2, chunk-count 0, chunks {}, acl-revision 2, groups #{eng sales}}`;
  live? stays false (chunk-count 0); no posting work (step 7). D0 → ACL-only record
  `{cr nil, chunk-count 0, acl 1, groups g}` → `get-document` nil/false/0/1/g. `groups #{}` →
  stored `#{}`, returned `#{}`. Equal revision → no-op, first set kept.
- **Efficiency:** README "must do work independent of the number of documents and chunks in
  the tenant": 1 seek + 1 write of this document's record only. PASS.
- **Flaws:** none found. **PASS.**

### `put-user-groups!`
- **Source:** "Iff `membership-revision` is strictly greater than the user's current membership
  revision (or none is set), replace the user's group set ... Never requires reindexing any
  document."
- **Trace:** bob (m 1 #{sales}); `put-user-groups! 1 #{eng}` → fence 1 ≯ 1 → no-op;
  `put-user-groups! 2 #{}` → record `{m 2, groups #{}}` → `get-user-groups` →
  `{:membership-revision 2 :groups #{}}` (step 10). No document PState touched. PASS on
  "Changing a user's groups must not perform document- or chunk-proportional work".
- **Flaws:** none found. **PASS.**

### `get-document`
- **Source:** "Returns nil if neither content nor ACL has ever been written ... otherwise
  {:doc-id :content-revision :live? :chunk-count :acl-revision :groups}".
- **Trace:** unknown key → `keypath` navigates to nil, `submap` yields `{}` → both revisions
  nil → client returns nil. D1 → `{cr nil, live? false, chunk-count 0, acl a, groups g}`.
  D3 with 32 chunks → chunk-count 32, payload excluded by `submap`. Unknown tenant → nil.
  `:groups` nil vs `#{}` preserved because the stored field is the accepted set verbatim.
  One seek, one roundtrip (README "read one entity"). **PASS.**

### `get-user-groups`
- **Source:** "Returns nil if the user has never had a membership write, otherwise
  {:membership-revision <int> :groups <set>}."
- **Trace:** unknown → nil; known `#{}` → `{... :groups #{}}`. One seek. **PASS.**

## Spec coverage — constraints

### Same-document / same-user invocation order
- **Source:** "Writes addressed to the same document (content, tombstone, ACL) must take effect
  in the order the client invoked them, and writes for the same user likewise."
- **Trace:** one client appends put d1 r1, acl d1 r1, delete d1 r2 in that order. All three
  hash to the same depot partition; `%mb` emits that partition in append order; each record's
  fence read + record write completes on the single-threaded task before the next record is
  emitted. Same-user records never hop. Across microbatches order is trivially preserved.
- **Flaws:** none found. **PASS.**

### Streams never interact
- **Source:** "Content writes never modify the ACL ... ACL writes never resurrect content ... A
  document recreated by a newer `put-document!` after a tombstone keeps the ACL it already had,
  at its existing ACL revision."
- **Trace:** whole-record `termval` copies the untouched facet from the loaded record: put on
  D5 copies acl 2 #{eng sales} (step 9 result `:acl-revision 2`); ACL on D5 keeps
  chunk-count 0 → `live? false`. By construction no write path assigns the other facet's
  fields from its own input. **PASS.**

### Revision advance and payload replacement atomic
- **Source (IMPLICIT_SPEC, follows from protocol retry semantics):** "the revision fence and
  the payload replacement of one write must never be observable as separated".
- **Trace:** single `termval` of the full record on one task = one atomic event; posting
  fan-out is in the same microbatch attempt, all-or-nothing on settle (microbatch.md line
  118). After `wait-for-processing!` (which waits for the microbatch to be processed), readers
  see revision r with exactly revision r's chunks and postings. **PASS.**

### Tenant isolation
- **Source:** "it must never read another tenant's data"; "the same string in two tenants
  refers to two different things."
- **Trace:** every key carries the tenant (`DocKey`, `UserKey`, `TokenKey`); `"globex"/"d1"`
  and `"acme"/"d1"` are distinct keys, possibly different tasks; a token read on
  `TokenKey ["globex" "rama"]` iterates only that key's map (step 12 → `[]`). **PASS.**

### Efficiency: query bullet on the delivered state
- **Source:** "`query` may examine query-token/chunk matches within the tenant and the matching
  candidate chunks. It must not scan documents or chunks that contain none of the query
  tokens."
- **Trace:** postings hold refs only for live chunks: tombstone and replacement delete stale
  refs in the same attempt that changes the record, so a token's map contains exactly the
  live chunks containing it. A tenant with 1,000,000 tombstoned or ACL-only documents adds
  zero entries to any token map. **PASS.**

### Efficiency: put/delete bullet
- **Source:** "may do work proportional to the chunks of that one document (old and new). They
  must not touch other documents."
- **Trace:** 1 record read/write + `|old-refs Δ new-refs|` ≤ 2,048 element writes derived from
  the record's stored `:tokens` sets; no other `DocKey` read. **PASS.**

### Durable state; 2 and 4 tasks
- **Source:** "All authoritative business state must be durable Rama state (depots and
  PStates); transient synchronization counters are allowed. The module runs with both 2 and 4
  tasks."
- **Trace:** state = `*writes`, `$$docs`, `$$users`, `$$postings`; client holds only the append
  counter. Nothing in routing or reads depends on N: depot partition, PState partition and
  client `foreign-select-one` all hash the same key value; at N = 2 the two-task fan-out and
  at N = 4 the four-task fan-out settle identically. **PASS.**

### Synchronization: second client observes writer's state
- **Source:** "Multiple clients wrapping the same deployed module must observe the same
  business state after the writing client synchronizes." Harness: "track the cumulative depot
  append count internally ... call `wait-for-microbatch-processed-count` ... for each
  microbatch topology."
- **Trace:** client A appends 3 records (shared counter 3), client B appends 2 (counter 5).
  A calls `wait-for-processing!` → waits for processed count ≥ 5 → A's 3 records are
  processed; B reads `$$docs` directly and sees them. Per-client counting (A waiting on 3)
  could be satisfied by B's 2 + 1 of A's, leaving A's writes unprocessed — the plan correctly
  rejects that. A client that never wrote waits on the current total → returns promptly.
  Rejected/malformed records are consumed records, so the count matches.
- **Flaw (minor):** the plan specified a process-wide `defonce` atom keyed by IPC instance and
  attributed it to the metrics-pipeline reference; that reference scopes the atom to the
  `create-module` result so closed IPC instances are released with it and wrappers of
  different IPCs built from one result keep independent counts. A `defonce` retains every
  IPC ever used for the JVM lifetime. Correctness for the contract is unaffected; the edit is
  one paragraph. **FAIL (minor) → fixed in plan:** registry is a `create-module`-scoped
  `(atom {})` closed over by `:wrap-client`, keyed by IPC instance.

### Retry / duplicate delivery
- **Source (protocol):** strictly-greater rule. **Trace:** duplicate `PutDocument r` after
  acceptance → fence `r ≯ r` → no-op; duplicate of a rejected record → rejected again.
  Microbatch retry → PStates reset then reprocessed, exactly-once. **PASS.**

## Build-phase verification items carried from the plan (not failures)
- `submap` on the nested fixed-keys value returns scalar fields without shipping `:chunks`.
- `ChunkRef` defrecord accepted as an inner subindexed-map key; fallback documented in plan.
- Fixed-keys fields left absent vs written as nil both read back as nil (pstate-schema.md
  lines 15, 21); `get-document` mapping treats both identically.

## Self-consistency
Re-read: the only item marked as a gap is the Synchronization registry scope, and it is the
single FAIL above, fixed in the plan. No other entry says "gap", "tradeoff", or "not ideal".

PHASE_VALIDATION:minor-fail
