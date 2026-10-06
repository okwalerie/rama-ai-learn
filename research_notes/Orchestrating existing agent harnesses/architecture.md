# Architectural boundaries for orchestrating existing agent harnesses

## Are nested harnesses, first-order agents, and external workers exclusive alternatives?

### Takeaway
No. Harness ownership (whose model/tool loop runs), execution location (process/container/VM), and control protocol (direct call versus durable job/worker client) are separate decisions. Recommended design hypothesis: keep the existing coding harness in an isolated worker, make the outer graph own a coarse durable job, and let the worker communicate as a client.

### Cited Findings
- Agent-o-rama defines nodes as ordinary Java/Clojure functions running on virtual threads; functions can therefore be long running and written in blocking style. Its programming guide says failed nodes retry, with a default maximum of two retries configured through `max.retries`. — [Programming agents](https://github.com/redplanetlabs/agent-o-rama/wiki/Programming-agents)
- The platform makes LangChain4j optional, permits other model APIs and external integrations, and provides evaluation, tracing, storage, and deployment around graph execution. — [Official repository](https://github.com/redplanetlabs/agent-o-rama)

### Inferences
- A graph node need not reproduce the CLI's reasoning loop. It can represent a meaningful application operation, such as “produce a candidate implementation,” while the CLI retains context management, tool decisions, and local session state. Calling this “subsuming the harness” risks conflating lifecycle ownership with internal control.
- The proposed external client is better described as a worker plus a CLI. A small worker daemon/adapter claims a job, creates the environment, launches a CLI, reports events, handles cancellation, and uploads artifacts. The LLM need not implement the scheduling protocol itself.
- Worker-as-client and graph-node-as-remote-job are compatible views of one system: the graph records the job lifecycle; the worker makes outbound claim/heartbeat/result requests. Push webhooks are dispatch hints and still require a durable authoritative job record if missed or duplicate delivery matters.
- Direct CLI subprocess inside a graph node: smallest adapter and easy initial input/output plumbing; couples process lifetime to graph execution and requires careful reattachment/retries. It can still launch a remote VM, so nesting does not imply colocated tools or absent sandboxing.
- First-order model/tool loop: most precise authority over context, budgets, tool policies, checkpoints, and trace semantics; also assumes responsibility for compaction, coding tools, prompts, recovery, session UX, model quirks, and ongoing harness evolution. It can run as either local node logic or an external worker.
- External CLI worker: clean fleet/environment lifecycle, mixed runtime support, independent reconnect and upgrades; costs a real distributed job protocol, delivery deduplication, reconciliation, credential scoping, and event schema/version handling. Externalizing does not remove orchestration complexity; it moves the boundary to a better-defined service contract.
- VM orchestration is orthogonal to inference billing. Any of these architectures can use allowed API billing; subscription eligibility depends on provider/product/authentication rules, not the diagram shape. Legal conclusions belong to separately verified policy research.

### Gaps
- This architectural analysis is a proposal, not a tested implementation or measurement. No application code, challenge execution, or tests were changed or run.
- AgentSky capabilities are covered by the coordinator's separate primary-source research; this note does not independently establish them.

## What does Agent-o-rama support, and what must the adapter own?

### Takeaway
Agent-o-rama can host the orchestration and tracing boundary, but external CLI durability is an application integration responsibility. A durable outer execution is not evidence that a subprocess, VM disk, CLI session, or external side effect resumes exactly once.

### Cited Findings
- `AgentClient.initiate` starts execution and returns an invocation handle; asynchronous invocation methods return futures. Client APIs also expose streaming and interaction with human input. — [AgentClient Java API](https://redplanetlabs.com/aor/javadoc/com/rpl/agentorama/AgentClient.html)
- `AgentNode.streamChunk` publishes explicit chunks, and `recordNestedOp` records typed nested operations and associated metadata including model token statistics. `getHumanInput` waits for human input submitted through client API or UI. — [AgentNode Java API](https://redplanetlabs.com/aor/javadoc/com/rpl/agentorama/AgentNode.html)
- Streaming callbacks have a reset indicator when a node fails and retries, clearing that node's chunks on restart. The API separately exposes all chunks, new chunks, and completion. — [Streaming guide](https://github.com/redplanetlabs/agent-o-rama/wiki/Streaming)

### Inferences
- A safe retry should query/reattach to the same logical job rather than unconditionally launch another CLI. Keep logical `job_id`, execution `attempt_id`, CLI `session_id`, and environment identity distinct. Retrying transport is different from asking the model to try solving again.
- Persist a job specification containing immutable repository/snapshot identity, harness and model versions, skill/input hashes, environment image, resource/deadline limits, and allowed capabilities. Do not embed credentials in that specification.
- Persist a result manifest containing terminal status, output/artifact locations and hashes, validation evidence, timings, model/harness identity, and raw event reference. A final assistant sentence is one result field, not the whole computation.
- Treat workspace, live processes, installed dependencies, network settings, CLI session data, and tool-generated files as part of execution state. A resumed conversation without the matching filesystem is not equivalent continuation. Ephemeral machines need explicit artifact export and, if required, recoverable workspace/session snapshots.
- For pull workers, leases and heartbeats distinguish live work from abandoned work; fencing prevents a worker with an expired lease from publishing an authoritative completion. At-least-once delivery requires deduplication of results and a clear policy for potentially repeated external effects. Do not promise exactly-once CLI actions.
- Cancellation needs separate desired and observed states: cancellation requested, worker acknowledged, process stopped, artifacts exported as appropriate, environment reclaimed. Dropping a graph future or ending a parent node does not by itself prove child-process termination.
- Preserve provider-native events alongside normalized events. Correlate run/job/attempt/session identifiers and retain explicit gaps or unavailable token/cost fields; AOR's automatic model tracing cannot be assumed to see an opaque subprocess. Use the documented manual tracing/streaming interfaces where they fit and keep artifact links for the full transcript.
- Prefer meaningful graph boundaries such as prepare, execute, validate, and analyze. Mirroring every private CLI turn/tool call in the outer graph creates two competing state machines; record inner events for observability without assuming the outer layer can replay them.
- A robust external-worker design still allows a node to wait on an external job, provided retry reconnect semantics are explicit. Alternatively use coarse successive invocations linked by a persisted job state. These are implementation options to validate, not claims of a native AOR activity-token facility.

### Gaps
- Reviewed AgentNode and AgentClient docs did not establish a general-purpose durable external activity completion token, webhook resume endpoint, or invocation cancellation API. Async futures and human-input requests do not establish those semantics. Verify the selected AOR version before selecting a continuation mechanism.
- No current repository-specific API wrapper, deployment version, or runner implementation was inspected; no claims about their present behavior are made.
- Automatic lossless conversion of arbitrary CLI events into AOR model/tool traces is not established.

## What small experiment could justify a port beyond the simple runner?

### Takeaway
Test recovery and operator visibility before expanding the graph abstraction. The null hypothesis is that the existing runner plus one isolated job adapter provides the needed functionality with less machinery.

### Cited Findings
- Agent-o-rama offers offline experiments, online evaluations, traces, and time-series telemetry in addition to execution. Those capabilities supply possible reasons for adopting it beyond launching an agent process. — [Official repository](https://github.com/redplanetlabs/agent-o-rama)

### Inferences
- Proposed comparison, not an authorized run: choose one existing representative challenge, pin one harness/model and immutable input snapshot, and run it through (A) the simple runner and (B) one outer graph operation using the same external job adapter. Hold inference and environment settings constant.
- By the end of one deliberately time-boxed prototype day, show the team one successful run, orchestrator restart while the CLI continues, network disconnect/reconnect, duplicate dispatch/completion delivery, worker loss, and cancellation. Avoid comparing different prompts/models and calling the result an orchestration benchmark.
- Acceptance evidence: no duplicate live solve for the same accepted attempt; explicit classification when worker loss requires restart; artifacts survive machine cleanup; status converges after reconnect; cancellation demonstrably stops the process or reports failure to stop; separate startup, solve, recovery, and cleanup timings; complete provenance of environment/harness/model/skills; explainable missing telemetry.
- Adoption bet: the AOR version should noticeably improve recoverability or evaluation/diagnosis without making ordinary runs harder to launch or debug. Report developer effort and operator interventions, not only successful model answers. If the improvement is only a graph-shaped representation, the simpler runner remains the stronger baseline.
- A minimal end-of-day deliverable is an inspectable comparison record and recovery timeline, with links to input manifest, trace, validation output, and artifacts. This is downstream-useful evidence for both the person deciding the port and the person who would operate it.

### Gaps
- The user has not yet selected the decisive objective: faster immediate sandboxed execution, durable scheduling, comparative evaluation UI, or a new programmable harness. The coordinator should ask one short null-hypothesis/bet question rather than requiring a broad architectural questionnaire.
