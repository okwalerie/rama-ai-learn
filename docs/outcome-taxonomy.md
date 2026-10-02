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
| `:unavailable` | A private suite exists but produced no verdict (not evaluated): `Ran 0 tests`, no `Ran N tests` line (compile or load error, missing implementation namespace), the `0/1` sentinel, a grader timeout, or a non-zero exit with no failing assertions. | `UNAVAIL` |
| `:not-run` | A private suite exists but was not started (the solver timed out, or the runner errored). | `not-run` |
| `:none` | The challenge has no `test-private/` directory. | `-` |

A sentinel (zero tests, no summary line, grader timeout) is **never**
reported as `FAIL`.

The `Private FAIL 0/1` sentinel is `Ran 1 tests`, `0 failures`, and every
counted assertion is an error (`Ran 1 tests containing 1 assertions. 0
failures, 1 errors.`). The suite errored before any check ran, so the tests
never ran: it is `:unavailable`, not `:fail`. A single test with a real
assertion failure (`1 failures`) stays `:fail`. The runner parses `Ran N tests containing M assertions.`
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

Each run writes `<report>.manifest.json` (JSON, `schema-version` 3) next to the
markdown report. The file is created once and never overwritten. It holds:

- run id, start and end timestamps, command-line arguments
- repository `HEAD` SHA, a dirty-tree flag, and the git tree SHA of each
  `challenges/<name>` directory
- agent, and the requested fast and slow model and effort
- grader timeout
- `isolation`: the record returned by the scored-run preflight (below):
  `mode` (`bubblewrap-provider-network` or `bubblewrap-public-only`),
  `filesystem` (`bubblewrap`), `network` (`strict` or `shared`), `bwrap`
  `{path, version}`, `probe`, `preflight`, `launcher`, `launcher-sha256`, and
  `snapshot` `{kind: "allowlisted-public-copy", shared-allowlist,
  challenge-allowlist, protected-dirs, audits: {<challenge>: {files,
  categories, violations: 0}}}`. A manifest cannot be built without it.
- `redaction-policy`: the rules below, `version` 2
- `evaluator-log`: the reference to the evaluator-only log (below), never its
  text: `{path, path-relative-to: "manifest-directory", sha256, bytes,
  audience: "evaluator-only", in-bundle: false, scrubbed: ["secret-env",
  "credential"]}`. A manifest cannot be built without it, and is not written
  unless the file next to it has exactly that length and SHA-256.
- per challenge: outcome, completion, private verdict with counts and reason,
  private-suite availability, score, builds, retries, a SHA-256 of the
  implementation tree, reported cost and estimated cost kept separate, and:
  - `private-test` (`null` when the suite was not started): `exit`,
    `timed-out`, `timeout-s`, `duration-s`, `status`, `sentinel`
    (`zero-of-one`, `ran-0`, `no-summary`, `grader-timeout` or `null`),
    `counted-as-failure` (true only for a genuine `:fail`), the
    scrubbed `stdout` and `stderr` (line for line), and `redactions`
    (count per kind)
  - `full-spec-review` (`null` when the stage never ran and wrote no
    report): `ran`, `verdict`, `exit`, `report-path`
    (`implementations/<name>/FULL_SPEC_REVIEW.md`), `report-present`,
    `report-skipped` (`"symlink"` when the report was a symlink, which is
    never followed), the scrubbed `report-text` and `final-message` (the
    session's last assistant message; line for line), and `redactions`
- per phase: id, attempt, subsystem, exit, verdict, timeout, provider-limit and
  user-stop flags, `isolation` mode, transient retries, duration, transcript
  path and SHA-256, reported cost and estimated cost

## Run bundle

Next to the manifest the runner writes `<report>.bundle.tar.gz`, also never
overwritten. It contains one file, `<run-id>/BUNDLE.json`:

```
{"schema-version": 1, "kind": "rama-ai-learn-run-bundle", "run-id": ...,
 "created-at": ..., "manifest-sha256": <SHA-256 of the manifest file>,
 "redaction-policy": {...}, "manifest": {...the manifest above...}}
```

The markdown report, transcripts and the evaluator log are not bundled:
alignment justifications come from a scorer that reads the reference
solution, and transcripts and the evaluator log are unscrubbed.

## Evaluator log

Before the manifest, the runner writes `<report>.private.log` next to the
report, in `../reports/` (outside the repository; `*.private.log` is also
gitignored). It is for the evaluator only and holds, per challenge, the
complete private-test `stdout` and `stderr`, `FULL_SPEC_REVIEW.md` and the
full-spec-review final message. Only the `secret-env` and `credential` rules
below are applied: protected text, clojure.test `expected:`/`actual:` lines
and private-test data are kept verbatim. Each section starts with
`===== <challenge> | <section> | <N> bytes =====` (N is the exact UTF-8
length of the text that follows) and ends with
`===== end <challenge> | <section> =====`.

The file is created owner-only (`0600`), never overwritten, synced, and read
back to check its length and SHA-256. If any step fails, or the reports
directory is inside a solver-writable `implementations/` directory, the file
is removed and no manifest or bundle is written; the run then exits with an
error after appending the results database. No solver can read it: solvers
see only the allowlisted snapshot, their implementation directory and named
dependency paths (see "Scored-run isolation"), none of which contains
`../reports/`. Hosts with other readers of `../reports/` (shared accounts,
backups, sync) must treat the file like `test-private/`.

## Redaction

`private-test.stdout`, `private-test.stderr`, `full-spec-review.report-text`
and `full-spec-review.final-message` keep every line of the original text
(redaction never adds or drops a line). Spans are replaced in place, in this
order, and counted per kind in `redactions`:

| Kind | What is replaced | Replacement |
|---|---|---|
| `secret-env` | The value of every set environment variable whose name contains `KEY`, `TOKEN`, `SECRET`, `PASSWORD`, `PASSWD`, `CREDENTIAL` or `AUTH` (case-insensitive) and whose value is at least 8 characters. | `[REDACTED:env:<NAME>]` |
| `credential` | PEM private-key blocks; `sk-ant-…` and `sk-…` API keys; GitHub `gh?_…`/`github_pat_…`, AWS access key ids, Slack and JWT tokens; `Authorization:` and `Bearer` values; `user:password@` in URLs; values of 8+ characters assigned with `=` or `:` to a name containing `api_key`, `secret`, `token`, `password`, `passwd` or `credential`. | `[REDACTED:<kind>]` |
| `private-test-assertion` | Private-test output only: the form after a clojure.test `expected:`, and an `actual: (not …)` comparison, which embeds the expected value. | `[REDACTED:private-test-assertion]`, `[REDACTED:private-test-comparison]` |
| `protected-plaintext` | Every run of 1–8 consecutive words that occurs in a protected file and in no public text. Words are runs of letters and digits, compared case-insensitively, so punctuation and spacing do not matter and there is no minimum length: a one-letter literal counts. Adjacent redacted words become one marker. | `[REDACTED:protected-plaintext]` |
| `private-test-data` | Private-test output only, on every line except clojure.test `Testing`/`FAIL in`/`ERROR in` headers, the `Ran …`/`… failures, … errors.` summary, stack frames (`at …(File.java:N)`, `... N more`) and already-redacted `expected:`/`actual:` lines: quoted strings, `{…}`/`[…]`/`#{…}` literals, numbers outside `file:line` locations, and words that are not public (see below). This covers exception messages, `ex-data`, and anything the tests or the implementation print. | `[REDACTED:private-test-data]` |
| `protected-index-unavailable` | Every non-blank line of every scrubbed field, when the protected files cannot be verified: a protected file cannot be read, only its `.enc` ciphertext is present, or the challenge directory is missing or contains a symlink or special file. | `[REDACTED:protected-index-unavailable]` |

**Protected files** are every file in `challenges/<name>/` that the solver
snapshot leaves out (see "Scored-run isolation"): `test-private/`,
`test-resources/`, `test/`, `test-harness/`, `src/` files whose names
contain `test`, and any other file outside the snapshot allowlist. Only
`.cpcache/` is skipped. Each protected file is streamed in full in 1 MiB
chunks, so there is no size limit, and binary files are scanned too.

**Public text** is what the snapshot copies (the shared and challenge
allowlists, with the same exclusions), plus repository-relative file paths
and a fixed list of clojure.test/JVM diagnostic words. The
`private-test-data` word check also accepts words from
`implementations/<name>/src/`, so the implementation's own exception
messages survive.

Never compared or redacted: line and column numbers in `file:line`
locations, and the clojure.test count lines. A word sequence is matched only
within one output line and between redaction markers.

Remaining limitations (also listed in the manifest as
`redaction-policy.limitations`):

- Values computed at run time from protected inputs never appear literally
  in a protected file. In private-test output, the `private-test-data` rule
  catches them when they are printed as numbers, strings, collections or
  non-public words. In review text, which the solver writes without access to
  protected files, only literal matches are redacted.
- A run of words is kept when the same consecutive run also occurs in public
  text, because the solver can see it.
- Only the challenge's own files count as protected sources. Repository
  `docs/`, `review/`, `.amp/` and other challenges' files do not.
- Credential patterns are heuristic.

## Scored-run isolation

Every `bb run-challenges` run is scored, so the preflight rejects a run
without `--isolate-network` or `--isolate` before any solver starts. It then
runs `scripts/isolate_solver.py --preflight`, which fails unless bubblewrap
launches a namespaced probe and every selected challenge's snapshot passes
the audit: every file under the allowlist; no `.git`, `test-private`,
`test-resources`, `test`, `test-harness`, `review` or `atlas` path, `.enc`
file or credential-named file; no byte-identical copy of any file under
`challenges/*/test-private`, `challenges/*/test-resources`, `docs/`,
`review/` or `.amp/`; no private-key or token pattern; and no value of a
secret-named environment variable. The launcher repeats the audit before
every solver phase and refuses a failing snapshot. Audit output names paths,
rules and variable names, never contents or values.

## Grader time limit

Private tests run under `bb run-challenges --grader-timeout <seconds>` (default 1800). The grader
runs in its own process session through `setsid` where available. On timeout,
or after a normal exit, the runner kills the whole process group, so leftover
JVMs or cluster workers cannot hold the output pipes open. A timeout records
the private verdict as `:unavailable` with the reason `grader timeout`.
