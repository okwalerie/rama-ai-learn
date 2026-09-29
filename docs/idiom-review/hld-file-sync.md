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
suites were read, but not run because no reference implementation rewrite was
made. The full tests are available through the challenge's `:test-private-harness`
alias; if a future implementation edit is made, run that full suite and the
reference-package checks before considering the change safe. No solver or
`bb run-challenges` run was performed.
