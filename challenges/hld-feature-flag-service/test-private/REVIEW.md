# Private harness validation (2026-09-23)

Run from `challenges/hld-feature-flag-service`:

```sh
clojure -J-Xmx1600m -X:test-private-harness
```

Final restored run: exit 0 in 66.66 s; 2 tests, 120 assertions, 0 failures,
0 errors (`/tmp/hld-flag-private-final.log` in this orb). Both task counts
reported identical 240/1240 samples: get 1 read/0 writes, evaluate 1/0,
put 1/1; bucket 0/0. Prior restored run before the additional missing-vs-nil
attribute checks had 2 tests, 116 assertions, 0 failures, 0 errors.

The downloaded archive SHA-256 was
`49e2f3224c61105718bd903373e7f208b11b79ee7e229593fddd5b54854143d6`.
`sha256sum -c .amp/hld-program/transfers/hld-feature-flag-service.contract.sha256`
reported OK for README, deps, and protocol before and after work. The
reference module matches the archive (`8a9663cf5c1fafab2c8d33aef51b0c0e71d0f23d2d6b3eb82c494115aa9defa3`).

The single `private_challenge_test` entrypoint runs one 2-task and one 4-task
suite. Both aliases discover this same entrypoint; `requiring-resolve` selects
the solver or reference according to the classpath. No explicit `:nses` is
needed. The unused duplicate `private_harness_test` entrypoint was removed.

Resource-path checks using `clojure -M:test-private-harness -e` resolved
`hld_feature_flag_service/module.clj` under `test-resources`. A disposable
distinct implementation namespace under the solver path resolved with
`clojure -M:test-private` and its `create-module` sentinel raised two test
errors (2 tests, 2 assertions, exit 1), proving no reference fallback on the
solver alias when the solver path exists. The stub was removed.

The selected configuration and evaluation output stay fixed across 240 and
1240 unrelated flag identities spanning 17 tenants and 11 environments.
Each stage captures RocksDB point reads, iterator creation/reads, and committed
write-batch records for one selected get, evaluation, and revisioned write to
another key. `compute-bucket` must have zero captured stored-state reads and
writes at both sizes. A deliberately generous comparative bound rejects clear
growth rather than requiring a particular layout or precise operation counts.

Restored negative controls (both compiled and ran):

- Mutating `compute-bucket` to do one `foreign-select-one` before hashing:
  2 tests, 116 assertions, 4 failures, 0 errors, exit 1; each stage recorded
  one read instead of zero.
- Mutating `get-flag-config` to point-read every populated `other-i` key until
  the first missing key: 2 tests, 116 assertions, 2 failures, 0 errors, exit 1.
  The observed get work increased from 242 to 1242 reads on both task counts.

The initial attempt at a top-level `foreign-select [ALL]` control raised
`InvalidQueryPathException` and is **not** mutation-detection evidence.
Earlier author controls caught unknown-operator precedence and equal-revision
replacement (see `test-resources/development/VERIFICATION.md`).

Inspection against README and protocol found no additional semantic contract
gap requiring a public change. The harness exercises whole-map replacement,
revision boundary, ordered rules, missing/nil/false/typed attributes, kill and
unknown precedence, threshold edges, null/absent rollout, synchronized
wrappers, same-module update, and published plus independent bucket vectors.
Finite hook samples cannot see opaque serialized bytes, uninstrumented
client-side scans, or production crash/network behavior; the reference layout
has one per-key PState and no growing per-key history. The shared alias still
prefers an implementation path if one exists on the harness classpath; parent
owns any later deps/classpath hardening, not this frozen public edit.
