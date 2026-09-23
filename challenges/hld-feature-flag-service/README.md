# HLD Feature Flag Service Challenge

Build a multi-tenant feature flag store: whole-configuration writes ordered
by revision, and deterministic evaluation with a kill switch, ordered
equality rules, and a SHA-256 percentage rollout.

## Attribution

This challenge is adapted from the case study
["Design a Feature Flag Service"](https://hld.handbook.academy/curriculum/case-studies/feature-flag-service/)
by The HLD Handbook contributors
([handbook-academy/engineering-handbook](https://github.com/handbook-academy/engineering-handbook)),
licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
The prose in this README is adapted from that case study and is likewise
offered under CC BY-SA 4.0.

### What was changed

- The case study's `SHA256(salt + user_id) mod 10000` bucketing is made
  fully explicit: the salt is the `[tenant env flag-key]` triple, components
  are length-prefixed, and the digest is reduced through its first 8 bytes.
  The revision is deliberately excluded so that bumping a flag never
  reshuffles cohorts.
- Config versioning is reduced to a single integer revision per flag with
  "strictly greater wins"; an equal revision is a stale no-op.
- Targeting is reduced to ordered equality rules over subject attributes.
  Any other operator anywhere in the rule list forces the safe value.
- Kill switch is a boolean on the configuration that short-circuits every
  rule.

### What was excluded

SDK distribution (SSE, CDN polling, relay proxies), config bundles,
experimentation statistics, audit logging, flag deletion, mutual-exclusion
layers, and all capacity numbers. Evaluation here is a read against durable
state; where it runs (client or module) is your choice.

## Domain model

**Flag identity.** `[tenant env flag-key]`, all non-empty strings. Each flag
stores exactly one configuration map:

```clojure
{:revision      3
 :killed?       false
 :off-value     "off"
 :default-value "v1"
 :rules         [{:attribute "plan"    :operator :eq :value "enterprise" :serve "v2"}
                 {:attribute "country" :operator :eq :value "DE"         :serve "v1"}]
 :rollout       {:threshold 7000 :serve "v2"}}   ; optional; nil or absent = no rollout
```

`:off-value` is the safe value served when the flag is killed or its
configuration cannot be trusted. `:default-value` is served when evaluation
falls through every rule and the rollout. They are distinct fields so tests
can tell the two paths apart.

**Revisions.** `put-flag-config!` replaces the whole configuration iff the
new `:revision` is strictly greater than the stored one. Equal or lower is a
stale write: no-op. The first write for a flag is accepted at any positive
revision. No partial updates, no deletes.

**Bucketing.** `compute-bucket` is a pure function of
`tenant, env, flag-key, subject-id`:

1. For each of the four components in that order, emit the byte length of
   its UTF-8 encoding as a 4-byte big-endian unsigned integer, followed by
   those UTF-8 bytes.
2. SHA-256 the concatenation.
3. Read the first 8 digest bytes as a big-endian unsigned 64-bit integer.
4. Reduce modulo 10000. The bucket is in `[0, 10000)`.

A subject is in the rollout iff `bucket < threshold`, with `threshold` in
`[0, 10000]`. Threshold `0` admits nobody; `10000` admits everybody.

**Evaluation order.** `evaluate` decides in this order and stops at the
first that applies:

| Order | Condition | Result |
|---|---|---|
| 1 | no configuration stored | `{:value nil :revision nil :reason :missing}` |
| 2 | `:killed?` true | `:value` off-value, `:reason :killed` |
| 3 | any rule at any position has an operator other than `:eq` | `:value` off-value, `:reason :unknown-operator` |
| 4 | first rule whose attribute is present and equal | `:value` rule serve, `:reason :rule`, `:rule-index i` |
| 5 | rollout present and `bucket < threshold` | `:value` rollout serve, `:reason :rollout`, `:bucket b` |
| 6 | otherwise | `:value` default-value, `:reason :default`, plus `:bucket b` iff rollout present |

A subject missing a rule's attribute simply does not match that rule.
`:revision` in the result is the stored configuration's revision for every
reason except `:missing`.

## Worked example

Bucket values for the exact encoding above (first 8 digest bytes shown as
hex and as the unsigned integer that is reduced):

| tenant | env | flag-key | subject-id | first 8 bytes | as integer | bucket |
|---|---|---|---|---|---|---|
| `acme` | `prod` | `new-checkout` | `user-42` | `d50405362748c908` | 15349399160130947336 | **7336** |
| `acme` | `prod` | `new-checkout` | `user-7` | `91d85e7379cbb919` | 10509253580526696729 | **6729** |
| `acme` | `staging` | `new-checkout` | `user-42` | `2228bb14907ac41e` | 2461422893355680798 | **798** |
| `acme` | `prod` | `dark-mode` | `user-42` | | | **598** |
| `globex` | `prod` | `new-checkout` | `user-42` | | | **2961** |

The exact byte string hashed for the first row is, in hex:

```
00000004 61636d65            ; len 4, "acme"
00000004 70726f64            ; len 4, "prod"
0000000c 6e65772d636865636b6f7574  ; len 12, "new-checkout"
00000007 757365722d3432      ; len 7, "user-42"
```

Now store the configuration shown in the domain model under
`["acme" "prod" "new-checkout"]` at revision `3`.

1. `evaluate "acme" "prod" "new-checkout" "user-42" {"plan" "enterprise"}` →
   `{:value "v2" :revision 3 :reason :rule :rule-index 0}`
2. `evaluate ... "user-42" {"country" "DE" "plan" "free"}` →
   `{:value "v1" :revision 3 :reason :rule :rule-index 1}`
3. `evaluate ... "user-42" {}` → bucket `7336`, `7336 < 7000` is false →
   `{:value "v1" :revision 3 :reason :default :bucket 7336}`
4. `evaluate ... "user-7" {"plan" "free"}` → bucket `6729 < 7000` →
   `{:value "v2" :revision 3 :reason :rollout :bucket 6729}`
5. Store the same configuration under `["acme" "staging" "new-checkout"]`
   at revision `1`. `evaluate "acme" "staging" "new-checkout" "user-42" {}` →
   bucket `798` → `{:value "v2" :revision 1 :reason :rollout :bucket 798}`.
   Same subject, different env, different cohort.
6. `put-flag-config! "acme" "prod" "new-checkout" {... :revision 3 :killed? true ...}` →
   no-op (equal revision). `put-flag-config!` at revision `2` → no-op.
   `get-flag-config` still returns the revision `3` map with `:killed? false`.
7. `put-flag-config!` at revision `4` with `:killed? true` and otherwise
   identical → accepted. `evaluate ... "user-42" {"plan" "enterprise"}` →
   `{:value "off" :revision 4 :reason :killed}`. Kill beats every rule.
8. `put-flag-config!` at revision `5`, `:killed? false`, rules
   `[{:attribute "plan" :operator :eq :value "enterprise" :serve "v2"}
     {:attribute "age" :operator :gt :value 18 :serve "v2"}]` → accepted.
   `evaluate ... "user-42" {"plan" "enterprise"}` →
   `{:value "off" :revision 5 :reason :unknown-operator}`, even though rule
   `0` would have matched.
9. Revision `6` with no `:rollout` and no matching rules:
   `evaluate ... "user-42" {}` → `{:value "v1" :revision 6 :reason :default}`
   (no `:bucket` key).
10. `evaluate "acme" "prod" "never-set" "user-42" {}` →
    `{:value nil :revision nil :reason :missing}`.

## Efficiency contract

Bounds concern application-level records examined or updated, allowing
input/output-size costs and ordinary lookup and ranking overhead.

- `evaluate` and `get-flag-config` must read only the one flag's
  configuration. Work must not grow with the number of flags, envs, or
  tenants.
- `put-flag-config!` may do work proportional to its supplied configuration,
  independent of other flags.
- `compute-bucket` must be deterministic and must not read stored state.

## Input assumptions

- `tenant`, `env`, `flag-key`, `subject-id`, and attribute names are
  non-empty strings.
- `:revision` is a positive signed 64-bit integer; `:threshold` is an integer in
  `[0, 10000]`; `:rules` is a vector (possibly empty).
- Tests supply well-formed configurations except for the explicitly
  specified cases: stale revisions and rules with unknown operators.

## Write ordering and synchronization

`put-flag-config!` is an asynchronous write. Tests call
`(harness/wait-for-processing! client)` after a group of writes and before
any read. Writes addressed to the same flag must take effect in the order
the client invoked them. No ordering is required between different flags.

All authoritative business state must be durable Rama state (depots and
PStates); transient synchronization counters are allowed. The module runs
with both 2 and 4 tasks in private validation. Multiple clients wrapping
the same deployed module must observe the same business state after the
writing client synchronizes. Tests alternate synchronized client phases;
no cross-client concurrent ordering or snapshot isolation is required.

## Protocol

Your implementation must satisfy the `FeatureFlagService` protocol defined
in `src/hld_feature_flag_service/protocol.clj`. The docstrings there are
part of the contract.

## Contract: `create-module`

Your namespace must provide a `create-module` function returning:

```clojure
{:module       <RamaModule instance>
 :wrap-client  (fn [ipc] -> <FeatureFlagService implementation>)}
```

- `:module` — the Rama module to deploy
- `:wrap-client` — given a started IPC cluster, returns a reified `FeatureFlagService` implementation

You choose all internal names (depots, PStates, topologies) freely.

## Synchronization: `Synchronizable`

Your `wrap-client` must reify `rama-challenges.harness/Synchronizable`. See
the docstring on that protocol for implementation requirements. Tests call
`(harness/wait-for-processing! client)` after writes, before reads.

## Namespace

Your solution must be in namespace `hld-feature-flag-service.module`.

## File Location

Write your solution to:
```
implementations/hld-feature-flag-service/src/hld_feature_flag_service/module.clj
```

## nREPL

Start an nREPL with the test classpath:

```bash
clj -M:nrepl
```
