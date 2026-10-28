# Phase 2: Plan Validation

Validate `PLAN.md` against the spec. Produce `PLAN_VALIDATION.md` with a verdict.

**You are a skeptical reviewer.** Neither verdict is the default: the evidence decides. PASS only after explicit scenario-tracing for every constraint shows no violations, and FAIL only for a failure you can show with citations. A false rejection costs as much as a false pass: it sends a sound design back for a redesign it doesn't need.

## Inputs

- The user-facing spec (read it now, even if you read it earlier)
- `<impl-root>/IMPLICIT_SPEC.md`
- `<impl-root>/PLAN.md`
- This skill

## Scope

Validate the subsystem you were asked to validate. When the module is decomposed, other subsystems are black boxes: what the spec says about them binds this plan, but a property that only another subsystem can provide is **N/A** here, not FAIL. Note it in one line and move on.

A check whose premise doesn't hold for this plan (no query topologies, no TaskGlobals, no internal depots, no stream topology) is **N/A**, with one line saying why. Never FAIL a check the plan has no way to satisfy because it doesn't apply.

## Reason codes

Every FAIL carries exactly one reason code. It decides how severe the FAIL can be.

| Code | Meaning | Most severe verdict |
|---|---|---|
| `SPEC` | The plan violates a requirement in the user-facing spec. Quote the spec. | major-fail |
| `PRINCIPLE` | The plan breaks a design rule in this skill (wasted reads, unbounded unsubindexed collections, cost that grows with cluster size, non-idempotent stream writes). Name the rule and its reference file. | major-fail |
| `IMPLICIT` | The plan conflicts with an inference in `IMPLICIT_SPEC.md` that the spec doesn't state. | minor-fail |
| `OPEN-Q` | The plan depends on a question the spec leaves open. | minor-fail |

`IMPLICIT_SPEC.md` is advisory: it records reasonable inferences, not requirements. When the spec leaves a question open, the fix is a recorded assumption, not a redesign. On a minor-fail, write the assumption into a `## Assumptions` section of `PLAN.md`.

## Steps

1. Copy the validation template if it doesn't already exist:
   ```bash
   cp <skill-root>/references/artifact-plan-validation.md <impl-root>/PLAN_VALIDATION.md
   ```
2. Read the spec in full. Re-read every constraint, prohibition, and performance requirement. Do not paraphrase — quote the exact wording when checking compliance.
3. Read `PLAN.md` in full.
4. Read `references/artifact-plan-validation.md` — the template lists the categories of checks to perform.
5. Walk every check item. **Do not tick PASS until you have done explicit scenario-tracing.** For each constraint in the spec:
   - State the constraint verbatim.
   - Walk through a concrete scenario that exercises it (specific values, specific entity counts, specific timing).
   - Cite the line(s) in `PLAN.md` that handle this scenario.
   - State whether the cited mechanism actually handles it. Do NOT accept "the plan addresses this" without a citation.
6. **Self-consistency check.** Re-read your own validation entries. Every gap, shortfall or tradeoff you mention must appear as a FAIL with a reason code, or say why it is acceptable (the spec doesn't require more, or the check is N/A). Do not pass while leaving a gap you named unclassified.
7. Fill in `PLAN_VALIDATION.md` with the results. For each check, state PASS, FAIL or N/A with cited evidence.
8. End `PLAN_VALIDATION.md` with a `## Failures` table, one row per FAIL: check, reason code, severity (minor or major), the quoted requirement or named rule, and the `PLAN.md` line it concerns. Write `none` if there are no FAILs.

## Output

`<impl-root>/PLAN_VALIDATION.md`, fully filled in.

## Verdict

Emit one of `PHASE_VALIDATION:pass`, `PHASE_VALIDATION:minor-fail`, or `PHASE_VALIDATION:major-fail` as the last non-empty line of your output.

- **pass** — every check passes or is N/A after honest scenario-tracing.
- **minor-fail** — every failure is fixable by specific, localized edits to `PLAN.md`, or is `IMPLICIT` or `OPEN-Q`. After emitting the verdict, fix the plan directly: edit `PLAN.md` to address every FAIL item yourself, and record assumptions. Then proceed.
- **major-fail** — at least one `SPEC` or `PRINCIPLE` failure needs a different architecture (different PStates, partitioning, or topology structure), and the `## Failures` table quotes the requirement or names the rule it breaks.

When unsure between minor and major, choose **minor-fail** and say what you were unsure about. A redesign has to be earned by a failure you can show.

## Orchestration routing

This is a three-way verdict phase. The orchestrator uses the verdict to decide what happens next:

- **pass** / **minor-fail** → proceed to the `build` phase (implement, validate, test, and iterate to green in one session). On minor-fail the validator first fixes `PLAN.md` directly.
- **major-fail** → return to Phase 1 (plan) for revision. The plan author reads `PLAN_VALIDATION.md` and addresses every FAIL item. Up to 3 retry iterations; if the cap is hit, the orchestrator proceeds with the best plan so far.

## Do NOT

- Do NOT pass a plan to be agreeable, or fail it to look rigorous.
- Do NOT paraphrase a constraint to make the plan satisfy it. Quote the spec verbatim and check the plan against the verbatim text.
- Do NOT treat an `IMPLICIT_SPEC.md` inference or an open question as a spec requirement.
- Do NOT FAIL a property that belongs to another subsystem, or a check whose premise doesn't apply.
- Do NOT skip scenario-tracing. "The plan addresses constraint X" is not evidence — a citation to specific lines that handle a specific scenario is.
