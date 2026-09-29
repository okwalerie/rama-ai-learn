# Idiom review: HLD stock exchange reference

## Scope and verdict

Reviewed at baseline `43abd3fccff8777d8995f8a29658f3c4c97df01a`:

- `challenges/hld-stock-exchange/README.md`, its protocol, the complete private
  test namespace, and `test-resources/hld_stock_exchange/module.clj`.
- The reference modules in `challenges/auction-module` and
  `challenges/chat-app`, and `plugins/rama-skill/skills/rama/SKILL.md`.

**Recommendation: keep this as a review-only change; do not replace the
reference module in this pass.** The existing implementation has a coherent
per-symbol ordering and indexed-state design, and the private suite stresses
substantial edge cases and bounded RocksDB work. Improving the batch staging
and revalidating ordering, retry, and resource guarantees safely would be a
module redesign, not a small idiom cleanup. This review does not claim a
replacement has passed the private suite.

## Findings and alternatives

### 1. One symbol-keyed command depot and one microbatch topology

**Evidence:** `challenges/hld-stock-exchange/test-resources/hld_stock_exchange/module.clj:76-80` declares one depot partitioned by `:symbol`,
then consumes it in the `core` microbatch. `challenges/hld-stock-exchange/test-resources/hld_stock_exchange/module.clj:87-100` keeps state
under a symbol key. The per-symbol partition is a good fit: matching and
sequencing for a symbol need one serialized owner, while distinct symbols can
be processed on different tasks. Do not repartition individual orders across
tasks without redesigning the atomic book operation and its ordering contract.

The microbatch choice also fits the skill's stated default for throughput and
cross-partition atomicity (`plugins/rama-skill/skills/rama/SKILL.md:63`). A stream topology is a plausible
alternative only if lower command-processing latency outweighs batch
throughput and the append/ack/barrier contract is deliberately preserved. The
auction reference illustrates using a stream for promptly updated state
(`challenges/auction-module/test-resources/auction_module/module.clj:77-100`); it is a contrast, not evidence that
stream is automatically better for this order book.

### 2. Batch regrouping, persistent ingress sequence, and sorting

**Evidence:** `challenges/hld-stock-exchange/test-resources/hld_stock_exchange/module.clj:101-111` reads and increments `:ingress-seq` for
each event, tags the command with that position, gathers each symbol's
commands using `+group-by` and `+vec-agg`, then sorts the gathered vector.
`challenges/hld-stock-exchange/test-resources/hld_stock_exchange/module.clj:112-123` processes the sorted list sequentially and handles
request replay/conflict before applying command effects. The intent is sound:
commands for one book must not be reordered by a grouping/aggregation step.

The cost is one read/transform of durable `:ingress-seq` per command, plus
vector materialization and sorting within the batch. The depot cap of 1,000
records (`challenges/hld-stock-exchange/test-resources/hld_stock_exchange/module.clj:78`) bounds this staging group, so it is not an
unbounded-history scan, but it still adds per-command storage work and
batch-local memory/CPU. Prefer a topology/event path that naturally processes
each symbol's partition in order if that can be shown to preserve per-client
same-symbol order across batches; otherwise retain explicit sequence tags and
sorting. Do not simply remove the sort: ordering is a correctness property.

The `auction-module` stream source at `challenges/auction-module/test-resources/auction_module/module.clj:95-114`
demonstrates direct event processing with local state updates and partition
hops. For a microbatch version, an order-preserving per-key grouping strategy
could avoid a separate persistent ingress counter, but should only replace
this mechanism after a test proves ordering under multiple batches and the
1,040-command scenario in
`challenges/hld-stock-exchange/test-private/hld_stock_exchange/private_test.clj:247-267`.

### 3. PState layout: one symbol record, but nested collections are indexed

**Evidence:** `challenges/hld-stock-exchange/test-resources/hld_stock_exchange/module.clj:87-100` declares `$$symbols` with a fixed-key value
per symbol. The potentially large `:orders`, `:ask-levels`, `:bid-levels`,
`:ask-queue`, `:bid-queue`, `:trades`, and `:requests` maps all use
`{:subindex? true}`. The nested queue is indexed at both price and order-seq
levels (`challenges/hld-stock-exchange/test-resources/hld_stock_exchange/module.clj:85-86`). This is materially different from reading and
rewriting a whole Clojure map as the value for a symbol: the paths address
individual entries and bounded sorted ranges.

The code does repeatedly read small point/range values while matching
(`challenges/hld-stock-exchange/test-resources/hld_stock_exchange/module.clj:137-165`) and makes a point read for the maker order and level;
that is necessary work per produced trade. Its loop stops at the first
non-crossing best level and iterates queue heads, so matching is proportional
to trades produced rather than scanning all resting orders. Depth uses a
bounded price-level range (`challenges/hld-stock-exchange/test-resources/hld_stock_exchange/module.clj:242-246`) and the trade tape uses a
sequence range capped by `limit` (`challenges/hld-stock-exchange/test-resources/hld_stock_exchange/module.clj:247-252`). Those are idiomatic
subindexed-map patterns for the stated workload.

Do not split the state into more PStates merely to make the declaration look
smaller: all those values are still keyed by symbol and the split could add
reads or make an atomic order update harder to reason about. A separate PState
is worth considering only if it changes partitioning or access cost for a
real query. Retaining every order, request outcome, and trade is required by
the README durability contract; cleanup is not an acceptable optimization.

The private tests exercise history growth directly: `bounded-work-and-deep-page`
compares read/iterator/write counts after the same symbol histories grow
fourfold (`private_test.clj:80-202`), checks a depth level with a growing
number of resting orders, and reads a five-trade page near the end of a larger
tape. This supports the conclusion that the data is not being consumed as a
whole unindexed value. It is evidence from those tested operations, not a
general proof of every possible workload.

### 4. Direct client-side PState reads versus query topologies

**Evidence:** the wrapper performs one `foreign-select-one` per direct lookup
or bounded range (`challenges/hld-stock-exchange/test-resources/hld_stock_exchange/module.clj:236-252`). These calls route using the symbol
key and retrieve only the requested order/outcome, up to 50 levels, or up to
500 trades. For these single-shard reads, direct foreign selects are
reasonable; moving them into query topologies does not inherently remove a
network roundtrip and may add topology machinery.

Use a query topology when a request needs multiple partition hops, fan-out,
joins, aggregation, or a composed bounded page. `challenges/chat-app/test-resources/chat_app/module.clj:435-494`
shows query topologies for paginated reads that join records across PStates
and aggregate results; its wrapper invokes those queries at
`challenges/chat-app/test-resources/chat_app/module.clj:581-590`. The same wrapper uses direct PState selects for
simple key lookups and a bounded member page (`challenges/chat-app/test-resources/chat_app/module.clj:546-563`),
which reinforces that these are complementary idioms, not mutually exclusive
rules. The skill calls out reducing client network roundtrips
(`plugins/rama-skill/skills/rama/SKILL.md:36-41`); apply that to multi-read composition, not as a blanket ban
on direct, bounded one-call selects.

### 5. Performance and correctness check-list for a future rewrite

Before changing the implementation, preserve these proof points:

1. Keep all commands for a symbol on the same partition and prove order
   through batch boundaries, including the mixed-command test at
   `private_test.clj:247-267`.
2. Preserve structural validation before request-id handling and replay /
   conflict handling before business validation, per README validation and
   idempotency clauses; malformed replays must not mutate conflict counts.
3. Keep all growing maps subindexed and all query ranges bounded. The
   README's resource guarantees and `private_test.clj:80-202` check history
   growth, not just functional output.
4. Preserve one atomic book transition for matching, order state, levels,
   queue, trade sequence, and durable outcome. The matching/retry coverage is
   in `private_test.clj:36-79`; cancellation, price priority and level
   recreation are in `private_test.clj:204-230`.
5. Run both private suite aliases from the challenge directory against the
   reference source (and `scripts/test_reference_packages.py` only if package
   isolation files are changed). No source-package changes are part of this
   doc-only review, so that package-isolation script was not run.

## Test and change status

This is documentation-only. No Rama implementation or challenge contract
files were edited, so the private implementation suite was not run. The
package-isolation script is not applicable to a change limited to this review
document. No solver or `bb run-challenges` run was performed.
