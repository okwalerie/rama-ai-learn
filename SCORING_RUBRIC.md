# Rama Challenge Scoring Rubric

Each challenge attempt is reported on three independent axes, plus one
informational comparison. No axis is folded into another.

| Axis | Question | Source |
|---|---|---|
| Headline | Is the implementation correct? | Private test verdict, see [outcome taxonomy](docs/outcome-taxonomy.md) |
| Score | Did it pass, and how many retries did it take? | Headline outcome and semantic retries |
| Quality | Does it avoid known Rama design failure modes? | Binary judges, see [judge criteria](docs/judge-criteria.md) |
| Alignment (informational) | How does its structure compare with the reference? | LLM comparison, 0-5 |

## 1. Headline: private verdict

The headline is the outcome derived in
[docs/outcome-taxonomy.md](docs/outcome-taxonomy.md). The private test suite
is the only correctness signal.

| Outcome | Meaning |
|---|---|
| `private-pass` | The private suite ran at least one test, exited 0, and reported no failures or errors. |
| `private-fail` | The private suite ran at least one test and reported at least one failure or error. |
| `private-unavailable` | A private suite exists but produced no verdict: `Ran 0 tests`, no `Ran N tests` summary, grader timeout, a non-zero exit with no failing assertions, or the `Private FAIL 0/1` sentinel (one test whose only report is an uncaught error, so the suite errored before any assertion ran). |
| `public-pass` | The challenge has no private suite, and the run completed. |
| `solver-fail`, `solver-no-implementation`, `timeout` | No private verdict, for a solver-side reason: the solver ended the run (failed or missing verdict), left no implementation, or ran out of time. |
| `infra-error`, `quota-or-provider-limit`, `user-stopped` | No private verdict, because something other than the solver ended the run. |

Rules:

- `PASS` is never shown when the private tests `FAIL` or are unavailable.
- A phase or runner `PASS` alone is completion, not correctness. It is shown
  as `Runner`, never as the challenge result.
- A sentinel is never reported as `FAIL`. It is `private-unavailable`.

## 2. Score

| Outcome | Score |
|---|---|
| `private-pass`, `public-pass` | `max(1, round(100 / 2^retries))` |
| `private-fail`, `solver-fail`, `solver-no-implementation`, `timeout` | `0` |
| `private-unavailable`, `infra-error`, `quota-or-provider-limit`, `user-stopped` | unscored: shown as `-` and left out of averages |

| retries | 0 | 1 | 2 | 3 | 4 | 5 | 6 | ≥7 |
|---|---|---|---|---|---|---|---|---|
| score | 100 | 50 | 25 | 13 | 6 | 3 | 2 | 1 |

`retries` counts only a phase invocation that directly follows a `FAIL` or
`MAJOR_FAIL` verdict in the same subsystem. It does not count:

- one build per independent subsystem: three subsystems are three builds and
  zero retries
- transient provider retries inside one phase invocation, which are
  infrastructure, not solver behaviour

`builds` is reported as telemetry only.

## 3. Quality: judge vector

Quality is a vector of independent binary judges, `J1` to `J8`, defined in
[docs/judge-criteria.md](docs/judge-criteria.md). Each judge detects exactly
one failure mode and reports exactly one value:

| Value | Meaning |
|---|---|
| `Pass` | The judge ran and did not detect its failure mode. |
| `Fail` | The judge ran and detected its failure mode. |
| `N/A` | The judge's precondition does not apply to this implementation. |
| `Unjudged` | The judge has not passed calibration (see the [calibration plan](docs/judge-criteria.md#calibration-plan)), was not run, or could not decide. |

- The runner does not compute judges yet. Until a judge is implemented and
  calibrated, its value is `Unjudged`.
- Report each judge's value separately, one column per judge.
- Do NOT average, sum, weight or combine judge values. There is no composite
  quality score.
- Judge values never change the headline or the score.

| Challenge | Headline | Score | J1 | J2 | J3 | J4 | J5 | J6 | J7 | J8 |
|---|---|---|---|---|---|---|---|---|---|---|
| `<name>` | `<outcome>` | `<score or ->` | `<value>` | `<value>` | `<value>` | `<value>` | `<value>` | `<value>` | `<value>` | `<value>` |

## 4. Structural alignment (informational only)

Structural alignment is an LLM comparison of the implementation with the
reference implementation. It is reported, but:

- it never affects the headline or the score, and is not part of the
  quality vector
- it is not a correctness signal: a high alignment score does not make a
  failing implementation correct, and a low one does not make a passing
  implementation wrong
- a different but valid design is not penalised: alignment measures added
  complexity, not resemblance to the reference

The runner's test-coverage alignment score (`TestAlign`) is informational on
the same terms.

The runner sends only this section to the alignment scorer. The anchors are
unchanged from the earlier rubric, so alignment values stay comparable across
runs.

Measures how closely the approach maps to the reference implementation's
structure. This dimension penalises unnecessary complexity, not valid
alternatives. A different but equivalent approach should score 4-5; a
more complex approach that achieves the same result should score lower.

| Score | Anchor |
|-------|--------|
| 5 | Approach is structurally equivalent to the reference or simpler. Any differences are purely stylistic. |
| 4 | Minor structural difference (e.g., one extra intermediate PState, or a slightly different path decomposition) with no complexity cost. |
| 3 | Noticeably more complex than the reference (extra topology, additional indirection layer, more states to reason about) but arrives at a correct result. |
| 2 | Substantially more complex than the reference in a way that introduces risk or maintenance burden: redundant topologies, unnecessary coordination, extra depot. |
| 1 | Approach is architecturally divergent from the reference in a way that is harder to reason about, harder to extend, or requires more code to accomplish the same thing. |
| 0 | Reference implementation is not available. Cannot score alignment. |
