# Authorized query — Phase 1 plan and Phase 2 validation

Authority: README and EnterpriseRag protocol. Revisioned-entities state is durable
`$$docs` (small revision/count/ACL header), `$$chunks` (document-local current
content), `$$users`, and `$$postings` (tenant/token → chunk reference and payload).
The entity plan's inline `:chunks` and presence-only posting sketches were
superseded during integration: a projected read on a non-subindexed map still
loads the full underlying value. Keeping chunks in `$$docs` made ACL updates
chunk-sized and query examine nonmatching chunks in candidate documents.

## Access path

1. `k = 0` or no query tokens → `[]`. Read the tenant/user membership once;
   unknown or empty membership → `[]` without index work.
2. Distinct query tokens each route to exactly one tenant/token posting map.
   Iterate matching entries only. Deduplicate chunk references. Each posting
   carries text and the complete current chunk token set; content updates
   refresh *every* new posting, including retained token/ref pairs.
3. For each distinct candidate document, read only its metadata/ACL header.
   No read of `$$chunks` is needed. Check current ACL revision, group
   intersection and chunk score before sorting/truncation. A tombstone removes
   postings in the same microbatch; stale postings cannot survive a settled
   accepted deletion. Return current content/ACL revisions with the posting's
   text, sort by `[-score doc-id chunk-id]`, then `take k`.

One query performs one user lookup, |Q| posting seeks, an iteration for each
query-token/chunk match, and one header lookup per distinct candidate document.
Its CPU/memory and transferred payload scale with matching postings, not other
documents, other tenants, or unmatched chunks. Query is implemented in the
foreign wrapper using durable Rama index/headers rather than a query topology;
this costs more client roundtrips than a server-side query but does not increase
application-record examination. No business state is retained in the client.
Reads across partitions are not a snapshot; the README requires synchronized
phases and explicitly does not require snapshot isolation.

## Validation (Phase 2)

- Candidate with score 2 but unset/revoked ACL precedes score-1 authorized
  candidate: authorized result still fills `k=1` (harness, both task counts).
- Reused chunk ID with new text/tokens: refresh retained posting and remove
  dropped token refs; query returns current text/revisions only.
- Tombstone then ACL widen: no posting survives; a later greater-revision put
  revives under the retained ACL.
- Same tenant-scoped IDs and token in another tenant: posting key and document
  header both carry tenant; no cross-tenant scan.
- Empty membership performs no posting read; duplicate query/chunk tokens
  cannot inflate scores.

Verdict: pass. The reference and harness exercise these scenarios; RocksDB
event-count growth test supplements structural inspection but cannot measure
opaque bytes read from values or prove worker crash atomicity.
