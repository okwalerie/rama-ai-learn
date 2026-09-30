# Idiom review: HLD stock exchange reference

## Scope, evidence and verdict

Reviewed at baseline `43abd3fccff8777d8995f8a29658f3c4c97df01a` (paths below
are relative to `challenges/` unless they start with `plugins/`):

- `hld-stock-exchange/README.md`, `src/hld_stock_exchange/protocol.clj`, the
  whole private namespace `test-private/hld_stock_exchange/private_test.clj`
  (7 deftests, each run at 2 and 4 tasks), and the reference
  `test-resources/hld_stock_exchange/module.clj`.
- For comparison: `auction-module/test-resources/auction_module/module.clj`,
  `chat-app/test-resources/chat_app/module.clj`, and
  `plugins/rama-skill/skills/rama/SKILL.md` plus the references it cites
  (`batch.md`, `microbatch.md`, `dataflow.md`, `depot-design.md`,
  `paths.md`, `syntax.md`, `aggregators.md`).

Line numbers `module.clj:N` refer to the hld-stock-exchange reference module.
Line numbers `private_test.clj:N` refer to its private test namespace.

**Evidence gathered in this pass.**

- Private suite against the unchanged reference, from
  `challenges/hld-stock-exchange`: `clojure -X:test-private-harness` →
  `Ran 7 tests containing 10096 assertions. 0 failures, 0 errors.`
  (Rama 1.9.0, about 4m20s wall).
- Small throwaway probe modules (outside the repo, same deps, in-process
  cluster, RocksDB counts from `rtest/with-event-hook` as in
  `private_test.clj:20-29`). They are cited as **P1–P4** below and summarized
  in the appendix. They show what Rama 1.9.0 did in these runs. They are not
  documented guarantees and not latency benchmarks.
- An out-of-tree variant of the reference (section 3) run through the same
  private suite. Nothing in the challenge was edited.

**Verdict: the design is sound and meets the README contract. Keep the
reference unchanged in this pass.** The ordering machinery that looks heavy
(section 2) is needed. The real findings are small: a schema that does not
describe what is stored (section 4), one redundant read per trade
(section 3), a type dispatch that `<<subsource` expresses better (section 5), and wrapper details (section 6). None is a correctness bug. The
only measured saving is one RocksDB read per trade, so it does not justify
changing a validated reference now. Record them for the next rewrite.

## 1. Depot and topology: one symbol-hashed command depot, one microbatch

**Evidence.** `module.clj:77` declares a single `*commands` depot with
`(hash-by :symbol)`. Both `SubmitOrder` and `CancelOrder` records
(`module.clj:10-11`) are appended to it. `module.clj:80` consumes it in
microbatch `"core"`. `$$symbols` (`module.clj:87-100`) is keyed by the symbol,
so the depot partition, the PState partition and the `+group-by` hash
(`module.clj:110`) all land on the same task.

**Assessment: idiomatic.** Submit and cancel for one book must be ordered
against each other, so they belong in one depot with one partition key
(`references/depot-design.md:14-17`, ordering table `:108-116`). Partitioning
the depot by the PState lookup key follows SKILL.md Implementation Goal 1
(`SKILL.md:53`). Microbatch is the skill's default (`SKILL.md:63`,
`references/microbatch.md:5`). Its exactly-once retry is what makes the
counter writes and `(term inc)` on `:conflicting-attempts`
(`module.clj:122-123`) safe. Nothing in the contract needs stream's
low-latency ack coordination: `wait-for-processing!` is the barrier, and
`foreign-append!` at the default ack level already gives "durably accepted"
(README "Command conventions"). The auction reference uses a stream topology
(`auction-module/.../module.clj:77-113`) because its bid writes are
last-writer upserts. That is a contrast, not a model for an order book.

**Observations.**

- The alignment is implicit. The pre-agg `local-select>`/`local-transform>`
  on `:ingress-seq` (`module.clj:106-108`) has no preceding partitioner. It is
  correct only because the depot's `hash-by :symbol` routes to the same task
  as `$$symbols`' key partitioning (SKILL.md Goal 5, `SKILL.md:61`). A short
  comment would make this visible.
- A hot symbol is served by one task. This is inherent to a single-writer
  book. Do not split one symbol's commands across tasks.
- `module.clj:79` raises `topology.microbatch.phase.timeout.seconds` to 3600.
  The real bound on one batch is `depot.microbatch.max.records` = 1000
  (`module.clj:78`). The timeout is a safety valve for a long serial loop on a
  hot symbol, not a design parameter. Document it next to the loop.

## 2. Ordering: `:ingress-seq` + `+group-by` / `+vec-agg` + sort + `loop<-`

**Evidence.** For each emitted command, `module.clj:105-109` reads
`[sym :ingress-seq]`, increments it, writes it back, and tags the command as
`[position cmd]`. `module.clj:110` gathers each symbol's pairs with
`(+group-by *sym (aggs/+vec-agg ...))`. `module.clj:111` sorts them by the
tag. `module.clj:112-211` applies them one at a time in a post-agg `loop<-`
that calls `(yield-if-overtime)` per command (`:116`) and per match step
(`:138`).

**Is the gather-then-serial-loop necessary? Yes, while the loop yields.**

- The matching loop can run for many iterations, so it must yield
  (`SKILL.md:26`). Yielding gives up event ordering on a task
  (`references/dataflow.md:156`).
- P3 tested the simpler shape: direct `(%mb :> ...)` emit
  (`references/microbatch.md:70`), with a per-record loop that sleeps 2 ms
  0–3 times and calls `yield-if-overtime`, then appends the record's index to
  a per-key log. With 400 records per key on 2 tasks, both keys were logged
  out of order (for example, index 4 was applied at position 2). Direct emit
  plus yielding therefore breaks the README's per-symbol ordering. Folding
  each symbol's commands into one list and walking it in one post-agg loop is
  what keeps them serial while the loop yields.

**Is the explicit tag plus sort necessary? Keep it.**

- Inside a batch block, Rama chooses execution order (`references/batch.md:3`),
  and `+vec-agg` collects into a vector (`references/aggregators.md:177`), so
  element order is whatever order values reach the aggregator. The skill documents
  `%mb` emit order (`references/microbatch.md:13`), but not arrival order at
  the aggregator.
- P4 ran the same shape without a tag (4 tasks, 8 keys, 1,000 records per
  key, gathered with `+vec-agg`). Every key came out in append order. That is
  an observation about one Rama version, not a guarantee. Removing the sort
  would make correctness rest on undocumented behavior, so do not remove it.

**Cost of the durable tag, stated precisely.** `:ingress-seq` is only ever
read at `module.clj:106-108`. It is kept in `$$symbols` forever, although only
the relative order inside one batch on one task matters. Measured I/O:

- Each `local-select>` of a scalar field under `(keypath *sym ...)` cost one
  RocksDB read, and repeated selects of the same field in one batch were not
  cached (P2: three selects = 3 reads). By that measure the tag costs up to
  **one read per command**. This was not isolated on the reference itself,
  and section 3 shows a probe-predicted read saving that did not appear in the
  reference, so treat it as an upper estimate.
- `termval` writes to fields of the same symbol record were coalesced: three
  writes to one field in one batch cost the same as one (P2). The tag's
  write-back therefore costs about one record write per batch, not one per
  command.

A per-batch counter that is not durable would remove that read. However, none
of the inspected skill references document a per-record emit index or depot
offset that can be read inside a microbatch. Adding one would need a
task-global or similar mechanism that was not evaluated here. **This is not
validated.** If it is attempted, it must pass `same-symbol-mixed-command-batches`
(`private_test.clj:247-267`: 1,040 unbarriered commands, so more than the
1,000-record batch cap) and the `rocks-work` comparisons in
`bounded-work-and-deep-page` (`private_test.clj:143-202`) at 2 and 4 tasks.

The replay/conflict check (`module.clj:118-123`) runs before any book-state
read, and structural validation happens client-side before the append
(`module.clj:223-227`, `:232`). This matches README "Validation order".

## 3. PState layout and per-command I/O

**Evidence.** `$$symbols` (`module.clj:87-100`) maps a symbol to a
`fixed-keys-schema`. Its growing members are all `{:subindex? true}`:
`:orders`, `:ask-/:bid-levels` (`module.clj:85`), the two-level
`:ask-/:bid-queue` price→seq→order-id (`module.clj:86`), `:trades` and
`:requests`. Bid levels are keyed by `1000000001 - price`
(`module.clj:25-26`), so "best first" is an ascending range on both sides.

**Assessment: idiomatic, and it follows SKILL.md Goal 4 (`SKILL.md:59`).**

- Matching (`module.clj:137-165`) reads the best level with
  `sorted-map-range-from-start 1`, then the queue head the same way, and stops
  at the first level that does not cross. Work is proportional to trades
  produced plus a constant, not to resting orders.
- Depth (`module.clj:245-246`) is one bounded range of at most `levels`
  entries. The per-level order count is maintained on the write path
  (`add-level` `module.clj:49-51`, `level-after` `:46-48`, `cancel-level`
  `:67-69`), so a level with 10,000 orders costs the same as a level with one.
  This follows the skill's "write-path work amortized over reads" rule
  (`SKILL.md:41`).
- The tape (`module.clj:251-252`) uses `sorted-map-range-from (inc after)
  {:max-amt limit}`: one seek plus at most `limit` iterator steps, whatever
  `after-seq` is.
- Every order, outcome and trade is kept (README "Durability"). No cleanup is
  required or wanted (`SKILL.md:34`).
- One record per symbol is fine. Nested fields are addressed individually,
  and one key keeps each atomic book transition on one task. Splitting into
  several PStates would not change partitioning.

**Reading a field reads the symbol record.** The skill states that `keypath`
followed by further navigation reads the value it navigates into, even when
the path ends in `termval` (`SKILL.md:68`, `references/paths.md:616`). Every
path in this module starts with `(keypath *sym ...)` and continues into the
record, so each one reads the symbol record first. The suite's own counts
agree: `get-order` and `get-outcome` each cost 2 RocksDB reads (symbol record
plus subindexed entry) at both history sizes. P2 agrees for writes: a
`termval` into a scalar field cost reads, while a no-op batch cost none.

**Concrete I/O findings (SKILL.md Goal 7, `SKILL.md:65-68`).**

1. **Level re-read per trade.** `module.clj:153` does
   `(local-select> (keypath *sym *opp-levels *key) ...)`. That value is
   already in `*best` from `module.clj:139-140`, because `*best` is a
   one-entry sorted map `{key level}`. `(second (first *best) :> *level)`
   removes the read. SKILL.md says not to re-read a value already held
   (`SKILL.md:67`).
2. **Two counter selects per submit (saving not confirmed).**
   `:next-order-seq` (`module.clj:134`) and `:next-trade-seq` (`:136`) are
   separate selects of the same record. In isolation, P1 measured one read for
   a `(submap [...])` of two fields against two reads for two separate
   selects. But merging them in the variant below did **not** change the
   non-crossing submit's measured reads. Treat this as a readability choice,
   not a proven I/O saving, until a count shows otherwise. Handle fields that were never written: a first
   variant that used `(get m k 0)` got `nil`, not `0`, for a new symbol and
   threw a `NullPointerException` in `inc`. `(or (get m k) 0)` works.
3. `:next-trade-seq` is rewritten on every submit, even without trades
   (`module.clj:179`). Because `:next-order-seq` is written to the same record
   on the line before (`:178`) and P2 showed writes to one record coalescing
   within a batch, this has no measurable cost. Do not change it for
   performance.

**Variant measurement.** Findings 1 and 2 were applied to an out-of-tree copy
of the reference and run through the full private suite.
Result: `Ran 7 tests containing 10096 assertions. 0 failures, 0 errors.`
at 2 and 4 tasks. The measured `rocks-read` counts, at both history sizes:

| Operation (`bounded-work-and-deep-page`) | Reference | Variant |
|---|---|---|
| 3-trade IOC match (whole batch) | 36 | 33 |
| Non-crossing submit (whole batch) | 10 → 12 | 10 → 12 |
| Cancel (whole batch) | 13 | 13 |

Iterator seeks, iterator steps and write-batch entries were identical. So
finding 1 saves exactly one read per trade, which is about 8% of this batch's
point reads. Finding 2 saved nothing measurable here. These are RocksDB
operation counts in an in-process test cluster, not latency measurements.

**Test evidence.** `bounded-work-and-deep-page` (`private_test.clj:143-202`)
counts RocksDB reads, iterator seeks and steps, and write-batch entries
through `rtest/with-event-hook` (`private_test.clj:20-29`). It covers depth, a
same-price queue, get-order, get-outcome, a non-crossing submit, cancel, a
3-trade IOC match and a deep 5-row tape page. For each it asserts
`large ≤ 2·small + 12` after history grows from 320 to 1,280
(`private_test.clj:31-38`). In this run every metric was flat between the two
sizes, except the non-crossing submit's reads (10 → 12). This shows the tested
operations do not scale with history. It is not a proof for every path.

## 4. Stored request payload and its schema

**Evidence.** `payload` is `(dissoc cmd :request-id)` (`module.clj:36`). It is
stored at `module.clj:208-210` under a field declared `:payload ICommand`
(`module.clj:99`), and compared at `module.clj:121`.

**Finding: the schema does not describe the stored value.** `request-id` is a
declared field of both records, and `dissoc` of a declared record field
returns a plain map. Checked in a REPL: the result is a
`clojure.lang.PersistentArrayMap`, and `(instance? ICommand ...)` is `false`.
So every stored `:payload` is a plain map, not an `ICommand`. The suite
passes because Rama 1.9.0 did not check this nested field. In P2, a plain map
written into an `ICmd`-typed field inside a subindexed map entry was
accepted, both as part of a whole-entry `termval` and as a direct field
`termval`, and read back as a plain map. The same plain map written into an
`ICmd`-typed field of the **top-level** fixed-keys record failed with
`ValueSchemaMismatchException` (P1). Do not rely on this difference.

**The replay check is still correct, for a different reason than it looks.**
Because the record type is dropped, the command type is *not* part of the
comparison. A submit and a cancel payload never compare equal only because
their key sets differ (`:side :price :qty :tif` against `:order-id`). A future
command type with the same fields as an existing one would compare equal to
it. The README defines payload as "command type together with every argument
other than `this` and `request-id`".

**Alternatives, either one sufficient:**

- Store `(assoc cmd :request-id nil)`. `assoc` of a declared field keeps the
  record type. Checked in a REPL: the result is still a `SubmitOrder` and an
  `ICommand`, and two commands that differ only in `request-id` compare equal.
  The command type is then part of `=`, and the declared schema is accurate.
- Keep `dissoc` and declare `:payload` as a map type (or `Object`, like
  `:outcome`), so the schema states what is actually stored.

## 5. Command dispatch: `instance?` versus `<<subsource`

**Evidence.** Inside the loop, `module.clj:125` dispatches with
`(<<if (instance? SubmitOrder *cmd) ...)`, then extracts fields with five
`get` calls (`:126-130`). The cancel branch uses two more (`:183-184`).

**Assessment: works, but `<<subsource` is the idiom the skill names for this.**
`references/depot-design.md:15-16` says to use `<<subsource` to dispatch the
distinct event types of one depot. `references/syntax.md:463` gives the form
`(<<subsource *data (case> TypeA :> {:keys [...]}) ... (case> TypeB) ...)`.
The chat-app reference dispatches its one-depot records this way in both its
stream and microbatch topologies (`chat-app/.../module.clj:201`, `:282`).
`<<subsource` binds fields by destructuring and names each record type
explicitly, instead of "SubmitOrder, else assume CancelOrder".

It fits where the dispatch is here. P2 compiled and ran a `<<subsource`
inside a post-agg `loop<-` in a `<<batch`, with each case binding the same
output var, used after the block (the cancel/submit `*outcome` here needs
exactly that). This is a readability and robustness change only. It does not
change I/O.

## 6. Client-side reads and the wrapper

**Evidence.** Each query does one `foreign-select-one`, routed by the symbol
key (`module.clj:236-252`): a point lookup for outcome or order, at most 50
levels, or at most 500 trades. `get-trades` returns `[]` for
`after = Long/MAX_VALUE`, so `(inc after)` cannot overflow (`module.clj:249`).

**Assessment: direct foreign selects are the right idiom here.** SKILL.md's
roundtrip rule (`SKILL.md:39`) is about replacing *several* client selects
with a query topology. Each read here is already one roundtrip to one
partition over a bounded range, so a query topology would add code and a hop
without removing a roundtrip. The chat-app reference makes the same split: it
uses direct `foreign-select-one` for key lookups and a bounded member page
(`chat-app/.../module.clj:546-563`), and query topologies only for pages that
join across PStates and partitions (`:417-494`, invoked at `:581-590`).

**Wrapper observations.**

- The processed-count atom is created once per `create-module` call
  (`module.clj:214`) and shared by every `wrap-client` from that call. This is
  deliberate: tests write through one client and wait through the other
  (`private_test.clj:215`, `:239`). The chat-app wrapper keeps one count per
  client (`chat-app/.../module.clj:514`), which would not cover that case. The
  count is sync bookkeeping, not business state, so the README's rule about
  process-local state is respected. It only covers appends made through
  clients of the same `create-module` call in the same JVM.
- One append is one depot record, and the microbatch processed count counts
  records, so the target matches.
- Validation runs before `swap!` (`module.clj:223-228`, `:232-233`). A call
  that throws `IllegalArgumentException` therefore does not move the target,
  which `validation-replay-and-update` depends on (`private_test.clj:119-128`).
- `swap! count inc` runs *before* `foreign-append!` (`module.clj:228-229`,
  `:233-234`). The auction and chat-app wrappers do the same
  (`auction-module/.../module.clj:178-180`, `chat-app/.../module.clj:518-519`).
  If an append throws, the target stays one ahead of what can ever be
  processed, and every later `wait-for-processing!` waits until it times out.
  Incrementing after a successful append avoids this.
- The local named `count` shadows `clojure.core/count` in the wrapper
  (`module.clj:214`). It is harmless today, since the only `count` call is in
  the top-level `valid-id!`, but a name such as `appended` avoids surprises.

## 7. Checklist for any future rewrite

1. Keep one depot partitioned by symbol, and keep all book state keyed by
   symbol on the same task. Do not repartition individual orders.
2. Keep the gather-then-serial-loop shape while the loop yields (P3), and keep
   an explicit ordering key plus sort for the `+vec-agg` gather. Check with
   `private_test.clj:247-267` at 2 and 4 tasks.
3. Order of checks: structural validation (client) → replay/conflict →
   business validation. Malformed calls must not touch `:conflicting-attempts`
   (`private_test.clj:113-141`).
4. Keep every growing collection subindexed and every read range bounded.
   Re-run the `rocks-work` comparisons (`private_test.clj:143-202`) after any
   change to a read or write path.
5. Keep one atomic transition per command covering trades, maker and taker
   order records, levels, queue and outcome (`private_test.clj:40-111`,
   `:204-230`). `update-module!` continuity is covered at
   `private_test.clj:133-141`.
6. Make the stored payload match its schema (section 4) and dispatch with
   `<<subsource` (section 5).
7. From `challenges/hld-stock-exchange`, run `clojure -X:test-private-harness`.
   If packaging files change, also run `scripts/test_reference_packages.py`.

## Appendix: probes

All probes used the challenge's own classpath
(`clojure -M:test-private-harness <file>`) and an in-process cluster. The
probe files lived in `/tmp` and are not part of the repository.

| Probe | Setup | Result |
|---|---|---|
| P1 | Top-level record `{:a Long :b Long :m <subindexed map> :pl ICmd}`, 1 task | Counts for one command per batch, relative to a no-op batch with 0 reads: select `:a` = 1 read; select `:a` then `:b` = 2; one `submap` of `:a :b` = 1; `termval` `:a` = 2 reads, 1 write. A plain map `termval`'d into top-level `:pl` failed with `ValueSchemaMismatchException`. |
| P2 | Same, plus `:r` = subindexed map of `{:pl ICmd :c Long}` | One `termval` of `:a` and three in the same batch both cost 2 reads, 1 write. Three selects of `:a` = 3 reads. A plain map in `:r` entry's `:pl` was accepted (whole-entry and direct-field `termval`) and read back as `PersistentArrayMap`. `<<subsource` inside a post-agg `loop<-` compiled and ran, with a shared output var. |
| P3 | Direct `%mb` emit, per-record loop with 0–3 × (2 ms sleep + `yield-if-overtime`), 2 tasks, 400 records × 2 keys appended while paused | Both keys logged out of append order. |
| P4 | `<<batch`, `+group-by` + `+vec-agg` with no tag, then a yielding `loop<-`; 4 tasks, 8 keys × 1,000 records appended while paused | All 8 keys logged in append order (observed, not guaranteed). |

## Test and change status

Documentation only. The README, protocol, private tests, reference module and
skill are unchanged. The private suite passed against the unchanged
reference: 7 tests, 10,096 assertions, 0 failures, 0 errors. The out-of-tree
variant in section 3 also passed the same suite; it was not applied to the
reference.
`scripts/test_reference_packages.py` does not apply, because no package files
changed. No solver or `bb run-challenges` run was performed.
