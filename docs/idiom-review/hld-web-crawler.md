# HLD web crawler reference idiom review

**Scope:** Review of `challenges/hld-web-crawler/test-resources/hld_web_crawler/module.clj` at baseline `43abd3fccff8777d8995f8a29658f3c4c97df01a`, against the challenge README, `src/hld_web_crawler/protocol.clj`, the private suite `test-private/hld_web_crawler/frontier_test.clj`, the auction-module and chat-app reference modules, and `plugins/rama-skill/skills/rama/SKILL.md` with its references. All `module.clj:N` citations refer to the HLD crawler reference module unless another path is given.

**Decision:** Documentation-only. None of the findings below is a correctness defect. The only safe local changes are readability changes (findings 2 and 5). They would change nothing measurable and would still require a full private-suite run, so they do not justify rewriting a validated reference. The README, protocol, private tests, reference module, and skill are unchanged.

## Findings

### 1. One host-partitioned depot and one microbatch topology — idiomatic, keep

- `module.clj:45-47` declares one `*events` depot partitioned by `(hash-by :host)` and one `"core"` microbatch topology. All four command types (`module.clj:33-36`) update the same host-owned `$$hosts` entry, and their relative order matters: claims depend on earlier discoveries, policies, and completions.
- This matches the skill's depot scope rule. Events that affect the same PStates and need mutual ordering share a depot (`plugins/rama-skill/skills/rama/references/app-design.md:116-129`, `references/depot-design.md:15-18`). Partitioning by the owning entity colocates the depot with its PState (`app-design.md:131-139`). It also follows "Default to microbatch" (`app-design.md:155-172`): writes are asynchronous (`README.md:145-147`), and the workload is about 50k ops/s per operation type (`README.md:104-111`).
- The auction reference splits depots because its events have *different* owners (`challenges/auction-module/test-resources/auction_module/module.clj:70-71`). This challenge has a single owner key, so a single depot is correct.
- One hot host is serialized on one task. That is inherent in the per-host single-lease/fence contract, not a partitioning defect.

### 2. Command dispatch uses `<<cond` + `instance?` instead of `<<subsource`

- `module.clj:76-77`, `:98`, `:103`, and `:172` dispatch with `(case> (instance? Discover *event))` and similar. They then pull fields with `get` (`:78`, `:104`, `(get *event :delay)` at `:101`, `(get *event :fence)`/`:outcome`/`:now` at `:175-186`).
- The skill's idiom for a multi-type depot is `<<subsource`, which dispatches by record type and destructures in the `case>` (`app-design.md:123-124`, `depot-design.md:16-17`, `references/syntax.md:463`). The chat reference uses it for exactly this situation, in both the stream topology and the microbatch (`challenges/chat-app/test-resources/chat_app/module.clj:201-203`, `:282-285`).
- **Idiomatic alternative:** `(<<subsource *event (case> Discover :> {:keys [*urls]}) ... (case> Policy :> {:keys [*delay *rules]}) ... (case> Claim :> {:keys [*claim-id *now]}) ... (case> Complete :> {:keys [*fence *outcome *now]}) ...)` inside the existing loop body. The loop-local `*event` is an ordinary value, so this changes only the dispatch form. It does not change semantics, partitioning, or ordering. It is a readability change only, which is why this review does not apply it.

### 3. `+group-by` + ordered accumulator + `loop<-` is justified by yielding; do not replace it with direct per-record emit

- `module.clj:65-70` enters `<<batch`, groups records by host with `+group-by`, and collects each host's commands in depot-emission order with the custom `+ordered-commands` accumulator (`:38-43`). It then replays that vector with one `loop<-` per host (`:70-188`).
- The naive idiomatic alternative is direct emit, `(%events :> *e)` followed by per-record `local-transform>` with no `<<batch` (`references/microbatch.md:68-84`). That form *is* ordered for synchronous code, because `%mb` emits each partition's records in append order (`microbatch.md:13`). But this module must call `(yield-if-overtime)` in long loops (`module.clj:71`, `:81`, `:130`, `:140`). Discovery processes up to 100 URLs per command, and a claim may retire up to 1,000 blocked head URLs (`README.md:110-111`), while tasks are single-threaded (`SKILL.md:26`). The skill is explicit about the consequence: "while an event is suspended at a yield point, later-queued events on the task execute ... Do NOT yield on a path where correctness depends on same-key events processing in order" (`references/dataflow.md:156`).
- Collecting one host's commands into one group and replaying them in a single sequential loop makes the whole host sequence one event. The loop can then yield without reordering same-host commands. The comment at `module.clj:38-39` states that intent. So the extra stage is a deliberate order-preserving construct, not redundant grouping. **Keep it.** Direct per-record emit would require either removing the yields (violating `SKILL.md:26` on hot hosts) or losing per-host ordering.
- Cost to acknowledge: the accumulator retains one vector of commands per host for the duration of the microbatch. That memory is bounded by the microbatch size, not by the host's queue. `+group-by` also performs a hash partition by `*host` (`references/aggregators.md:78-83`), but because the depot is `hash-by :host` it resolves to the task already holding the records.
- `+ordered-commands` is order-dependent and has no combiner (`aggregators.md:41`). Its correctness relies on every record for one host originating from one depot partition and on `+group-by` accepting a non-combiner accumulator. Both hold here, but anyone changing the depot partitioner (for example, to a random or composite key) must revisit this.
- The module attaches no ingress sequence number to events and does not need one: ordering comes from single-partition append order plus the single-loop replay.

### 4. Pending queue, pagination, and blocked-head skipping use subindexed sorted-set ranges — idiomatic, keep

- `module.clj:55-62` stores URL status, the active pending set, and claim outcomes as subindexed nested structures. `module.clj:129-155` scans `:pending` in sorted chunks of 64 (`sorted-set-range-from-start` / `sorted-set-range-from ... {:max-amt 64 :inclusive? false}`) and retires each disallowed head URL as `:blocked`, removing it from `:pending` (`:145-151`). `list-pending` (`module.clj:228-233`) reads one bounded sorted-set range with the caller's `limit`.
- This matches the skill's sorted-set navigators: one seek plus a sequential scan on subindexed sets (`references/paths.md:488-506`). A claim does fixed work plus amortized work per blocked URL, because each blocked URL leaves `:pending` exactly once. `list-pending` work is bounded by `limit`. `bounded-work-growth` (`frontier_test.clj:263-301`) and `skip-chunks-and-pagination` (`frontier_test.clj:166-190`) enforce these bounds.
- The chunk loop stops once the explicit `:queued` counter reaches zero (`module.clj:152-155`). That avoids a final empty range read when the queue is exhausted.
- Do not replace range navigation with `+vec-agg` plus a post-aggregation sort. That would materialize an unbounded host queue and break the README's bounded-work contract (`README.md:118-127`).

### 5. State-machine loop: correct placement, some local readability costs

- Claim (`module.clj:103-170`) and Complete (`:172-187`) keep lease expiry, politeness, requeue, fencing, clock advance, and outcome recording in one host-owned sequential path. That is the right place for them. Moving these decisions into the client wrapper would race between clients and violate the README requirement that all business state live in the module (`README.md:162-165`).
- Readability costs that a future rewrite may address (no behavior change):
  - The discover loop tests `(nil? *existing)` twice (`module.clj:87-94`): once to write and once to choose the `continue>`. A single `<<if` with both transforms followed by `(continue> (rest *remaining) (inc *n))`, and `(continue> (rest *remaining) *n)` in the `else>`, expresses the same logic once.
  - The `:info` map is read, `assoc`-ed, and written back with `termval` at `:95-96`, `:100-101`, `:169`, and `:185-187`. This is safe because the host is processed by one sequential loop and `:info` is a small fixed-keys record. Field-level paths (`(keypath *host :info :delay)` etc.) would make the "policy never touches the clock, fence, lease, or last-claim-at" guarantee (`protocol.clj:65-70`) visible in the code rather than dependent on `assoc` discipline. This is optional.
  - Pure helpers for the claim decision would make the rules easier to audit, for example `(claim-decision info tick) -> {:outcome ... :free-info ...}` mirroring protocol steps 3-5 (`protocol.clj:72-89`). The PState reads and writes would stay in dataflow.
- Before any loop rewrite, rely on the private suite's boundary coverage: expiry equality (`frontier_test.clj:64-75`), rejected-completion clock immutability, non-retroactive policy (`:130-164`), claim-id replay (`:101-103`, `:324-329`), stale requeue order (`:226-238`), and unchanged-module update durability (`:303-338`).

### 6. Durable history is correctly subindexed; unbounded growth is a contract consequence

- `:urls` keeps every URL ever seen, for exact "at most once, ever" dedup (`README.md:49-50`) and `get-url`. `:claims` keeps every `(host, claim-id)` outcome so replays are no-ops and outcomes stay immutable (`protocol.clj:74`, `:108`). Both are subindexed with `{:track-size? false}` (`module.clj:57`, `:58`, `:62`). That is correct because `get-host`'s `:queued` comes from the explicit counter in `:info` (`module.clj:30-31`, `:53`), not from counting a collection, as `README.md:124-125` requires.
- `:urls` and `:pending` both hold an active URL key. The first provides point status lookup and permanent history; the second provides an ordered active-only index. This is the standard trade of a second index for bounded reads, not accidental duplication.
- Pruning is not possible without changing the contract ("ever" dedup and immutable claims). Storage growth is an accepted consequence.

### 7. Client reads are direct single-partition `foreign-select-one` / bounded `foreign-select` — idiomatic, keep

- `get-claim`, `get-url`, and `get-host` each make one `foreign-select-one` call on a host-leading path (`module.clj:213-227`). `list-pending` makes one bounded `foreign-select` (`:228-233`). Writes each make one `foreign-append!` (`:198-212`). `discover!` canonicalizes, deduplicates, and groups by host client-side (`:203-205`), which the README permits (`README.md:164-165`). As a result, each host gets one ordered `Discover` record, which preserves within-host element order (`README.md:185-187`).
- Both peer references use direct foreign selects for single-key reads: auction at `challenges/auction-module/test-resources/auction_module/module.clj:184-204`, and chat at `challenges/chat-app/test-resources/chat_app/module.clj:546-553`. Chat moves to query topologies only for composite or paged views and online filtering (`chat_app/module.clj:417-494`, invoked at `:575-590`). Every read in this protocol targets one host partition, so a query topology would add an invocation hop without saving a round trip.

## Idiom comparison

| Topic | Current reference | Idiomatic direction |
|---|---|---|
| Event partitioning | One depot, `hash-by :host` | Keep: single owner key; same-host order required. |
| Topology | One microbatch topology | Keep: asynchronous API, high throughput, exactly-once. |
| Type dispatch | `<<cond` + `instance?` + `get` | Prefer `<<subsource` with `case>` destructuring (as in chat-app). |
| Command ordering | `+group-by` + ordered accumulator + one `loop<-` per host | Keep: required so `yield-if-overtime` does not reorder same-host commands (`dataflow.md:156`). |
| Pending order / pagination | Subindexed sorted set + range navigators | Keep. |
| Aggregation / sort | None on frontier reads | Keep absent; never gather/sort an unbounded queue. |
| Counters | Explicit `:queued`, `track-size? false` | Keep. |
| Growing history | Subindexed `:urls` / `:claims` | Keep under the current dedup/idempotency contract. |
| Reads | Direct single-partition foreign selects | Keep; use query topologies only for composite/fan-out reads. |

## Verification / limits

- Branch `orb/idiom-hld-web-crawler` starts at baseline `43abd3fccff8777d8995f8a29658f3c4c97df01a`. The only changed file relative to the baseline is this document.
- Every `file:line` citation above was checked against the files at the baseline.
- The reference was not rewritten, so the private suite was not run for this review, as instructed for a doc-only outcome. This document is not a new test verdict. The reference's own recorded validation is in `challenges/hld-web-crawler/test-resources/BUILD_VALIDATION.md:5-6` (author-reported: 10 tests, 2,892 assertions, 0 failures).
