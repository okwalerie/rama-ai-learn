# HLD metrics pipeline idiom review

Reviewed at baseline `43abd3fccff8777d8995f8a29658f3c4c97df01a`.

## Scope and baseline check

The checkout contained the requested baseline commit, and this review branch
was created directly from it. This review covers the challenge README and
protocol, both private test namespaces and their support code, the reference
module, the auction-module and chat-app reference modules, and
`plugins/rama-skill/skills/rama/SKILL.md`.

One important correction to the proposed audit targets: the checked-in
`hld-metrics-pipeline` reference does **not** contain `:ingress-seq`,
`+group-by`, `+vec-agg`, a `loop<-` repartition, or a client-side distributed
query. Those constructs appear in neither its source nor its client methods.
The review below assesses the actual implementation at this baseline rather
than attributing those patterns to it.

## Findings

### 1. The single series-keyed depot and microbatch topology are deliberate and fit the ordering contract

**Evidence:** `challenges/hld-metrics-pipeline/test-resources/hld_metrics_pipeline/module.clj:100-128`
declares one depot hashed by `:series`, one `metrics` microbatch topology,
one event source, and dispatches the two event records with `<<subsource`.
There is no repartitioner after the microbatch source. `series-key` canonicalizes
the labels at lines 21-29, so append routing and the state key use the same
identity. The README requires same-series writes to take effect in client
invocation order (`challenges/hld-metrics-pipeline/README.md`, “Write ordering
and synchronization”). The private suite tests both advance-then-ingest and
ingest-then-advance ordering at
`challenges/hld-metrics-pipeline/test-private/hld_metrics_pipeline/functional_test_support.clj:405-427`.

**Assessment:** This is an appropriate idiom, not a defect. Keeping both write
kinds on one per-series partition avoids a cross-depot ordering protocol;
microbatch processing supplies the chosen durable processing model and the
client synchronization barrier. Splitting writes across depots or inserting a
fan-out/group/repartition stage would add coordination and could break the
specified order. A stream topology is an alternative only if lower-latency
visibility is a requirement strong enough to justify a different processing
model; it is not a drop-in performance improvement for this contract.

**Related idioms:** Auction uses distinct depots where writes have different
partitioning needs (`challenges/auction-module/test-resources/auction_module/module.clj:69-75`),
while chat uses one user-actions depot for actions whose common user partition
supports a local registration gate (`challenges/chat-app/test-resources/chat_app/module.clj:158-160,196-201`).
The right choice depends on ordering and colocation requirements, not a rule
that every write kind gets its own depot.

### 2. The single PState value has bounded per-series collections; top-level series growth is inherent in the contract

**Evidence:** `module.clj:103-123` stores clock/counters and raw/rollup state
under a canonical series key. Raw and 60-wide maps are sorted subindexed
structures; the 3600-wide map is deliberately a plain field. Expiry removes
only eligible entries in the advancing series (`module.clj:129-155`). The
README permits `advance-clock!` work proportional to data expired on that
series and says reads must not examine other series. The efficiency support
grows unrelated series and out-of-range data in
`test-private/hld_metrics_pipeline/efficiency_test_support.clj:142-190`.

**Assessment:** There is no single growing in-memory collection for all
samples: the per-series raw and 60-wide collections are constrained by
retention and subindexed for point/range access. The retained 3600 map is
small by its fixed width and retention window; `module.clj:212-213` scans and
sorts it in memory, an intentional constant-small-map tradeoff. Splitting the
three collections into separate PStates could be reasonable if the schema or
partitioning needs diverged, but here all are keyed by the same series and
frequently read or updated together. Extra PStates would add complexity and
potential reads without a demonstrated access-pattern benefit.

The outer keyspace can grow as new series are encountered. The contract
requires durable per-series clock and cumulative counters, and the README
provides no series-deletion or cardinality-cap rule. Evicting inactive series
would therefore lose required observable state. If a production product
later introduces explicit series deletion, cardinality limits, or archival,
that should be designed as a separate, specified lifecycle policy.

**Related idioms:** Auction subindexes user/listing and bidder collections
that can grow (`auction-module/module.clj:78-93`); chat similarly subindexes
message, reply, membership, and inbox collections (`chat-app/module.clj:172-179,255-277`).
Those examples support choosing indexing per collection size and query shape,
not subindexing every small map indiscriminately.

### 3. Range-bounded reads execute inside query topologies, not on the client

**Evidence:** `module.clj:184-195` and `197-214` define `query-raw` and
`query-rollup`. Each query hashes the series, reads its clock, clamps the
requested interval, selects only the relevant sorted range where applicable,
and returns from the query topology. Client methods invoke those topologies
once each at `module.clj:248-251`. `get-series-info` is a single keyed
`foreign-select-one` at lines 240-247. There are no client-side scans,
per-entry distributed selects, or client-side query loops in this reference.

**Assessment:** This is preferable to fetching the clock and collection in
separate client calls: each public query uses one network roundtrip while the
dependent local PState reads remain on the owning task. The skill explicitly
advises minimizing network roundtrips and using query topologies for distributed
reads. Replacing this with client-side selects would regress latency and could
make the reads less coherent. `get-series-info` is appropriately a simple
point select because it needs one fixed-size record and no repartitioning.

**Related idioms:** Chat keeps multi-step read logic in query topologies and
only aggregates after returning from distributed work
(`chat-app/module.clj:417-433,468-478`). Auction demonstrates direct client
PState selects for keyed reads (`auction-module/module.clj:184-204`); those
are appropriate when the read is a direct keyed lookup, not as a substitute
for a query requiring several dependent local reads.

### 4. The named aggregation / sorting / repartition patterns are absent here

The target implementation has no `+group-by`, `+vec-agg`, or `loop<-`.
`bucket-rows-in` uses `sort-by` only over the deliberately tiny 3600-wide
map (`module.clj:70-76,211-213`); raw and 60-wide results retain sorted-map
range order (`module.clj:184-214`). The query topologies each start with
`|hash *series` and perform local access on that same key. No distributed
fan-out is gathered with `+vec-agg`.

For comparison, chat uses `+vec-agg` after range-limited distributed query
work to gather page tuples, then sorts those returned tuples for its API
(`chat-app/module.clj:417-449`). That pattern is justified for a genuinely
distributed result; it would be unnecessary overhead for this implementation,
where each requested series and its state are colocated on one task. The
`get-online-members` client loop in chat (`chat-app/module.clj:564-580`) pages
through a large membership set with bounded batches and separate online
filtering. That is a special cursor/pagination strategy, not a pattern used
or needed by this metrics module's single-series range queries.

## Recommendation

Keep the current topology and state shape at this baseline. The apparent
anti-patterns in the requested audit description do not match the checked-in
reference. The implemented alternatives already align with the skill's
partition-alignment, subindexing, query-topology, and I/O-efficiency guidance.
The only notable whole-map scan/sort is over a retention-bounded 3600-wide
map, and splitting durable series state would not be justified without a
changed contract or measured bottleneck.

This branch intentionally contains this review document only; the reference,
README, protocol, private tests, and skill were not modified. No private test
suite was run because no implementation change was made.
