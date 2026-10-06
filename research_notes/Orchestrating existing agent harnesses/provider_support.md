# Official provider interfaces and subscription boundaries

## Are existing harnesses intended to be embedded or orchestrated?

### Takeaway
Yes. Both providers expose intentional programmatic integration surfaces. This is different from scraping a terminal UI, although particular transports and features have maturity limits.

### Cited Findings
- Claude Agent SDK exposes Claude Code's tools, agent loop and context management in Python and TypeScript. The documentation describes building production agents using Claude Code as a library. — [SDK overview](https://code.claude.com/docs/en/agent-sdk/overview)
- `claude -p` is the CLI form of that SDK, documented for scripts and CI/CD; SDK packages add native messages and tool approval callbacks. Headless mode supports continued conversations and structured output. Its recommended `--bare` mode removes ambient discovery and does not read subscription OAuth credentials: it requires API/provider authentication. This is a concrete environment-control versus authentication consideration. — [Headless Claude](https://code.claude.com/docs/en/headless)
- Codex SDK explicitly supports CI/CD, internal workflows, applications and agents that engage Codex. TypeScript supports start/continue/resume; Python controls local app-server over JSON-RPC with a pinned runtime dependency. Current docs say the old Codex MCP-server command was removed. — [Codex SDK](https://learn.chatgpt.com/docs/codex-sdk)
- App-server powers rich Codex clients and exposes authentication, history, approvals and streamed events. The documentation recommends SDK for jobs/CI. Protocol supports bidirectional JSON-RPC. In the "Connect a remote Code Mode host" section the exact caveat is: "The app-server command and WebSocket transport are experimental and aren’t supported for production workloads." The transports section separately calls WebSocket experimental/unsupported while listing stdio as default. Keep this scope visible rather than inferring every local SDK integration lacks support. — [App-server](https://learn.chatgpt.com/docs/app-server)
- `codex exec` supports JSON event streams, output schemas, session resumption and saved CLI authentication. The automation guide prefers API keys but explicitly documents ChatGPT-managed accounts for trusted enterprise CI runners. It prohibits that advanced account-auth workflow for public/open-source repositories. — [Non-interactive Codex](https://learn.chatgpt.com/docs/non-interactive-mode)

### Inferences
- A worker wrapping the documented SDK/JSON protocol is a first-class integration pattern; terminal-output scraping is not required.
- Application task state and CLI session state should be separate. Resume IDs help continue sessions but do not imply replay-safe external side effects or durable workspace reconstruction.
- Pin runtime/protocol versions and preserve artifacts outside ephemeral workers. The docs' differing feature maturity reinforces this engineering recommendation.

### Gaps
- These sources establish supported interfaces, not prevalence of enterprise deployments or an enterprise SLA for a custom orchestrator.
- Undated current docs were fetched during research on 2026-10-05 client date. No future-dated publication was used, but documentation content can change independently of release dates.

## Does wrapping Claude guarantee permissible subscription-based production automation?

### Takeaway
No blanket guarantee follows from wrapping the official executable. Current evidence distinguishes ordinary individual automation from third-party services using customer credentials, and there is tension between the support article and legal guidance at the boundary.

### Cited Findings
- The help article dated June 16, 2026 says a planned June 15 Agent SDK billing change was paused. Its decisive current phrase is: "Claude Agent SDK, `claude -p`, and third-party app usage still draw from your subscription's usage limits." The credit schedule below that update is explicitly preserved historical content, not current policy. — [Subscription SDK update](https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan)
- Legal guidance says advertised Pro/Max limits assume ordinary individual Claude Code/SDK use. It directs product/service developers to API keys or supported cloud providers, disallows third-party Claude.ai login and requests routed through Free/Pro/Max credentials on users' behalf, and forbids collecting/storing/intermediating Claude.ai credentials or session tokens. Sign-in must use Anthropic's flow. — [Legal and compliance](https://code.claude.com/docs/en/legal-and-compliance)

### Inferences
- Personal headless automation can draw subscription limits; this does not establish permission for pooled credentials, a multiuser commercial service, or all third-party auth designs.
- The sources can partly coexist if the support article concerns permitted individual SDK use while legal guidance concerns developer credential intermediation. However, the phrase about third-party apps is broad enough that unresolved ambiguity should be stated, not silently resolved.
- Suggested user-facing correction: subscription savings are real for supported individual usage, but they are not a property guaranteed by nesting harnesses, and current provider/account terms determine the boundary.

### Gaps
- No authoritative general ruling located for the exact proposed internal ephemeral-VM orchestrator and authentication arrangement. Provider clarification would be needed before representing a multiuser subscription-backed product as definitely compliant.
- No independent legal assessment conducted; this is documentation evidence, not a legal guarantee.

## Can an independently implemented OpenAI harness use subscription inference?

### Takeaway
Yes, official Sign in with ChatGPT documentation describes direct Responses API requests charged to an authorized user's ChatGPT plan for eligible open-source/local applications. Retaining Codex's harness is therefore not inherently required for subscription inference.

### Cited Findings
- Sign in with ChatGPT offers plan usage for eligible Responses API requests from open-source/local apps; paid or remotely hosted apps are referred to an interest form. Registration binds client, user and workspace, while agent host identifies the execution environment. This is explicitly a preview route with eligibility limits. — [Plan usage overview](https://developers.openai.com/siwc/token-sharing-open-source)
- Preview requests require streaming, no server-side stored response, and context supplied by the client. Numerous Responses features are unavailable, including background mode, hosted MCP, file search and Code Interpreter. Local function/custom tools remain usable; Codex local thread resume still works. Thus custom harnesses must implement their own relevant state/tool functions. — [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- An official self-hosted VM guide exists for open-source apps. It explains stable per-VM host IDs, local OAuth, protected credential transfer and VM-owned renewal, while noting host-specific attribution/revocation for transferred sessions is unavailable. This documents a capability, not authorization to override this repository's stricter prohibition on syncing auth/session state. — [Self-hosted VMs](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms)

### Inferences
- Harness ownership, worker placement and billing/authentication are independent design axes, constrained by provider eligibility. An own-loop OpenAI worker need not always pay ordinary API-key rates; a Claude wrapper need not always qualify for subscription billing.
- For ephemeral VM design, use runtime-scoped provisioning within the user's applicable security constraints; do not copy complete agent homes or assume a worker can inherit long-lived credentials.

### Gaps
- The overview does not establish automatic eligibility of a paid managed orchestrator. Enterprise commercial terms and account-specific access were not verified.
- No cost comparison was computed; throughput/rate limits, retries and plan eligibility can dominate any nominal subscription savings.
