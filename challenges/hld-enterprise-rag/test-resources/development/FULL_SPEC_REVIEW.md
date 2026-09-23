# Full-spec review

Verdict: pass on the contract's synchronized read/write phases; limitations
listed below. Rechecked README, protocol, implicit spec, decomposition,
reference, and private harness after the Oracle review and mutant controls.

## Findings repaired

| Contract clause | Finding | Repair / evidence |
|---|---|---|
| ACL/membership work independent of tenant document/chunk count | Inline document chunks made ACL rewrite load chunk payload | Separate durable header and content PStates; 70+70 unrelated-corpus event-count test and structural inspection |
| Query must not scan chunks without query tokens | Candidate document header lookup loaded full inline chunks | Posting payload supplies matching chunk; query reads header only |
| Authorization before truncation | Highest-ranked forbidden chunk can displace permitted one | Asymmetric `k=1` assertion; postfilter-before-authorization mutant fails twice |
| Content tombstone removes searchability | ACL revocation masked stale postings in initial tests | Delete while still authorized, widen ACL while tombstoned, recreate; omission-of-index-deletes mutant fails six assertions |
| Tenant-scoped IDs and independent revisions | Unknown user masked cross-tenant collision in initial tests | Both tenants now have memberships, identical IDs with conflicting revisions, and distinct result text |

## Limits

Event hooks count RocksDB operations, not opaque-value byte materialization.
Module update verifies persisted state, not every worker-crash interleaving.
No latency bound or concurrent snapshot semantics is claimed. The query's
per-candidate network roundtrips are a throughput cost within the public
application-record efficiency bound.
