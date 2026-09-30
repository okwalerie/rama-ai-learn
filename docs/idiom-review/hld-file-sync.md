# Rama idiom review: HLD file sync

## Scope and verdict

Reviewed the reference module, challenge contract and private tests, and the
auction-module and chat-app references against the Rama skill. This is an
architecture review, not a claim that the implementation is incorrect.
The current design is deliberately specialized to preserve the challenge's
per-namespace command ordering, durable idempotency, and bounded storage work.
I recommend **no reference rewrite in this change**: altering ordering or
state placement in this 467-line implementation would be a correctness-risky
change, and the existing full private suite is broad. The deliverable is this
review only; no README, protocol, tests, or skill files were changed.

## Findings and alternatives

### 1. One namespace-partitioned command depot and a shared state PState

- [`module.clj:247-248`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L247)
  declares one `*commands` depot with `(hash-by :ns-id)` and caps each
  microbatch at 200 records. [`module.clj:251-282`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L251)
  declares one `$$namespaces` PState keyed by namespace. Its unbounded maps
  (`:blocks`, `:files`, nested `:versions`, `:journal`, and `:requests`) are
  all subindexed; the per-version blocklist is a vector, but its length is
  contract-bounded at 1024.
- **What is idiomatic here:** partitioning by namespace colocates all state
  needed to validate a command and update its outcome/version/journal, while
  spreading independent namespaces across tasks. The PState schema indexes
  the growing collections rather than serializing whole histories on each
  lookup. This matches the PState guidance to partition by the dominant
  access key and subindex unbounded collections.
- **Tradeoff / alternative:** a single exceptionally busy namespace is a
  hot key: its events are serialized on one depot partition and its data
  resides on one state partition. Sharding that namespace by file or block
  could distribute work, but would require coordination for namespace-wide
  request-id uniqueness, `:next-seq`, ordered commands, atomic registration
  validation, and contiguous journal sequencing. A second sequencer or
  denormalized index adds cross-task coordination and storage work; it is not
  a drop-in `|hash` change. The README does not state a per-namespace
  throughput target, and the performance tests explicitly treat the
  per-task depot-read distribution as diagnostic only
  ([`performance_test_support.clj:171-190`](../../challenges/hld-file-sync/test-private/hld_file_sync/performance_test_support.clj#L171)).
  Keep the simple partitioning unless a specified hot-namespace workload
  justifies a redesigned ordering and consistency contract.

### 2. Microbatch, per-namespace grouping, and order restoration

- [`module.clj:284-298`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L284)
  stamps each command with `:ingress-seq` in the depot's append-order
  pre-aggregation path, then groups by namespace with `+group-by` and
  `+vec-agg`. [`module.clj:116-120`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L116)
  sorts those pairs by the durable ingress position; [`module.clj:299-372`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L299)
  applies them sequentially. The loops yield cooperatively, and the
  `read-known-sizes` helper also yields between bounded point reads
  ([`module.clj:226-241`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L226)).
- **Why this is justified:** the README requires issue order for commands
  from one client against a namespace, and replay/conflict detection must
  happen before business validation. Aggregation can reorder inputs, so
  `+vec-agg` output order cannot itself serve as the contract; the explicit
  sequence and sort make the restoration visible. Processing a namespace's
  grouped commands sequentially also lets subsequent commands in the same
  attempt observe prior uncommitted writes. The 200-record microbatch cap
  bounds the vector and sort for a batch; the tests exercise ordered bursts
  without intermediate barriers ([`functional_test_support.clj:409-477`](../../challenges/hld-file-sync/test-private/hld_file_sync/functional_test_support.clj#L409)).
- **Cost / alternative:** this adds a durable sequence read/write per input,
  aggregation buffers, and sorting per namespace per microbatch. A
  per-command stream topology could avoid the grouped sort and lower
  visibility latency, but coordinating a single order across command types,
  shared clients, retries, and the namespace sequence would still be
  required. Splitting command types into independent depots/topologies
  without a common sequencer would break required ordering and can make
  competing commits observe invalid state. Microbatch is a defensible default
  for the stated durable, ordered work; switch only if the application needs
  low-latency acknowledgements strongly enough to accept/rebuild those
  guarantees. The skill similarly distinguishes stream for low latency/ack
  coordination from microbatch for other work.
- `+group-by` is being used for keyed routing, not a global funnel. The skill's
  batch reference notes that group-by hash-partitions on its key; this is
  preferable to routing the entire command set through `|global`.

### 3. Growing PState collections and access shapes

- The schema at [`module.clj:251-282`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L251)
  marks every unbounded nested map subindexed. That includes block metadata,
  files, each file's versions, the namespace journal, and request outcomes.
  `:blocklist` is stored as a bounded vector in one version record, not as
  the unbounded history container.
- **Assessment:** this directly supports point lookup of one hash, request,
  file head/version and a sorted journal range without loading a namespace's
  full history. The performance tests check that registration and commit work
  stay proportional to submitted lists, point reads remain constant as
  history grows, and journal reads depend on `limit`, not cursor depth or
  total history ([`performance_test_support.clj:77-169`](../../challenges/hld-file-sync/test-private/hld_file_sync/performance_test_support.clj#L77)).
  This is consistent with the schema/path idiom rather than the anti-pattern
  of storing an ever-growing ordinary Clojure map as one value.
- **Alternative:** a separate PState per logical collection might clarify
  ownership, but does not by itself improve placement: the same namespace key
  still routes those records to the same task, while additional PStates can
  increase path/state plumbing. Split only when distinct partition keys,
  privacy boundaries, or lifecycle needs materially improve access cost.

### 4. Client PState queries versus query topologies

- [`module.clj:374-383`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L374)
  implements `get-file` as a query topology because it first reads the head
  version and then reads the corresponding version record on the same
  namespace partition. This keeps the dependent reads inside the module and
  returns one result. In contrast, [`module.clj:419-455`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L419)
  uses single client-side foreign PState selects for outcome, block-size,
  exact version, and a bounded sorted-map journal page.
- **Assessment:** those direct client selects are not inefficient
  multi-query fanout: each method makes one foreign PState operation, with
  the namespace key leading the path; `get-changes` performs one range scan
  capped by `limit`. Putting each one-read lookup in a query topology would
  not reduce network roundtrips and could add topology overhead. Keeping the
  head-plus-version dependent lookup together is the useful query-topology
  boundary.
- **Reference comparison:** the auction reference similarly uses direct
  foreign PState reads for local list/point lookups
  ([`module.clj:184-204`](../../challenges/auction-module/test-resources/auction_module/module.clj#L184)),
  while chat-app uses query topologies where work needs cross-partition
  enrichment or aggregation, for example room pages and unread counts
  ([`module.clj:417-494`](../../challenges/chat-app/test-resources/chat_app/module.clj#L417)).
  Chat's `get-online-members` client method deliberately loops over member
  chunks and invokes another query for each chunk
  ([`module.clj:564-580`](../../challenges/chat-app/test-resources/chat_app/module.clj#L564));
  that is a real multi-roundtrip pattern. HLD file sync has no analogous
  client-side fanout to consolidate.

### 5. Additional source-verified observations

Each item below was checked against the reference source and the published
skill references. Claims the references do not support are marked as open
questions rather than findings.

- **Partition alignment is implicit.** Two sets of local PState operations
  on `$$namespaces` must land on the same task:
  - the pre-agg `:ingress-seq` read and write
    ([`module.clj:290-292`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L290)),
    which run on the depot partition chosen by `(hash-by :ns-id)`
    ([`module.clj:247`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L247));
  - the post-agg reads and writes
    ([`module.clj:299-372`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L299)),
    which run where `+group-by *ns` routes them.

  The skill documents both as hash-of-key-mod-task-count placement:
  - [`core-concepts.md:75`](../../plugins/rama-skill/skills/rama/references/core-concepts.md#L75)
    covers `hash-by`;
  - [`aggregators.md:78-83`](../../plugins/rama-skill/skills/rama/references/aggregators.md#L78)
    says `+group-by` auto-hash-partitions by key.

  Both use the same key, so they align. The functional and performance
  suites exercise this at 2 and 4 tasks
  ([`functional_test_support.clj:633-637`](../../challenges/hld-file-sync/test-private/hld_file_sync/functional_test_support.clj#L633)).
  Line 294's comment notes the post-agg routing, but nothing notes that the
  pre-agg write also depends on the depot partitioner. Changing the depot
  partitioner or the `+group-by` key without the other would misplace state
  silently, because the skill warns that misalignment compiles and produces
  wrong results (SKILL.md Implementation Goal 5).
- **`:ingress-seq` cost and scope.** Every command pays one local read and
  one field write for its position
  ([`module.clj:290-292`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L290)).
  This includes replays, conflicting attempts, and rejections. The
  positions are compared only inside one microbatch's grouped vector
  ([`module.clj:295-298`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L295)).
  Cross-batch continuity is therefore not used for ordering. Keeping the
  counter in the PState makes it retry-safe, because a microbatch attempt
  resets PStates to their previous state before reprocessing
  ([`microbatch.md:128`](../../plugins/rama-skill/skills/rama/references/microbatch.md#L128)).
  This review does not propose a non-durable replacement: the skill
  references document no per-record ordering key for microbatch emits that
  could substitute for it.
- **Block-size read strategy differs from the plan, and the stated reason is
  not supported by the skill.** The plan proposed a single
  `(submap *hashes)` read with `{:allow-yield? true}`, with a point-read
  loop as the fallback
  ([`PLAN.md:276-277`](../../challenges/hld-file-sync/test-resources/PLAN.md#L276)).
  The reference uses the fallback loop
  ([`module.clj:226-241`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L226)).
  Its docstring says a suspended yielding select "reads a committed
  snapshot" and would miss blocks registered earlier in the same
  microbatch.

  The published references do not support that claim:
  - [`dataflow.md:195`](../../plugins/rama-skill/skills/rama/references/dataflow.md#L195)
    says emits with `:allow-yield?` are identical to emits without it,
    continuing on a stable snapshot;
  - [`pstate-schema.md:44`](../../plugins/rama-skill/skills/rama/references/pstate-schema.md#L44)
    says reads inside the owning topology see its uncommitted writes.

  Neither reference explicitly says whether that snapshot includes the
  attempt's uncommitted writes. Treat the docstring's rationale as an
  unverified claim, not as a Rama rule. The loop itself is correct and yields between reads. Any switch to
  `submap` with `:allow-yield?` must be validated by the unbarriered
  register→commit tests
  ([`functional_test_support.clj:433-477`](../../challenges/hld-file-sync/test-private/hld_file_sync/functional_test_support.clj#L433))
  and the full private suite.
- **Request records duplicate commit blocklists.** `request-record` stores
  the full command as `:payload`
  ([`module.clj:199-200`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L199),
  [`:279`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L279)).
  For an accepted commit, that payload contains the same blocklist of up
  to 1024 hashes that the version record stores
  ([`module.clj:350-353`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L350)).
  The contract requires this data. Replay detection compares the whole
  payload with `=`
  ([`README.md:104-113`](../../challenges/hld-file-sync/README.md#L104);
  [`module.clj:364`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L364)),
  and outcomes must be kept for the module's lifetime. The cost is storage
  growth per command, not a correctness problem. `get-outcome` projects out
  `:payload` with `submap`
  ([`module.clj:423-425`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L423)),
  so the client never receives it. The skill references do not say whether
  a nested `fixed-keys-schema` value in a subindexed map is read field by
  field, so this review makes no claim about server-side read savings.
- **Optional one-read `get-file` (not adopted).** The `file-head` query
  topology is idiomatic as written. The skill lists "combining multiple
  reads on the same partition into a single roundtrip" as a query-topology
  use
  ([`core-concepts.md:51`](../../plugins/rama-skill/skills/rama/references/core-concepts.md#L51)).
  There is also a single-read alternative:
  - Versions are written only as `1` on create or `head + 1` on update
    ([`module.clj:186-188`](../../challenges/hld-file-sync/test-resources/hld_file_sync/module.clj#L186)),
    and they are never deleted, so the largest key in a file's `:versions`
    map is its head.
  - Subindexed maps are sorted, and numeric keys sort as their in-memory
    values
    ([`pstate-schema.md:77-79`](../../plugins/rama-skill/skills/rama/references/pstate-schema.md#L77)),
    and `sorted-map-range-to-end` returns the last entries of a map
    ([`paths.md:486`](../../plugins/rama-skill/skills/rama/references/paths.md#L486)).
  - A client
    `foreign-select-one [(keypath ns-id :files file-id :versions) (sorted-map-range-to-end 1) ALL]`
    could therefore return `[head record]` in one read, with no dependent
    `:head` lookup and no query topology.

  Both forms take one client roundtrip. The alternative was not run against
  the private suite, including the `get-file` read ceilings in
  [`performance_test_support.clj:127-141`](../../challenges/hld-file-sync/test-private/hld_file_sync/performance_test_support.clj#L127).
  It is recorded as an option, not a recommendation.

## Reference idioms inspected

- **Auction module:** two depots partitioned by different entity keys and a
  stream topology for low-latency listing/bid writes; subindexed maps for
  growing listings/bidders/bids; explicit repartitioning before writing
  bidder-owned state ([`module.clj:69-114`](../../challenges/auction-module/test-resources/auction_module/module.clj#L69)).
  It demonstrates when separate event paths/stream processing fit, not a
  direct replacement for HLD's namespace-wide command order.
- **Chat app:** separates stream-visible writes from microbatch-derived
  views and routes/registers according to the ownership key; subindexes
  all user/room histories that can grow ([`module.clj:158-180`](../../challenges/chat-app/test-resources/chat_app/module.clj#L158)).
  Its query topologies perform page enrichment and cross-partition
  aggregation ([`module.clj:417-494`](../../challenges/chat-app/test-resources/chat_app/module.clj#L417)).
- **Published skill:** inspected
  [`SKILL.md`](../../plugins/rama-skill/skills/rama/SKILL.md), especially
  partition balance/colocation, subindexing, partition alignment, topology
  choice, and storage-I/O guidance at lines 49-68. The skill was not edited.

## Verification boundary

This change adds documentation only. The private functional and performance
suites were read but not run: the reference implementation was not
rewritten, and the review session could not execute Clojure. Section 5
therefore records the untested alternatives as options and does not report
them as validated. The full tests are available through the challenge's
`:test-private-harness` alias. Before treating any future implementation
edit as safe, run that full suite and `scripts/test_reference_packages.py`. No solver or
`bb run-challenges` run was performed.
