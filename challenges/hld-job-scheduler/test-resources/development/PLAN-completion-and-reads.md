# Completion and reads plan

Completion reads the one bounded execution state (≤32 nodes), checks existence, success, worker, token and `clock < expiry`, then updates the node once. A result, including nil, is stored under `:result`; success is tracked separately so nil is unambiguous. Retain attempts, clear lease on success. No separate effect-id storage: derive `[execution-id node-id]` at read time; it never changes with the attempt token.

Reads fetch only the bounded `:state` for one execution; derive node status from completion, dependencies and clock, in that order. `get-execution` produces every node status and success iff all nodes succeeded. `get-claimable-nodes` sorts ready IDs. `get-claim` point-selects only the one subindexed claim-id. Hash partitioning: N=1,16,128 all take one task, one bounded state read or claim point lookup; no scan over executions or claim history. No query topology is needed because each read uses one PState record. Input/output work for a DAG is O(32²) worst case and independent of external population.

Self-check: expiry exactly C=10 rejects completion under expiry 10, stale token rejects even after reclaim, duplicate completion cannot replace a result, and `:result nil` after success is not confused with pending. Multi-client wrappers share the IPC append counter; no client holds business state.
