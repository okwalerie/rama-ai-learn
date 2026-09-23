# Build log — hld-file-sync (private, resumed build session 2026-09-23)

Engineering records for the resumed build phase. Safe summaries only.

=== BUILD (resumed after quota reset) ===

Decision: resume from the preserved reference and validated plan; no planning phases repeated.
Basis: AUTHORING_CHECKPOINT.md; PLAN.md and PLAN_VALIDATION.md already carry the phase-2 fix.
Outcome: module inspected, one efficiency defect fixed, private suite written, build artifacts produced.

Decision: reuse the preexisting nREPL (PID 22707, port 47811) for probes only; run every verification in a fresh JVM.
Basis: the nREPL was healthy (correct working directory, module namespace loaded), but `:reload` of a namespace that defines `definterface` + `defrecord` produced a ClassCastException between old and new interface classes, and a later deliberately failing launch probe left the shared IPC executor rejecting tasks. No supervised-service convention exists in the repo for a replacement process.
Outcome: no new long-lived process started; all suite runs and the update probes used `clojure -X:test-private-harness` / `clojure -M:test-private-harness -i` in the foreground.

Decision: replace the quadratic `register-plan` with a set-backed membership check.
Basis: the previous version scanned the accumulated `new` vector for every block (`some #(= hash (first %)) acc`), quadratic in a 1024-entry call, contradicting the README's "proportional to the length of the submitted list".
Outcome: `{:seen #{} :new []}` reducer, first-occurrence order preserved; verified by the 1024-block boundary test (`:registered 1024`) and the register cost capture (15 reads, 7 writes for 5 blocks in a 1500-block namespace).

CONFUSION: ModuleAssignmentInfoNotFoundException in the earlier nREPL log had no established cause.
Evidence gathered (fresh JVM unless noted): stale client across two IPCs → `Executor pool is shut down` (not it); client before launch → `ModuleNotAliveException` (not it); client after `destroy-module!` → callback timeout (not it); failing launch → `RejectedExecutionException` in the nREPL IPC (not it); `update-module!` followed by foreign select/append → no log line; `update-module!` followed by the first `foreign-invoke-query` → the exact log line at 14:28:52.255, with the query returning the correct head record 120 ms later.
Resolution: it is Rama's client-side cache redirect for the query-topology handle after a module update; benign, logged once per update, no assertion affected. Recorded in IMPLEMENTATION_VALIDATION.md.

Decision: the reference keeps the plan's fallback (loop of point reads with `yield-if-overtime`) instead of `submap` with `:allow-yield?` for block lookups.
Basis: the plan permits the fallback; a yielding select runs on a stable snapshot and the register→commit-without-barrier ordering test requires seeing blocks written earlier in the same microbatch. Cost is the same k point reads.
Outcome: ordering test passes at 2 and 4 tasks.

Decision: private suite layout mirrors the bank-transfer package (support namespace + `*_challenge_test` resolving `hld-file-sync.module/create-module`), with explicit launches at `{:tasks 2 :threads 2}` and `{:tasks 4 :threads 2 :workers 2}` for both suites.
Basis: README "Tests exercise both 2 and 4 tasks"; two workers exercise inter-worker serialization of the custom records.
Outcome: functional 434 assertions, performance 106 assertions; total 540, 0 failures, 0 errors.

Decision: efficiency ceilings are loose constants plus small-vs-big ratios, with correctness asserted outside captures and evidence printed (`rocks-ops ...`).
Basis: prompt requirements; query results can be truncated under `with-event-hook`; event counts cannot detect blob storage (covered by schema review instead).
Outcome: see TEST_VALIDATION.md for measured counts.

Decision: one test bug found and fixed in the suite, not the module.
Basis: first run failed only at `functional_test_support.clj:138` because the expected journal entry used the 128-char id as `:request-id` while the commit was issued as `s4`; the module output was correct.
Outcome: expectation corrected; rerun green.

Decision: negative control = last-writer-wins mutant (`copy? false` in `commit-plan`).
Basis: prompt's suggested plausible bug for this domain.
Outcome: 60 failures, first at `functional_test_support.clj:223`; module restored byte-identical (`cmp`), restored suite green.

Decision: lint via clj-kondo after `bash scripts/import-kondo-configs.sh hld-file-sync`.
Outcome: tests 0 errors / 0 warnings. Module: 3 kondo errors and 12 warnings remain, all false positives of dataflow unification (`*outcome`/`*record` bound in both `<<if` branches, `set-launch-depot-dynamic-option!` not in the kondo config); the module compiles and runs.

Limitations stated plainly:
- The performance fixture is 1500 blocks / 1200 versions / 300 files per launch; ceilings are calibrated to reject scans of that history, not to certify production latency.
- `:depot-read` distribution is only checked when such events exist.
- No full-spec-review has run; a fresh session must do that next.

=== FULL-SPEC-REVIEW (fresh session, 2026-09-23) ===

Decision: review the whole package from the README outward, not from the Oracle list; treat the Oracle items as inputs to verify.
Basis: phase-full-spec-review.md ("the unit under review is the whole module against the whole spec"); Oracle findings were confirmed against the README text before any edit.
Outcome: no reference correctness defect found; all changes are to private tests, README wording, and evidence documents. Module byte-identical (sha256 prefix 9048c319e3c63ca0 before and after).

Decision: run every verification in a fresh JVM with `clojure -X:test-private-harness` (optionally `:nses` to split), foreground; never touch nREPL 22707.
Basis: that nREPL is polluted by a failed probe and an interface reload; the challenge-phase rules forbid background runs.
Outcome: five suite runs (79 s, 49 s, ~80 s, ~80 s, 97 s, ~100 s) all completed within the foreground timeout.

Decision: fix the cross-client dependency in "writes continue after the update" with a barrier between the two clients' commits rather than by issuing both through one client.
Basis: README "Ordering" guarantees order only within one client; keeping the second client as the issuer of the first commit preserves the two-client coverage while removing the unguaranteed dependency; reads still go through the other client.
Outcome: expectations unchanged in substance; suite green at 2 and 4 tasks.

Decision: drop the "depot reads on >= 2 tasks" assertion; keep the hook as a printed diagnostic and assert per-namespace independence with distinct sizes instead.
Basis: the README states no ingress topology or partition requirement; the old check also proved nothing about business independence because every namespace used size 1.
Outcome: `{0 2, 1 2, 2 2, 3 2}` printed at 4 tasks, `{0 1, 1 1}` at 2 tasks; independence asserted for 2·tasks namespaces plus an absent one and the untouched fixture.

Decision: bound empty-tail reads by `40 + 2·limit` and compare a fixed-limit empty tail on the small vs big journal.
Basis: README "proportional to `limit`, not to the journal length or to `after-seq`"; a per-candidate-seq point lookup is a valid O(limit) design that the old `< 40` bound at limit 500 rejected.
Outcome: reference tail costs 2 reads on both journals; a journal scan (1502 iterator reads) fails both the 140 bound and the 2·small+30 ratio.

Decision: add the three Oracle coverage scenarios inside existing groups/launches (ordering group: 1024 distinct hashes; shared-state group: repeated conflicting body across the update and rc2/q1 replay after the update).
Basis: no new test namespaces or launches; expectations computed from inputs (size sums 1547776 and 773632 stated as literals).
Outcome: functional assertions 434 → 556 across both launches; mutant A now fails 70 (was 60), mutant B fails 40 including the new blocks.

Decision: state in README "Shared state" that `wait-for-processing!` on any client of one `create-module` result covers every client's commands, and add a scenario with a client created after all writes.
Basis: the suite barriers through a different client than the writer; the harness docstring ("all pending topology processing") implies this, but the README did not, so a per-client counter could have read as compliant and failed the suite.
Outcome: one README sentence added; `fresh-client` scenario reads earlier state and issues + barriers seq 6; green.

Decision: record, not fix, the register cost of ≈ 2 RocksDB reads per new hash (2073 reads for 1024 blocks).
Basis: each write-only `[(keypath *ns :blocks *h)]` transform re-navigates the top-level entry; the cost is linear in the submitted list, which is the README guarantee; the plan's "2 + k seeks" was an estimate. Restructuring the write loop into one multi-path transform would drop the per-iteration yield the plan requires.
Outcome: measured numbers recorded in TEST_VALIDATION.md and FULL_SPEC_REVIEW.md; module unchanged.

CONFUSION: the first last-writer-wins mutant run reported "1 assertions, 0 failures, 1 errors".
Evidence: the sed-style replacement appended `;; MUTANT`, which commented out the `]` closing the `let` bindings, so the namespace failed to load. Resolution: re-ran with `copy? false` alone → 70 failures; the discarded run is noted in FULL_SPEC_REVIEW.md and not used as evidence.

Decision: verdict pass.
Basis: two further full hunt passes over every README clause after the fixes found nothing new; final run 2 tests, 710 assertions, 0 failures, 0 errors; mutants rejected; kondo clean; module unchanged.
Outcome: FULL_SPEC_REVIEW.md written; AUTHORING_CHECKPOINT.md, TEST_VALIDATION.md and IMPLEMENTATION_VALIDATION.md updated. Independent parent verification and the Oracle implementation review of this state remain to be done after this session.
