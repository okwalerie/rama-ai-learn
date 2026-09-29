# Challenge outcome taxonomy

`scripts/run_challenges.bb` records three separate facts about every
challenge attempt, then derives one headline outcome from them. Each fact is
on its own axis, so the headline can never hide a failing or missing private
verdict behind a passing phase pipeline.

## Axis 1: completion (how the solver run ended)

| Value | Meaning |
|---|---|
| `:completed` | Every phase exited 0 and the build and full-spec-review verdicts were `PASS`. |
| `:solver-fail` | The solver ended the run: a `FAIL` verdict, a missing or invalid verdict, repeated plan-validation failures, or a non-zero exit that is not one of the causes below. |
| `:timeout` | A phase or the overall challenge budget timed out. |
| `:quota-or-provider-limit` | The failing phase's error fields match a quota, billing, rate-limit, overload or 5xx pattern after the transient-retry cap. |
| `:user-stopped` | The failing phase exited 130 (SIGINT) or 143 (SIGTERM) without a runner timeout. |
| `:infra-error` | The runner threw before or around the solver (tooling set-up, hidden set-up, encryption). |

Completion is not correctness. A `:completed` run only means the solver
said it was finished.

## Axis 2: private verdict (the correctness signal)

| Value | Meaning | Shown as |
|---|---|---|
| `:pass` | The private suite ran at least one test, exited 0, and reported no failures or errors. | `PASS` |
| `:fail` | The private suite ran at least one test and reported at least one failure or error. | `FAIL` |
| `:unavailable` | A private suite exists but produced no verdict: `Ran 0 tests`, no `Ran N tests` line (compile or load error, missing implementation namespace), a grader timeout, or a non-zero exit with no failing assertions. | `UNAVAIL` |
| `:not-run` | A private suite exists but was not started (the solver timed out, or the runner errored). | `not-run` |
| `:none` | The challenge has no `test-private/` directory. | `-` |

A sentinel (zero tests, no summary line, grader timeout) is **never**
reported as `FAIL`. The runner parses `Ran N tests containing M assertions.`
and `F failures, E errors.` rather than trusting the exit code alone.

## Headline outcome

The headline is derived in this order; the first match wins:

1. `:infra-error` when the runner threw.
2. `:private-pass` or `:private-fail` when the private suite produced a verdict.
   The private verdict dominates whatever the phase pipeline reported.
3. `:timeout`, `:quota-or-provider-limit` or `:user-stopped` from completion.
4. `:solver-no-implementation` when `implementations/<name>/src` has no `.clj` files.
5. `:private-unavailable` when a private suite exists but gave no verdict.
6. `:public-pass` when there is no private suite and completion is `:completed`.
7. `:solver-fail` otherwise.

Console lines, the summary table, the markdown report, `results.edn` and the
run manifest all lead with this outcome. The runner's own phase status is
shown as `Runner`, and never as the challenge result.

## Score

| Outcome | Score |
|---|---|
| `:private-pass`, `:public-pass` | `max(1, round(100 / 2^retries))` |
| `:private-fail`, `:solver-fail`, `:solver-no-implementation`, `:timeout` | `0` |
| `:private-unavailable`, `:infra-error`, `:quota-or-provider-limit`, `:user-stopped` | not scored (`-`), and left out of averages |

`retries` counts **semantic retries** only: a phase invocation that directly
follows a `FAIL` or `MAJOR_FAIL` verdict in the same subsystem. It does not
count:

- one build per subsystem: decomposing into three subsystems is three builds
  and zero retries
- transient provider retries inside one phase invocation, which are
  infrastructure, not solver behaviour

`builds` (the old `iterations`) is still reported as telemetry.

## Run manifest

Each run writes `<report>.manifest.json` next to the markdown report. The file
is created once and never overwritten. It holds:

- run id, start and end timestamps, command-line arguments
- repository `HEAD` SHA, a dirty-tree flag, and the git tree SHA of each
  `challenges/<name>` directory
- agent, and the requested fast and slow model and effort
- grader timeout
- per challenge: outcome, completion, private verdict with counts and reason,
  private-suite availability, score, builds, retries, a SHA-256 of the
  implementation tree, reported cost and estimated cost kept separate
- per phase: id, attempt, subsystem, exit, verdict, timeout, provider-limit and
  user-stop flags, transient retries, duration, transcript path and SHA-256,
  reported cost and estimated cost

## Grader time limit

Private tests run under `--grader-timeout` seconds (default 1800). The grader
runs in its own process session through `setsid` where available. On timeout,
or after a normal exit, the runner kills the whole process group, so leftover
JVMs or cluster workers cannot hold the output pipes open. A timeout records
the private verdict as `:unavailable` with the reason `grader timeout`.
