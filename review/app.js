"use strict";
const $ = (s) => document.querySelector(s);
const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const unknown = '<span class="unknown">Unavailable</span>';
const fmt = (value) =>
  value == null ? unknown : esc(Number(value).toLocaleString("en-US"));
const money = (value) =>
  value == null ? unknown : `$${Number(value).toFixed(4)}`;
const duration = (value) =>
  value == null
    ? "Unknown"
    : `${Math.floor(value / 60)}m ${String(value % 60).padStart(2, "0")}s`;
const tag = (value) =>
  `<span class="tag ${esc(String(value).toLowerCase())}">${esc(value)}</span>`;
const threadLink = (r) =>
  r.thread
    ? `<a href="https://ampcode.com/threads/${esc(r.thread)}" target="_blank" rel="noreferrer">Owner thread ↗</a>`
    : '<a href="/evidence/archive-index.json" target="_blank" rel="noreferrer">Archive provenance ↗</a>';
let data,
  annotations = {},
  selected = "auction-20260916",
  tab = "overview",
  filter = "all",
  search = "";
let compare = ["A-url-01", "B-url-01"],
  undo = [],
  saveQueue = Promise.resolve(),
  saveVersions = {};
let sweepReceiptCount = null;
let reviewer = localStorage.getItem("rama-reviewer");
if (!reviewer) {
  reviewer = crypto.randomUUID();
  localStorage.setItem("rama-reviewer", reviewer);
}
let backupKey;
const current = () => data.runs.find((r) => r.id === selected);
const visible = () =>
  data.runs.filter(
    (r) =>
      (filter === "all" || r.classification === filter) &&
      `${r.id} ${r.challenge} ${r.harness}`
        .toLowerCase()
        .includes(search.toLowerCase()),
  );

async function request(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) throw new Error(`Request failed (${response.status})`);
  return response;
}
function notice(message) {
  $("#notice").textContent = message;
  $("#notice").style.display = "block";
  clearTimeout(notice.timer);
  notice.timer = setTimeout(() => ($("#notice").style.display = "none"), 4000);
}
function progress() {
  $("#progress").textContent =
    `${Object.values(annotations).filter((a) => a.label).length} / ${data.runs.length} reviewed in this browser`;
}
function save(id) {
  annotations[id].pending = true;
  localStorage.setItem(backupKey, JSON.stringify(annotations));
  progress();
  const payload = {
    reviewer,
    dataset: data.dataset_hash,
    run: id,
    label: annotations[id]?.label || "",
    note: annotations[id]?.note || "",
  };
  const version = (saveVersions[id] = (saveVersions[id] || 0) + 1);
  if (id === selected && $("#save-status"))
    $("#save-status").textContent = "Saving…";
  // Serialize writes so a slow earlier keystroke cannot overwrite a later note.
  saveQueue = saveQueue.then(async () => {
    try {
      const result = await (
        await request("/api/annotations", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Review-Request": "1",
          },
          body: JSON.stringify(payload),
        })
      ).json();
      if (version === saveVersions[id]) {
        annotations[id].updated = result.updated;
        annotations[id].pending = false;
        localStorage.setItem(backupKey, JSON.stringify(annotations));
        if (id === selected && $("#save-status"))
          $("#save-status").textContent = "Saved";
      }
    } catch (_) {
      if (version === saveVersions[id]) {
        annotations[id].pending = true;
        localStorage.setItem(backupKey, JSON.stringify(annotations));
        if (id === selected && $("#save-status"))
          $("#save-status").textContent =
            "Saved in this browser only. Select Save to retry the server.";
      }
    }
  });
}
function label(value) {
  undo.push({
    id: selected,
    previous: structuredClone(annotations[selected] || { label: "", note: "" }),
  });
  annotations[selected] = {
    ...annotations[selected],
    label: value,
    note: annotations[selected]?.note || "",
  };
  save(selected);
  renderReview();
  renderList();
}
function undoLabel() {
  const change = undo.pop();
  if (!change) return notice("Nothing to undo");
  annotations[change.id] = change.previous;
  save(change.id);
  renderReview();
  renderList();
}
function move(delta) {
  const runs = visible(),
    index = runs.findIndex((r) => r.id === selected);
  const next = runs[index + delta];
  if (next) {
    selected = next.id;
    updateRunURL();
    renderList();
    renderDetail();
  }
}
function updateRunURL() {
  history.replaceState(null, "", `#runs/${encodeURIComponent(selected)}`);
}
function route() {
  if (!data) return;
  const [view, id] = (location.hash.slice(1) || "runs").split("/");
  document
    .querySelectorAll("nav a")
    .forEach((a) => a.classList.toggle("active", a.hash === `#${view}`));
  if (view === "compare") renderCompare();
  else if (view === "audit") renderAudit();
  else if (view === "coverage") renderCoverage();
  else if (view === "sweep") renderSweep();
  else {
    if (id && data.runs.some((r) => r.id === id)) {
      selected = id;
      filter = "all";
      search = "";
    }
    renderExplorer();
  }
  $(".scope-strip").hidden = view === "sweep";
  $("#export").hidden = view === "sweep";
  progress();
  window.scrollTo(0, 0);
}
async function renderSweep() {
  $("#main").innerHTML =
    '<article class="paper"><h2>Current sweep</h2><p>Loading snapshot…</p></article>';
  try {
    const s = await (await request("/api/sweep")).json();
    if (location.hash !== "#sweep") return;
    const commit = (sha) =>
      `<a href="https://github.com/okwalerie/rama-ai-learn/commit/${esc(sha)}" target="_blank" rel="noreferrer">${esc(sha.slice(0, 12))} ↗</a>`;
    const stages = s.stage_rollup;
    const continuations = s.continuations;
    const startOverlay = s.latest_confirmed_start_overlay;
    const continuationPanels = continuations.completion_panels
      .map((panel) => {
        const continuationLabel = panel.attempt_number_discrepancy
          ? "continuation (attempt number disputed)"
          : panel.continuation_retry == null
            ? "continuation"
            : `continuation retry ${esc(panel.continuation_retry)}`;
        const modelPhaseEvents = panel.model_phase_events?.length
          ? `<ul class="model-phase-events">${panel.model_phase_events.map((event) => `<li><strong>${esc(event.model ?? "Model not identified")}</strong> · ${esc(event.phase ?? "phase not identified")}${event.effort ? ` · effort ${esc(event.effort)}` : ""}${event.requested_effort ? ` · effort requested ${esc(event.requested_effort)}${event.native_effort_confirmed ? " (native tier confirmed)" : " (native tier unverified)"}` : ""}${event.strict_isolated ? " · strict-isolated" : ""} · ${esc(event.result ?? "result unavailable")}</li>`).join("")}</ul>`
          : `<p class="meta">Exact model/phase metadata is not present in this receipt summary; checks below are the supported evidence.</p>`;
        const attemptNumberNote = panel.attempt_number_discrepancy
          ? `<p class="callout warning">Attempt-number discrepancy: ${esc(panel.attempt_number_discrepancy)}</p>`
          : "";
        return `<section class="skill continuation-panel" data-continuation-id="${esc(panel.id)}"><h4>${esc(panel.lane)} · ${esc(panel.challenge)} · ${esc(panel.original_attempt)} ${continuationLabel}</h4><p class="callout">${esc(panel.status)}</p>${modelPhaseEvents}${attemptNumberNote}<p>${panel.checks.map(esc).join(" · ")}</p><dl><dt>New scored invocation</dt><dd>${panel.new_scored_invocation ? "Yes" : "No — continuation of original attempt"}</dd>${panel.receipt_location ? `<dt>Receipt location</dt><dd><code>${esc(panel.receipt_location)}</code></dd>` : ""}${panel.receipt_copy_status ? `<dt>Receipt copy</dt><dd>${esc(panel.receipt_copy_status)}</dd>` : ""}${panel.source_identity_caveat ? `<dt>Source identity</dt><dd>${esc(panel.source_identity_caveat)}</dd>` : ""}${panel.cli_estimated_usd != null ? `<dt>Continuation CLI estimate</dt><dd>$${esc(panel.cli_estimated_usd)}</dd>` : ""}${panel.cli_estimate_components_usd ? `<dt>Estimate components</dt><dd>Build $${esc(panel.cli_estimate_components_usd.build)} + review $${esc(panel.cli_estimate_components_usd.review)}</dd>` : ""}${panel.original_a1_cli_estimated_usd != null ? `<dt>Original A1 CLI estimate</dt><dd>$${esc(panel.original_a1_cli_estimated_usd)}</dd><dt>Original A1 catalog estimate</dt><dd>$${esc(panel.original_a1_catalog_estimate_usd)}</dd><dt>Continuation billing</dt><dd>Unknown</dd>` : ""}${panel.billed_usd !== undefined ? `<dt>Billed</dt><dd>${panel.billed_usd == null ? unknown : `$${esc(panel.billed_usd)}`}</dd>` : ""}${panel.preservation_note ? `<dt>Receipt distinction</dt><dd>${esc(panel.preservation_note)}</dd>` : ""}</dl>${panel.source_base ? `<p class="meta">Source base ${esc(panel.source_base)}${panel.module_sha_unchanged ? " · module SHA unchanged" : ""}</p>` : ""}</section>`;
      })
      .join("");
    const preflightPanels = continuations.preflights
      .map(
        (panel) => `<section class="skill preflight-panel" data-preflight-id="${esc(panel.id)}"><h4>${esc(panel.lane)} · ${esc(panel.challenge ?? "runtime readiness")}${panel.attempt ? ` · ${esc(panel.attempt)}` : ""}</h4><p class="callout warning">${esc(panel.status)}</p><ul>${panel.checks.map((item) => `<li>${esc(item)}</li>`).join("")}</ul><dl><dt>Scored invocation</dt><dd>No</dd><dt>Phase</dt><dd>${esc(panel.phase ?? "preflight only")}</dd><dt>Receipt/source</dt><dd><code>${esc(panel.receipt_location ?? "Not supplied")}</code></dd></dl></section>`,
      )
      .join("");
    const reconciliationPanels = continuations.reconciliation_panels
      .map(
        (panel) => `<section class="skill reconciliation-panel" data-reconciliation-id="${esc(panel.id)}"><h4>${esc(panel.lane)} · ${esc(panel.challenge)} · receipt reconciliation</h4><p class="callout warning">${esc(panel.status)}</p>${panel.prior_owner_reported_disposition ? `<p class="callout">Prior owner-reported disposition: ${esc(panel.prior_owner_reported_disposition)}</p>` : ""}${panel.receipt_reconciliation_warning ? `<p class="callout warning">${esc(panel.receipt_reconciliation_warning)}</p>` : ""}<p>${panel.checks.map(esc).join(" · ")}</p><dl><dt>New scored invocation</dt><dd>No</dd><dt>Receipt distinction</dt><dd>${esc(panel.preservation_note)}</dd><dt>Historical owner receipt</dt><dd><code>${esc(panel.receipt_location)}</code></dd></dl></section>`,
      )
      .join("");
    const overlayStartItem = (item) => {
      const attempt = item.attempt ? ` ${esc(item.attempt)}` : "";
      const phases = item.phases?.length
        ? `<div class="meta">Phases: ${item.phases.map(esc).join(" · ")}</div>`
        : "";
      const model = item.model
        ? `<div class="meta">Model: ${esc(item.model)}</div>`
        : `<div class="meta">Model not identified by supplied receipt summary.</div>`;
      const signal = item.native_signal_status
        ? `<div class="callout warning">${esc(item.native_signal_status)}</div>`
        : "";
      const receipt = item.receipt_location
        ? `<div class="meta">Receipt: <code>${esc(item.receipt_location)}</code></div>`
        : "";
      const copyStatus = item.receipt_copy_status
        ? `<div class="meta">${esc(item.receipt_copy_status)}</div>`
        : "";
      const eventTime = item.event_time === null
        ? "Event time not supplied."
        : `Event time ${esc(item.event_time)}.`;
      return `<li><strong>${esc(item.challenge)}${attempt}:</strong> ${esc(item.status)}${phases}${model}${signal}${receipt}${copyStatus}<span class="meta">${eventTime}</span></li>`;
    };
    const startOverlayPanels = startOverlay.lanes
      .map((lane) => {
        const starts = lane.additional_starts?.length
          ? `<ul>${lane.additional_starts.map(overlayStartItem).join("")}</ul>`
          : "<p>No later launch receipt reported.</p>";
        const notLaunched = lane.not_launched?.length
          ? `<div class="callout warning"><strong>Not launched:</strong><ul>${lane.not_launched.map((item) => `<li>${esc(item.challenge)} ${esc(item.attempt)} — ${esc(item.status)}</li>`).join("")}</ul></div>`
          : "";
        return `<section class="skill latest-start-panel" data-lane="${esc(lane.lane)}"><h4>${esc(lane.lane)}</h4><p class="callout"><strong>${fmt(lane.scored_starts)} scored starts / ${fmt(lane.cells)} cells</strong></p><p>${esc(lane.prior_census)}</p><p class="meta">Source time: ${esc(lane.source_time)}</p>${starts}${notLaunched}</section>`;
      })
      .join("");
    const stageTable = (key, title) => `<section class="section" id="stage-${key}"><h4>${esc(title)}</h4><p>${esc(stages[`${key}_basis`])}</p><div class="table-wrap"><table><thead><tr><th scope="col">${key === "inventory" ? "Counting unit" : "Stage / category"}</th>${stages.columns.map((name) => `<th scope="col">${esc(name)}</th>`).join("")}</tr></thead><tbody>${stages[key].map(([label, ...counts]) => `<tr><th scope="row">${esc(label)}</th>${counts.map((count) => `<td>${count == null ? '<span class="unknown">Unknown</span>' : esc(count)}</td>`).join("")}</tr>`).join("")}</tbody></table></div></section>`;
    $("#main").innerHTML =
      `<article class="paper" id="sweep"><p class="eyebrow">Separate from the historical 13-record audit</p><h2>Current sweep</h2>
      <p class="callout warning">Snapshot: <time datetime="${esc(s.snapshot_at)}">${esc(s.snapshot_at)}</time> · America/Edmonton. Manual updates only; reload does not contact workers.</p><p>${esc(s.basis)}</p>
      <p class="callout warning">Previous aggregate checkpoint: ${esc(s.attempt_snapshot_at)}. Stale pending complete owner receipts. Imported starts below supersede only their own cells; other worker activity is unknown.</p><div class="status-row"><div><small>Imported attempt records</small><strong>${fmt(s.attempts.length)}</strong></div><div><small>Private PASS receipts — not completed challenges</small><strong>${fmt(s.attempts.filter((a) => a.private_verdict === "PASS").length)}</strong></div><div><small>Quota-blocked receipts — not solver failures</small><strong>${fmt(s.attempts.filter((a) => a.outcome === "quota-blocked").length)}</strong></div></div><p class="callout warning">${esc(s.private_evidence_correction)}</p>
      <section class="section" id="stage-rollup"><h3>Runner stage rollup</h3><p class="callout warning">Manual owner snapshots at different times; not live worker state. This historical stage-source table is preserved; see the separate latest-start overlay below.</p><p>${esc(stages.unit)}</p><ul>${stages.sources.map((source) => `<li><strong>${esc(source.lane)}</strong> · ${esc(source.as_of)} · ${esc(source.basis)} ${threadLink(source)}</li>`).join("")}</ul>${stageTable("inventory", "Assigned cells versus confirmed starts")}${stageTable("current", "Current / stopped stage — exclusive counts")}${stageTable("cumulative", "Cumulative stages reached — overlapping counts")}${stageTable("blocked", "Blocked subtype — same attempts, separate classification")}<p class="callout">${esc(stages.model_basis)}</p><p class="callout warning">${esc(stages.preservation_warning)}</p></section>
      <section class="section" id="latest-start-overlay"><h3>Latest confirmed-start overlay — supplemental</h3><p class="callout warning">As of ${esc(startOverlay.as_of)} · ${esc(startOverlay.basis)} Historical stage tables and detailed receipt records are unchanged. Continuations add ${fmt(startOverlay.continuations_add_scored_starts)} scored starts.</p><div class="overlay-grid">${startOverlayPanels}</div></section>
      <section class="section" id="continuations"><h3>Continuation events — separate from scored starts</h3><p class="callout warning">Event audit as of ${esc(continuations.event_audit_as_of)} · ${esc(continuations.event_audit_basis)} Strict-isolated completed continuations: ${fmt(continuations.strict_isolated_completion_count)}. Continuations added to scored invocation count: ${fmt(continuations.continuations_add_scored_invocations)}.</p>${continuationPanels}<section id="continuation-preflights"><h4>Preflight / HOLD records — no scored invocation</h4><p class="callout warning">These readiness checks are not continuation model runs and do not change the scored-start count.</p>${preflightPanels}</section><section id="continuation-reconciliation"><h4>Receipt reconciliation — original starts remain separate</h4>${reconciliationPanels}</section><h4>Scoped process audits</h4><p class="callout warning">Partial scoped checks only. Other workers/orbs remain unknown; no global no-process conclusion.</p><ul>${continuations.process_audits.map((audit) => `<li><strong>${esc(audit.scope)}</strong> · ${esc(audit.observed_at)} · ${esc(audit.result)}</li>`).join("")}</ul><h4>Source and checkout provenance</h4><dl><dt>Scored baseline</dt><dd>${esc(continuations.source_provenance.scored_baseline)}</dd><dt>Go Rama coordinator</dt><dd>${esc(continuations.source_provenance.go_rama_coordinator)}</dd><dt>Go HLD coordinator</dt><dd>${esc(continuations.source_provenance.go_hld_coordinator)}</dd></dl><p class="meta">Owner receipts and continuation artifacts are not transferred unless a panel names a checkout path.</p></section>
      <section class="section"><h3>Shipment confirmed</h3><p>The ship gate is open. Owner checkout reconciliation and actual attempt receipts remain unverified here. A gate is not a scored outcome.</p><dl><dt>Shipped baseline</dt><dd>${commit(s.shipped_sha)}</dd><dt>Shipment reported</dt><dd>${esc(s.ship_reported_at)}</dd><dt>Previous remote checkpoint</dt><dd>${commit(s.checkpoint_sha)} — superseded</dd><dt>Nine reference imports</dt><dd>${fmt(s.reference_tests)} tests / ${fmt(s.reference_assertions)} assertions, owner-reported. These are not solver scores.</dd></dl><p class="meta">${esc(s.reference_note)}</p>${threadLink({ thread: s.ship_thread })}</section>
      <section class="section"><h3>Planned lanes</h3>${s.lanes.map((l) => `<section class="skill sweep-lane"><h4>${esc(l.name)}</h4><p>${esc(l.scope)}</p><p class="meta">${esc(l.configuration)}</p><p class="callout">${esc(l.latest_update || "No post-shipment reconciliation receipt received.")}</p><p>${fmt(l.scored_attempts)} reported scored attempts at the stale checkpoint · current confirmed-start count unavailable</p><code>${esc(l.ledger)}</code><p class="meta">${esc(l.ledger_state)}. Retrieve an approved metadata receipt from the owner before adding an attempt.</p>${threadLink(l)}</section>`).join("")}</section>
      ${s.lanes.filter((l) => l.active_challenges_reported).map((l) => `<section class="section"><h3>${esc(l.name)} owner-confirmed active attempts</h3><p>Grouped receipt ${esc(l.active_receipt_at)}: ${fmt(l.active_attempts_reported)} active; ${fmt(l.confirmed_attempts_reported)} starts including stopped hotel A1. Worker URLs and exact start times unavailable; not duplicated into the individual-receipt count above.</p><ul>${l.active_challenges_reported.map((name) => `<li>${esc(name)} · attempt ${esc(l.active_attempt_numbers?.[name] ?? l.active_attempt_default)} · running (owner-reported) · private unknown</li>`).join("")}</ul>${threadLink(l)}</section>`).join("")}
      <section class="section" id="model-audit"><h3>Claude model audit — requested versus observed</h3><p>${esc(s.claude_model_audit.note)}</p><p>Fast/build transcript labels reported for A1: ${s.claude_model_audit.fast_transcript_attempt_1_challenges.map(esc).join(", ")}.</p>${threadLink({thread: s.claude_model_audit.source_thread})}</section>
      <details><summary>Claude dispatch inventory — not confirmed starts (${s.dispatches.length})</summary><ul>${s.dispatches.map(([name, thread]) => `<li>${esc(name)} · ${threadLink({ thread })}</li>`).join("")}</ul></details>
      <section class="section"><h3>Attempt ledger</h3><p>Only actual scored attempts belong here. Partial, infrastructure-blocked, quota-blocked and unknown outcomes stay distinct. Phase PASS is not private PASS; a stopped run does not prove a quota block.</p><div id="sweep-attempts">${
        s.attempts.length
          ? s.attempts
              .map(
                (a) =>
                  `<details><summary>${esc(a.challenge)} · attempt ${esc(a.attempt_number)} · ${esc(a.outcome)}</summary><dl>${Object.entries(
                    {
                      "Requested provider / model / effort": [
                        a.provider,
                        a.model,
                        a.effort,
                      ]
                        .map((v) => v ?? "Unavailable")
                        .join(" / "),
                      "Retry reason": a.retry_reason,
                      "Worker receipt": a.worker_receipt,
                      "Shipped run SHA": a.shipped_run_sha,
                      Started: a.started_at,
                      "Earlier launch receipt": a.launch_reported_at,
                      Finished: a.finished_at,
                      "Implementation reached": a.implementation_reached,
                      "Runner challenge status": a.runner_status,
                      "Shell exit code": a.shell_exit,
                      "Native private verdict (reported)": a.native_private_verdict,
                      "Private execution evidence": a.private_execution,
                      "Substantiated private result / disposition": a.private_verdict,
                      "Tests executed": a.tests,
                      Assertions: a.assertions,
                      Failures: a.failures,
                      Errors: a.errors,
                      "Evidence basis": a.evidence_basis,
                    },
                  )
                    .map(
                      ([k, v]) =>
                        `<dt>${esc(k)}</dt><dd>${v == null ? unknown : k === "Worker receipt" && /^https:\/\/ampcode\.com\/threads\/T-[a-z0-9-]+$/.test(v) ? `<a href="${esc(v)}" target="_blank" rel="noreferrer">Worker thread ↗</a>` : k === "Shipped run SHA" ? commit(v) : esc(v)}</dd>`,
                    )
                    .join(
                      "",
                    )}</dl><p>Estimated spend: ${money(a.estimated_spend_usd)} · Billed spend: ${money(a.billed_spend_usd)}</p></details>`,
              )
              .join("")
          : '<div class="empty">No scored attempt receipts imported. The owners report zero scored attempts at this checkpoint.</div>'
      }</div></section>
      <section class="section"><h3>Spend and evidence rules</h3><p>Estimated spend: ${money(s.estimated_spend_usd)} · Billed spend: ${money(s.billed_spend_usd)}. No invoice was supplied.</p><p>A private PASS needs a receipt with actual tests and assertions, including a nonzero test count. Missing counts or verdicts remain unknown. Future receipts must identify the challenge, model and effort, attempt and retry reason, worker, shipped revision, times and implementation stage.</p><p>Do not import raw reasoning, private tests, reference contents, credentials or personal data. Historical reviews and annotations remain separate.</p><a href="/api/sweep" target="_blank" rel="noreferrer">Open this sweep snapshot ↗</a></section></article>`;
  } catch (_) {
    if (location.hash === "#sweep")
      $("#main").innerHTML =
        '<article class="paper"><h2>Current sweep</h2><p>Snapshot unavailable. Reload to retry; no run status can be inferred.</p></article>';
  }
}
function renderExplorer() {
  $("#main").innerHTML =
    `<p class="callout run-scope">13 historical review entries are listed here. The separate current-sweep ledger contains <strong>${fmt(sweepReceiptCount)} imported attempt records</strong>; open <a href="#sweep">Current sweep</a> to inspect them.</p><div class="workspace"><aside class="inventory" aria-label="Historical run inventory"><div class="filters"><input id="search" aria-label="Search historical runs" placeholder="Find challenge or attempt…" value="${esc(search)}"><select id="filter" aria-label="Historical attempt classification"><option value="all">All historical runs</option><option value="completed">Completed</option><option value="failed">Runner failed</option><option value="stopped">User-stopped</option></select><div id="count" class="count"></div></div><div id="run-list"></div></aside><article id="detail" class="paper"></article></div>`;
  $("#filter").value = filter;
  const refresh = () => {
    search = $("#search").value;
    filter = $("#filter").value;
    const runs = visible();
    if (!runs.some((r) => r.id === selected) && runs.length)
      selected = runs[0].id;
    renderList();
    renderDetail();
  };
  $("#search").oninput = refresh;
  $("#filter").onchange = refresh;
  renderList();
  renderDetail();
}
function renderList() {
  if (!$("#run-list")) return;
  const runs = visible();
  $("#count").textContent = `${runs.length} of ${data.runs.length} attempts`;
  $("#run-list").innerHTML = runs.length
    ? runs
        .map(
          (r) =>
            `<button class="run-button ${r.id === selected ? "selected" : ""}" data-run="${esc(r.id)}" aria-pressed="${r.id === selected}"><strong>${esc(r.challenge)}</strong><small><span>${esc(r.date)} · ${esc(r.id.startsWith("A-") || r.id.startsWith("B-") ? r.id : "historical")}</span>${tag(r.classification === "stopped" ? "Stopped" : r.private)}</small><small><span>${esc(r.harness)}</span><span>${annotations[r.id]?.label ? "Reviewed · " + esc(annotations[r.id].label) : "Unreviewed"}</span></small></button>`,
        )
        .join("")
    : '<div class="empty">No matching attempts. Clear the search or change the filter.</div>';
  document.querySelectorAll("[data-run]").forEach(
    (b) =>
      (b.onclick = () => {
        selected = b.dataset.run;
        updateRunURL();
        renderList();
        renderDetail();
      }),
  );
}
function renderDetail() {
  if (!$("#detail")) return;
  if (!visible().length) {
    $("#detail").innerHTML = "<h2>No attempt selected</h2>";
    return;
  }
  const r = current(),
    m = r.metrics;
  $("#detail").innerHTML =
    `<div class="detail-header"><div><p class="eyebrow">${esc(r.id)}</p><h2>${esc(r.challenge)}</h2><p class="meta">${esc(r.date)} · ${esc(r.harness)} · ${r.evidence.length ? "Includes recovered files" : "Owner thread only"}</p></div>${threadLink(r)}</div>
  <div class="status-row"><div><small>Attempt</small>${tag(r.classification)}</div><div><small>Phase outcome</small>${tag(r.status)}</div><div><small>Private tests</small>${tag(r.private)}</div><div><small>Reported cost estimate (USD)</small><strong>${money(m.Cost)}</strong></div></div>
  <p class="callout ${r.private === "FAIL" || r.classification === "stopped" ? "warning" : ""}">${esc(r.summary)}</p>
  <div class="subnav" aria-label="Run details">${[
    ["overview", "Overview"],
    ["phases", "Phases"],
    ["events", "Transcript"],
    ["evidence", "Evidence"],
    ["metrics", "Metrics"],
  ]
    .map(
      ([id, name]) =>
        `<button data-tab="${id}" class="${tab === id ? "active" : ""}" aria-pressed="${tab === id}">${name}</button>`,
    )
    .join("")}</div><div id="tab-body"></div><div id="review"></div>`;
  document.querySelectorAll("[data-tab]").forEach(
    (b) =>
      (b.onclick = () => {
        tab = b.dataset.tab;
        renderDetail();
      }),
  );
  renderTab(r);
  renderReview();
}
function timeline(r) {
  if (!r.phases.length)
    return `<p class="meta">${esc(r.phase_basis)}</p><div class="empty">Phase timeline unavailable.</div>`;
  return `<p class="meta">${esc(r.phase_basis)}</p><ol class="timeline">${r.phases.map(([name, seconds], i) => `<li><span class="phase-index">${String(i + 1).padStart(2, "0")}</span><span>${esc(name)}</span><span class="duration ${seconds == null ? "unknown" : ""}">${duration(seconds)}</span></li>`).join("")}</ol>`;
}
function renderTab(r) {
  const body = $("#tab-body");
  if (tab === "phases")
    body.innerHTML = `<h3>Phases</h3>${timeline(r)}<p class="callout warning">${esc(r.retry_note)}</p>`;
  else if (tab === "evidence") {
    body.innerHTML = `<h3>Evidence files</h3><p class="meta">Each SHA-256 hash identifies the exact original or exported file.</p>${r.evidence.map((e) => `<div class="evidence-item"><button data-evidence="${esc(e.file)}">${esc(e.label)} ↗</button><p class="meta spaced">${esc(e.kind)} · ${fmt(e.bytes)} original bytes</p><code>Original path: ${esc(e.origin)}</code><code>Original SHA-256: ${esc(e.sha256)}</code><code>Export SHA-256: ${esc(e.export_sha256)}</code><a class="meta" href="/evidence/${encodeURIComponent(e.file)}" target="_blank" rel="noreferrer">Open ${esc(e.label.toLowerCase())} export</a></div>`).join("") || '<div class="empty">No artifact transferred. Only the owner thread is available for this attempt.</div>'}<div class="section"><h3>Missing or withheld evidence</h3><p>${esc(r.missing)}</p><h3>How to retrieve files</h3><p class="meta">${esc(r.retrieval)}</p>${threadLink(r)}</div>`;
    bindEvidence();
  } else if (tab === "events") {
    const captures =
      r.transcripts ||
      (r.events_file ? [{ file: r.events_file, label: "Final review" }] : []);
    body.innerHTML = `<h3>Transcript</h3><p class="meta">Event records exclude reasoning, tool arguments, result contents and proxy diagnostics.</p><h3>Review notes from reports and sources</h3>${r.decisions.map((t) => `<p class="callout">${esc(t)}</p>`).join("")}<h3>Records from the phase transcript</h3>${captures.length ? `<label>Phase capture<select id="phase-capture">${captures.map((c) => `<option value="${esc(c.file)}">${esc(c.label)}</option>`).join("")}</select></label><div id="events"><p>Loading records…</p></div>` : `<div class="empty">No phase transcript transferred for this attempt. ${esc(r.retrieval)}</div>`}`;
    if (captures.length) {
      const refresh = () => loadEvents(r, $("#phase-capture").value);
      $("#phase-capture").onchange = refresh;
      refresh();
    }
  } else if (tab === "metrics") {
    body.innerHTML = `<h3>Reported metrics</h3><p class="meta">${esc(r.metric_basis)}</p><div class="table-wrap"><table><tbody>${metricRows(
      r,
    )
      .map(([key, value]) => `<tr><td>${esc(key)}</td><td>${value}</td></tr>`)
      .join(
        "",
      )}</tbody></table></div><p class="callout warning spaced">The challenge score penalises iterations; it is not a success probability. Alignment scores use uncalibrated ratings or policy penalties. Costs exclude unknown usage. No billing or quota records were recovered.</p>`;
  } else {
    body.innerHTML = `<div class="columns"><section><h3>Configuration and sources</h3><dl><dt>Slow model</dt><dd>${esc(r.slow)}</dd><dt>Fast model</dt><dd>${esc(r.fast)}</dd><dt>Run source revision</dt><dd>${r.commit ? `<a href="https://github.com/okwalerie/rama-ai-learn/commit/${esc(r.commit)}" target="_blank" rel="noreferrer">${esc(r.commit.slice(0, 12))} ↗</a>` : unknown}</dd><dt>Metric source</dt><dd>${esc(r.metric_basis)}</dd></dl></section><section><h3>Review notes</h3>${r.decisions.map((t) => `<p class="meta">${esc(t)}</p>`).join("")}<p class="meta">${esc(r.retry_note)}</p></section></div><section class="section"><h3>Isolation evidence</h3><p>${esc(r.isolation)}</p></section><details><summary>Missing evidence and how to retrieve it</summary><p>${esc(r.missing)}</p><p class="meta">${esc(r.retrieval)}</p></details>`;
  }
}
function metricRows(r) {
  const m = r.metrics;
  return [
    ["Challenge score (out of 100)", fmt(m.Score)],
    ["Structural alignment (out of 5)", fmt(m.Align)],
    ["Test alignment (out of 5)", fmt(m.TestAlign)],
    ["Iterations (runner definition)", fmt(m.Iterations)],
    ["Reported duration", m.Duration == null ? unknown : duration(m.Duration)],
    ["Tool uses", fmt(m["Tool Uses"])],
    ["Input tokens", fmt(m["In Tokens"])],
    ["Output tokens", fmt(m["Out Tokens"])],
    ["Cache creation tokens", fmt(m["Cache Create"])],
    ["Cache read tokens", fmt(m["Cache Read"])],
    ["Reported cost estimate (USD)", money(m.Cost)],
    ["Provider invoice and quota", unknown],
  ];
}
async function loadEvents(r, file) {
  try {
    const stream = await (await request(`/evidence/${file}`)).json();
    if (
      current().id !== r.id ||
      !$("#events") ||
      $("#phase-capture")?.value !== file
    )
      return;
    $("#events").innerHTML =
      `<p class="legend">${stream.events.length} exported records from ${stream.normalized_event_count} standardised events. Selected capture only. ${stream.events.some((e) => e.time) ? "Recorded times are UTC." : "Event timestamps are unavailable; file modification times are not execution times."}</p><div class="filter-row"><label>Event type <select id="event-filter"><option value="all">All exported events</option><option value="tool_call">Tool calls</option><option value="tool_result">Tool results</option><option value="claimed_verdict">Claimed verdicts</option><option value="usage">Usage records</option><option value="result">Final result</option></select></label></div><div id="event-rows" class="events-scroll"></div>`;
    const refresh = () => {
      const rows = stream.events.filter(
        (e) =>
          $("#event-filter").value === "all" ||
          e.kind === $("#event-filter").value,
      );
      $("#event-rows").innerHTML =
        rows
          .map(
            (e) =>
              `<div class="event ${esc(e.kind)}"><small>#${e.index} · ${esc(e.time ? new Date(e.time).toISOString() : "Timestamp unavailable")}</small><span><b>${esc(e.tool || e.kind.replaceAll("_", " "))}</b> ${esc(e.verdict || e.detail || "")}</span>${e.kind === "tool_result" ? `<span>${e.error ? "Tool reported error" : "No tool error flag"}</span>` : ""}${["result", "usage"].includes(e.kind) ? `<span>Recorded cost: ${money(e.cost_usd)} · duration: ${e.duration_ms == null ? "unavailable" : duration(Math.round(e.duration_ms / 1000))}</span><span>Input tokens: ${fmt(e.usage?.input_tokens)} · output tokens: ${fmt(e.usage?.output_tokens)}</span>` : ""}</div>`,
          )
          .join("") ||
        '<div class="empty">No exported records of this type. The original transcript may contain records omitted from this export.</div>';
    };
    $("#event-filter").onchange = refresh;
    refresh();
  } catch (_) {
    if (
      $("#events") &&
      current().id === r.id &&
      $("#phase-capture")?.value === file
    )
      $("#events").innerHTML =
        '<div class="empty">Could not load the transcript. Select Transcript to retry.</div>';
  }
}
function renderReview() {
  if (!$("#review") || !current()) return;
  const a = annotations[selected] || {},
    runs = visible(),
    i = runs.findIndex((r) => r.id === selected);
  $("#review").innerHTML =
    `<section class="review-box"><h3>Does the evidence support the private test verdict?</h3><p class="meta">Pass: supported. Fail: contradicted. Defer: missing or uncertain evidence. Your review does not change benchmark results.</p><div class="review-actions">${["Pass", "Fail", "Defer"].map((value) => `<button data-label="${value}" class="${a.label === value ? "chosen" : ""}" aria-pressed="${a.label === value}">${value}</button>`).join("")}<button id="undo">Undo</button><button id="save">Save</button></div><label class="meta" for="note">Evidence notes. Do not include credentials, personal data or private tests.</label><textarea id="note" maxlength="10000" placeholder="Cite evidence or describe what is missing…">${esc(a.note || "")}</textarea><div id="save-status" class="review-status" role="status">${a.pending ? "Waiting to save to the server" : a.updated ? "Saved" : "Notes save automatically"}</div><div class="review-nav"><button id="previous" ${i <= 0 ? "disabled" : ""}>← Previous</button><span class="meta">${i + 1} of ${runs.length}</span><button id="next" ${i === runs.length - 1 ? "disabled" : ""}>Next →</button></div><p class="shortcuts spaced">← → Navigate · 1 Pass · 2 Fail · D Defer · U Undo · ⌘/Ctrl S Save · ⌘/Ctrl Enter Save and next</p></section>`;
  document
    .querySelectorAll("[data-label]")
    .forEach((b) => (b.onclick = () => label(b.dataset.label)));
  $("#note").oninput = () => {
    annotations[selected] = {
      ...annotations[selected],
      note: $("#note").value,
      label: annotations[selected]?.label || "",
    };
    save(selected);
  };
  $("#previous").onclick = () => move(-1);
  $("#next").onclick = () => move(1);
  $("#undo").onclick = undoLabel;
  $("#save").onclick = () => {
    annotations[selected] ||= { label: "", note: "" };
    save(selected);
  };
}
function renderCompare() {
  $("#main").innerHTML =
    `<article class="paper"><h2>Compare attempts</h2><p class="callout warning">These runs cannot rank models. All A/B attempts stopped before grading. Historical runs used different challenges and modified prompts.</p><div class="compare-select">${["Left attempt", "Right attempt"].map((title, i) => `<label>${title}<select id="compare-${i}">${data.runs.map((r) => `<option value="${esc(r.id)}">${esc(r.id)} · ${esc(r.challenge)}</option>`).join("")}</select></label>`).join("")}</div><div id="comparison"></div></article>`;
  compare.forEach((id, i) => {
    $(`#compare-${i}`).value = id;
    $(`#compare-${i}`).onchange = () => {
      compare[i] = $(`#compare-${i}`).value;
      renderComparison();
    };
  });
  renderComparison();
}
function renderComparison() {
  const [a, b] = compare.map((id) => data.runs.find((r) => r.id === id));
  const rows = [
    ["Challenge", esc(a.challenge), esc(b.challenge)],
    ["Attempt", tag(a.classification), tag(b.classification)],
    ["Private tests", tag(a.private), tag(b.private)],
    ["Harness", esc(a.harness), esc(b.harness)],
    ["Slow model", esc(a.slow), esc(b.slow)],
    ["Fast model", esc(a.fast), esc(b.fast)],
    ...metricRows(a).map(([key, val], i) => [key, val, metricRows(b)[i][1]]),
    ["Metric source", esc(a.metric_basis), esc(b.metric_basis)],
    ["Isolation evidence", esc(a.isolation), esc(b.isolation)],
    ["Missing evidence", esc(a.missing), esc(b.missing)],
    ["Owner evidence", threadLink(a), threadLink(b)],
  ];
  $("#comparison").innerHTML =
    `<p class="meta">${a.id === b.id ? "Same attempt selected twice." : a.challenge === b.challenge ? "Matched challenge. Check completion and missing evidence before comparing results." : "Different challenges. These are not controlled model comparisons."}</p><div class="table-wrap"><table class="comparison"><thead><tr><th>Measure or evidence</th><th>${esc(a.id)}</th><th>${esc(b.id)}</th></tr></thead><tbody>${rows.map(([key, l, r]) => `<tr class="${l !== r ? "mismatch" : ""}"><td>${esc(key)}</td><td>${l}</td><td>${r}</td></tr>`).join("")}</tbody></table></div><div class="columns section"><section><h3>${esc(a.id)} phases</h3>${timeline(a)}</section><section><h3>${esc(b.id)} phases</h3>${timeline(b)}</section></div>`;
}
function renderAudit() {
  $("#main").innerHTML =
    `<article class="paper"><h2>Fix reporting and scoring before ranking models</h2><p class="quiet">11 findings from an agent audit, with source links and proposed tests. The fixes have not been applied.</p><div class="filter-row"><input id="finding-search" aria-label="Search findings" placeholder="Search findings or proposed tests…"><select id="priority" aria-label="Priority"><option value="all">All priorities</option><option>P0</option><option>P1</option><option>P2</option></select></div><div id="findings"></div></article>`;
  const refresh = () => {
    const q = $("#finding-search").value.toLowerCase(),
      priority = $("#priority").value;
    const found = data.findings.filter(
      (f) =>
        (priority === "all" || f.priority === priority) &&
        JSON.stringify(f).toLowerCase().includes(q),
    );
    $("#findings").innerHTML =
      found
        .map(
          (f, i) =>
            `<details ${i === 0 ? "open" : ""}><summary><span class="tag ${f.priority}">${f.priority}</span>${esc(f.id)} · ${esc(f.title)}</summary><div class="finding-body"><p class="meta">${esc(f.area)} · ${esc(f.status)}</p><h4>Observation</h4><p>${esc(f.observation)}</p><h4>Consequence</h4><p>${esc(f.impact)}</p><h4>Recommended fix</h4><p>${esc(f.fix)}</p><h4>Validation proposal</h4><p>${esc(f.validation)}</p><h4>Source evidence</h4>${f.sources.map(([name, start, end]) => `<button class="source-link" data-source="${esc(name)}" data-start="${start}" data-end="${end}">${esc(name)}:${start}–${end}</button>`).join("")}<p>${(f.runs || []).map((id) => `<a href="#runs/${esc(id)}">${esc(id)}</a>`).join(" · ")}</p></div></details>`,
        )
        .join("") || '<div class="empty">No matching findings.</div>';
    document
      .querySelectorAll("[data-source]")
      .forEach(
        (b) =>
          (b.onclick = () =>
            showSource(
              b.dataset.source,
              Number(b.dataset.start),
              Number(b.dataset.end),
            )),
      );
  };
  $("#finding-search").oninput = refresh;
  $("#priority").onchange = refresh;
  refresh();
}
function renderCoverage() {
  $("#main").innerHTML =
    `<article class="paper"><h2>Evidence coverage and limits</h2><p class="callout">${esc(data.scope)}</p>
    <div class="coverage-grid section"><section><h3>Recovered files</h3><p>We recovered 11 files from 7 owner orbs: 4 reports, one final-review transcript, 3 implementations and 3 startup records. Each run links to its exports and file hashes.</p>
    <p>A-rate-01 has only thread evidence. Owners report 27 completed phase captures across the stopped attempts; none were transferred.</p>
    <p>Both added Opus reports and implementations were transferred after the owner thread was restored. Its Claude transcripts remain missing.</p>
    <p>The 2 local archives contain 62 file occurrences: 41 unique files and 21 duplicates. We exported 4 reports and 25 unique Codex phase captures. Twelve other documents are withheld. The archive labelled September 16 contains September 15 captures, not the missing Claude transcripts. <a href="/evidence/archive-index.json" target="_blank" rel="noreferrer">Archive provenance and hashes ↗</a></p>
    <p>Local report-to-capture links are provisional. Native events have no timestamps; filename stamps have no timezone. Archive modification times are not execution times. No local implementation source was supplied.</p>
    <p>${esc(data.privacy)}</p></section><section><h3>Versions and saved reviews</h3><dl><dt>Audited repository revision</dt><dd><a href="https://github.com/${esc(data.repository)}/commit/${esc(data.audit_commit)}" target="_blank" rel="noreferrer">${esc(data.audit_commit.slice(0, 12))} ↗</a> (not the historical run revision)</dd><dt>Mounted evals-skills revision</dt><dd><a href="https://github.com/okwalerie/evals-skills/commit/${esc(data.skills_commit)}" target="_blank" rel="noreferrer">${esc(data.skills_commit.slice(0, 12))} ↗</a></dd><dt>Dataset SHA-256</dt><dd class="hash">${esc(data.dataset_hash)}</dd><dt>Saved reviews</dt><dd>Reviews are stored in SQLite under review/.state, with a browser backup. Each review records your browser ID, dataset hash and attempt ID. Export your reviews before moving to another orb.</dd></dl></section></div>
    <section class="section"><h3>Supported transcript formats</h3><div class="table-wrap"><table><thead><tr><th>Harness</th><th>Evidence</th><th>Limit</th></tr></thead><tbody>${data.adapters.map((a) => `<tr><td>${esc(a.name)}</td><td>${esc(a.state)}</td><td>${esc(a.limit)}</td></tr>`).join("")}</tbody></table></div></section>
    <section class="section"><h3>Preparing local transcripts</h3><p>No access to your local machine or server is configured. Keep native JSONL files locally. Use safe-review to produce one metadata-only JSON export per phase, after checking the CLI format.</p><p>Include a manifest with run and phase IDs, CLI and model versions, source revision, timestamps, exit status and separate private verdict evidence. Record original and export hashes, byte counts, missing files and truncated captures. Omit personal paths and secrets.</p><p>Review exports before authenticated transfer. This is a proposed handoff format, not an automatic importer or upload endpoint. Server access needs separate configuration.</p></section>
    <section class="section"><h3>How we used the 8 mounted skills</h3>${data.skills.map((s) => `<div class="skill"><h4>${esc(s.name)}</h4><p>${esc(s.use)}</p></div>`).join("")}</section>
    <section class="section"><h3>Validation steps</h3><ol><li>Test fixes for correctness checks, retry counting and usage accounting with synthetic fixtures.</li><li>Recover file inventories and phase records before comparing historical runs.</li><li>Ask domain experts to label specific failures. Keep training, development and final test examples separate.</li><li>Seek approval for paired model evaluations with fixed configurations. This audit ran no scored evaluations.</li></ol><p class="meta">Related work: <a href="https://ampcode.com/threads/T-01a0d0d3-19ac-772b-b0af-b52812cae5c3" target="_blank" rel="noreferrer">cross-CLI skills and log portability ↗</a>. Those changes are in a separate checkout.</p></section></article>`;
}
function codeHTML(text, start = 0, end = 0) {
  return `<pre>${text
    .split("\n")
    .map(
      (line, i) =>
        `<span class="code-line ${i + 1 >= start && i + 1 <= end ? "target" : ""}"><span class="line-number">${i + 1}</span><span class="${line.trim().startsWith(";") || line.trim().startsWith("#") ? "code-comment" : line.trim().startsWith("(") ? "code-form" : ""}">${esc(line)}</span></span>`,
    )
    .join("")}</pre>`;
}
function openViewer(title, html) {
  $("#viewer-title").textContent = title;
  $("#viewer-body").innerHTML = html;
  if (!$("#viewer").open) $("#viewer").showModal();
}
async function showSource(name, start, end) {
  openViewer(`${name}:${start}–${end}`, "<p>Loading source…</p>");
  try {
    const file = await (
      await request(`/api/source?name=${encodeURIComponent(name)}`)
    ).json();
    $("#viewer-body").innerHTML =
      `<p class="meta">Source from the audited checkout, not the historical run.</p>${codeHTML(file.text, start, end)}`;
    $("#viewer-body .target")?.scrollIntoView({ block: "center" });
  } catch (_) {
    $("#viewer-body").textContent =
      "Source unavailable. Only approved public source files are available here.";
  }
}
function bindEvidence() {
  document.querySelectorAll("[data-evidence]").forEach(
    (b) =>
      (b.onclick = async () => {
        const file = b.dataset.evidence;
        openViewer(b.textContent, "<p>Loading evidence…</p>");
        try {
          const text = await (await request(`/evidence/${file}`)).text();
          if (file.endsWith(".clj"))
            $("#viewer-body").innerHTML =
              `<p class="meta">Recovered agent source. No baseline is available for a diff.</p>${codeHTML(text)}`;
          else if (file.endsWith("metrics.json")) {
            const report = JSON.parse(text);
            $("#viewer-body").innerHTML =
              `<p class="meta">Fields from the original report. Grading explanations based on private references are withheld.</p><table><tbody>${Object.entries(
                report.rows[0],
              )
                .map(
                  ([k, v]) =>
                    `<tr><td>${esc(k)}</td><td>${v == null ? unknown : esc(v)}</td></tr>`,
                )
                .join(
                  "",
                )}</tbody></table><p class="hash">Original SHA-256: ${esc(report.sha256)}</p>`;
          } else $("#viewer-body").innerHTML = `<pre>${esc(text)}</pre>`;
        } catch (_) {
          $("#viewer-body").textContent =
            "Export unavailable. Close this window and reopen the file to retry.";
        }
      }),
  );
}
$("#close-viewer").onclick = () => $("#viewer").close();
$("#export").onclick = async () => {
  if (!data) return;
  await saveQueue;
  const blob = new Blob(
    [
      JSON.stringify(
        {
          schema: 1,
          criterion: "Evidence substantiates reported private verdict",
          dataset: data.dataset_hash,
          reviewer,
          annotations,
        },
        null,
        2,
      ),
    ],
    { type: "application/json" },
  );
  const url = URL.createObjectURL(blob),
    a = document.createElement("a");
  a.href = url;
  a.download = "rama-evidence-reviews.json";
  a.click();
  URL.revokeObjectURL(url);
  notice("Review export downloaded");
};
window.addEventListener("hashchange", route);
window.addEventListener("keydown", (e) => {
  if (!$("#review") || $("#viewer").open || !visible().length) return;
  const typing = ["INPUT", "TEXTAREA", "SELECT"].includes(e.target.tagName);
  if ((e.metaKey || e.ctrlKey) && ["s", "Enter"].includes(e.key)) {
    e.preventDefault();
    annotations[selected] ||= { label: "", note: "" };
    save(selected);
    if (e.key === "Enter") move(1);
    return;
  }
  if (typing || e.metaKey || e.ctrlKey || e.altKey) return;
  const actions = {
    ArrowLeft: () => move(-1),
    ArrowRight: () => move(1),
    1: () => label("Pass"),
    2: () => label("Fail"),
    d: () => label("Defer"),
    u: undoLabel,
  };
  if (actions[e.key]) {
    e.preventDefault();
    actions[e.key]();
  }
});
(async () => {
  try {
    data = await (await request("/api/data")).json();
    try {
      const sweep = await (await request("/api/sweep")).json();
      sweepReceiptCount = sweep.attempts.length;
      $("#sweep-count").textContent = `${sweepReceiptCount} records`;
    } catch (_) {
      $("#sweep-count").textContent = "unavailable";
    }
    backupKey = `rama-reviews-${reviewer}-${data.dataset_hash}`;
    let backup = {};
    try {
      backup = JSON.parse(localStorage.getItem(backupKey) || "{}");
    } catch (_) {
      /* Ignore corrupt browser backup. */
    }
    try {
      annotations = await (
        await request(`/api/annotations?reviewer=${reviewer}`)
      ).json();
    } catch (_) {
      notice("Server reviews unavailable. Using this browser’s backup.");
    }
    for (const [id, a] of Object.entries(backup))
      if (data.runs.some((r) => r.id === id) && (a.pending || !annotations[id]))
        annotations[id] = a;
    route();
  } catch (_) {
    $("#main").innerHTML =
      '<div class="empty"><h2>Could not load runs</h2><p>The server is unavailable. Reload the page to retry.</p></div>';
  }
})();
