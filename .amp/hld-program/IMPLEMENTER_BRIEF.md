# Per-challenge implementation handoff

You own the one challenge named in your initial prompt in `okwalerie/rama-ai-learn`. Work yourself in this fresh Medium orb. Do not create another thread. Claude CLI is no longer required for reference implementation; use this Amp worker and preserve useful existing work. This is challenge AUTHORING, not a counted solving evaluation.

## Exact input and ownership

The parent provides an archive containing the entire current package, its plans/review/recovery artifacts, and a SHA-256 manifest for README, protocol, and deps. Your remote default branch is `origin/master`; parent integration branch `hld-case-studies` contains unpushed work, so a branch name or commit ID alone does not transfer the package. Download the named archive from the parent using `download_thread_file`, verify its supplied digest, and extract from repository root. Verify the contract manifest before coding and before returning.

Own changes only under your named `challenges/<name>/` directory. Do not edit shared skills, runner, registration, other packages, or accepted metrics/URL/rate challenges. The README and protocol are frozen authoritative requirements. Plans and reviews are useful design evidence, not authority over the public contract. Do not weaken or silently expand the contract to suit an implementation or test. Report genuine contradictions to the parent with exact clauses, competing interpretations, and proposed smallest reconciliation; continue independent work where possible.

## Build

Read repository guidance and load Rama before reading/writing module code. Read the supplied recovery and review findings before changing code. Reuse completed phases and working reference portions. Follow Rama phase guidance with fresh-context bounded subagent help where a new phase requires it; do not restart already completed planning merely for ceremony. For an incomplete multi-subsystem plan, complete the needed phase before implementing it.

Build real durable Rama reference code in `test-resources/<namespace>/module.clj`, returning the existing `{:module :wrap-client}` contract. Keep business state in Rama, not client atoms. Transient synchronization bookkeeping is allowed only as the public contract permits. Preserve exact time, retry, version, rejection, and ordering semantics. Avoid materializing unbounded subindexed histories; bound work by the public operation's input/output/expiry scope. Make long processing cooperative where required. Do not replace a distributed module with a central in-memory simulator.

Write private harnesses in `test-private/<namespace>/`, callable via the existing private aliases against either the reference or a future solver implementation. Expectations must be independent of the reference. Exercise explicit 2-task and 4-task deployments, fresh/second wrappers and barriers, lifecycle/version boundaries, conflicting retry bodies, durable state across module update when applicable, and architecture-neutral work bounds stated publicly. Do not require a particular topology, depot layout, PState name, exact reference operation count, or unspecified cross-client ordering. Do not introduce private latency thresholds. Include asymmetric boundary cases where plausible wrong implementations differ.

Existing tests may contain known overconstraints; correct them against the frozen public contract. For performance instrumentation that cannot observe opaque-value bytes, document the limitation honestly and inspect the reference layout; never invent event-hook fields or claim a nonexistent executable guarantee. A test pass is not proof of all production failure modes.

## Verification and return

Run the full private harness from the package, preferably `clojure -J-Xmx1600m -X:test-private-harness`, retaining the complete log, exit status, test/assertion/failure/error counts, task configurations, and runtime. Verify aliases do not accidentally test the reference on the solver path. Reject zero-test passes or missing-test footers. Use finite foreground test commands and tracked process status. Supervise REPL services with `amp orb service`; never rely on shell backgrounding to survive pause.

Run focused negative controls against risky behavior and restore the reference, then rerun green. Compilation errors do not count as mutation detection. Record the mutant change, expected distinction, actual failing assertions, and restored revision. A separate validation orb may perform additional independent controls; do not duplicate its assigned work once the parent names it.

Obtain focused Oracle review where requested in your task, repair concrete findings, and preserve review evidence privately. Final cross-contract review must distinguish inspected guarantees from executed tests. Return a local commit containing only your owned package, reproducible commands/results, contract manifest verification, known limitations, and any blockers. Do not push, open PRs, ship, or run benchmark solving evaluations. Parent owns integration/shipment/evaluations.

Reply to parent `T-01a0cd1f-f3a0-77ae-b5c5-f68127682fa0` using `send_thread_message` when complete or genuinely blocked. Include the same findings in your final response. Parent will transfer the entire package, inspect it, and independently rerun its harness. Deliver a complete package, not just a plan checkpoint.
