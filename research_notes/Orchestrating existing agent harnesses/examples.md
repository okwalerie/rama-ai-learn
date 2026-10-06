# Applications that orchestrate existing agent harnesses

## Do real applications reuse CLI agents or their harnesses?

### Takeaway
Yes: there are credible production examples, but distinguish SDK embedding, server integration, and a forked harness. These establish existence, not market prevalence or a guarantee that arbitrary CLI wrappers are robust. Sources checked 2026-10-05; figures below belong to their publication dates.

### Cited Findings
- Apple Xcode 26.3 integrates the Claude Agent SDK, explicitly the same underlying harness as Claude Code, with subagents, background tasks, and plugins. The announcement dated February 3, 2026 describes autonomous multi-step work and IDE tools. This is a substantial application embedding an existing harness, although not a remote fleet orchestrator. — [Anthropic announcement](https://www.anthropic.com/news/apple-xcode-claude-agent-sdk)
- Ramp Inspect runs each session in a Modal sandbox VM and recommends OpenCode specifically for its server-first design, typed SDK, and plugins. Its January 12, 2026 post reports approximately 30% of merged frontend/backend PRs from Inspect. Repository images are prepared ahead of time; snapshots support continuation. This is a company production implementation reusing a harness via a service boundary. It is not evidence of Claude/Codex CLI reuse. Direct page extraction was sparse; the search index returned the full primary article. — [Ramp engineering](https://engineering.ramp.com/post/why-we-built-our-background-agent)
- Stripe Minions internally forked Block's Goose, customized for unattended use and Stripe's LLM infrastructure. Its February 19, 2026 article reports over 1,300 human-reviewed, minion-produced merged PRs weekly. Blueprints mix deterministic nodes with agent loops inside isolated developer VMs. Stripe explicitly contrasts this with human-supervised Claude Code and Cursor use. Thus this demonstrates harness reuse plus customization, not a headless Claude wrapper. — [Stripe engineering](https://stripe.dev/blog/minions-stripes-one-shot-end-to-end-coding-agents-part-2)
- Vibe Kanban supports Claude Code, Codex, Amp and other existing coding agents, with authentication handled in those agents before use. It is a concrete orchestration application. Its April 10, 2026 shutdown announcement says Bloop is closing and the project continues as community-maintained open source, with local workspaces continuing. It should not be presented as an actively supported enterprise procurement recommendation. — [Official repository](https://github.com/BloopAI/vibe-kanban); [shutdown announcement](https://www.vibekanban.com/blog/shutdown)
- Anthropic's November 4, 2025 Cognizant announcement explicitly pairs Cognizant Neuro multi-agent orchestration with Claude Agent SDK, but this is an announced deployment/partnership, less concrete than the implementation accounts above. — [Anthropic announcement](https://www.anthropic.com/news/cognizant-partnership)

### Inferences
- The useful split is between who owns the task workflow and who owns the inner agent loop. Existing-harness reuse does not require recreating its inner loop as graph nodes.
- Ramp and Stripe make environmental reproducibility central; the choice of harness remains a separate decision from the VM/sandbox lifecycle.

### Gaps
- This sample does not measure prevalence across enterprise applications. It cannot support the claim that this is rare or inherently janky.
- Ona has current background-agent marketing, but I did not establish its exact underlying harness reuse from primary architecture evidence, so omit it from the strongest examples.

## Is AgentSky the product the user means, and what is documented?

### Takeaway
Yes: agentsky.dev is AgentSky. It explicitly offers the proposed boundary: keep the workflow graph in your system and call its managed runtime for harness execution. Documentation is evidence of the offered interface, not an independently tested reliability claim.

### Cited Findings
- AgentSky advertises Claude Code, Codex and other harnesses through a common API, with named customer stories. Treat customer launch totals as vendor-reported marketing rather than independent enterprise-adoption evidence. — [AgentSky](https://agentsky.dev/)
- Its software-factories page assigns queueing, concurrency, policy, retries, and terminal-failure decisions to the caller. AgentSky supplies isolated computers, persistent runtime state, normalized events, and recovery. This directly supports using it underneath an orchestrator rather than replacing the workflow engine. — [Software factories](https://agentsky.dev/software-factories)
- The quickstart describes persistent agents and pod-backed sessions. Send messages asynchronously with POST /v1/sessions/{id}/events; consume a standing SSE stream; stop on session.status_idle and inspect stop_reason. Missed events can be replayed from the events endpoint and deduplicated by ID. — [Quickstart](https://platform.agentsky.dev/docs)
- agent.toml exposes harness type, model, reasoning effort, CPU/memory, instructions, and skills. Skills may be revision-pinned; GitHub skill references can use commit SHAs. The platform composes its own preamble. Secrets are separate from the spec. These are useful controls, but this reference does not establish arbitrary base images or pinned harness binaries. — [agent.toml](https://platform.agentsky.dev/docs/agent-toml)
- AgentSky's security page explicitly directs evaluations to confirm provider routing, trace settings, retention, revocation, and contractual requirements instead of assuming uniform policies. — [Security and data handling](https://agentsky.dev/security)

### Inferences
- This is a plausible execution-backend candidate for a short experiment. It is not yet established as a drop-in replacement for Rama challenge orchestration.
- A graph node can submit an AgentSky session, persist its ID, and resume on an event; this is simultaneously a graph representation and a client/server implementation. Those are not opposing architectures.

### Gaps
- No authenticated trial was performed. Exact process implementation (CLI subprocess versus SDK) for each AgentSky harness was not independently established.
- API reference fetch failed twice. I did not verify cancellation semantics, create-request idempotency, webhook delivery, raw native transcript export, pinned harness versions, or custom container images.
- The docs describe persistent pod-backed runtimes and isolated cloud computers. They do not establish the exact hypervisor/container isolation mechanism or disposable-clean-VM semantics required by this user.
- Subscription usage marketing exists, but vendor marketing is not provider authorization. Leave terms/subscription conclusions to the provider-policy researcher.

## Should Rama AI Learn use AgentSky now?

### Takeaway
Recommend a bounded comparison as an optional backend, not an immediate migration. Its documented separation of orchestration and execution fits the question well; benchmark reproducibility and evidence export remain decisive unknowns.

### Cited Findings
- Caller-owned retries and workflow control are explicitly compatible with AgentSky's offering. — [Software factories](https://agentsky.dev/software-factories)
- Skill revisions and CPU/memory controls are documented, but the platform adds its own preamble. — [Agent specification](https://platform.agentsky.dev/docs/agent-toml)

### Inferences
- Run one representative public/non-sensitive challenge through the existing runner and one AgentSky adapter using identical source commit, skill revision, model, and time budget. Do not upload protected solutions/private tests or personal auth as part of this research.
- Before choosing it for benchmark orchestration, test: required JVM/Clojure/Rama dependencies; exact input snapshot; clean isolation; complete native trace/token/timing export; cancel and retry behavior; deterministic harness/version settings; artifact download; cleanup; and total cost.
- Keep the validation/verdict outside the solver's workspace and authority. A successful model response is not equivalent to a passing challenge.
- If those tests pass, buy the environment/runtime service and keep challenge scheduling, evaluation, and evidence accounting in the current orchestrator. If image/version/trace control is insufficient, a small self-hosted worker adapter remains the straightforward baseline.

### Gaps
- No measured latency, cost, successful Rama run, or recovery test exists from this research. A trial would produce that evidence; documentation alone cannot.
