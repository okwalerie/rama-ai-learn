# Challenge test validation

Marker: `optchat-challenge-build`.

Verdict: pass for the simplified challenge's complete public contract. The production-only provider/auth/budget/MCP clauses remain explicitly outside this challenge. Strict generic hot-chat F1 is a documented parent scope exception, not a test or scaling success.

## Template checks

**Minimize IPC launches — PASS.** `test-private/optchat_memory/contract_test.clj`, deftest `durable-memory-contract` (100–295), reuses one IPC for every disjoint scenario. Two configurations, 2/4 tasks with two workers each, explicitly test routing and transport; these are intentional verification axes. `test/optchat_memory/public_test.clj` is the minimal standalone public-example entrypoint. Its five assertions are also included through test_support inside the private suite, without another private launch. The separate maintainer-only boundary script must have isolated seeded metadata, because that deliberately sparse state is not a valid normal challenge history.

**Implicit spec coverage — PASS within the canonical simplified-challenge boundary.** Every hidden behavior is mapped to a public clause below. The independent oracle is intentionally slow and enumerates addresses/pairs rather than sharing reference helper functions, internal indexes or names. The private namespace requires only the protocol, create-module factory and generic harness, never private reference helpers. The boundary script alone uses reference internals and is not a solver grading test.

**Synchronization — PASS.** `append-sync` (14–15), `compact-sync` (16–17) and public `basic-examples` await `harness/wait-for-processing!` after every write before dependent reads. Concurrent futures use these wrappers. The one explicitly named immediate-visibility block (276–279) reads before waiting by design: the public contract independently requires successful writes to be immediately observable; waiting first would conceal an append-ack bug. The normal wait follows it. Same-schema update runs after all prior writes are synchronized.

**Test namespaces compile — PASS.** Protocol, support, public and private namespaces loaded cleanly on dedicated nREPL7891. Clojure lint for src/test/test-private reports 0 errors/warnings. Reference module compilation and official Rama-aware lint report 0 errors/warnings. There is no solver-visible require of excluded test_support.

## Hidden-assertion to public-clause map

Line numbers below refer to `test-private/optchat_memory/contract_test.clj`. Every `is` in each listed block is covered by its public README heading; oracle functions (28–97) are subordinate to those same rules.

|Block|Public clause and what the assertions establish|
|---|---|
|basic-examples via support (107)|Append, Zoom, Date, View: exact Unicode original, negative timestamp, duplicate index and unchanged count. The standalone public test invokes these same five assertions.|
|argument precedence, missing resources, mathematical integer types (109–145)|Common types/errors, Append, View, Zoom, Date, Compact: unknown message/cursor fields, malformed Unicode, byte limits, chat/key UTF-8 128-byte boundary, malformed keys, timestamp domain including MAX, Integer/BigInt/BigInteger arguments, floating/ratio rejection, address alignment/power/overflow, missing chat/ID, invalid before lookup and lookup before stale.|
|exact extraction, Unicode, original retention and byte limits (146–159)|Deterministic extraction, representation shape, Append/Zoom/Date: empty, CRLF, emoji/combining forms, source at 512/513 bytes, 65,536-byte text versus one byte over, oversized unbroken first unit, leading whole LF units, and apparent instructions treated as data. Raw text is compared exactly.|
|concurrent distinct keys, identical retries and operation/chat key scoping (160–173)|Append and Compact retry semantics: 48 concurrent calls for 12 keys allocate precisely indices0..11; changes to each payload field conflict; another chat's same key and another operation's same key remain independent.|
|bounded page ranges, stale checks, revision purity and interleaved chats (174–201)|View: 64/65/200-part page boundaries, 64/64/64/8 traversal, exact range/watermark/revision/continuation, whole-view totals on each page and terminal empty page, mathematical cursor integers, s>T rejection, wrapper recreation and repeated reads/duplicate append purity; new append makes old revision stale before boundary validation.|
|independent full-state oracle (202–229)|Append/View/Zoom/Compact/Extraction: 32-message original model; after every compact, compare all current parts, aggregate content/rendered bytes, pending nodes, revision, over-budget flag and all complete binary zoom child addresses. Full enumeration derives eligible order and exact due order independently. Limits include1,2,3,32. Positive actions invalidate cursors, no-op preserves them; identical compact returns its old result. Nonboundary starts inside first and final merged parts are rejected.|
|exact cross-level due tie (230–248)|Compact due rule, no merge under budget, retry semantics: build10 parents under budget, merge two pairs, append to T20; ready useful 0+4 and10+2 have equal exact score5/2. Eight concurrent identical compact calls all return one result; smaller start is selected and10 remains a leaf.|
|large history (249–258)|Resource contract and View: history with1,025 parts returns17 calls of<=64 parts, total1,025, and another chat's point read succeeds between pages. Bounded navigator inspection complements this functional test; test timing alone does not prove I/O complexity.|
|nonreducing parents, soft budget, inherited omission (259–275)|Compact, bytes and Extraction: build31 nodes without merging when no current useful pair exists, retaining over-budget status; free parent of an omitted child stays extractive/omitted. Exactly2,048 bytes is not over budget;2,049 is.|
|immediate visibility (276–279)|Successful write/read visibility: full accepted append is readable through Date before synchronization.|
|recorded compact outcome/update (280–295)|Durability and Append/Compact retries: retained covering, exact original, previous completed result and appended index survive same-schema IPC update and new wrapper. Later append does not split previously merged parts or rewrite old compact outcome.|

The entity/write matrix is exercised as follows: missing+valid append creates; active+new append extends once; duplicate preserves reads/counts; conflicting/invalid rejects; missing+compact stays absent; active+compact preserves accepted prefix and originals while updating visible work/covering; no-op/duplicate compact preserves revision; retry after later writes returns the original outcome; invalid resource commands do not prevent subsequent valid chat work. Worker-completion/failure/provider-reservation rows have no API in this explicitly simplified challenge and are not secretly graded.

Capacity cannot be reached through2^63 actual public appends in a finite suite. `boundary-checks.clj.txt` is an explicitly reference-only maintainer script, outside grading, that seeds sparse boundary metadata in isolated IPC. It verifies final index MAX-1, subsequent capacity error, retry/conflict precedence, exact timestamp MIN, 2^80 BigInteger PState/query roundtrip and retention on update. Fifteen assertions passed. This is boundary-branch evidence, not fabricated full-history coverage. Hard OS-process crash, replication faults and backup restore remain unperformed; same-schema IPC update tests the supported worker-state recreation path.

## Packaging and evidence

`README.md`, `deps.edn`, and `src/optchat_memory/protocol.clj` are the only challenge files in the actual isolated snapshot. Host-only public examples/test_support, private tests, reference and maintainer design are excluded. Actual snapshot API audit returned58 total files and zero violations (`SNAPSHOT_AUDIT.json`). Protocol requires no hidden helper.

The parent requested additional protection for non-snapshot runs. All planning/validation artifacts were moved from root docs/optchat into this protected maintainer directory. `scripts/encrypt_challenges.bb`'s actual sensitive-dirs/source-files enumerates them recursively, plus private/public tests and src test_support. A disposable copy was encrypted using a public synthetic test key, every protected plaintext file disappeared, and decrypt restored byte-identical contents (`ENCRYPTION_AUDIT.json`). No real environment key was accessed. `boundary-checks.clj.txt` deliberately does not end in `.clj`, so runner `find-ref-files` does not concatenate it into structural-alignment reference code. Actual runner discovery returns `{:name "optchat-memory" :batch nil :difficulty :standard}`; runner configuration was unchanged.

Final commands from challenge directory:

```clojure
;; Through clj-nrepl-eval --port 7891 --timeout 120000, code on stdin:
(require 'optchat-memory.module :reload)
(require 'optchat-memory.contract-test :reload)
(load-file "test/optchat_memory/public_test.clj")
(time (clojure.test/run-tests 'optchat-memory.contract-test 'optchat-memory.public-test))
(time (load-file "test-resources/optchat_memory/maintainer/boundary-checks.clj.txt"))
```

Actual CLI options have spaces: `clj-nrepl-eval --port 7891 --timeout 120000`. Test output was bound to the checked-in `TEST_RUN.log`; summaries/timings came back through nREPL.

Final combined suite: **2 tests, 1,641 assertions, 0 failures, 0 errors, 22.137752463s**. Boundary script: **15 assertions, 0 failures/errors, 5.839667619s**. Initial run failed 76 revision/stale assertions; fixed module branch-local revision emission, retained all failing tests, then added missing exact tie/boundary cases identified by source review. No failing test was deleted or weakened. IPC timings are not production benchmarks.

Verdict: **pass**.
