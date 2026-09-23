# Implicit Spec

<!-- Phase 0 artifact for hld-feature-flag-service. Requirements only — no
PState, depot, or topology design. Inputs: README.md,
src/hld_feature_flag_service/protocol.clj, lib/harness Synchronizable
docstring, skills/rama SKILL.md. -->

## Derived domain facts

These follow from the stated rules and every later phase may rely on them.

- **Flag identity is the triple `[tenant env flag-key]`, compared by string
  value.** `["acme" "prod" "x"]`, `["acme" "staging" "x"]`, and
  `["globex" "prod" "x"]` are three unrelated flags. Nothing about one flag
  (its existence, revision, kill state, rules) may influence another.
- **The only stateful entity is the flag configuration.** Tenants and envs
  are namespaces, not records: there is no "create tenant" step, and a
  tenant "exists" only in the sense that some flag under it has been
  configured. Subjects and their attributes are per-call input; nothing is
  remembered about a subject between calls. `evaluate` records nothing.
- **Revision order is a total order per flag, and "strictly greater wins"
  makes the *revision number* order-independent but the *content* at an
  equal revision order-dependent.** Whatever sequence of writes a flag
  receives, the stored revision ends at the maximum revision written. But
  if two writes carry the same revision with different content, the one
  applied first is kept forever. That is why per-flag application order
  must match client invocation order (README "Write ordering").
- **A retried or duplicated write is harmless by the spec's own rule.**
  Re-applying an already-accepted configuration hits the "equal revision"
  case and is a no-op. Any delivery of the same put more than once must
  leave the state identical to a single delivery.
- **Stale means rejected in full.** An equal-or-lower revision changes
  nothing: not the revision, not `:killed?`, not rules, not rollout. There
  is no merge. Worked example step 6: writing revision 3 with
  `:killed? true` over a stored revision 3 leaves `:killed? false` visible.
- **The stored configuration is the accepted map, key for key.**
  `get-flag-config` must return a map `=` to the one passed to the
  accepting `put-flag-config!`. Consequences: a config written with
  `:rollout nil` reads back with a `:rollout` key whose value is nil; a
  config written without `:rollout` reads back without that key (the two
  are not `=`); rules with unknown operators (e.g. `:gt`) are stored and
  returned as written; killed configs are stored and returned as written;
  a `false` or `0` value anywhere is preserved and never treated as absent.
- **Untrusted and killed configurations are still configurations.** They
  are accepted on revision grounds alone, they advance the revision, and
  they are visible via `get-flag-config`. Only `evaluate` treats them
  specially (serves off-value).
- **Superseded configurations need not be retained.** The protocol exposes
  only the current configuration; there is no history read. "No deletes"
  means a flag, once configured, never returns to the never-configured
  state.
- **Bucketing is fully specified and verified.** Recomputing the README
  table with length-prefixed UTF-8 components, SHA-256, first 8 digest
  bytes as an *unsigned* big-endian 64-bit integer, mod 10000 reproduces
  all five rows (7336, 6729, 798, 598, 2961). Three of the five rows
  (`user-42`/prod/new-checkout, `user-7`, `dark-mode`) have the top digest
  bit set; interpreting those 8 bytes as a signed long gives 5720, 5113,
  and 8982 instead. The unsigned interpretation is therefore observable and
  mandatory. Length prefixes make `("ab","c")` and `("a","bc")` distinct.
  Lengths are UTF-8 *byte* lengths, not character counts (a non-ASCII
  component such as `"café"` is 5 bytes, not 4).
- **Bucket is independent of revision and of the configuration.** The same
  `[tenant env flag-key subject-id]` yields the same bucket before the flag
  exists, at every revision, and whether or not a rollout is configured.
  Changing threshold or serve never moves a subject between cohorts; only a
  change to any of the four identity/subject strings does.
- **Rollout membership is `bucket < threshold`, exclusive at the top.** A
  subject whose bucket equals the threshold is *not* in the rollout.
  Threshold 0 admits nobody but the rollout is still "present" (so
  `:default` results carry `:bucket`). Threshold 10000 admits every subject
  since every bucket is ≤ 9999.
- **Evaluation is a pure function of (stored config, subject-id,
  attributes).** Given the same stored configuration and the same inputs,
  `evaluate` returns the same map every time, on every client, on any task
  count. There is no randomness, time dependence, or per-subject memory.
- **The evaluation result has exactly the keys listed for its reason.**
  The worked example shows exact maps (step 1: a `:rule` result has no
  `:bucket` even though a rollout is configured; step 9: a `:default`
  result with no rollout has no `:bucket`). Tests compare whole maps with
  `=`, so extra keys are failures:
  - `:missing` → `{:value nil :revision nil :reason :missing}`
  - `:killed` → `{:value off :revision R :reason :killed}`
  - `:unknown-operator` → `{:value off :revision R :reason :unknown-operator}`
  - `:rule` → `{:value serve :revision R :reason :rule :rule-index i}` (0-based)
  - `:rollout` → `{:value serve :revision R :reason :rollout :bucket b}`
  - `:default` → `{:value default :revision R :reason :default}` plus
    `:bucket b` iff the config has a non-nil `:rollout`.
- **Reason precedence is strict and each gate is evaluated against the
  whole config.** Kill beats a matching rule and beats an unknown operator.
  Unknown-operator is decided by scanning *every* rule before any matching
  is attempted (step 8: rule 0 would match but rule 1's `:gt` forces
  off-value). "Operator other than `:eq`" includes a missing `:operator`
  key, a string `"eq"`, or any other keyword.
- **Rule matching is Clojure `=` on the attribute value and requires key
  presence.** A subject with no such attribute key does not match, with no
  error. An attribute present with a value of `false` matches a rule whose
  `:value` is `false`. `18` does not match `"18"`. The first matching rule
  in vector order wins; later matching rules are irrelevant.
- **Empty inputs are ordinary.** `:rules []` skips straight to the rollout
  check. `attributes {}` matches no rule. A tenant with many flags and a
  never-configured key still yields `:missing` for that key.
- **Reads never observe a torn configuration.** Because a write replaces
  the whole map, an `evaluate` or `get-flag-config` concurrent with a put
  sees either the entire old configuration or the entire new one. In
  particular the `:revision` in an `evaluate` result is always the revision
  of the very configuration whose rules/rollout/kill state decided the
  result.

## Operations

### `put-flag-config! tenant env flag-key config`

- **Latency**: Asynchronous. Effects need not be visible on return; they
  must be visible to every client wrapping the module once the writing
  client's `wait-for-processing!` returns. Hundreds of milliseconds is
  acceptable. Configuration changes are operator or CI actions, not
  request-path actions.
- **Throughput**: Driven by humans and deploy pipelines editing flags.
  Orders of magnitude below `evaluate`. Volume scales with number of
  tenants × flags × edit frequency; bursts happen when a pipeline pushes
  many flags at once, typically across many distinct flag keys rather than
  repeatedly to one key.
- **Consistency/correctness invariants**:
  - Accepted iff no configuration is stored or `(:revision config)` is
    strictly greater than the stored revision. First write accepted at any
    positive revision (1, 3, 100 alike).
  - Acceptance replaces the entire map; rejection changes nothing.
  - After acceptance, `get-flag-config` returns a map `=` to `config`.
  - Writes to the same flag from one client are applied in invocation
    order. Two writes at the same revision with different content: the
    first invoked is the one kept.
  - Duplicate delivery of the same write is a no-op on second delivery
    (state after one or many deliveries is identical).
  - Isolation: a write to `[t e k]` never changes any other triple.
  - Stored revision is monotone non-decreasing over the life of a flag.
- **Data growth and scale**: Number of flags is unbounded (many tenants,
  a few envs each, hundreds to thousands of flags per tenant-env). Each
  configuration is small: a rules vector of typically tens of entries, at
  most bounded by what an operator writes. Storage grows with the number of
  distinct flags, not with the number of writes: stale writes and
  superseded revisions must not accumulate as retained state. Access is by
  exact triple; no range access over flags is required by any operation.
- **Concurrency behavior**: Sequential writes from one client to one flag:
  ordered. Writes from different clients to the same flag: no ordering
  required, but the outcome must equal some serial order (max revision
  wins; equal revisions keep whichever was applied first; never a blend).
  Writes to different flags: independent, no ordering.
- **Edge cases**:
  - First write at revision 1; first write at a large revision; then a
    lower revision (rejected).
  - Revision equal to stored, identical content (no-op, indistinguishable).
  - Revision equal to stored, different content (rejected; old content
    stays).
  - Higher revision with `:killed? true` (accepted; step 7).
  - Higher revision containing a non-`:eq` operator (accepted; step 8).
  - `:rules []`; `:rollout nil`; `:rollout` key absent; threshold 0;
    threshold 10000.
  - Values that are `false`, `0`, integers, or booleans in `:off-value`,
    `:default-value`, `:serve`, or rule `:value` must round-trip exactly.
  - Same flag-key under two envs or two tenants written at different
    revisions: independent revision counters (step 5: staging at revision
    1 while prod is at 3).

### `get-flag-config tenant env flag-key`

- **Latency**: Single-digit milliseconds expected (SDK bootstrap and admin
  UI reads); not the hottest path but a direct user-facing read.
- **Throughput**: Driven by SDK/config-fetch and admin traffic; scales
  with number of connected applications and admin sessions. Read-heavy but
  well below `evaluate`.
- **Consistency/correctness invariants**:
  - Returns `nil` iff the flag has never had an accepted write.
  - Otherwise returns the most recently accepted configuration, `=` to the
    map that was accepted, including `:revision`.
  - Never returns content from a rejected (stale) write.
  - Same answer from every client wrapping the module after the writer
    synchronizes.
- **Data growth and scale**: Reads exactly one flag's configuration. Cost
  must not depend on how many flags, envs, or tenants exist (efficiency
  contract).
- **Concurrency behavior**: Read concurrent with a write sees the whole
  old or whole new map. Read-only; never modifies state.
- **Edge cases**: Never-configured key under an otherwise populated
  tenant/env → `nil`. Flag whose current config is killed or contains an
  unknown operator → returned as-is. Flag with `:rollout nil` vs absent →
  returned faithfully. Called before any write to the module at all → `nil`.

### `compute-bucket tenant env flag-key subject-id`

- **Latency**: Sub-millisecond; it is one SHA-256 over a few dozen bytes.
  Must not perform any storage read or network roundtrip.
- **Throughput**: Called at least once per `evaluate` that reaches the
  rollout step, so it scales with evaluation traffic. Also callable
  directly by tests and SDKs.
- **Consistency/correctness invariants**:
  - Pure and deterministic: same four strings → same integer, on every
    client, forever.
  - Result in `[0, 10000)`.
  - Exactly the README encoding: for each of tenant, env, flag-key,
    subject-id in that order, 4-byte big-endian UTF-8 byte length then the
    UTF-8 bytes; SHA-256; first 8 digest bytes as unsigned big-endian
    64-bit; mod 10000.
  - Independent of revision, stored config, attributes, and of whether the
    flag exists.
  - Reproduces the five README rows (7336, 6729, 798, 598, 2961).
- **Data growth and scale**: No state. Input size is the four strings.
- **Concurrency behavior**: None; no shared state.
- **Edge cases**:
  - Digest top bit set (three of five README rows): unsigned handling is
    required or results are wrong.
  - Non-ASCII components: byte length ≠ character length.
  - Same subject across envs (798 vs 7336) and across tenants (2961 vs
    7336) and across flag keys (598 vs 7336) differ; cohorts are per flag.
  - Flag never configured: still returns a bucket.

### `evaluate tenant env flag-key subject-id attributes`

- **Latency**: The hot path. Single-digit milliseconds end to end; this is
  called inline in application requests, often several flags per request.
- **Throughput**: Driven by application request volume × flags evaluated
  per request across all tenants. Orders of magnitude above writes.
  Traffic is skewed: a few flags in a few tenants receive most evaluations,
  so a single flag's evaluation must stay cheap under concentrated load.
- **Consistency/correctness invariants**:
  - Decision order 1–6 from the README, first applicable wins.
  - `:revision` in the result equals the stored revision of the exact
    configuration used to decide (nil only for `:missing`).
  - Result map contains exactly the keys listed under "Derived domain
    facts" for its reason; no `:bucket` on `:rule`, `:killed`,
    `:unknown-operator`, or `:missing`; no `:rule-index` except on `:rule`.
  - `:rule-index` is the 0-based position in the stored `:rules` vector of
    the first rule whose attribute key is present and whose value is `=`.
  - `:bucket` when present equals `compute-bucket` for the same four
    strings.
  - `:killed` and `:unknown-operator` serve `:off-value`; `:default` serves
    `:default-value`; these are distinct fields and must not be confused.
  - Evaluation is read-only. Reads exactly one flag's configuration; work
    is proportional to the rules vector length plus attribute lookups,
    never to the number of flags/envs/tenants.
  - Same result from every client wrapping the module after the writer
    synchronizes; same result with 2 or 4 tasks.
- **Data growth and scale**: No state growth from evaluation, however many
  subjects are evaluated. Per-call work bounded by config size.
- **Concurrency behavior**: Concurrent with a put on the same flag: sees
  whole old or whole new config, so `:revision` and `:value` always come
  from the same config. Concurrent evaluations of one flag do not interfere.
- **Edge cases** (each maps to a README step or a direct consequence):
  - Never-configured flag → `{:value nil :revision nil :reason :missing}`
    (step 10), including when the same key exists in another env/tenant.
  - Killed config with a rule that would match → `:killed` with off-value
    (step 7).
  - Killed config that also has an unknown operator → `:killed`.
  - Unknown operator at any index, even after a matching rule →
    `:unknown-operator` with off-value (step 8). Unknown operator with a
    rollout that would admit the subject → still `:unknown-operator`.
  - Two rules match → lowest index wins.
  - `attributes {}` with non-empty rules → falls through to rollout/default.
  - Subject has the attribute but a different value, or a different type
    (`18` vs `"18"`) → no match.
  - Attribute value `false` vs rule value `false` → match.
  - `:rules []` and rollout present → rollout or default with `:bucket`.
  - Rollout present, bucket == threshold → `:default` with `:bucket`.
  - Rollout threshold 0 → always `:default` with `:bucket`.
  - Rollout threshold 10000 → always `:rollout` (when no rule matches).
  - `:rollout nil` or absent → `:default` without `:bucket` (step 9).
  - `:off-value`/`:default-value`/`:serve` of `false`, `0`, or an integer
    → returned as-is as `:value`.
  - Same subject, same flag-key, different env → potentially different
    cohort (step 5: staging bucket 798 admitted at threshold 7000, prod
    bucket 7336 not).
  - After a stale (rejected) write, evaluation reflects the previously
    accepted config, including its `:revision` (step 6).

### `wait-for-processing!` (harness `Synchronizable`)

- **Latency**: Bounded by the time to apply all writes previously issued
  by that client. Must return promptly on a client that has issued no
  writes.
- **Throughput**: Called once per test phase after a group of writes.
- **Consistency/correctness invariants**: After return, every write that
  client issued before the call is visible to `get-flag-config` and
  `evaluate` on *any* client wrapping the same deployed module, in the
  same task-count configuration. It is a barrier for that client's own
  writes; it makes no promise about writes from other clients still in
  flight.
- **Edge cases**: Called twice in a row; called on a second client that
  never wrote; called after writes that were all rejected as stale (still
  must wait until the rejections have been decided, so a subsequent read
  cannot observe a not-yet-processed stale write as pending).

## Entity State × Write Matrix

Only one entity has state: **the flag configuration at `[tenant env
flag-key]`**. Tenants, envs, subjects, and rules have no independent
lifecycle (rules exist only inside a config; subjects are call inputs).
`compute-bucket` is listed on every row because it is callable in every
state; it never changes.

Flag states (R = stored revision; states 2–4 are distinguished only by how
`evaluate` treats the content):

- **S0 — never configured.** No accepted write yet.
- **S1 — live at R.** `:killed? false`, every rule operator `:eq`.
- **S2 — killed at R.** `:killed? true` (regardless of rules).
- **S3 — untrusted at R.** `:killed? false`, at least one rule operator
  ≠ `:eq`.

The only write is `put-flag-config!` with revision `r` and content of kind
live/killed/untrusted. The target state after an accepted write is decided
by the *new* content's kind, not the old state.

### S0 (never configured) × `put-flag-config!` at any positive `r`

Accepted. Flag moves to S1/S2/S3 according to the new content.
- `get-flag-config`: returns the map `=` to the one written, with
  `:revision r`. Before this write it returned `nil`.
- `evaluate`: no longer `:missing`. Result per the new content: live →
  rule/rollout/default with `:revision r`; killed → `:killed` off-value,
  `:revision r`; untrusted → `:unknown-operator` off-value, `:revision r`.
- `compute-bucket`: unchanged (pure); same value as before the write.

### S1 (live at R) × `put-flag-config!` with `r > R`

Accepted; whole map replaced; new state by new content.
- `get-flag-config`: the new map, `:revision r`. Old rules/rollout are
  gone entirely (no merge).
- `evaluate`: decided solely by the new config with `:revision r`. A
  subject that was `:rollout` under the old threshold may now be
  `:default` (or vice versa) only because the threshold changed, never
  because the bucket changed. If the new content is killed → `:killed`;
  untrusted → `:unknown-operator`.
- `compute-bucket`: unchanged.

### S1 (live at R) × `put-flag-config!` with `r = R`

Rejected, even if content differs (e.g. `:killed? true` at the same
revision, step 6).
- `get-flag-config`: still the old map at R, `=` to what was accepted
  earlier; the attempted content is nowhere visible.
- `evaluate`: identical results to before the write, `:revision R`.
- `compute-bucket`: unchanged.

### S1 (live at R) × `put-flag-config!` with `r < R`

Rejected.
- `get-flag-config`: old map at R.
- `evaluate`: identical to before, `:revision R`.
- `compute-bucket`: unchanged.

### S2 (killed at R) × `put-flag-config!` with `r > R`

Accepted; new state by new content (step 8 goes killed → untrusted; a live
config would "un-kill").
- `get-flag-config`: new map at `r`.
- `evaluate`: new content decides. Live → rules/rollout/default resume with
  `:revision r`, subjects regain the same cohorts they had before the kill
  (bucket unchanged). Killed → still `:killed` but `:revision r`. Untrusted
  → `:unknown-operator`, `:revision r`.
- `compute-bucket`: unchanged.

### S2 (killed at R) × `put-flag-config!` with `r ≤ R`

Rejected. Notably, an attempt to un-kill at an equal or lower revision
fails; the flag stays killed.
- `get-flag-config`: old killed map at R, `:killed? true`.
- `evaluate`: `:killed` off-value `:revision R` for every subject and every
  attribute map.
- `compute-bucket`: unchanged.

### S3 (untrusted at R) × `put-flag-config!` with `r > R`

Accepted; new state by new content (step 9 goes untrusted → live).
- `get-flag-config`: new map at `r`.
- `evaluate`: live → normal decisions, `:revision r`; killed → `:killed`;
  still-untrusted → `:unknown-operator`, `:revision r`.
- `compute-bucket`: unchanged.

### S3 (untrusted at R) × `put-flag-config!` with `r ≤ R`

Rejected. A fix that reuses the same revision number does not take effect;
the flag keeps serving off-value.
- `get-flag-config`: old untrusted map at R, with the non-`:eq` rule still
  present as written.
- `evaluate`: `:unknown-operator` off-value `:revision R` for every subject,
  including subjects that a preceding `:eq` rule would match and subjects
  the rollout would admit.
- `compute-bucket`: unchanged.

### Cross-entity rows (any state) × `put-flag-config!` to a *different* triple

Whether the other triple differs in tenant, env, or flag-key, this flag is
untouched.
- `get-flag-config` on this flag: unchanged (nil if S0, else old map at R).
- `evaluate` on this flag: unchanged, including `:revision R` and cohort
  membership.
- `compute-bucket` on this flag: unchanged.

### Any state × duplicate delivery of an already-applied write

Second and later deliveries hit the `r = R` case and are no-ops.
- `get-flag-config`: same map as after the first delivery.
- `evaluate`: same results as after the first delivery.
- `compute-bucket`: unchanged.
