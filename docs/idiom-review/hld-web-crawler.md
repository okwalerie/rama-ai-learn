# HLD web crawler reference idiom review

**Scope:** Review of `challenges/hld-web-crawler/test-resources/hld_web_crawler/module.clj` at the requested baseline `43abd3fccff8777d8995f8a29658f3c4c97df01a`. This is a review only; the reference implementation, README, protocol, tests, and Rama skill are unchanged.

**Decision:** Keep this branch documentation-only. A rewrite would change the event-ordering mechanism, durable-state layout, and all state-machine transitions for a production-scale workload. Although the private suite is available, no safe, narrow rewrite is established by this audit alone; changing the reference under a deadline would be riskier than recording actionable alternatives.

## Findings

### 1. One host-partitioned depot and one microbatch topology — mostly idiomatic

- `module.clj:45-47` declares one `*events` depot partitioned by `:host` and one `"core"` microbatch topology. All four write operations affect the same host-owned state and their order matters to claims, policies, discovery, and completion.
- This matches the depot-design guidance in `plugins/rama-skill/skills/rama/references/app-design.md:114-145`: events that need ordering for the same entity belong together, and partitioning by the entity keeps that entity's events together. It also matches the reference skill's default to microbatch for high-throughput writes and exactly-once PState updates (`references/app-design.md:158-170`).
- The design naturally leaves one hot host on one task. That is an inherent per-host serialization boundary for this contract, not something a different depot partitioner can remove without changing the state machine. The documented workload includes a host with up to one million pending URLs, so it is still important not to make each host event scan or materialize that queue.
- Splitting command types into separate depots/topologies is not the first improvement to make: it would complicate the per-host ordering contract and permit topology schedules to change the semantics. The app-design guidance says to split events when they are independent or have distinct throughput/latency/ack profiles, not merely because they have different record types.

### 2. Grouping and ordered command buffering add complexity and per-batch memory

- `module.clj:63-70` enters a microbatch `<<batch`, groups by host with `+group-by`, and collects every host's commands in `+ordered-commands`. The accumulator at `module.clj:38-43` appends each command to a vector; `module.clj:70-188` then serially replays that vector with `loop<-`.
- The input depot is already hash-partitioned by host (`module.clj:46`), and the batch source documentation describes per-partition emission in depot append order (`plugins/rama-skill/skills/rama/references/microbatch.md:11-13`). The extra group/collect/replay stage therefore deserves a proof of necessity: it builds a second in-memory representation of all commands for a host in the microbatch and delays PState updates until the group is collected. A very busy host or large batch makes this retained vector a potential memory/latency cost.
- `+group-by` intentionally hash-partitions by its key and groups rows (`plugins/rama-skill/skills/rama/references/aggregators.md`, “+group-by”). This is a meaningful repartitioning step even though the source depot was already partitioned by host; do not assume it is free or that the accumulator's ordering property follows merely from grouping.
- **Alternative to evaluate:** process source records directly in the microbatch's per-record dataflow, routing/writing by the host key and retaining the same-host sequential transition semantics. This avoids a whole-batch command vector and potentially redundant grouping. Before changing it, prove that order for records of the same host is preserved through the chosen batch form and that retries remain idempotent. If a grouping stage is required, document precisely where order is established and boundedly accumulate only the data needed for a group.
- The requested token `:ingress-seq` does not occur in this reference module. No sequence field is currently attached to events; the ordering argument instead relies on depot partitioning and the custom ordered accumulator. Do not add an ingress sequence speculatively: first establish what guarantee the runtime requires and whether direct same-partition processing already supplies it.

### 3. Queue pagination and blocked-head skipping use appropriate subindexed range access

- `module.clj:55-62` keeps URL status, pending URLs, and claim results in nested subindexed maps/set. `module.clj:129-155` scans pending URLs in sorted chunks of 64 and retires disallowed head entries; `module.clj:228-233` pages through a sorted-set range with the caller's limit.
- These choices fit the contract's bounded-read requirements and the skill's sorted subindex range guidance (`plugins/rama-skill/skills/rama/references/paths.md:457-490`). `list-pending` reads a bounded range rather than loading/sorting the full host queue. It avoids client-side sorting and avoids scanning retired URLs because the pending set is a separate active index.
- `claim!` work is bounded by fixed processing plus the number of newly blocked URLs: each blocked URL is removed from the active pending set as it is retired (`module.clj:145-151`). The chunk size is a tuning constant, but changing it is not necessary to fix an asymptotic issue.
- `:ingress-seq`, `+vec-agg`, and a post-aggregation `sort` are not used in this module. The set's sorted range navigation is the ordering mechanism. Replacing that with `+vec-agg` followed by sorting would materialize an unbounded host queue and violate the bounded-work contract; an aggregator/sort is appropriate only for an already-bounded result set.

### 4. Large per-host state is correctly subindexed, but historical cardinality is a real storage obligation

- In `module.clj:48-62`, `:urls` preserves every URL for exact dedup and status lookup; `:pending` is the ordered active URL index; `:claims` preserves outcomes so `(host, claim-id)` replay is a no-op. All three can grow without bound for a hot host. They are subindexed with size tracking disabled.
- The shape supports point reads and bounded range scans, and disabling subindex size tracking is reasonable because the code keeps `:queued` as an explicit counter (`module.clj:30-31`, updated around `module.clj:79-96` and claims at `module.clj:156-170`). The schema avoids the prohibited full-collection count scan.
- There is no safe pruning alternative under the current contract: exact dedup is “ever,” claims are immutable/replay-safe, and reads may occur after processing. Retention/compaction would require an explicit contract change, not a cleanup hidden in the topology. Storage growth should be called out as an accepted consequence of those semantics.
- The `:urls` status record and pending set intentionally duplicate the URL key while it is active: the former supports `get-url` and permanent dedup/status history; the latter supports ordered active-only pagination. This is a write/storage tradeoff for bounded reads, not accidental duplication.

### 5. The transition loop is complex but aligned to per-host atomic state transitions

- `module.clj:70-188` processes a host's collected commands serially and calls `yield-if-overtime` in the outer command loop, URL discovery loop, and claim/skip loops (`:70-71`, `:80-81`, `:129-140`). This is important because tasks are single-threaded and one long synchronous event can stall queries on that task.
- Within `Claim`, stale-lease requeue, delay checks, blocked-URL retirement, queue count, fencing, and outcome recording all share one `*host` owner (`:103-170`). `Complete` similarly validates and updates lease, URL status, and host clock in the same owner path (`:172-187`). The location of these transitions is sensible for consistency.
- **Alternative to evaluate:** extract pure helper functions for the host-level state-machine decisions and keep the path reads/transforms in the topology. This may make correctness review easier without changing partitioning or durability. Avoid moving policy/lease decisions client-side: two clients could then race, and each would need extra reads/roundtrips before appending.
- Before any loop rewrite, test invariants at expiry equality, rejected completion clock immutability, policy non-retroactivity, retry of a claim ID, and sequential same-host discovery. The existing private suite covers these cases and the work-growth constraints.

### 6. Client-side reads are direct single-host PState reads, not multi-hop distributed queries

- `module.clj:213-233` implements `get-claim`, `get-url`, and `get-host` using one `foreign-select-one` each; `list-pending` uses one bounded `foreign-select` on the host's sorted set. Writes append one command each (`:202-212`).
- These are appropriate state-inspector-style reads for the current one-host-per-call protocol. The paths have a leading host key and resolve to one owning partition; the wrapper does not fan out over all hosts or make an N+1 set of PState reads. So the concern is a client-to-Rama roundtrip per protocol read, not a distributed client-side scan.
- **Alternative to evaluate only if the API evolves:** a query topology can combine multiple host-local reads into one invocation or centralize a compound response. The chat reference demonstrates query topologies for bounded pages/composite reads (`challenges/chat-app/test-resources/chat_app/module.clj:417-494`), and invokes them from its wrapper (`:581-590`). For the present protocol, each read returns one small value and already costs one request, so replacing each direct select with a query topology is not automatically lower latency or lower network cost.
- The auction reference uses direct foreign selects for simple entity reads (`challenges/auction-module/test-resources/auction_module/module.clj:184-204`); the chat reference likewise uses them for simple point lookups (`:546-553`). Both provide precedent for keeping small point reads direct.

## Idiom comparison

| Topic | Current reference | Idiomatic direction |
|---|---|---|
| Event partitioning | One depot hash-by host | Keep: host is the owning state key and commands need same-host order. |
| Topology | One microbatch topology | Keep unless a measured/required latency profile justifies stream; this API is asynchronous and workload is high throughput. |
| Grouping / command order | `+group-by` + ordered vector accumulator + `loop<-` | Re-evaluate against direct ordered processing; current approach has group/repartition and whole-group memory overhead. Preserve same-host order explicitly. |
| Pending order | Subindexed sorted set + range navigation | Keep: bounded pagination and lexicographic next-URL selection. |
| Aggregation/sorting | No `+vec-agg` or `sort` in frontier reads | Keep absent; do not gather/sort an unbounded queue. Use aggregators only for bounded cross-partition result sets. |
| Growing history | Subindexed URL/claim maps | Keep under current exact-dedup/idempotency contract; no pruning without changing that contract. |
| Read API | Direct single-key foreign selects | Keep for individual point reads; use query topology if a future operation composes multiple reads or needs server-side fanout. |

## Reference material reviewed

- Challenge README, protocol, complete private test namespace, and reference module.
- `challenges/auction-module/test-resources/auction_module/module.clj`: separate depots for different ownership keys, stream/microbatch separation for distinct timing needs, and direct foreign selects for simple reads.
- `challenges/chat-app/test-resources/chat_app/module.clj`: entity-key partitioning, subindexed collections, query topologies for bounded pages/composite views, and direct foreign selects for simple point reads.
- `plugins/rama-skill/skills/rama/SKILL.md` and relevant app-design, microbatch, aggregator, path, and query-topology references.

## Verification / limits

- Baseline checked: `43abd3fccff8777d8995f8a29658f3c4c97df01a` is present, and this branch starts exactly there.
- No implementation rewrite was made, so private tests were not run. The review is not a test verdict on the reference implementation.
- The only file added by this work is this review document.
