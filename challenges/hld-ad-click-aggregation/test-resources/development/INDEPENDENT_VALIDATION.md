# Independent frozen-reference validation

Scope: semantic tests only; no public contract, reference, or pre-existing
harness edits. Input archive SHA-256:
`11004bf317dcdc9353b6bbed6de3aad4696fbc7ce0dda0436345cc0ffaaaab4b`.
The README, deps, and protocol hashes in the transferred contract manifest
matched at extraction and after testing. Reference module SHA-256 before and
after the temporary control: `7ca80f9303587896beda9fe373c61099aedd292650bd87d6911487cf487bbed2`.

`independent_semantics_test.clj` adds two explicit 2-/4-task deployments,
26 assertions total. The same request ID is first late in campaign A at
watermark 180 and billed in B at 179; the audit watermarks, both campaign
windows, and billed spend are checked independently. After B closes at 180,
conflicting retries arrive from a second wrapper. A's late record must not
turn into a billed future-window click, B's bill must not change, and a new
request in B's closed window must remain audit-only. These distinguish
global request-ID dedup, processing/arrival-time substitution, and billing
or audit rewrites on retry. Expected records/counters are literal
contract-derived values, not computed by the reference.

Commands from `challenges/hld-ad-click-aggregation` (logs kept under `/tmp`):

- `clojure -J-Xmx1600m -X:test-private-harness :nses '[hld-ad-click-aggregation.independent-semantics-test]'`
  → 2 tests, 26 assertions, 0 failures, 0 errors; 49.50 s, exit 0.
- `clojure -J-Xmx1600m -X:test-private-harness`
  → 6 tests, 414 assertions, 0 failures, 0 errors; 104.47 s, exit 0.
  This includes the original functional and efficiency suites. The IPC
  logged transient Kafka index recovery / module leader-resolution errors
  during setup/update, but no test errors.
- Negative control: temporarily treated an existing `:late` audit as a new
  arrival on retry, then ran the selected command. The compiled mutant ran
  2 tests / 26 assertions and produced **6 assertion failures, 0 errors**
  (38.36 s, exit 1): the old audit was replaced by `:billed` with timestamp
  600, and a billed window/spend 1000 appeared in both task configurations.
  An initial inline `or` mutation failed Clojure/Rama macro compilation and
  was **not** counted as evidence; the executed control used a pure predicate.
  Restored the reference byte-identically, confirmed its SHA-256, and reran
  the selected command → 2 tests, 26 assertions, 0 failures, 0 errors;
  40.83 s, exit 0.

No reference defect reproduced. Existing fairness issues described in
`RECOVERY.md` remain separate: efficiency growth compares a tiny baseline
against a grown state, and update uses a fresh factory result. Neither was
changed here. IPC cannot inject a process-loss retry or prove production
exactly-once behavior; the adversarial calls are explicit replay writes.
