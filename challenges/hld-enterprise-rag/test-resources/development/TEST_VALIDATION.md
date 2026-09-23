# Private test validation

Verdict: pass for exercised paths. Every protocol method is asserted against
independently specified full result maps at 2 and 4 tasks, with alternating
writers/readers, shared processed-count barriers, revisions on three independent
streams, unknown/equal/lower/tombstone/recreation, current ACL/membership,
tenant collision, score/ties/top-k and module update. Tests resolve
`hld-enterprise-rag.module/create-module` dynamically, so the ordinary private
alias cannot access the reference and the harness alias can.

Architecture-neutral RocksDB event counts compare a one-match query before
and after 70 unrelated in-tenant documents plus 70 foreign-tenant documents,
and bound an ACL update's durable write count. These counters do not measure
bytes read from opaque values; the reference's split metadata layout was
inspected separately. The tests do not simulate crash at every microbatch
commit boundary or prove a production failure sequence.
