# Implementation validation

Verdict: pass for synchronized-phase contract. `*writes` hashes on tenant/entity
identity; `entities` microbatch fences each independent revision stream on its
own key task. `$$docs` contains header/ACL only, `$$chunks` holds document-local
content, and token-partitioned subindexed `$$postings` holds current matching
chunk payloads. Accepted content writes replace chunks and update all current
postings while deleting removed refs. ACL and membership writes do not fan out.
All authoritative state is durable Rama state; the factory-scoped atom counts
append acknowledgments solely for IPC synchronization across wrappers.

Deviation from validated revisioned-entities plan: split content from header
and store payload in posting value. The original inline map would make ACL
rewrites load/write chunk payloads and candidate-header reads materialize
nonmatching chunks; presence-only postings would then require a chunk read per
match. The public efficiency contract takes precedence over that plan.

Known constraints: the client query uses one network roundtrip per query token
and candidate document rather than server-side fan-in. Cross-partition reads
are not an atomic snapshot; the public contract does not require one.
