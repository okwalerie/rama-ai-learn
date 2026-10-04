# OptChat challenge plan validation

Marker: `optchat-challenge-phase2`
Verdict: **major-fail — return to Phase 1**.

This is design validation, not runtime validation. No challenge implementation, test execution, runner change, provider call or live action occurred. The validator read the complete canonical IMPLICIT_SPEC.md, subordinate SOURCE_OPTCHAT.md, DECOMPOSITION.json, repository AGENTS.md, Rama SKILL.md, Phase 2 instructions and validation template, actual harness, existing fixture dependency aliases, runner/snapshot filters and stream acknowledgement reference. References below use the corrected PLAN-challenge-packaging.md line numbers. Canonical sources are in `/tmp/optchat-rama-plus-agents/docs/optchat/`.

Promise evidence: only this artifact and PLAN-challenge-packaging.md were modified; the marker above is the readback target. Localized corrections are applied, but do not resolve the structural verdict. No runtime correctness or restart durability is certified.

## Failures requiring Phase 1 resolution

### F1 — partitioning gate: FAIL, structural

The validation template requires: "`|hash`: is the keyspace large (many keys per task → negligible hash variance) AND free of any key taking a disproportionate share of events or storage? <if the keyspace is sparse or any key can be hot, FAIL>". The skill also requires balanced storage. Plan lines 127–129 explicitly allow one unbounded hot chat, and all its records live under one chat key.

Concrete trace: N=128, one chat with 1,000,000 originals averaging 1 KiB. At least roughly 1 GiB of originals, plus all nodes, receipts and covering, land on one task; the other 127 tasks cannot relieve its disk or command work. Restart restores that concentration; retry preserves it. Hash collisions are not the central issue, and replacing hash with a fixed/direct task or assigning a chat through a placement table does not distribute this single chat.

Per-chat acceptance order and coherent covering mutations require a sequencer/serialization point. That does **not** establish that immutable originals must remain colocated forever. Constructed alternative: retain chat metadata/acceptance receipts at the sequencer, route immutable originals by `(chat,index)` or fixed-size chunk, acknowledge only after durable publication, and keep covering together. Point date/leaf zoom can route by address in one point lookup, without scanning all N tasks. Compared with the current one-task append transaction, this adds a cross-task write/commit dependency and durable retry/reconciliation protocol; it distributes roughly 1 GiB over tasks instead of one task. Node/covering distribution is a separate decision: blindly striping covering over all 128 tasks raises a view from 2 logical reads to 129 and fails the template's aggregate-I/O gate. A placement lookup alone adds at least one lookup without solving hot-key throughput/storage.

Therefore no cosmetic routing edit earns PASS. Phase 1 must either construct/cost a bounded publication protocol with distributed historical storage while retaining efficient coherent view, or explicitly return the conflict for parent/user resolution. The canonical requirement says "acceptance must not scan or rewrite the entire history" and does not impose a hot-chat throughput SLO; the simplified challenge explicitly disclaims that SLO (line 19). That makes the practical benefit a storage/balance benefit, not a proven user-visible latency need. Nevertheless the validator cannot silently waive the mandatory skill gate. Do not smuggle striping into public solver requirements as a solution hint.

### F2 — unbounded non-yielding view: FAIL, structural execution concern

Skill wording: "Because tasks are single-threaded, topology code must use cooperative multitasking" and identifies large subindexed reads as a risk. Plan lines 87 and 123 choose an unbounded covering read without yielding to preserve coherence, and line 157 permits O(V) response memory. With 1,000,000 unmerged parts, this can iterate 1,000,000 entries (~5 seconds at the skill's illustrative 5 microseconds/entry, before serialization). A second chat on that task waits. This is distinct from hash balance: it can happen at N=1 even with excellent placement.

The public full-view response is explicitly permitted to scale with V, so response size alone is not a failure. The missing mechanism is a cooperative coherent read: for example, version-check and retry a yielding read, with a stated consistency/liveness contract, or immutable/pinned snapshot state and bounded traversal. Merely setting allow-yield true can mix revisions; merely disabling yield can monopolize the task. The plan's sentence about a possible future revision check does not select/design that mechanism. Phase 1 must resolve it before a build claims compliance. No synchronous command is unbounded: append is <=65,536 source bytes and compact <=32 actions, so this finding concerns view reads.

## Local corrections completed

1. **Counter overflow** (lines 45, 99, 107): byte totals and revisions now use concrete arbitrary-precision integer storage. 512 × 2^55 exceeds Long.MAX_VALUE while 2^55 accepted messages is below the advertised index capacity; no earlier aggregate cap was public. Watermark and pending-node count remain Long. Schema conversion and serialization remain build checks.
2. **Acknowledgement semantics** (line 119): wrappers must use full `:ack`, extract the `commands` topology's `ack-return>` value, and preserve duplicate outcomes. `:append-ack` cannot fulfill immediate read visibility.
3. **Bounded candidate maintenance** (line 111): explicit build/merge/append transitions, level flag updates, unique sibling checks and overflow-safe address arithmetic replace a deferred maintenance obligation.
4. **Actual solver visibility** (lines 161–163): source files with `test` in their filename, test/, test-harness/, test-private/ and test-resources/ are excluded. test_support is host-side; minimal visible examples belong in README/protocol docs. Allowed source must not depend on excluded fixtures. Current fixture aliases and agent-generated test location are recorded without changing isolation.

## Query and schema checks

- **View useful reads: PASS for access plan, F2 for execution.** Missing chat: N=M=1 metadata probe; 20 parts: N=M=2 logical navigations plus 20 iterator steps; 200 parts: N=M=2 plus 200 steps (lines 85,123). Branch is dynamic. Denormalized part text avoids 20/200 extra node probes. Physical nested-index seeks are not claimed measured.
- **Zoom useful reads: PASS.** Invalid/incomplete address: metadata only; existing count=4: metadata plus two half-range probes, N=M=3, with absent nodes meaningful pending results. Leaf: N=M=1 original probe. Date: N=M=1 timestamp probe, including a missing result (lines 85,123). Routing uses the chat key throughout.
- **Schema grouping: PASS.** One String/chat-partitioned $$chats (lines 97–107), with fixed-shape records. No duplicated PStates sharing key and partitioner, no Object or polymorphic-map escape. All history/request-scaled nested collections are subindexed; level flags are enforced by <=63 legal count levels. BigInteger concrete scalar compatibility is a build compile/roundtrip obligation, not runtime evidence here.
- **Topology choice: PASS.** One depot/stream handles append acceptance and completed compact outcomes, each coordinated with caller ack (lines 91,117–119). No unrelated asynchronous concern is put in stream. Microbatch plus receipt polling is viable but adds a read/latency; tests are not the reason for stream.
- **Partition cost accounting: PASS for shown view mix only.** Each N row set sums to 1. Recomputed aggregate seeks = .1×1+.7×2+.2×2=1.9; iterator reads = .7×20+.2×200=54; cost = .95+.27=1.22ms. There is no divide-by-N. The table is a view workload, not a measured all-operation mix or a cure for F1. Revised partition architecture must cost writes and point reads as well.
- **Internal depots/cross-topology protocol: PASS, absent.** No internal depot means no undocumented dependency or missing commit-before-internal-append boundary. No cross-partition write chain exists in current design; an F1 redesign must be revalidated rather than inheriting this result.
- **Stream retry arithmetic: PASS in present transaction design.** Watermark/revision/byte/pending counters and covering edits share the durable receipt transaction. Crash before commit leaves none; crash after commit retries receipt and produces no second mutation (lines 93,119). No TaskGlobal owns accepted truth.

## Minimality and throughput attack

The simplest plausible implementation uses one chat-routed command stream, one durable chat record with subindexed originals/nodes/parts/receipts, two server queries and point foreign reads. Bounded compaction needs ordered eligible and merge candidates, or else it repeatedly scans accumulated history. The proposed mechanisms match this sketch; replacing all storage with a serialized Chat would turn each append into O(H) copying and violate the explicit append bound. This sketch still fails F1/F2; minimality is not overall approval.

|Mechanism|Delete / merge / bypass attack and result|
|---|---|
|Command depot + stream|Deleting durable ingress loses accepted write/retry processing. One stream already handles both ack-coupled operations; there is no redundant handoff.|
|One $$chats|All same-key state is already merged. Flattening growing collections into a single blob breaks "acceptance must not scan or rewrite the entire history".|
|Originals|Removing them violates "Preserve exact originals"; 65,536-byte raw text cannot be recovered from a 512-byte descriptor.|
|Append receipts|Without request-key→index, ambiguous ack retry allocates a second index. Storing the whole original in receipts duplicates up to 65,536 bytes per append; index plus original point lookup is smaller.|
|Immutable nodes|Deleting prior representations breaks recursive zoom and source immutability. Recomputing on read breaks read purity/persisted choices.|
|Persisted parts|Rebuilding on view violates "Persist observable view choices across restarts" and cost proportional to returned view. Storing addresses only saves <=512 bytes/part but adds V point reads (roughly 50ms for V=100) versus one scan (~1ms), line 113.|
|Eligible ordered entries|Without them, finding the next build scans O(H) built/missing addresses. Each inserted/deleted entry is bounded per created node.|
|Candidate ordered entries|Without them, finding most due scans O(V) on every action. At each count, oldest start suffices; <=63 head probes replace potentially a million-part scan.|
|Level flags|Removing them requires probing up to 63 empty levels each action; a bounded flag value skips absent levels. Their maximum size follows Long power-of-two domain, not typical load.|
|Metadata byte/pending totals|Without maintained totals, view/status and each compact budget check traverse accumulated nodes/parts; per-write deltas avoid that scan. Revision supports a coherent observable version.|
|Compact receipts|Without stored completed result, retry after later append returns different watermark or does new work, violating line 69.|
|View query|Client metadata+parts calls can straddle a mutation. One server query avoids the client race, though cooperative coherence still fails F2.|
|Internal zoom query|Metadata and two probes cannot mix accepted watermark/source existence across uncoordinated client reads. Immutable children reduce races, but one query also avoids three client round trips.|
|Date/leaf direct reads|Already one meaningful point read; combining them into a query does not remove a read.|

No alternative with fewer aggregate accesses and the same public behavior was established for the current colocated architecture. This is a bounded design comparison, not a universal optimality or benchmark claim. Persistent heap caches, provider orchestration and worker job protocols were correctly omitted from the simplified fixture.

## Public behavior, adversarial traces and source coverage

For the following traces, **F/R** denotes the explicitly traced fault/race envelope: clear worker memory before transaction commit → no accepted changes and retry executes once; clear it after commit → durable state and receipt survive and retry returns stored result; two clients on one chat serialize at the depot task, identical keys deduplicate and different keys receive consecutive acceptance-order indices; no partitioner hops occur inside the mutation group, so there is no partial multi-partition write or hop-induced reorder. Reads select committed state and never repair it. These statements apply to each row below; F1/F2 remain separate failures and no trace asserts a running implementation exists.

### 1. Append identity: PASS

Source: "Each accepted message has a contiguous, zero-based index within its chat, immutable kind/text/timestamp, and a caller-supplied idempotency key."

Trace: missing chat A receives key k with user/"é\r\n"/timestamp -1 → index 0, T=1, exact original and leaf. Concurrent distinct key q → index 1; concurrent k same payload → index 0; k timestamp 0 → conflict and T unchanged. Plan lines 27,35–37,93,101,119. F/R prevents lost acceptance or duplicate index; chat B's k has independent scope. None found beyond structural findings.

### 2. Cancellation / retry retention: PASS

Source: "Client cancellation after acceptance cannot erase the message; retrying the key resolves ambiguous acknowledgement."

Trace: A/k commits index 0 and response is lost. Recreate wrapper, append q at index 1, retry k → 0 without mutation. No volatile wrapper owns dedup (lines 35,93,119). F/R includes crash after commit; original index lookup supplies exact conflict comparison. No expiry/deletion exists.

### 3. Input / original integrity: PASS

Source: "Empty text is valid. ... Reject oversized input explicitly rather than truncate originals."

Trace: empty user text creates representation "user: " (6 bytes), raw zoom remains empty; 65,536-byte text accepted, 65,537 rejected, malformed Unicode rejected. Unknown message field rejected. Emoji/combining forms remain byte-for-byte distinct; negative timestamp preserved. Lines 27–29,35,73–77. F/R on error has no transaction mutation; reads are unchanged. Byte validation must precede mutation in build.

### 4. Binary addressing and leaf zoom: PASS

Source: "count is a positive power of two, start is nonnegative and divisible by count, and the complete range exists. Count 1 opens the exact original; larger nodes open the two children."

Trace: T=4, zoom(0,4) before compact returns pending (0,2),(2,2); zoom(0,2) returns two ready leaf representations; zoom(0,1) returns exact original. (1,2) invalid; (4,2) not-found; count=3 invalid. Huge aligned address beyond T is not-found without Long overflow. Lines 27–29,53,85,111. F/R cannot alter immutable children; concurrent append sees a coherent accepted watermark. No summary fabricated.

### 5. Date: PASS

Source: "return the stored timestamp for a message, without reinterpreting a historical local timezone."

Trace: date(A,0) returns -1, not a formatted date; missing index returns not-found. Lines 55–57,85. One point probe; F/R original timestamp is immutable and durable, no write/retry effect.

### 6. Covering / no split: PASS for mutation invariant; F2 for large read execution

Source: "The view covers the complete accepted prefix of a chat, oldest first, without gaps or overlap."

Trace: T=8 initially has eight 400-byte leaf parts, C=3200. Once ready, useful parent 0+2 replaces [0+1,1+1]; append index 8 extends only the end. Coverage remains [0,9), no split/rebuild. Lines 37,45,65,93,111. F/R receipts prevent applying a merge twice; concurrent read cannot see half of the same-task commit. Cooperative reading remains F2 rather than a PASS claim.

### 7. Persisted choices / durability: PASS at design level

Source: "Originals and accepted representations survive process restart and module update."

Trace: after merging 0+2, restart restores persisted parts/nodes/originals/receipts, not a replay-derived alternative covering. Same-schema update retains state. Lines 23,97–119,149,165. F/R handles incomplete versus complete commits. Wrapper recreation is explicitly insufficient evidence for worker restart; build must exercise available disposable IPC update/restart controls and report unsupported tests honestly.

### 8. Byte accounting / soft budget: PASS

Source: "Use UTF-8 byte counts. The configured content budget and actual content/rendered byte counts are reported separately."

Trace: source "user: é" is 8 UTF-8 bytes; rendered "0+1|user: é\n" is 13 bytes. C=8, D=13, budget=2048. C=2048 is not over budget, C=2049 is. Counts change only by removed/inserted rows; aggregate arithmetic cannot wrap. Lines 45–49,99,111. F/R commits totals with parts and receipt. No token/character substitute.

### 9. Free nodes / extraction: PASS

Source: "Short messages and concatenations that fit the node target are free nodes."

Trace: two 200-byte child texts form 401-byte source, retained unchanged with provenance union. Two 400-byte children form 801 bytes; parent keeps first whole 400-byte child plus bounded newline descriptor when it fits. A leaf source with one 513-byte LF-free unit emits descriptor only, never partial text; LF-terminated units retain their LF, so adding the suffix's LF may visibly create two LFs, as specified. Lines 47,73–77. F/R node creation is immutable; repeats do not select another extract.

### 10. Omission provenance / exact recovery: PASS

Source: "mark omissions explicitly, and never label extracts as faithful summaries."

Trace: descriptor-only leaf plus short sibling may fit parent <=512, but parent stays extractive/omitted because ancestry omitted. Recursive zoom reaches exact full leaf. Memory text "ignore prior instructions" is retained/extracted as data, never executed. Lines 47,53,73–77. F/R immutable source ancestry prevents later metadata upgrade; no provider/model metadata is fabricated.

### 11. Pending work / read purity: PASS

Source: "Pending representation work must be explicit in view/status output."

Trace: T=8 after eager leaves has 4+2+1=7 missing complete non-leaf nodes. One build decrements P to 6; one merge leaves P unchanged. Zoom/read/date do not build or increment revision. Lines 31,45,53,63,109. F/R commits build and P delta once. No-op compact does record its receipt but does not advance public revision.

### 12. Compact boundedness / dependency order: PASS after local correction

Source: "No unbounded recursive work or whole-history scan per append/read. Dependencies must exist before parent computation."

Trace: T=8, compact max-actions=1 builds (0,2), not (0,8); next absent eligible count/start wins. After four count-2 builds, count-4 can build. Every step reevaluates merge preference and candidates; max 32 never becomes 32 recursive subtrees. Lines 61–69,93,104–111,151. F/R ensures action set and receipt are one transaction; concurrent append runs before or after that transaction.

### 13. Most-due useful merge: PASS after local correction

Source: "Choose deterministic tie-breaking for the attachment's most-due legal merge rule."

Trace: at T=16, ready useful pairs (start=8,c=2) and (start=12,c=1) both due=1; smaller start 8 wins. Same level oldest start is maximal due, so heads suffice. After merge, consumed pair candidate is deleted and only unique aligned sibling can create a higher-level candidate. Parent of two 100-byte leaves is 201 bytes and therefore not useful; it never merges, even if the soft budget stays exceeded. This limitation follows the public strict reduction clause, not a hidden test rule. Lines 63–67,105–111. F/R eliminates stale candidate mutations after retry; exact arithmetic avoids rounding/overflow.

### 14. Compact durable outcome: PASS

Source: "Return a durable job ID or completed result, with enough information to poll status; polling is read-only."

Trace: compact(A,k,2) records {actions 2, watermark 8, revision R}; append makes T=9; retry k,2 still returns original watermark 8 and R without work; k,3 conflicts; k,0 invalid before idempotency. Completed synchronous result needs no queued-worker status API. Lines 17,29,61–69,107,119. F/R preserves old receipt and avoids double build/merge.

### 15. Structured errors / isolation: PASS for command behavior

Source: "All interfaces return structured errors for invalid arguments, missing resources, idempotency conflicts, ownership failures and exhausted quotas."

Challenge omits auth/ownership failure and provider quota surface publicly. Trace: invalid count on missing chat → invalid-argument; valid compact missing chat → not-found; invalid input on A never enters poisoning retry loop, so subsequent B append remains processable. Lines 17,27–31,81,119. F/R errors leave accepted state unchanged. F2 is a separate long valid read scheduling failure, not an error-path excuse.

### 16. Growth / no replay / resource ceiling: PASS on access bounds, FAIL F1 on balance

Source: "The history is unbounded; acceptance must not scan or rewrite the entire history."

Trace: H=1,000,000 append performs one original/receipt/leaf/part write and bounded sibling work, not a million-row replay. Compact probes <=63 level heads per action; date is point access. All growing collections remain SI, raw inputs capped. Lines 81,97–111,149–157. F/R resumes durable PStates, not whole history reconstruction. Storage growth is explicit; 512-byte target is not a constant total memory claim. F1 prevents full production-scale approval.

### 17. Providers / execution boundaries: PASS for explicit challenge reduction

Source: "It omits live providers, subscription auth, MCP and operator billing while retaining history, view, zoom, deterministic compaction and idempotency semantics."

Trace: compact on text demanding a model call still runs extract-v1 only. No worker API, reservation, external attempt or billing exists; no assertion can demand stale provider completions. Lines 17,47,73–81,171. F/R pertains solely to durable local commands; no external exactly-once claim. Production provider/budget/status requirements are outside this expressly simplified fixture, not silently implemented by a stub.

### 18. Public contract minimality / hidden tests: PASS as plan, fixture audit pending

Source: "Publicly specify every behavior private tests depend on."

Trace: solver sees protocol docs and README containing exact bytes, limits, order, errors, omission format, retry semantics and simplifications. It does not see internal candidate maps, stream/PState names, architectural plan or reference. A hidden assertion on CRLF, tie ordering, malformed Unicode or duplicate compact outcome points to lines 27–77; an assertion on topology name would be forbidden. Host test_support and private files are excluded under actual runner rules, so visible sources cannot require them. Lines 19,23,161–167. Build must produce a per-assertion clause mapping and real snapshot audit before calling the fixture sufficient. F/R checks must use the protocol, not private internal field names.

### 19. Harness / validation evidence: PASS as bounded build obligation

Source: "Verify actual Rama IPC behavior, not only pure helper functions."

Actual harness `with-module` calls zero-arity create-module, deploys returned module, calls wrap-client with IPC, and chooses 2 or 4 tasks with 2..tasks threads. It expects Synchronizable; full-ack stream can make wait-for-processing! a no-op. Lines 23,31,119,165 match this. Deterministic task-count/update/recreation tests need an explicit IPC lifecycle in private tests because the macro hides IPC and randomizes task count. No existing test run or restart success is inferred. Build may inspect the supported controls and report a missing capability; it must not touch waler to fill the evidence gap.

## Routing and final evidence

Return to Phase 1 for F1 and F2. Local fixes are reviewable in the plan and do not authorize implementation. Phase 1 should preserve the minimal public behavior, quantify any distributed-history publication cost, and explicitly select a cooperative coherent-view mechanism. If the partition mandate conflicts with the user's simplified learning objective, escalate that precise conflict rather than claiming an exception was approved.

Readback target: `optchat-challenge-phase2`. Final scoped audit must show only the two authorized plan artifacts. No memory files were needed; all evidence came from current local sources.

PHASE_VALIDATION:major-fail
