# Idiom review: `hld-rate-limiter` reference implementation

Scope: `challenges/hld-rate-limiter/test-resources/hld_rate_limiter/module.clj`
(the reference module) at baseline commit
`43abd3fccff8777d8995f8a29658f3c4c97df01a`. Read as context: the challenge
`README.md`, `src/hld_rate_limiter/protocol.clj`, all four files under
`test-private/hld_rate_limiter/`, the design notes in
`test-resources/PLAN.md`, the `auction-module` and `chat-app` reference
modules, and `plugins/rama-skill/skills/rama/SKILL.md`.

**Outcome: I found no defects, and the reference module is unchanged.** This
document is the only change on the branch. For how much was verified, see
"Verification status" at the end. In short, the private suite was **not**
run during this review.

Line numbers below refer to the reference `module.clj` unless another file
is named.

## 1. What the code actually does (observed)

| Aspect | Observed code | Location |
|---|---|---|
| Depots | One depot `*user-events`, `(hash-by :user-id)`, carrying two record types `SetConfig` and `Check` | `module.clj:20-21`, `:101` |
| Topologies | One microbatch topology `"core"`; no stream, query, or tick topology | `:103` |
| Dispatch | `source>` → `%mb` → `<<subsource *event` with `case> SetConfig` / `case> Check` | `:124-139` |
| PStates | One PState `$$users`: `{String (fixed-keys-schema {:limiter … :decisions (map-schema String … {:subindex? true})})}` | `:104-122` |
| Partitioners | None in the topology. Every `local-select>`/`local-transform>` is keyed by `*user-id` on the task the depot routed to | `:131-148` |
| `set-config!` path | 1 `local-select>` of `:limiter`, a pure `new-limiter`, and a conditional `termval` of the whole `:limiter` | `:130-135`, `:35-45` |
| `check!` path | 1 `local-select>` of `:decisions rid`. If absent: 1 `local-select>` of `:limiter`, a pure `evaluate-check`, a `termval` of the decision, and a conditional `termval` of `:limiter` | `:139-149`, `:47-96` |
| Reads | Each read method makes exactly one `foreign-select-one`, keyed by `user-id`. `get-status` projects `available(T)` on the client with a pure function | `:191-199`, `:162-173` |
| Barrier | Shared `counter` atom plus `rtest/wait-for-microbatch-processed-count … "core"` | `:182-185`, `:201-203`, `:205-209` |

## 2. Requested audit patterns: which are present

I was asked to audit several patterns. Several of them **do not appear in
this module**, and I report no defect for anything that is absent. Grepping
`module.clj` for `ingress|group-by|vec-agg|sort|loop<-|query-topology|\|global|\|hash`
matches nothing. The only `select>`-family hits are the three
`local-select>` calls at `:131`, `:140` and `:142`.

| Requested pattern | Present? | Finding |
|---|---|---|
| Single multiplexed depot + microbatch topology | **Yes** (`:101`, `:103`, `:127`) | Idiomatic here. See §3.1 and §3.2 |
| `:ingress-seq` | **No** | Not applicable |
| `+group-by` | **No** | Not applicable |
| `+vec-agg` | **No** | Not applicable |
| sort (in dataflow or client) | **No** | Not applicable |
| `loop<-` | **No** | Not applicable |
| Repartition (`\|hash`, `\|global`, `select>`) | **No** | Every access is local to the depot's partition. See §3.3 |
| Growing collection inside a single PState | **Yes**: `:decisions` (`:115-122`) | Already `{:subindex? true}`. See §3.4 |
| Client-side distributed queries | **No** | Each read is one keyed `foreign-select-one`, with no fan-out or client-side merge. See §3.5 |

## 3. Findings

### 3.1 One multiplexed depot is required for correctness (keep)

- Evidence: `module.clj:101` declares one depot. `:20-21` define the two
  record types, and `:127-139` dispatch them with `<<subsource`/`case>`.
- Why it is correct: the protocol says that "Sequential write calls from
  one client to the same logical owner (user) are processed in invocation
  order", and this covers a mix of `set-config!` and `check!`. The private
  test "one client: check, config, check, config, check in invocation
  order" (`functional_test_support.clj:387-401`) depends on it. Rama orders
  appends only within one depot partition. With two depots (config and
  check), a `check!` could be processed before an earlier `set-config!`
  from the same client.
- Comparison: `chat-app` uses the same shape. `*user-actions-depot` is
  `(hash-by :user-id)` with `<<subsource`/`case>` over several record types
  (`chat_app/module.clj:160`, `:201-250`). `auction-module` uses separate
  depots (`auction_module/module.clj:70-71`) because its contract needs no
  cross-type ordering.
- Alternative: none recommended. Splitting the depot would break the
  ordering contract.

### 3.2 Microbatch is the right default (keep)

- Evidence: `module.clj:103`.
- Why: the check path is a read-modify-write of two buckets, the clock,
  and a decision record (`:140-149`). Microbatch gives exactly-once
  application of that read-modify-write. Nothing in the contract needs
  millisecond visibility or `foreign-append!` returning after processing:
  reads are only required after `wait-for-processing!` (README
  "Synchronization"), and write methods return `nil`. `PLAN.md:81-92`
  gives the same reasoning.
- Stream alternative (not recommended): a stream topology would also stay
  retry-safe here, because rule 1 (`:140-141`) turns a replayed `Check`
  into a no-op. But stream would lower per-partition throughput under the
  stated 10^6 decisions/s workload, and nothing in the contract would
  benefit.

### 3.3 Partition alignment is by construction (no defect)

- Evidence: there are no partitioners in the topology. The depot
  partitioner `(hash-by :user-id)` (`:101`) routes each event to
  `hash(user-id)`. Every PState path starts with `(keypath *user-id …)`
  (`:131`, `:134`, `:140`, `:142`, `:144`, `:148`), so the reads and
  writes all land on the task that owns that key. The client reads use
  `foreign-select-one` with a leading `keypath user-id` (`:193`, `:195`,
  `:198`), which the default hash partitioner routes to that same task.
- This matches SKILL.md "Implementation Goals" §3 (colocate related data)
  and §5 (partition alignment). All of a user's state (config, clock,
  buckets, decisions) is on one task. That is what lets the two-bucket
  debit and the config+reset switch be atomic without coordination
  (SKILL.md line 24: every task is single-threaded).
- Tradeoff (inherent, not a defect): all of a hot user's traffic goes
  through one task. The protocol requires per-user serial decisions,
  because both buckets and the user clock are debited atomically. Any
  correct design therefore serialises each user on a single owner.
  `PLAN.md:101` notes this.

### 3.4 Growing collection: `:decisions` is subindexed; `:endpoint-buckets` rightly is not

- Evidence: `:decisions` is a `map-schema` with `{:subindex? true}`
  (`:115-122`). The README allows up to 1,000,000 decisions per user.
  Lookup and insert are keyed point operations (`:140`, `:144`, `:193`),
  and nothing iterates the history. The private growth tests compare
  histories of 32 and 512 decisions (`performance_test_support.clj:167-267`).
  That is exactly what they guard.
- `:endpoint-buckets` (`:114`) and `:config :endpoints` (`:111`) are plain
  maps capped at 16 entries by the grammar. Under SKILL.md §4 ("Don't
  subindex small collections"), leaving them unsubindexed is correct.
- Write shape: after a debit, the code writes the whole `:limiter` with
  one `termval` (`:148`), using the value it already read at `:142`. This
  follows SKILL.md §7 ("If you already HAVE the value … use
  `(termval *new-val)`"). The non-subindexed `:limiter` lives in the
  top-level record, so splitting it into per-field `termval`s would not
  cut RocksDB writes. It would also lose the single-write atomicity that
  the code comment at `:128-129`/`:137-138` relies on.
- Read shape: a retried `check!` stops after one point read of
  `:decisions rid` (`:140-141`) and never reads `:limiter`. A new `check!`
  issues two `local-select>` and at most two `local-transform>` calls,
  whatever the history size. **These are counts of dataflow calls taken
  from the code, not measured RocksDB counts** (see "Verification
  status").

### 3.5 Client reads: single-roundtrip point selects (no defect; optional narrowing)

- Evidence: `get-decision` (`:191-193`), `get-config` (`:194-196`) and
  `get-status` (`:197-199`) each make one `foreign-select-one` against the
  owning partition. No read touches more than one partition, and no read
  merges results on the client.
- SKILL.md (line 39) prefers query topologies over *multiple* client-side
  foreign selects. That does not apply here: each method already makes
  one roundtrip. A `<<query-topology` would add a hop and gain nothing.
  `chat-app` uses plain `foreign-select-one` for single-key lookups in the
  same way (`chat_app/module.clj:547-553`).
- `get-status` computing `available(T)` on the client (`:162-173`) is a
  pure projection of one read value. It does not mutate state, which
  meets the protocol rule that reads never mutate.
- Optional, low value: `get-config` and `get-status` fetch the whole
  `:limiter` (at most 16 endpoint configs plus 16 buckets) and discard the
  fields they don't use. A narrower path, such as
  `(keypath user-id :limiter) (submap [:version :config])` for
  `get-config`, would move fewer bytes over the network. It would **not**
  change the RocksDB seek count, because `:limiter` is one non-subindexed
  value. I left the code unchanged: the saving is bounded and small, and
  the tests do not measure bytes.

### 3.6 Harness plumbing (consistent with sibling references)

- The `counter` atom (`:175-185`, `:205-209`) is harness-only
  synchronization. It is shared by every wrapper from one `create-module`
  call, so a barrier through either client covers both clients' appends.
  `auction-module` (`auction_module/module.clj:208-212`) and `chat-app`
  (`chat_app/module.clj:595-597`) use the same pattern. The wrapper holds
  no business data, as the README requires.
- `module.clj:1-2` has a boilerplate comment asking editors to re-read
  `PLAN.md`. It is harmless and outside the dataflow.

## 4. Idiomatic alternatives considered and rejected

| Alternative | Reason rejected |
|---|---|
| Separate depots for config and checks | Breaks one-client invocation order across `set-config!`/`check!` (§3.1) |
| Stream topology | No contract needs it; lower throughput; microbatch gives exactly-once for free (§3.2) |
| Separate PStates for config, buckets, and decisions | All are keyed by user on the same task, so atomicity would be kept. But it adds a PState per concern and no fewer reads: `check!` still needs one read of limiter state and one of the decision. No gain |
| Per-endpoint subindexing of buckets | At most 16 entries; subindexing adds overhead with no benefit (SKILL.md §4) |
| Query topology for `get-status` | Adds a hop; the current single `foreign-select-one` is already fixed work (§3.5) |

## 5. Verification status

- **Verified by reading:** every `file:line` citation above. I checked the
  absent patterns by searching `module.clj` for them.
- **Not verified in this session:** I could not run the private suite. The
  command `clojure -X:test-private-harness` (from
  `challenges/hld-rate-limiter/`) required approval that this
  non-interactive session could not get, so it was denied. The
  statements about budgets and test outcomes above are therefore
  reasoning from the code and the test source, not observed results.
- `scripts/test_reference_packages.py` does not apply:
  `hld-rate-limiter` is not in its `PACKAGES` tuple
  (`scripts/test_reference_packages.py:11-13`).
- Because nothing could be validated by running it, the reference module,
  README, protocol, private tests and skill are all unchanged.
