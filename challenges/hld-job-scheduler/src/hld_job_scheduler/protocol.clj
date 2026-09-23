(ns hld-job-scheduler.protocol
  "Protocol definition for the hld-job-scheduler challenge.

   An execution is an immutable DAG of at most 32 named nodes plus a
   per-execution, explicit, monotonic logical clock (initially 0). Workers
   claim named nodes; a granted claim is a lease of 10 clock units carrying
   a per-node fencing token that increases with every grant. There is no
   explicit failure: a node that is not completed before its lease expires
   simply becomes claimable again.

   Node statuses:
     :pending  some dependency is not :success
     :ready    all dependencies are :success, no valid lease, not :success
     :running  a lease is held and clock < lease expiry
     :success  completed; result is immutable
   A lease is valid while clock < expiry.

   See README.md for the full rules with worked numbers.")

(defprotocol JobScheduler
  "Bounded-DAG task scheduler with leases, fencing tokens, and idempotent
   claims."

  (submit-execution! [this execution-id dag]
    "Write. Create an execution. `dag` is a map from node-id (non-empty
     string) to the set of node-ids it depends on (a set, possibly empty).
     The DAG is validated in full before anything is stored. It is rejected,
     with no effect at all, if any of these hold:
       - it has 0 nodes or more than 32 nodes
       - any dependency names a node-id that is not a key of the map
       - it contains a cycle (including a node depending on itself)
     A rejected execution-id is as if never submitted: get-execution returns
     nil for it. If `execution-id` already exists, the call is a no-op; the
     original DAG is immutable. Submission never removes claim decisions
     recorded before the execution existed; replaying such a claim-id
     preserves its original :unknown-execution denial.")

  (advance-clock! [this execution-id clock]
    "Write. Advance the execution's clock to `clock` (integer >= 0).
     Monotonic: a value <= the current clock is a no-op. Unknown
     execution-id: no-op. Leases whose expiry <= clock are no longer valid;
     their nodes (if not :success) become :ready.")

  (claim! [this execution-id node-id worker-id claim-id]
    "Write. Ask, on behalf of `worker-id`, to run `node-id`. `claim-id` is
     unique within the execution and identifies this request. If
     `claim-id` has already been decided for this execution, the call has
     no effect and the original decision stands (idempotent replay), even
     if the other arguments differ or the node's state has since changed.

     Otherwise a decision is recorded, readable via get-claim. Evaluated in
     order against the clock C at the time the write is applied:
       1. execution unknown                 -> denied :unknown-execution
       2. node-id not in the DAG            -> denied :unknown-node
       3. node is :success                  -> denied :already-succeeded
       4. some dependency is not :success   -> denied :dependencies-incomplete
       5. node has a lease with C < expiry  -> denied :lease-held
       6. otherwise                         -> granted
     A grant sets the node's lease to {:worker-id worker-id
     :token <previous node token + 1, starting at 1> :expiry (+ C 10)} and
     the node becomes :running.")

  (complete! [this execution-id node-id worker-id token result]
    "Write. Report completion of `node-id` under a lease. Effective iff, at
     the clock C when the write is applied: the execution and node exist,
     the node is not already :success, the node's current lease has this
     exact `worker-id` and `token`, and C < lease expiry. When effective the
     node becomes :success with `result` stored immutably and its lease
     cleared. In every other case the call is ignored. `result` is any
     Clojure value; tests use strings and small maps.")

  (get-execution [this execution-id]
    "Read. Returns nil for an unknown or rejected execution-id, otherwise
       {:execution-id <str> :clock <int>
        :status <:running|:success>
        :dag {node-id #{dep-node-id ...} ...}
        :node-statuses {node-id <:pending|:ready|:running|:success> ...}}
     :status is :success iff every node is :success.")

  (get-node [this execution-id node-id]
    "Read. Returns nil if the execution or node is unknown, otherwise
       {:node-id <str>
        :effect-id [execution-id node-id]
        :dependencies #{...}
        :status <:pending|:ready|:running|:success>
        :attempts <int>
        :lease nil | {:worker-id <str> :token <int> :expiry <int>}
        :result <value or nil>}
     :effect-id is the stable identity of the node's logical side effect;
     it never changes across attempts. :attempts is the number of grants so
     far (equal to the highest token issued, 0 if none). :lease is the most
     recently granted lease, even if it has expired, and nil once the node
     is :success or if no grant has ever happened.")

  (get-claim [this execution-id claim-id]
    "Read. Returns nil if no claim with this claim-id has been applied for
     the execution, otherwise the immutable decision
       {:claim-id <str> :node-id <str> :worker-id <str> :clock <int>
        :granted? true  :token <int> :lease-expiry <int>}
     or
       {:claim-id <str> :node-id <str> :worker-id <str> :clock <int>
        :granted? false :reason <keyword>}
     where :clock is the execution clock observed when the claim was
     decided (0 for :unknown-execution).")

  (get-claimable-nodes [this execution-id]
    "Read. Returns a vector of the node-ids whose status is :ready, sorted
     ascending. Empty vector for an unknown execution."))
