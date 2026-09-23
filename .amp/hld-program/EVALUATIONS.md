# Evaluation ledger

No counted attempt starts before all 15 packages and the planning-template repair are shipped. Record a thread and attempt ID here before dispatch. Every attempt uses a fresh Medium orb at the recorded shipped commit. Authoring calls, metadata checks, and infrastructure smokes are not attempts.

Configuration A: Claude CLI, `claude-fable-5-1` medium / `claude-opus-5-5` high.
Configuration B: OpenCode, `openrouter/z-ai/glm-5.3` high / `openrouter/meta/muse-spark-1.3-contributor` high. User explicitly authorized GLM-5.3 high in place of unavailable GLM 5.3 Pro medium.

| Challenge | A | B |
| --- | --- | --- |
| hld-url-shortener | UNSTARTED | UNSTARTED |
| hld-rate-limiter | UNSTARTED | UNSTARTED |
| hld-notification-system | UNSTARTED | UNSTARTED |
| hld-web-crawler | UNSTARTED | UNSTARTED |
| hld-search-autocomplete | UNSTARTED | UNSTARTED |
| hld-file-sync | UNSTARTED | UNSTARTED |
| hld-ticketing-system | UNSTARTED | UNSTARTED |
| hld-payment-system | UNSTARTED | UNSTARTED |
| hld-stock-exchange | UNSTARTED | UNSTARTED |
| hld-hotel-reservation | UNSTARTED | UNSTARTED |
| hld-metrics-pipeline | UNSTARTED | UNSTARTED |
| hld-ad-click-aggregation | UNSTARTED | UNSTARTED |
| hld-job-scheduler | UNSTARTED | UNSTARTED |
| hld-feature-flag-service | UNSTARTED | UNSTARTED |
| hld-enterprise-rag | UNSTARTED | UNSTARTED |

## Attempt record fields

ID, challenge, configuration, thread URL, shipped base commit, start/end UTC, implementation reached, runner phase/stop classification, private-suite command/exit/test/assertion/failure/error counts, native transcript/proxy log/result paths, requested and reported model/effort, measured cost, retry reason and counters, any excluded-reference access.

## Retry and quota policy

- Implementation completed but private tests fail: one fresh retry.
- Preimplementation logistics halt (including quota or reasoning extraction): fix logistics and continue; do not count as planning or implementation failure.
- Preimplementation planning failure: up to three fresh retries; stop earlier when implementation is reached successfully.
- No duplicate attempt while an existing worker/process is active. Preserve artifacts before retrying. Post-solve scorer context cannot become a fresh solver context.
- Claude five-hour quota exhaustion: stop further Claude dispatch; schedule a durable one-time continuation approximately five hours later after loading building-schedules. Record paused cells, active processes, schedule ID, due time, and actual resumption.
- Private SKIP, missing test footer, timeout, or zero tests is never success. Independently run the private suite on an existing implementation if runner reporting omitted it. Do not repair a solver implementation in the supervising context.
- Run strict filesystem/PID/network isolation. CONNECT restrictions are not TLS-authority inspection or adversarial containment. Retain logs; observed excluded-reference retrieval invalidates the attempt and requires a logistics repair/rerun.

## Quota pause 1 — authoring, not evaluation

September 23, 2026 at 09:04–09:06 UTC: all three author workers reported Claude session-limit exhaustion, reset 12:20 UTC. New Claude dispatch stopped; partial work preserved. All 30 cells remain UNSTARTED; no retry counter changes.

One-time continuation schedule `18dee77d-4d58-5e6c-937b-4e3ec6d0fc01` fired September 23 at 14:06:03.439 UTC (08:06 America/Edmonton), approximately five hours after the reports, and exhausted normally. Parent resumed coordination at14:06UTC after confirming three idle checkpoints. Discovery reported a real Fable5.1 phase reached package JVM validation at14:11UTC without quota/auth/safeguard error, confirming recovery beyond initialization. Transactions/analytics were resumed at14:13UTC, one active Fable session per worker. No separate smokes, evaluation attempts, or retry-counter changes. See STATE.md for package checkpoints.
