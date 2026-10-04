# OptChat memory challenge packaging plan

Marker: `optchat-challenge-phase1`
Status: revised Phase 1 design; F2 resolved by bounded public pages; F1 strict-template exception explicitly selected by parent for this simplified challenge. Independent revalidation required; no implementation or tests executed.
Worktree: `/tmp/optchat-rama-ai-learn`, branch `codex/optchat-challenge`.

## Scope and sources

Authoritative sources read: `/tmp/optchat-rama-plus-agents/docs/optchat/IMPLICIT_SPEC.md` (entire canonical contract), `SOURCE_OPTCHAT.md` (subordinate attachment, especially binary nodes, free nodes, due ordering and zoom), `DECOMPOSITION.json` (challenge boundary). Production stages are unfinished; this plan derives from the full contract, not assumptions about their implementations. Compare the eventual fixture with completed production semantics before delivery.

Also read repository AGENTS; Rama SKILL.md; phase-1-plan; app-design; query-topologies; pstate-schema and pstate-schema-clojure-api; patterns; depot-design; unique-ids; microbatch; stream; artifact-plan and artifact-plan-validation. Consulted bank-transfer-module README/protocol/deps, attack public test_support (function fixture, useful only for minimal exposure), and `lib/harness/src/rama_challenges/harness.clj`. Historical simple-streaming and bank-transfer public test_support paths do not exist here. `challenges/optchat-memory` does not exist; initial git status was clean.

Promise: edit only this planning artifact, not implementation, runner, skills, spikes or live resources. Readback and scoped diff are evidence. Independent Phase 2 validation follows.

## Explicit simplifications

One implicit owner and multiple named chats. No auth, MCP, deployment, live providers, subscription clients, worker completion/failure API, billing or background jobs. Deterministic extraction is the only policy. Compact returns a completed bounded result synchronously, with a durable retry key. Append eagerly creates its leaf representation; missing parent work is observable.

Structured logical view transported in fixed pages of at most 64 parts, with revision-checked continuation and no presentation options. A simple fixed serialization exists solely for byte accounting. Fixed node target 512 UTF-8 bytes and view content budget 2048 bytes. No history-length cap except signed-long index capacity. The budget is soft; no constant-size storage/full-logical-view or hot-chat distributed-throughput promise. These simplifications must be PUBLIC before tests depend on them. The maintainer design below must NOT appear in README/test_support or solver snapshot.

## Proposed public contract (normative draft)

Namespace `optchat-memory.module`, `create-module` returns `{:module <RamaModule> :wrap-client (fn [ipc] <client>)}`. Client implements `OptchatMemory` and `harness/Synchronizable`. Internal names/design unrestricted. Accepted originals, representations, covering and retry outcomes are durable Rama state, surviving client recreation, worker restart and same-schema module update; volatile state cannot be their sole copy.

### Common types and errors

Chat IDs and request keys are nonempty strings <=128 UTF-8 bytes. Text is well-formed Unicode <=65,536 UTF-8 bytes; empty is valid. Do not normalize Unicode/newlines. Kind is `:user :talk :tool :echo :note`. Timestamp is a caller-supplied signed-long epoch-millisecond integer, preserved unchanged. Message indices and starts are nonnegative signed-long integers. Counts are positive power-of-two signed-long integers; all arithmetic is overflow-safe. Integer arguments are accepted by mathematical integer value and range, not exact JVM wrapper class; floating-point and ratio values are invalid even when numerically integral.

Every method returns `{:ok value}` or `{:error code}`; codes are `:invalid-argument`, `:not-found`, `:idempotency-conflict`, `:capacity-exhausted`, `:stale-view`. Documented invalid/resource/conflict cases do not throw. Validate arguments before resource lookup, then check idempotency. Unknown message fields invalid. Missing chat is not-found except valid append creates it. Valid but incomplete range/missing ID is not-found. Misalignment/non-power-of-two count is invalid-argument. All errors leave accepted state unchanged.

Successful writes return only when reads can observe accepted changes. Tests call wait-for-processing after writes but separately test immediate visibility. Reads never mutate state or initiate work.

### append! [client chat-id request-key message]

Message has exactly `{:kind k :text s :timestamp t}`. Allocate contiguous indices 0,1,... atomically with acceptance; return `{:ok {:index i}}`. First valid append creates chat. Same chat/key and equal payload returns original index without mutation; any changed field conflicts. Keys scoped by chat and operation; append/compact key spaces separate. Distinct concurrent writes get unique acceptance-ordered IDs (no caller scheduling order promised). Identical concurrent retries accept once. Cancellation/lost acknowledgement cannot erase acceptance.

Append atomically adds the exact original, ready leaf representation, and final view part. It does not compact existing parts. Earlier originals/timestamps never change. Maximum accepted message count T is Long.MAX_VALUE (9,223,372,036,854,775,807), so the final allocatable index is Long.MAX_VALUE-1. At T=Long.MAX_VALUE a new valid append returns :capacity-exhausted; an existing identical key still returns its original index and a conflicting key still conflicts. Check the receipt before capacity. Never allocate index Long.MAX_VALUE, whose exclusive endpoint would exceed the watermark domain.

### view [client chat-id cursor]

cursor is nil for the first page, or exactly `{:revision R :start s}`: R a nonnegative arbitrary-precision integer, s a nonnegative signed-long integer. Return one coherent page:

`{:watermark T :revision R :parts [representation ...] :covered-start s :covered-end e :next-cursor nil-or-cursor :content-bytes C :rendered-bytes D :budget-bytes 2048 :over-budget? b :pending-nodes P}`.

A nil cursor starts at 0 in the current revision. A non-nil cursor must match current revision, otherwise `{:error :stale-view}`; no old snapshot is retained. Check cursor shape/types first, chat existence second, revision third, then range/boundary. At matching revision, s>T or a start inside a current part is invalid-argument. s=T is valid and returns empty parts, covered-start=covered-end=T and nil continuation. Otherwise return the next min(64, remaining part count) complete parts. covered-end is the exclusive end of the final returned part. next-cursor is `{:revision R :start e}` exactly when e<T, else nil. Unknown cursor fields are invalid.

All metadata/byte totals describe the whole logical covering at R, while only `[covered-start,covered-end)` is included in this page. A partial page never claims to contain the entire chat. Follow continuations only at the same R to reconstruct a complete view. Any new append or compact doing an action invalidates old cursors; duplicate/no-op/error/read does not. On stale-view the caller discards that traversal and may explicitly restart at nil. Each call completes bounded read work; a complete traversal is guaranteed to finish only while the revision stays unchanged for its pages. Continuous writes can prevent completion across pages; no automatic unbounded retry loop or historical-snapshot guarantee exists.

T is accepted message count. Byte totals and revisions are nonnegative arbitrary-precision integers; they never wrap or impose an earlier history cap. Revision starts at 0 before the first mutation, increments once per new append, once per compact call doing at least one action. Duplicate/no-op/error/read does not advance it. The logical covering, reconstructed from all pages of one revision, covers exactly `[0,T)` oldest-first, without gaps/overlap. Once merged, parts never split. P counts existing complete binary addresses without ready representation, including those waiting on dependencies; each build decrements P once, merge does not. An empty logical covering would have zero bytes (no explicit empty-chat creation API). A terminal empty page of a nonempty chat still reports that chat's whole-covering byte totals.

Representation is exactly `{:start s :count n :text text :provenance :verbatim|:extractive :policy "extract-v1" :content-bytes bytes :omitted? boolean}`. Text <=512 UTF-8 bytes. Verbatim means no omissions in source ancestry, not unformatted raw text. Extractive/omitted propagates from either child even when concatenation fits. No generated/model metadata.

C is the sum of text UTF-8 lengths across the whole logical covering, not just this page. D is UTF-8 length of concatenating decimal start, `+`, decimal count, `|`, text, LF for each part, with no wrapper and no newline replacement. Over-budget is exactly C>2048. This is a soft content budget, not rendered-byte cap.

### zoom [client chat-id start count]

Require aligned start (`start mod count=0`) and completely existing range. Count 1 returns `{:ok {:index start :kind k :text exact-original :timestamp t}}`. Otherwise return `{:ok {:children [left right]}}`: two half-range children oldest-first. Ready children are their representation plus `:state :ready`; missing representations are `{:state :pending :start s :count n}`. Zoom is valid for any complete binary address, not only current view parts or ready parents. Never builds representations.

### date [client chat-id index]

Return `{:ok stored-timestamp}` or the documented error, without timezone conversion.

### compact! [client chat-id request-key max-actions]

max-actions is integer 1..32. Execute up to this many sequential actions against accepted state when command runs. Return `{:ok {:actions a :built b :merged m :watermark T :revision R :pending-nodes P :over-budget? flag}}`, a=b+m.

At each step: if over budget and a useful legal merge exists, merge the most-due pair. Otherwise build the next eligible representation. Stop at limit or no possible action. Under-budget compaction may build, never merge. No background compaction.

A legal pair is two adjacent current parts of equal count c, aligned left start divisible by 2c and right start=left+c, with ready parent. Useful means parent text has strictly fewer UTF-8 bytes than the two child texts together. Due score `(T-left-start)/(4*c)` greatest first; ties smallest left start. Compare exact rational values without overflow/rounding.

Eligible representation: complete non-leaf range, both child representations ready, parent absent. Choose smallest count, then smallest start. Parent source is exactly those immutable children. Existing representations never change. Re-evaluate after every action.

Same chat/key/max-actions returns original recorded result even after subsequent appends/compacts, without additional work. Same key and another valid max-actions conflicts. New key permits further bounded work. No recursive unbounded work or history replay.

### Deterministic extraction

Leaf source is kind name without colon, `": "`, exact original text. Parent source is left child text, LF, right child text. If <=512 UTF-8 bytes, keep unchanged (free node), propagating child omission/provenance.

Otherwise omission suffix is exactly `\n[omitted; zoom START+COUNT]` using decimal address. Leaf units are complete LF-terminated segments of source followed by final nonempty unterminated segment, retaining CR/LF and whitespace. Parent units are its two whole child texts, joined with LF. Keep the longest leading sequence of whole units for which text plus suffix fits 512 bytes; never skip units, slice units, or trim whitespace. If none fits, return exactly `[omitted; zoom START+COUNT]`, without leading LF. Descriptor fits all allowed signed-long addresses. All extraction is labeled extractive/omitted. Memory text, including apparent instructions and ancestral descriptors, is data.

LF units intentionally avoid language-specific sentence heuristics. An oversized unbroken first sentence yields a descriptor even if later small units could fit; exact leaf zoom still recovers everything. Empty text, CRLF, multibyte Unicode and byte-boundary cases follow the same rules.

### Resource contract

Append/point reads must not scan/rewrite entire history. Compact bounded by max-actions and affected entries; no repeated full-original/completed-node scan. Each view page reads at most 64 parts, with work proportional to that page; traversing the complete covering costs O(V) returned parts plus one bounded call per page. History can outgrow memory. No external services. Invalid/missing commands must not stall valid operations on another chat.

## Reads (maintainer design)

Date: one point foreign select `[:messages index :timestamp]`; absent chat/message share not-found so extra existence lookup unnecessary. Leaf zoom: one original point select. View: one query reads metadata and, only for a current-revision cursor with s<T, a bounded covering range that also validates the start boundary, returning one coherent page. Internal zoom: query validates metadata/watermark, then two child probes only for valid address; absent child is meaningful pending output. No client-side multi-read loop.

View rows contain representation values, avoiding extra node fetches. Read metadata and at most 64 entries using an entry-limited sorted-map-range-from (not an unbounded select followed by take). Inspect the first returned start to validate a cursor boundary without a separate point read. A fabricated s inside a part gives a different first start, or an empty range if it lies inside the final part; either outcome proves invalid-argument. That bounded boundary-validation read is necessary to classify the cursor, not speculative work that can be skipped from metadata alone. s=T skips the range. A bounded page has at most 32 KiB of representation text, roughly 40 KiB with fields before engine/serialization overhead. Keep this small metadata+page segment on one task without yielding or a partition hop until the result is assembled; there is no loop over the rest of V. Return to the scheduler between calls. Missing metadata/stale cursor skips the range. Page-size boundedness is the selected alternative to a large yielding traversal, not permission for an unbounded synchronous read.

## Writes

One chat-ordered command depot with append/compact events. One stream topology because both operations return computed accepted outcomes with ack coordination. No independent asynchronous concern remains.

Append: validate, inspect receipt, compare exact original payload via stored index, allocate watermark, write original/leaf/part/metadata/receipt in one same-task transaction. Compact: inspect receipt, perform <=32 actions, commit changed indexes/nodes/covering/counters and complete result receipt atomically. No yield/repartition between check and mutation. Leaf work bounded by 65,536 bytes; parent input <=1025 bytes. No provider calls.

## PState design

One `$$chats: {String Chat}`, Chat fixed-keys schema. SI denotes subindexed map. All record shapes below are concrete fixed-keys schemas, not Object/untyped maps. Keyword below means the resolvable class clojure.lang.Keyword. Normalize validated bounded integer inputs to Long before typed writes; normalize cursor revisions to java.math.BigInteger for comparison. Public output remains integer-valued. Nippy 3.2.0 source in the installed dependency contains distinct BigInteger/BigInt serializers and BigInteger reconstruction; use java.math.BigInteger consistently, and verify an actual Rama PState/query roundtrip during build rather than treating source inspection as an executed test.

- meta: watermark/pendingNodes Long; revision/contentBytes/renderedBytes java.math.BigInteger (concrete scalar schema; initialize with java.math.BigInteger/ZERO, explicitly normalize every persisted aggregate/revision and receipt revision with biginteger after arithmetic; never write a clojure.lang.BigInt or Long into this exact-class schema). Counts of stored bytes and revisions must not overflow before signed-long index capacity.
- messages: SI `{Long Original}`, Original `{kind Keyword, text String, timestamp Long}`.
- appendReceipts: SI `{String Long}` (index for exact original comparison, avoiding duplicate text storage).
- nodes: SI `{Long -> SI {Long Representation}}`, keyed count then start. Representation fields use Long/String/Keyword/Boolean from public contract.
- parts: SI `{Long Representation}`, keyed start, persisted current covering with denormalized texts.
- eligible: SI `{Long -> SI {Long Boolean}}`, count then start; only absent parents with ready children.
- mergeCandidates: SI `{Long -> SI {Long Candidate}}`, child-count then start, Candidate `{parentTextBytes Long}`. Only adjacent useful pairs with ready parent.
- eligibleLevels and candidateLevels: fixed <=63-level flags; skip empty levels. No unbounded non-subindexed collection.
- compactReceipts: SI `{String CompactReceipt}`; maxActions/actions/built/merged/watermark/pendingNodes Long, revision java.math.BigInteger, overBudget Boolean.

When a node is created, inspect its one sibling and insert its absent parent into eligible only if both ready. Creating a leaf increments complete-node count by `1+trailing-zero-count(new watermark)`; leaf already ready, so pending increment is trailing-zero-count. Building parent decrements once. Total complete binary nodes is sum floor(T/2^k), including leaves, providing an arbitrary-precision independent oracle (this total is not a Long metadata counter). Pending nodes are bounded by T-popcount(T), which fits Long; intermediate oracle and endpoint arithmetic use arbitrary precision.

At a fixed candidate level oldest start has greatest due. Compare at most 63 nonempty level heads, no history/covering scan. Append/build/merge updates only local adjacency and immediate parent eligibility. On building `(s,n)`, remove that eligible entry, probe its one sibling to enqueue its absent complete parent (unless n is the largest supported power of two), and insert candidate `(n/2,s)` only if both half-range children are current parts and the new parent strictly reduces bytes. On merging `(s,c)` with `(s+c,c)`, remove candidate `(c,s)` and the two parts, insert part `(s,2c)`, then inspect its unique aligned sibling part and ready parent to insert the next-level candidate if useful. No other candidate can overlap a consumed part: aligned same-level siblings are unique. On append, add the leaf and inspect its sibling for eligibility; its new parent cannot already exist. Empty per-level maps clear their level flag, insertion sets it. Every action updates bytes using the removed and inserted rows, never rescans parts. Perform endpoint/parent arithmetic with arbitrary precision before checking allowed address range; never compute an overflowing Long `2c` or `4c`. These transitions and flags commit with the receipt; implementation validation must test each transition.

Alternatives: serialized whole Chat saves seeks but append copies O(history), disallowed. Indexed nodes plus covering addresses saves <=512 bytes per part but adds p node point reads to each p-part page; p=64 adds about 32ms to the metadata+range read, versus roughly 1.32ms for the denormalized page including metadata and iterator work. Chosen denormalized covering pays <=512-byte write per changed part, justified by view throughput. Do not denormalize full originals into retry receipts.

## Depots, topologies and atomicity

`*commands` partitioned by chat ID; one `commands` stream owns $$chats. Stream selected for returned acceptance result/ack coordination, not test synchronization. Microbatch could return via durable receipt polling, but adds at least one read and processing latency; no single-digit-ms SLO is claimed.

All writes in one task transaction, no internal depots/multipartition write chains. Durable request receipts deduplicate counters and edits; retry after committed mutation returns old outcome, retry before commit performs once. Concurrent keys serialize. Invalid arguments return errors without poisoning topology retries. No generated random IDs. New wrappers don't hold correctness state. Foreign wrappers use full `:ack`, extract the `commands` topology's `ack-return>` structured result, and never substitute `:append-ack`. Both initial acceptance and deduplicated paths return the same stored result. Validate malformed routing keys before depot append; validate remaining fields before any mutation, with structured errors returned rather than thrown.

## Query topologies

View examples: missing/stale/terminal cursor -> metadata probe only; 20-part first page -> metadata + one 20-entry range; 200-part first page -> metadata + one 64-entry range. A complete stable traversal of 200 parts takes four calls with 64,64,64,8 entries: eight seeks and 200 iterator steps. Reads variable by existence/cursor; branch dynamically, not padded empty scans. Internal zoom: incomplete range -> metadata only; complete count 4 -> metadata + two child probes, each ready or pending. Date/leaf remain single direct selects. Every returned page is one revision; continuation explicitly checks that revision before reading any parts. No full-view server convenience method may reintroduce unbounded traversal.

## Partitioning efficiency

Dominant page read wants metadata and its <=64 consecutive covering rows colocated, f(chat,page)->one task. A chat sequencer is needed for contiguous acceptance and deterministic compact decisions, but immutable originals and immutable representations are genuinely stripeable; serialization alone does not justify their colocation. The selected simple arrangement keeps everything under chat ID and therefore fails the generic hash/hot-key indicator. Direct placement of a whole chat, even load-aware, does not fix this.

The parent selected the colocated arrangement as a **project-scope exception for the requested simplified single-owner learning challenge**, after the explicit conflict was raised. This is not a strict-template PASS, not user approval of a new distributed throughput requirement, and not permission to weaken durability, retry safety, read bounds or coverage. One large chat still concentrates disk and command work. Preserve this exception visibly in subsequent validation. The canonical one-person learning rationale does not make skew disappear.

### Constructed alternatives and costs

Logical reads below count metadata/point/range navigations; fixed-depth physical index overhead, replication, network bytes and batched write optimizations are excluded, not called zero. A read seek is estimated at 0.5ms and an iterator entry at 0.005ms; writes are counted separately without inventing a write-latency constant. These are comparisons, not measurements or an offered workload SLO.

A. **Selected colocation.** New append: receipt miss + metadata + sibling probe = about 3 logical reads (1.5ms), then original, receipt, leaf, part and metadata = 5 writes, plus <=1 eligibility entry and bounded level flags. Duplicate append: receipt + original = 2 reads, no writes. Date/raw zoom: 1 read (0.5ms). Internal zoom: metadata + 2 nodes = 3 reads (1.5ms). Nonterminal page of p<=64 parts: 2 reads+p iterations (1+0.005p ms). Build action reads its 2 immutable children and bounded eligibility/candidate state; merge reads its 2 parts/parent and bounded candidate state. Up to 63 candidate level heads per action remain as already designed. All command writes share one transaction/receipt, with no cross-task retry stages.

B. **Striped immutable history, colocated control/covering.** Place original chunks by a computed task f(chat,floor(index/256)); use 256-message chunks round-robin across N tasks with a chat-derived offset. Place nodes by f(chat,count,floor((start/count)/256)), storing contiguous addresses within each chunk. This distributes large histories and node levels; date/raw zoom can compute their data destination without an all-task fan-out. There is no placement lookup in this variant. It does not distribute growing covering, request receipts or eligible/candidate control indexes, and compact/acceptance still serialize.

For stream publication, a concrete safe protocol must reserve an index and exact request identity durably at the sequencer (not yet accepted), write immutable data at its destination idempotently, return a durable completion to the sequencer, and only then publish watermark/leaf/part/final receipt atomically. Later indices may be reserved but cannot publish past a hole. A failure after remote write but before publication leaves staged data, not an accepted original; replay resumes the reserved request, never allocates a new index. Conflicting same-key requests compare the durable reserved identity. Public date/raw zoom must first check the accepted watermark before reading the remote data, otherwise staged index i leaks before the atomic append is accepted. Thus point reads become 2 reads and an inter-task dependency (about 1ms aggregate versus 0.5ms); checking a per-record committed flag instead cannot atomically switch remote visibility with the sequencer watermark. Final full ack follows publication, not reservation.

Compared to A, each append adds at least 1 reservation write, 1 reservation read on completion, and 2 directed partition transfers (outward data and return publication), plus durable recovery tracking; an exact in-flight payload copy may cost B bytes until publication. The original write is moved, not duplicated permanently. A duplicate append needs its remote original for exact comparison unless receipts retain a duplicate full payload. One node build moves its immutable node write remotely and may read 2 remote child nodes; eligibility/publication needs the same idempotent staged-then-publish discipline. Remote ready nodes need acceptance/representation-version gating on zoom; node existence alone is not readiness. A <=32-action compact must either stage the entire command and atomically publish its resulting covering, or change the public atomic/retry contract; per-action receipt tricks do not preserve a single coherent command revision. Exact candidate selection still needs colocated control or a distributed head reduction.

Microbatch is a viable alternative implementation of B: one topology atomically commits multi-partition originals/nodes/control and final receipts, avoiding the manual reservation/publication protocol for visibility. Group commands by chat in acceptance order; preserve each compact's <=32 sequential decisions and each outcome. The caller appends then polls the durable receipt until commit, adding >=1 receipt read per write plus polling/wakeup latency; normal processing has roughly >=300ms microbatch latency, within the canonical hundreds-of-ms-to-seconds target. This alternative must not be rejected on test synchronization. Multi-read queries still need revision validation across tasks to avoid mixing successive committed microbatches. It distributes payload disk but retains O(H+V+K) colocated control and does not satisfy the absolute no-hot-key indicator. It is a useful production option if actual storage pressure warrants it, not a strict-template cure.

C. **Striped covering/control too.** With stateless row striping across S tasks, a page requires metadata plus S candidate range reads and merge to select its first 64 rows; empty fan-out probes count. At S=128 a page costs at least 129 seeks (~64.5ms before iteration) versus 2 (~1ms), and up to 64S candidates may be inspected. Cost grows with task count and fails the other strict gate. Ordered bounded-size chunks avoid all-N fan-out: maintain a durable ordered chunk directory, each chunk on one task, and versioned rows. Each chunk segment needs at least a directory navigation and data seek plus the initial metadata read: >=3 seeks for a one-chunk page versus 2, more on boundary crossing. Direct placement requires a stable task assignment per chunk; load-aware assignments add placement reads/writes, and moving a chunk needs a durable migration/version handshake. Append then touches sequencer, chunk assignment, chunk data and revision publication. Merges crossing chunks require multi-partition publication or a microbatch. Immutable historical revisions or version checks must protect page assembly. Receipt and candidate indexes also need distribution/coordination to avoid merely relocating the original unbounded hot key. This is a substantially different distributed indexing exercise; no complete optimal implementation or strict balance proof is claimed here.

For H=1,000,000 at B=1024 bytes, A concentrates ~1.024GB original text plus up to ~1.28GB node records (2H*640), <=0.64GB covering and receipts/control on one task. B can spread originals to roughly 8MB/task at N=128 and node payloads to roughly 10MB/task, ignoring chunk rounding/variable sizes, but leaves <=0.64GB covering and O(H+K) receipts/control at the sequencer. Neither equates a necessary O(1) watermark/ordering point with necessary colocation of all history. For one append + one 64-part page + one raw read, A has 6 logical reads and 64 iterator steps (~3.32ms aggregate); stream B has >=8 reads and the same iterations (~4.32ms), >=1 extra write and two append transfers, before remote-node/control overhead. No operation-frequency mix was supplied; this illustrative bundle is not workload evidence. Relative storage relief can be real despite higher aggregate read work.

**Decision:** A best preserves the requested narrow challenge and lowest constructed per-operation I/O. B is the first substantive alternative if distributed historical storage becomes part of scope; C is needed before claiming general hot-chat load balance, with further design/validation. The strict template cannot be honestly passed merely by choosing B, C's name, or a direct partitioner. The parent's scope exception selects A; independent validation must still enumerate that strict failure separately and check every other gate. No public solver requirement will mention stripes, partitioners, internal indexes, receipts or publication stages.

### Dominant page-read cost table

Illustrative categories, not measured frequencies. Proportions sum to 1 at every N. Invalid structural arguments are rejected before I/O and outside this valid-call illustrative mix; missing/stale/terminal are each meaningful metadata outcomes. Successful continuation pages use the same bounded reads as first pages.

|Tasks|Category|Proportion|Total seeks/op|Iterator reads/op|
|---|---|---:|---:|---:|
|1|missing chat|0.05|1|0|
|1|stale cursor|0.03|1|0|
|1|terminal cursor|0.02|1|0|
|1|20 remaining parts|0.70|2|20|
|1|>=64 remaining parts|0.20|2|64|
|16|missing chat|0.05|1|0|
|16|stale cursor|0.03|1|0|
|16|terminal cursor|0.02|1|0|
|16|20 remaining parts|0.70|2|20|
|16|>=64 remaining parts|0.20|2|64|
|128|missing chat|0.05|1|0|
|128|stale cursor|0.03|1|0|
|128|terminal cursor|0.02|1|0|
|128|20 remaining parts|0.70|2|20|
|128|>=64 remaining parts|0.20|2|64|

At N=1,16,128: weighted seeks 1.9; weighted iterator reads 26.8; aggregate and single-task illustrative critical path 1.9*0.5+26.8*0.005=1.084ms. Flat cost is evidence of no cluster-size fan-out, not balanced hot-chat load. Full traversal of V>0 stable parts costs 2*ceil(V/64) seeks and V iterations, not 2 seeks regardless of V. For V=1,000,000, 15,625 calls sum to roughly 20.625 seconds of estimated disk work (15.625s seeks+5s iterations), plus network/serialization. No individual page iterates over a million rows; clients may stop after a page. This is the explicit I/O price of bounded public pages without historical snapshot machinery.

## Design decisions and state primitive selection

All source/derived/receipt state durable PState. No TaskGlobals, external database, cache rebuild, or read-time history folding. Count-level flags bounded by signed-long range; every collection growing with messages/commands subindexed.

Per append: bounded source extraction, one original/receipt/leaf/part, metadata, immediate sibling/neighbor maintenance and <=63-level arithmetic. Per compact action: bounded node/neighbor edits and at most 63 candidate heads. No entire-history rewrite. Same-task atomic groups are small; implementation must not introduce unbounded loops.

## Resource usage analysis

H messages, average original UTF-8 B<=65,536, V parts, K compact keys. Original about B+64 bytes plus engine overhead; append receipt <=128-byte key+8-byte index. Binary nodes <2H, each <=512-byte text plus ~128 metadata/index bytes. Parts duplicate <=640V bytes. Eligible/candidate entries O(H), ~64 bytes each. Compact receipts <=~384K bytes, retained forever for retry guarantee. Approximate total O(H*(B+1500)+640V+384K), plus depots, replication and RocksDB overhead. Measure actual storage later.

Per-task division by N applies only to balanced chat load; a large chat remains colocated. No persistent heap cache. Event memory bounded by source input and <=32 node actions; view response memory O(64*640) plus fixed metadata/serialization; caller-side full traversal accumulation is optional and O(V), not a task-thread operation. Soft content budget is not a memory bound. Retaining exact originals inherently grows disk.

## Packaging and validation plan

Build phase creates only challenge fixture: README/deps, `src/optchat_memory/protocol.clj`, minimal host-side test_support, host-side test entrypoint, protected private tests and `test-resources/optchat_memory/module.clj` reference. Mirror current protocol factory/dependency conventions and verify runner discovery. No runner changes on assumptions. Keep plan/reference/adversarial oracle outside solver-visible snapshot; use protected reference/private paths.

The actual isolation allowlist copies README.md, deps.edn, src and .clj-kondo but rejects every src filename containing `test`; test/ and test-harness/ are also excluded. Therefore test_support and test entrypoints are host-side only. Put minimal solver-visible examples in README/protocol docs (short append/duplicate, exact leaf/date, binary zoom, invalid address), without internal data model or scheduling hints. No protocol or allowed source file may require excluded test_support. Configure host :test-private and :test-private-harness aliases; keep agent :test pointing to implementations/optchat-memory/test, matching current fixtures. Do not change the runner to expose fixtures. Private tests map each assertion to a normative clause above: extraction boundary oracle; empty/CRLF/emoji/combining; 65,536 versus 65,537 bytes; malformed Unicode; changed kind/timestamp conflicts; cross-chat keys; concurrent retries/distinct appends; aligned/malformed/overflow ranges; pending children; compact retries after intervening commands; persisted covering; exact due ordering/ties; nonreducing parent rejection; action cap; soft budget and byte counts; read purity; wrapper recreation; >100-entry growth; 64/65/200-part pagination; exact covered ranges and continuation; malformed/nonboundary/terminal cursors; stale cursor after append or compact; duplicate/no-op commands preserve cursors; bounded page work on large histories.

Actual IPC reference tests at multiple task counts required. Use wait-for-processing after writes and separate immediate-read assertions. Build session must inspect testing reference for same-schema update/restart controls. Wrapper recreation proves client independence only, not worker restart. Report unsupported restart test as unverified; never touch shared waler cluster for this fixture. No paid execution.

Later required checks: scoped Clojure/reference tests, public contract audit, private-test-to-public-clause mapping, runner allowlist audit, diff scope audit. No test success claimed by this plan.

## Design difficulty log

Decision: synchronous deterministic compact outcomes replace provider attempts. Basis: canonical challenge boundary omits providers and permits completed compact result. Consequence: stale external completion/provider failures are out of challenge scope; command retry/conflict safety retained.

Decision: eager leaves, explicit parent work and compact-only coarsening. Basis: bounded append and explicit observable progress without hidden background timing. Consequence: differs from attachment fit-on-append and is publicly stated, not hidden in tests.

Decision: complete LF-unit extraction with exact descriptor. Basis: deterministic public behavior, no language heuristic. Consequence: oversized first unit can yield descriptor only; exact leaf recovery unchanged.

## Self-validation and phase verdict

Covered: operations/types/errors, UTF-8 accounting, immutable originals, ancestry provenance, binary addresses, complete persisted covering, exact retry outcomes, no paid/external work, bound per command. Same-task receipt and mutation protect concurrent/retried/restarted acceptance; no multipartition partial mutation. Read queries avoid unnecessary client roundtrips and skip empty speculative ranges. Maintainer design must not leak into public fixtures.

Open independent-validation concerns: record the explicit parent scope exception for strict hot-chat partitioning; verify bounded page/coherence traces below; nested-index physical costs; correctness of local candidate maintenance; exact IPC restart capability. These are not claims of implementation success.

Phase verdict: REVISION READY FOR INDEPENDENT VALIDATION WITH EXPLICIT F1 SCOPE EXCEPTION; not a strict-template PASS. Stop after this artifact; Phase 2 approves or requests concrete corrections before implementation.

## Phase 2 localized corrections

Validation corrected arbitrary-precision aggregate counters, explicit full-ack response handling, exact local eligibility/candidate transitions, and fixture visibility to match the actual isolation allowlist. A separate strict-template partitioning failure remains (parent scope exception recorded in revision 1); see PLAN_VALIDATION-challenge-packaging.md. This plan is not approved for build.


## Phase 1 revision 1 — separate revision record

Marker: `optchat-challenge-revision1`.

Re-read canonical contract and subordinate source, both current plan artifacts, repository instructions, Rama skill, Phase 1 and validation templates. Preserved all four validator corrections: BigInteger byte/revision fields, full stream ack extraction, bounded local candidate transitions, and actual solver allowlist exclusions. No implementation, tests, runner edits, skill edits, provider calls or live operations performed.

F1 disposition: strict-template FAIL remains visible; parent explicitly selects colocation as a scope exception for this simplified single-owner learning exercise. The constructed alternatives above separate unavoidable sequencing from genuinely distributable payload and quantify reads, writes, transfers, staged visibility, retries and disk distribution. This is a scope decision, not evidence that hot keys scale. Reopen the decision if distributed hot-chat capacity becomes required.

F2 disposition: replace full unbounded server view with public fixed-size pages. Canonical source explicitly allows transport pagination and requires actual range/revision/continuation plus stale detection. Deleting the cursor loses coherent multi-call traversal; deleting the range fields makes a partial result misrepresent coverage. A yielding full query with before/after revision check would need >=3 seeks, O(V) memory/serialization and potentially unbounded retries under writes; returning stale after one attempt fixes retry liveness but not huge response memory. A retained immutable snapshot would require additional versioned covering writes/retention. Public pages use existing revision and covering with no new persistent snapshot state; no solver-visible internal design hint is needed.

Concrete traces:

- T=200 unmerged leaves at R=200: nil returns [0,64), then cursor 64 returns [64,128), 128 returns [128,192), 192 returns [192,200) and nil. Each page repeats whole-view totals, returns <=64 parts, and the four ranges tile [0,200). Appending index 200 between pages advances R; old cursor returns stale-view from metadata alone. Restarting at nil explicitly starts a new traversal, not a silently mixed snapshot.
- Compact merges a pair across a previously issued page boundary: any action increments R, so the old cursor fails stale-view before boundary lookup. At current R a fabricated start inside the merged part returns invalid-argument. An identical compact retry returns its old receipt and does not change R, so a current cursor stays usable. A no-action compact similarly leaves it usable.
- Lost page response / wrapper restart: retry same cursor returns the identical page if R is unchanged; otherwise stale-view. Worker restart preserves revision/parts, requires no cursor registry, and cannot create an ABA match because revision never decreases or wraps. Concurrent clients have independent cursor values and read-only queries; same-task bounded metadata+range segment prevents intra-page races. There is no cross-partition write in A.
- V=1,000,000: one view invocation navigates metadata and at most 64 records, returns to scheduler and stops. Another chat's operation can run between pages. There is no server loop constructing a million-part response or automatic stale retry. Build validation must inspect actual bounded navigator use and exercise large-history page interleaving; this plan is not timing evidence.

Readback: this marker, public cursor contract, updated page table and explicit F1 exception must be present. Independent Phase 2 must revalidate this revised artifact; the existing failure report remains untouched as historical input. Stop after this planning artifact.
