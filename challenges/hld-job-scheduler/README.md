# HLD Job Scheduler Challenge

Build a task scheduler for immutable, bounded DAGs: workers claim named
nodes under 10-unit leases with increasing fencing tokens, expired leases
are reclaimed, claim IDs are idempotent, and completion is accepted only
from the worker holding the current valid lease. Time is an explicit
per-execution logical clock.

## Attribution

This challenge is adapted from the case study
["Design a Distributed Job Scheduler"](https://hld.handbook.academy/curriculum/case-studies/job-scheduler/)
by The HLD Handbook contributors
([handbook-academy/engineering-handbook](https://github.com/handbook-academy/engineering-handbook)),
licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
The prose in this README is adapted from that case study and is likewise
offered under CC BY-SA 4.0.

### What was changed

- DAGs are capped at 32 nodes (the case study allows 10k) and are immutable
  once submitted. Validation (all references resolve, acyclic) happens up
  front, before anything is stored.
- Wall-clock time, heartbeats, and the 10-second lease TTL become an
  explicit per-execution logical clock with 10-unit leases. Missing a
  heartbeat is modelled purely as lease expiry.
- Workers claim a **named node**; there is no global dispatch queue.
- The case study's idempotency key `{workflow_id, task_id, attempt}` is
  split into a stable logical effect ID `[execution-id node-id]` and a
  per-node fencing token that identifies the attempt.
- There is no explicit failure or retry policy. The only retry path is lease
  expiry followed by a fresh claim.

### What was excluded

Cron triggers and missed-run policies, job definition CRUD/pause, conditional
branching, signals, cancellation and compensation, task logs and lineage,
callbacks, sharded scheduler tiers, and all capacity numbers. Exactly-once
execution of the **external** effect is explicitly out of scope: this module
provides the coordination state (effect ID, fencing token, single accepted
result) that an idempotent worker would rely on, and nothing more.

## Domain model

**Execution.** `submit-execution!` takes an `execution-id` and a `dag`: a
map from node ID to the set of node IDs it depends on. The whole DAG is
validated before storing. It is rejected with no effect if it has 0 nodes,
more than 32 nodes, a dependency on a node ID that is not a key, or a cycle
(a node depending on itself is a cycle). A rejected ID is indistinguishable
from one never submitted: `get-execution` returns `nil`. Resubmitting an
existing ID is a no-op.

**Clock.** Each execution has an integer clock, initially `0`.
`advance-clock!` is monotonic: a value `<=` the current clock is a no-op.

**Node status.** Derived from state at the current clock:

| Status | Meaning |
|---|---|
| `:pending` | some dependency is not `:success` |
| `:ready` | all dependencies are `:success`, not `:success` itself, no valid lease |
| `:running` | a lease is held and `clock < expiry` |
| `:success` | completed; result immutable |

A lease is valid while `clock < expiry`. At `clock = expiry` it is no longer
valid.

**Claims.** `claim!` names a node. `claim-id` is unique within the execution
and is idempotent: a repeated `claim-id` has no effect and `get-claim`
returns the original decision, even if the arguments differ or the node has
since changed state. A new claim is decided in this order at the current
clock `C`:

| Order | Condition | Decision |
|---|---|---|
| 1 | execution unknown | denied `:unknown-execution` |
| 2 | node not in DAG | denied `:unknown-node` |
| 3 | node is `:success` | denied `:already-succeeded` |
| 4 | some dependency not `:success` | denied `:dependencies-incomplete` |
| 5 | lease held with `C < expiry` | denied `:lease-held` |
| 6 | otherwise | granted |

A grant issues token `previous node token + 1` (first grant is token `1`)
and lease `{:worker-id w :token t :expiry (+ C 10)}`. Tokens strictly
increase per node across all grants, including reclaims after expiry.

**Completion.** `complete!` is effective iff the node is not `:success`, its
current lease has exactly the given `worker-id` and `token`, and
`clock < expiry`. Then the node becomes `:success` with the given result,
stored immutably, and the lease is cleared. Every other completion (wrong
worker, stale token, expired lease, already succeeded, unknown node) is
ignored. A worker whose lease has expired cannot complete even if nobody
else has claimed the node yet.

**Effect identity.** `get-node` exposes `:effect-id [execution-id node-id]`,
the stable identity of the node's logical work, separate from `:attempts`
and the fencing token. External exactly-once execution is out of scope; the
module only guarantees that at most one result is ever recorded per effect
ID.

## Worked example

```clojure
(submit-execution! c "run-1"
  {"fetch"   #{}
   "parse"   #{"fetch"}
   "enrich"  #{"fetch"}
   "publish" #{"parse" "enrich"}})
```

1. `get-execution "run-1"` → `:clock 0`, `:status :running`,
   `:node-statuses {"fetch" :ready "parse" :pending "enrich" :pending "publish" :pending}`.
   `get-claimable-nodes "run-1"` → `["fetch"]`.
2. `claim! "run-1" "fetch" "w1" "c1"` → granted.
   `get-claim "run-1" "c1"` → `{:claim-id "c1" :node-id "fetch" :worker-id "w1" :clock 0 :granted? true :token 1 :lease-expiry 10}`.
   `get-node "run-1" "fetch"` → `:status :running`, `:attempts 1`,
   `:lease {:worker-id "w1" :token 1 :expiry 10}`, `:effect-id ["run-1" "fetch"]`.
3. `claim! "run-1" "parse" "w2" "c2"` → denied `:dependencies-incomplete`.
4. `claim! "run-1" "fetch" "w2" "c3"` → denied `:lease-held`.
5. `advance-clock! "run-1" 10` → lease for `fetch` is no longer valid
   (`10 < 10` is false). `fetch` is `:ready`; `get-claimable-nodes` → `["fetch"]`.
6. `complete! "run-1" "fetch" "w1" 1 "ok"` → ignored (lease expired).
7. `claim! "run-1" "fetch" "w2" "c4"` → granted, token `2`, expiry `20`. `:attempts 2`.
8. `complete! "run-1" "fetch" "w1" 1 "stale"` → ignored (token mismatch).
9. `complete! "run-1" "fetch" "w2" 2 "v1"` → effective. `fetch` is `:success`,
   `:result "v1"`, `:lease nil`. `get-claimable-nodes` → `["enrich" "parse"]`.
10. `complete! "run-1" "fetch" "w2" 2 "v2"` → ignored; `:result` stays `"v1"`.
11. `claim! "run-1" "fetch" "w3" "c5"` → denied `:already-succeeded`.
12. `claim! "run-1" "fetch" "w1" "c1"` (replay) → no effect.
    `get-claim "run-1" "c1"` still reports `:granted? true :token 1 :lease-expiry 10`.
13. `advance-clock! "run-1" 5` → no-op; clock stays `10`.
14. After `parse` and `enrich` both succeed, `publish` becomes `:ready`; once it
    succeeds, `get-execution` reports `:status :success`.

Rejected DAGs, each leaving `get-execution` → `nil`:

- `{"a" #{"b"} "b" #{"a"}}` (cycle)
- `{"a" #{"a"}}` (self-cycle)
- `{"a" #{"missing"}}` (unresolved reference)
- `{}` (empty)
- any map with 33 or more nodes

`claim! "nope" "a" "w1" "c1"` against an unknown execution records a denial:
`get-claim "nope" "c1"` → `{... :granted? false :reason :unknown-execution :clock 0}`.
Submitting `"nope"` later does not erase this decision. Replaying `"c1"`
still returns the original denial; a fresh claim ID is needed to claim work.

## Efficiency contract

Bounds concern application-level records examined or updated, allowing
input/output-size costs and ordinary lookup and ranking overhead.

- Operations may do work proportional to the size of the one DAG they touch
  (at most 32 nodes). That is the only allowed proportionality.
- No operation may do work proportional to the number of executions, claims
  in other executions, or workers.
- Reads of one execution must not read state belonging to any other
  execution.

## Input assumptions

- `execution-id`, `node-id`, `worker-id`, and `claim-id` are non-empty
  strings.
- `clock` and `token` are nonnegative signed 64-bit integers. Clocks are
  at most `Long/MAX_VALUE - 10`, leaving room for lease expiry. Inputs
  guarantee the number of granted attempts per node fits signed 64-bit.
- Tests supply valid input except for the explicitly specified cases:
  invalid DAGs (cycles, unresolved references, size), stale clock advances,
  denied claims, replayed claim IDs, and fenced or expired completions.

## Write ordering and synchronization

All `!` methods are asynchronous writes. Tests call
`(harness/wait-for-processing! client)` after a group of writes and before
any read. Writes addressed to the same execution must take effect in the
order the client invoked them (a `claim!` invoked after an `advance-clock!`
on the same execution is decided against the advanced clock). No ordering is
required between different executions.

All authoritative business state must be durable Rama state (depots and
PStates); transient synchronization counters are allowed. The module runs
with both 2 and 4 tasks in private validation. Multiple clients wrapping
the same deployed module must observe the same business state after the
writing client synchronizes. Tests alternate synchronized client phases;
no cross-client concurrent ordering or snapshot isolation is required.

## Protocol

Your implementation must satisfy the `JobScheduler` protocol defined in
`src/hld_job_scheduler/protocol.clj`. The docstrings there are part of the
contract.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <JobScheduler implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified `JobScheduler` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `rama-challenges.harness/Synchronizable`. See
the docstring on that protocol for implementation requirements. Tests call
`(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `hld-job-scheduler.module`.

## File Location

Write your solution to:
```
implementations/hld-job-scheduler/src/hld_job_scheduler/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
