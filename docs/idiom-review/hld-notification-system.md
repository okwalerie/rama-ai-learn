# HLD notification system reference idiom review

Baseline: `43abd3fccff8777d8995f8a29658f3c4c97df01a` (`Import nine source-backed Rama challenges with isolated evaluation and atlas`). The review is against `challenges/hld-notification-system/test-resources/hld_notification_system/module.clj` and its README/protocol contract. This is a review only; the reference, README, protocol, private tests, and skill were not changed.

## Findings

### Barrier check: no cross-client counter defect found in this harness model

`counter` is allocated once per `create-module` result, outside the `wrap-client` function (`module.clj:190-203`), so the `a` and `b` wrappers created from that result share it. The append lock covers both the depot append and counter increment (`:199-203`), and the barrier waits through the resulting shared count (`:227-230`). This matches the private tests' pattern of writing through one wrapper and waiting through the other (`notification_test.clj:14-18`; `independent_test.clj:62-67`). The specific suspected per-wrapper counter bug is therefore not present for the challenge's single-process test setup. This conclusion does not claim that an in-memory counter is a cross-process production barrier; that deployment scope is outside what these tests establish.

The shared lock also serializes every `foreign-append!` made through wrappers from this factory result, including unrelated user and submission owners (`:199-202`). That is a throughput bottleneck against the README's 400,000/s combined peak, even though it simplifies the counter/barrier bookkeeping. A replacement should preserve the tested barrier semantics without placing all client writes behind one monitor; measure the acknowledgement or watermark strategy under concurrent clients rather than assuming atomic-counter bookkeeping is free.

### 1. `$$task-pos` is read and written without establishing partition alignment

**Evidence:** After reading the current task ID, the submission path selects and transforms `$$task-pos` by that ID (`module.clj:137-140`), but there is no `(|hash *task)` or other partitioner between `ops/current-task-id` and those local PState operations. The source depot is partitioned by the polymorphic `:owner` (`:102`); the submission path later needs user-key state and then submission-key state (`:131-161`). The Rama skill requires each local PState operation to be aligned to the owning partition (canonical skill `SKILL.md:61`).

**Impact:** `local-select>` / `local-transform>` are local operations, not distributed lookups. The rank allocator's accesses therefore do not establish that the key `*task` is on the current task. If the key is not local, the rank may be absent or unrelated; the code silently produces misleading candidate ranks. Because ranks are used to select a first-wins candidate (`:151-154`), this is in a correctness-sensitive path even though rank is only auxiliary metadata.

**Idiomatic alternative:** Remove the rank PState if it is not required by the public ordering contract. If a sequence is required, allocate it where its owner is already local, or explicitly repartition to the sequence key before local access and account for the return repartition. Rama's `:ingress-seq` is a candidate only if its documented scope and ordering match the required first-wins semantics; it should not be substituted merely to avoid the alignment proof. Re-check all local reads/writes whenever introducing a new partitioner.

### 2. A single `:owner` depot key multiplexes two ownership domains

**Evidence:** All five event record types share `*events (hash-by :owner)` (`module.clj:10-14,101-103`). User/profile/submission events set owner to user ID, whereas attempt and receipt events set it to submission ID (`:205-214`). All are consumed by one microbatch topology and dispatched through separately filtered batches (`:126-188`). The workload calls for 100,000 submissions/s plus roughly 300,000 attempt/receipt events/s (`README.md:87-92`).

**Impact:** `:owner` means recipient for some records and submission for others. This can colocate/order each domain's events, but it hides two distinct key contracts behind one partitioner, couples their throughput and batch scheduling, and makes it harder to reason about hot-user versus hot-submission skew. The code still explicitly repartitions as it moves from recipient state to submission state and back (`:157-180`). A single microbatch is not automatically wrong: durable cross-state effects and exactly-once processing matter here. The concern is whether this shared ingestion/processing boundary remains a good fit at the stated aggregate rate and whether the necessary ordering is preserved when it is changed.

**Idiomatic alternative:** Use explicit depots keyed by the logical owner for each write family (recipient-owned device/preference/submit writes versus submission-owned attempt/receipt writes), with clear record routing and a documented ordering argument. Keep the state transitions that need atomicity together; do not split PState ownership across independent topologies without resolving write ownership, retry behavior, and the barrier contract. `auction-module` demonstrates separate listing- and bid-keyed depots feeding topologies (`auction-module/module.clj:69-76,95-114`); `chat-app` uses distinct depots for distinct key spaces and documents which topology consumes each (`chat-app/module.clj:26-30,158-165,279-283`). These are comparison patterns, not drop-in designs for this contract.

### 3. The `+group-by`/materialize arbitration stage is more machinery than the contract appears to require

**Evidence:** The code tracks a per-user submit sequence, allocates per-task positions, derives `candidate-rank`, groups submissions by ID, sorts via `aggs/+limit` options, materializes winners, then checks the final submission PState before storing (`module.clj:134-161`). There is no `:ingress-seq`, `+vec-agg`, explicit `sort`, or `loop<-` in this reference; the actual arbitration mechanism is `+group-by` + ranked `+limit` + `materialize>`.

**Impact:** The grouped stage introduces transient state, extra materialization work, and a second pass over candidate submissions to implement first-wins deduplication. The rank is not an ingress sequence: it combines task ID and task-local position (`:89-90,137-154`), so its relationship to arrival order is not self-evident. The contract requires invocation order for sequential writes from one client to the same logical owner, but allows different clients' writes to serialize in any order (`README.md:155-159`). The implementation should establish whether deterministic arbitration is actually needed beyond that contract.

**Idiomatic alternative:** Prefer a direct idempotent point claim/update at the submission-ID owner if the deployed Rama processing semantics allow a correct first processed write to win under retries. If cross-partition same-batch contenders need explicit arbitration, retain grouping but specify the tie-break contract, make the rank source correctly aligned and retry-safe, and test same-batch and cross-batch races. Do not introduce `+vec-agg` + client/topology-side sorting or a `loop<-` repartition cycle by default: they do not by themselves provide first-wins correctness, and a loop of PState hops adds repeated reads/partitioner traffic. Use aggregation/sort only where a bounded result set and a specified ordering genuinely require it.

### 4. The large PState collections are subindexed, but intentionally unbounded

**Evidence:** The user's recent-submission IDs and dead letters are per-user maps keyed by monotonically increasing sequence numbers and have `{:subindex? true}` (`module.clj:104-115`); page reads take only 100 entries from the sorted tail (`:221-226`). Submission records are keyed independently by submission ID and keep a bounded delivery map (maximum 8 devices) (`:116-123`). The README explicitly allows up to 100,000 submissions and 100,000 dead-letter entries per recipient (`README.md:90-92`) and requires page work bounded independently of history (`:99-105`).

**Impact:** Subindexing makes the indexed page read bounded; it does not cap retained history or the storage footprint. Recent IDs and dead-letter entries continue to grow for the lifetime of a user, as does the global submission-ID PState. That may be correct because the protocol exposes historical submission lookup and does not authorize deletion, but it must be treated as an explicit retention/storage choice rather than assuming that a 100-entry page bounds storage.

**Idiomatic alternative:** Preserve the current subindexed sorted range access for bounded latest-page reads. If bounded retention is a product requirement, first add an explicit retention contract (including whether `get-submission` remains valid after pruning), then delete/compact using an indexed range operation or time/sequence buckets. Do not silently cap to 100: the current protocol does not say older records may be discarded, and the skill says not to delete data absent an explicit requirement (`plugins/rama-skill/skills/rama/SKILL.md:34`). The fixed-size `:devices` and `:prefs` maps are different: their limits are explicit (8 devices, 16 categories) and their fixed-work access is appropriate.

### 5. Client reads are direct distributed PState selections, not multi-read client joins

**Evidence:** `get-devices` and `get-preferences` each make one keyed `foreign-select-one` against `$$users`; `get-submission` makes one point select against `$$submissions`; each page read makes one bounded range select (`module.clj:215-226`). There are no query topologies in this module.

**Assessment:** This is a sound choice for the current API: every method reads one keyed PState path, and page reads are bounded. Replacing these calls with query topologies would add a topology invocation without eliminating a multi-PState client round trip. The client does not stitch several independently fetched partitions together.

**Idiomatic alternative:** Keep direct foreign reads for these single-state point/range reads. Add a query topology when a future read must join state across keys/PStates, fan out across partitions, or perform work that should not be exposed as several client network round trips. The `chat-app` reference uses query topologies for multi-stage room/profile and user-room/room-sequence lookups (`chat-app/module.clj:417-433,468-478`), while its simple key lookups remain direct client selections (`:546-553`).

## Disposition

Doc-only. No reference rewrite was attempted: the local PState alignment finding affects correctness, and choosing a replacement would require re-deriving submission arbitration and re-running the complete private suite. This checkout has a review deadline context but no explicit remaining-time budget; the existing implementation is not sufficiently safe to rewrite speculatively. Per request, no challenge README, protocol, private tests, or skill files were edited, and no solver or `bb run-challenges` run was made.

## Files and line references consulted

- `challenges/hld-notification-system/README.md:85-162` — workload, bounded-work contracts, barrier and ordering semantics.
- `challenges/hld-notification-system/src/hld_notification_system/protocol.clj:22-142` — method-level state and transition contract.
- `challenges/hld-notification-system/test-private/hld_notification-system/notification_test.clj:1-188` and `independent_test.clj:1-128` — lifecycle, duplicate IDs, pages, cross-client barriers, bounded work, and module update coverage.
- `challenges/hld-notification-system/test-resources/hld_notification_system/module.clj:10-230` — reference implementation.
- `challenges/auction-module/test-resources/auction_module/module.clj:69-114,161-218` and `challenges/chat-app/test-resources/chat_app/module.clj:26-35,158-179,279-283,392-494,496-597` — comparative depot, topology, and query patterns.
- `plugins/rama-skill/skills/rama/SKILL.md:22-68` — correctness, partition alignment, collection sizing, topology and I/O guidance.
