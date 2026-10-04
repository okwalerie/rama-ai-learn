# OptChat memory

Implement a durable, deterministic conversation memory service in Rama.

One implicit owner and multiple named chats. No auth, MCP, deployment, live providers, subscription clients, worker completion/failure API, billing or background jobs. Deterministic extraction is the only policy. Compact returns a completed bounded result synchronously, with a durable retry key. Append eagerly creates its leaf representation; missing parent work is observable.

Structured logical view transported in fixed pages of at most 64 parts, with revision-checked continuation and no presentation options. A simple fixed serialization exists solely for byte accounting. Fixed node target 512 UTF-8 bytes and view content budget 2048 bytes. No history-length cap except signed-long index capacity. The budget is soft; no constant-size storage/full-logical-view or hot-chat distributed-throughput promise.

Namespace `optchat-memory.module`, `create-module` returns `{:module <RamaModule> :wrap-client (fn [ipc] <client>)}`. Client implements `OptchatMemory` and `harness/Synchronizable`. Internal names/design unrestricted. Accepted originals, representations, covering and retry outcomes are durable Rama state, surviving client recreation, worker restart and same-schema module update; volatile state cannot be their sole copy.

### Common types and errors

Chat IDs and request keys are nonempty strings <=128 UTF-8 bytes. Text is well-formed Unicode <=65,536 UTF-8 bytes; empty is valid. Do not normalize Unicode/newlines. Kind is `:user :talk :tool :echo :note`. Timestamp is a caller-supplied signed-long epoch-millisecond integer, preserved unchanged. Message indices and starts are nonnegative signed-long integers. Counts are positive power-of-two signed-long integers; all arithmetic is overflow-safe. Integer arguments are accepted by mathematical integer value and range, not exact JVM wrapper class; floating-point and ratio values are invalid even when numerically integral.

Every method returns `{:ok value}` or `{:error code}`; codes are `:invalid-argument`, `:not-found`, `:idempotency-conflict`, `:capacity-exhausted`, `:stale-view`. Documented invalid/resource/conflict cases do not throw. Validate arguments before resource lookup, then check idempotency. Unknown message fields invalid. Missing chat is not-found except valid append creates it. Valid but incomplete range/missing ID is not-found. Misalignment/non-power-of-two count is invalid-argument. All errors leave accepted state unchanged.

Successful writes return only when reads can observe accepted changes. Tests call wait-for-processing after writes but separately test immediate visibility. Reads never mutate state or initiate work.

### append! [client chat-id request-key message]

Message has exactly `{:kind k :text s :timestamp t}`. Allocate contiguous indices 0,1,... atomically with acceptance; return `{:ok {:index i}}`. First valid append creates chat. Same chat/key and equal payload returns original index without mutation; any changed field conflicts. Keys scoped by chat and operation; append/compact key spaces separate. Distinct concurrent writes get unique acceptance-ordered IDs (no caller scheduling order promised). Identical concurrent retries accept once. Cancellation/lost acknowledgement cannot erase acceptance.

Append atomically adds the exact original, ready leaf representation, and final view part. It does not compact existing parts. Earlier originals/timestamps never change. Maximum accepted message count T is Long.MAX_VALUE (9,223,372,036,854,775,807), so the final allocatable index is Long.MAX_VALUE-1. At T=Long.MAX_VALUE a new valid append returns :capacity-exhausted; an existing identical key still returns its original index and a conflicting key still conflicts. Retry and conflict outcomes take precedence over capacity exhaustion. Never allocate index Long.MAX_VALUE, whose exclusive endpoint would exceed the watermark domain.

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

## Examples

After appending `{:kind :user :text "hello" :timestamp -1}` to chat `"a"` with key `"k"`, the result is `{:ok {:index 0}}`. Repeating that call returns the same result. `date` at 0 returns `{:ok -1}` and leaf `zoom` returns the exact original plus `:index 0`. After a second append, `zoom` at 0+2 returns the two ready leaf representations; 1+2 is invalid-argument.

Implement `optchat-memory.module/create-module` and add tests under `implementations/optchat-memory/test`. Run `clojure -X:test` from this directory.
