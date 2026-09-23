# Five-package development checkpoint

Status: **resumed September 23, 2026 at 14:07 UTC** on parent authorization.
One Fable 5.1 CLI phase runs at a time; no model substitution. Quota/auth
failures have not recurred. The earlier quota pause and initial checkpoint
below are historical, not instructions to stop current work.

## hld-url-shortener update (September 23, 2026, full-spec review)

This note covers only hld-url-shortener; the other four packages are
unchanged from the sections below. The BUILD phase produced
IMPLEMENTATION_VALIDATION.md and TEST_VALIDATION.md (Oracle test/README
fixes applied, module untouched, mutation evidence M1..M5 and M6b), and a
fresh full-spec review produced FULL_SPEC_REVIEW.md. The private suite
now has 4 tests and 270 assertions, independently green at 2 and 4 tasks
with the canonical command after moving winning/last/missing outcome
lookups into the measured work loop. The four "known test corrections" listed below have
been applied for the shortener (see README "Enforced work budgets"); the
opaque-value materialization check is a stated review-only limitation
because the event hook carries no value-size field.

## Historical 09:10 recovery state (superseded for the shortener above)

All five packages now have completed Phase-1 plans and Phase-2 validation
artifacts, with minor failures corrected in the plans. Fresh sessions
with the parent-corrected artifact template completed without safeguard
errors. The generic template modification is parent-owned and excluded
from package commits and transfer paths.

| Package | Reference and private tests | Independently executed checkpoint check |
|---|---|---|
| hld-url-shortener | module.clj and four private test/support files exist | 4 tests, 216 assertions, 0 failures/errors |
| hld-rate-limiter | module.clj and four private test/support files exist | 4 tests, 212 assertions, 0 failures/errors |
| hld-notification-system | not yet written | plans only; no module test run |
| hld-web-crawler | not yet written | plans only; no module test run |
| hld-search-autocomplete | not yet written | plans only; no module test run |

Each of the two completed checkpoint checks used exactly:

```bash
clojure -J-Xmx1600m -X:test-private-harness
```

Run from its own challenge directory, exit 0. The four tests are
functional and performance entrypoints at both 2 and 4 tasks. Two
wrappers from the same factory exercise shared synchronization barriers.
These are reference checks, not evaluation solves.

**No package is ready for registration.** No build-validation artifacts,
fresh full-spec review, mutation evidence, or final Oracle implementation
review exists. Known test corrections for shortener and limiter:

- The performance tests currently require `:depot-read` events and exactly
  800 observed records, inadvertently excluding valid stream designs.
  Remove that topology dependence; retain broad durable-write distribution
  and functional isolation checks.
- Small/large history operation-count equality is too strict. Use public,
  generous bounded-growth budgets rather than exact count equality.
- A whole-history opaque value could evade seek/iterator metrics; assess
  a practical value-size/materialization check without inventing hook APIs.
- README assertions about architecture-neutral checks must be updated to
  match the corrected executable tests.

Oracle's additional design findings are recorded in ORACLE_DESIGN_REVIEW.md.
The follow-up plans now separate autocomplete owner authority from read
replicas and stamp phrase operations on one ordered path; notification uses
same-microbatch contender arbitration extending recipient order. Implement
and test these details, including distinct losing payloads/devices. Crawler
follow-up notes incorrectly cost 1,001 seeks at roughly 10 ms; correct this
estimate and avoid unnecessary per-blocked-URL seeks where possible.

Both active BUILD processes exited 1 with the identical quota message,
"You've hit your session limit · resets 12:20pm (UTC)". No Claude or JVM
process remained after checkpoint test completion. Subsequent CLI evidence:

| Invocation | Result | Reported USD |
|---|---|---:|
| Phase 1 with corrected template | success, five plans | 6.67837575 |
| Phase 2 | success, five validation artifacts and local fixes | 6.773327 |
| Phase-2 Oracle follow-up | success, ordering revisions | 5.46199575 |
| BUILD shortener + limiter | session quota, partial artifacts preserved | 6.756768 |
| BUILD notification + crawler + autocomplete | session quota, no code written | 5.3742035 |

## Historical initial checkpoint

The initial status was **incomplete, blocked at Phase 1**. It covered only
`hld-url-shortener`, `hld-rate-limiter`, `hld-notification-system`,
`hld-web-crawler`, and `hld-search-autocomplete`.

## Completed

- Read the full five Handbook Academy case studies (source URLs and
  CC BY-SA 4.0 adaptation notices appear in each public README).
- Studied `bank-transfer-module` and `lib/harness` conventions.
- Oracle design review completed; recommendations incorporated into
  the public contracts. Important corrections include exact lease-expiry
  rejection, generation-guarded device invalidation, guarded retry order,
  rejection without limiter clock changes, and policy filtering before
  autocomplete top-k selection.
- Fable 5.1 fresh sessions produced Phase 0, a Phase-0 correction, and
  decomposition artifacts. Each package has README, protocol, deps,
  IMPLICIT_SPEC, and DECOMPOSITION files.
- Checkout started at the required reasoning-fix commit on master.

## Claude CLI evidence

All calls explicitly selected `claude-fable-5-1`; JSON usage reports
confirmed that canonical model. No fallback model was used.

| Invocation | Result | Reported USD |
|---|---|---:|
| Availability probe | READY | 0.2520785 |
| Phase 0 | success | 8.0780095 |
| Phase-0 correction | success | 3.470224 |
| Decompose | success | 1.68519925 |
| Phase 1 | safeguard `reasoning_extraction`, no plan written | 8.9382655 |
| Fresh Phase-1 retry requesting engineering summaries only | same safeguard | 0.6820435 |

The failure was **not a reported quota exhaustion**. The current
`.claude/commands/challenge-phase.md` contains the shipped safe engineering
log wording, but `artifact-plan.md` still asks for a first-person live
design-difficulty narrative. This is a possible trigger, not a proven
diagnosis. Shared skill and runner files were not modified.

Phase-1 request IDs:
- `req_011CfL2mtrinXu1ZQNy9jV41`
- `req_011CfL2tLBeBXuSBouxMCLfh`

## Checks actually run

- Loaded all five protocol files using `clojure -M -e` from the repository
  root: five `PASS protocol <name>` lines, exit 0.
- Ran `clojure -P -X:test-private-harness` from `challenges/hld-url-shortener`:
  exit 0, dependencies prepared (external-path deprecation warnings only).
- `git diff --check`: clean before checkpoint.

No private test suites, reference implementations, module plans, plan
validation, build artifacts, mutation tests, or final review exist yet.
No evaluation solves were run. These packages must not be registered as
complete challenges until the remaining work passes.

## Required continuation

Resume the skill's fresh-session Phase 1 with Fable, then plan validation,
build, and full-spec review. Add real durable Rama references under
test-resources and adversarial tests under test-private. Validate each
suite deterministically at both 2 and 4 tasks, with explicit waits and
a second client. Implement and exercise the stated cross-client barrier,
or explicitly narrow it before implementation. Keep synchronization
bookkeeping distinct from business state.

Scaling assertions must execute architecture-neutral work checks at
different history/corpus sizes, not impose exact topology types or exact
RocksDB counts, and not use the production latency aspirations as IPC
thresholds. Check independently derived outputs as well as work. Include
targeted plausible-bug mutations when practical. Obtain the requested
Oracle implementation/final review once code exists.
