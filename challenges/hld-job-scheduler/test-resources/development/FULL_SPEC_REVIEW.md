# Full-spec and verification review

The README and protocol were checked against the reference and private tests after the focused Oracle review. Verdict: PHASE_VALIDATION:pass for the contract exercised here; this is not a proof of every production failure interleaving.

The ordered, execution-hashed depot and one microbatch topology apply submit, clock, claim, and completion on the owning task. `:state` is a bounded 32-node ExecutionState; `:claims` is a subindexed per-execution point-lookup map. Submission writes only `:state`, preserving decisions for unknown executions. Claim replay checks the durable decision before inspecting its new body or node state. Completion requires the exact worker and token and C < expiry, writes success once, and does not claim external exactly-once execution. The effect ID is derived from the execution and node identifiers, never from attempts. All reads select one execution's bounded state or one decision. Transient append counts are shared by wrappers of one IPC; no business data is cached in clients.

The harness uses independent expected maps and runs each scenario with **2 and 4 tasks**, two wrappers, writer barriers, an unchanged module update, post-update writes, a 32-node chain, 40 unrelated executions, and 80 decisions under one execution. The growth scenario is functional and architecture-neutral, **not a measured bound**: opaque PState bytes cannot be inferred from event hooks. Layout/path inspection supplies the bounded-work argument; no latency claim is made. It does not exercise a process crash, concurrent cross-client calls, or an external effect worker.

Focused Oracle review found no reference blocker; it identified missing granted/denied replay and post-update synchronization checks, which were added. Its observation that nil was outside the stated test-value corpus led to replacing that test value with a small map. The review did not execute tests.

From this package directory, `clojure -J-Xmx1600m -X:test-private-harness` returned exit 0 in 67.19 seconds with **2 tests, 90 assertions, 0 failures, 0 errors**; complete output: `VERIFICATION-harness.log`. `-X:test-private` uses `test-private` and the implementation path, not `test-resources`; the `-X:test-private-harness` alias additionally includes `test-resources`. The solver wrapper requires the solver namespace and passes its `create-module` to the same support functions.

Negative controls (full logs retained here) were temporary source mutations, each compiled and ran against the same harness:

- `completed-state`: C < expiry → C <= expiry, incorrectly accepting completion at exact expiry. The prior 82-assertion suite failed 12 assertions, 0 errors, including ready status and fenced-result checks (`VERIFICATION-mutant-expiry.log`). Restored.
- Claim replay guard: `(nil? *old)` → always true, recomputing existing decisions. The 90-assertion suite failed 22 assertions, 0 errors, including exact unknown denial and historical granted/denied decision checks (`VERIFICATION-mutant-replay.log`). Restored.

The restored reference was rerun green as above. The transferred frozen contract manifest was verified with `sha256sum -c .amp/hld-program/transfers/hld-job-scheduler.contract.sha256` from the repository root: all three files OK. No benchmark solving evaluation was run.
