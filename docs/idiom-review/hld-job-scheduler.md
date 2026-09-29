# Idiom review: HLD job scheduler

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a` (`orb/idiom-hld-job-scheduler`).
Scope: reference module, challenge contract and tests, the auction-module and
chat-app reference implementations, and `plugins/rama-skill/skills/rama/SKILL.md`.
This is a review, not a rewrite: the reference already follows the storage and
partitioning constraints that matter here, and there is no safe behavior change
to justify under the stated deadline constraint.

## Findings on the scheduler reference

### One execution-keyed depot and one microbatch topology are appropriate

- `challenges/hld-job-scheduler/test-resources/hld_job_scheduler/module.clj:79-88`
  declares one `*execution-events` depot hashed by `:execution-id` and one
  `"lifecycle"` microbatch topology. The events for any execution are thus
  routed by the same key, matching the README's same-execution ordering
  requirement (`challenges/hld-job-scheduler/README.md:171-177`). Different
  executions may be handled independently, as the contract permits.
- The single `<<subsource` dispatch at lines 89-117 is a compact shared
  state-transition path, not a funnel through a global task. No additional
  depot or topology is justified by the current operations: they all need
  execution-local ordered updates to the same durable state.
- The reference does **not** use `:ingress-seq`, `+group-by`, `+vec-agg`, or
  `loop<-` to repartition writes. It dispatches records directly from the
  execution-hashed source and performs local state access. The only sort is
  `sort` in `get-claimable-nodes` (`challenges/hld-job-scheduler/test-resources/hld_job_scheduler/module.clj:146-149`),
  producing the protocol's ascending vector from at most 32 nodes; it is not
  batch sorting/repartitioning. Therefore
  a review framed as removing those constructs does not match this baseline.
  Adding batch grouping/repartitioning would add complexity and potentially
  work without curing an observed bottleneck; no hot-key or bulk-ingest
  requirement in the contract calls for it.
- Alternative: separate streams/topologies by operation only if they have
  materially different latency, throughput, failure, or consistency
  requirements. They do not here, and splitting them risks weakening the
  required ordering among clock advancement, claim, and completion events for
  a single execution.

### State is execution-local; the only unbounded per-execution collection is indexed

- The outer PState key is `execution-id` and contains fixed `:state` and
  `:claims` fields (`challenges/hld-job-scheduler/test-resources/hld_job_scheduler/module.clj:82-85`).
  The `ExecutionState` holds the DAG, clock, and node records
  (`challenges/hld-job-scheduler/test-resources/hld_job_scheduler/module.clj:13`,
  `40-48`). The contract caps one DAG
  at 32 nodes (`challenges/hld-job-scheduler/README.md:3-7`, `135-141`), so
  retaining the whole state value for bounded DAG calculations is a defensible
  trade-off; operations do not scan all executions.
- Historical claim decisions must remain immutable and queryable for
  idempotent replay. `:claims` is a per-execution map with
  `{:subindex? true}` (`challenges/hld-job-scheduler/test-resources/hld_job_scheduler/module.clj:85`);
  each claim checks/inserts one `claim-id` by key
  (`challenges/hld-job-scheduler/test-resources/hld_job_scheduler/module.clj:102-110`).
  This is the right shape for the potentially growing history: it preserves
  the contract without reading all claims. Do not replace it with a growing unindexed vector or delete old
  decisions.
- All updates stay on the depot's `hash-by :execution-id` partition and use
  local PState operations
  (`challenges/hld-job-scheduler/test-resources/hld_job_scheduler/module.clj:80`,
  `92-117`). There is no cross-partition
  `|hash`/`select>` write-path bounce. For a read that crosses multiple
  entities, a query topology could colocate or aggregate the work; this API
  reads one execution and one indexed claim at a time, so that alternative
  would not reduce the work required.
- The functional private suite populates 40 unrelated executions
  (`challenges/hld-job-scheduler/test-private/hld_job_scheduler/functional_test_support.clj:120-128`).
  The independent private suite grows unrelated executions from 256 to 1024 and
  target history from 256 to 1024 denied claims
  (`challenges/hld-job-scheduler/test-private/hld_job_scheduler/independent_test_support.clj:34-69`).
  Its contract checks point reads, claim writes, clock writes, and completion
  writes remain bounded as unrelated history grows. This is aligned with the
  README efficiency contract rather than a latent all-record scan.

### Client reads make one direct selection per requested entity

- `make-client` obtains one foreign PState handle and `state` performs one
  `foreign-select-one` on an execution key (`module.clj:119-126`).
  `get-execution`, `get-node`, and `get-claimable-nodes` derive responses from
  that one bounded execution state (`module.clj:133-149`); `get-claim` selects
  one indexed claim (`module.clj:144-145`). No client method loops over
  executions or makes a sequence of distributed reads per node/claim.
- These are direct, key-addressed reads, not the problematic client-side
  fan-out pattern. Replacing them with query topologies would be a reasonable
  boundary if reads later combine multiple partitioned PStates or require a
  single distributed aggregation, but the current contract has neither.
  Preserve the one-key read path and avoid adding roundtrips or topology
  machinery without a demonstrated need.

## Idioms from the comparison references

- **Auction** (`challenges/auction-module/test-resources/auction_module/module.clj`):
  separate the low-latency stream (`:77-114`) from expiration microbatch
  processing (`:115-158`); define separate PStates by access pattern
  (`:78-95`); repartition with `|hash` when a subsequent local operation is
  addressed by a different key (`:112-113`, `:149`). This supports the
  principle that topology and PState boundaries should follow distinct
  workloads and key ownership. It does not imply splitting the scheduler's
  ordered per-execution lifecycle events.
- **Chat app** (`challenges/chat-app/test-resources/chat_app/module.clj`):
  multiple topologies/PStates support genuinely different visibility and
  read patterns (`:159-160`, `:254-276`, `:393-495`); paginated query
  topologies aggregate tuples at the query boundary (`:417-493`), and the
  client calls those query topologies for combined pages (`:585-593`). Its
  direct key lookups still use foreign PState selects (`:547-553`), and the
  intentionally multi-step member scan (`:563-581`) exists because online
  filtering requires scanning candidate members. Neither cross-partition
  page assembly nor iterative filtering exists in the scheduler's reads.
- The Rama skill emphasizes matching depot partitioners to PState keys,
  colocating related state, subindexing growing collections, and using query
  topologies to avoid multiple client roundtrips (`plugins/rama-skill/skills/rama/SKILL.md:51-68`).
  The scheduler follows these principles: execution is the partition key,
  claim history is subindexed, and each client read makes one selection.

## Recommendation

Keep this reference unchanged. The small per-execution node state is bounded
by the explicit 32-node contract, claim history is a necessary per-execution
subindexed map, and the direct client reads are single-key lookups. Do not
transplant chat's query aggregation or its multi-hop read paths: those solve a
different access pattern. Reconsider the design only if production requirements
change (for example, multiple executions become a single query unit, a single
execution becomes unbounded, or distinct write classes need different service
levels). Any such change should preserve same-execution ordering, durable
claim-id decisions, and the independent storage-work bounds.
