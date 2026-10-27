"""Offline review regressions; synthetic canaries, no models/private suites."""

import hashlib
import json
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from review_index import report_metrics, safe_events
from review_server import ROOT, SOURCES, dataset, make_server
from transcript_events import normalize

CANARY = "SYNTHETIC_PRIVATE_CANARY_DO_NOT_EXPORT"


class ExportTests(unittest.TestCase):
    def test_sensitive_locations_are_omitted_not_redacted(self):
        events = [
            {
                "type": "assistant",
                "source_event": {"secret": CANARY},
                "message": {
                    "content": [
                        {"type": "thinking", "thinking": CANARY},
                        {"type": "text", "text": CANARY + "\nPHASE_VERDICT:PASS\n"},
                        {
                            "type": "tool_use",
                            "name": "Read",
                            "input": {"file_path": CANARY},
                            "id": CANARY,
                        },
                    ]
                },
            },
            {
                "type": "user",
                "message": {
                    "content": [
                        {
                            "type": "tool_result",
                            "content": CANARY,
                            "tool_use_id": CANARY,
                        }
                    ]
                },
            },
            {
                "type": "run_metadata",
                "stderr": CANARY,
                "model": CANARY,
                "exit": 143,
                "isolation": "bubblewrap-provider-network",
            },
            {
                "type": "result",
                "result": CANARY,
                "errors": [CANARY],
                "usage": {"input_tokens": 31, "extra": CANARY},
                "total_cost_usd": None,
            },
        ]
        result = safe_events(events)
        self.assertNotIn(CANARY, json.dumps(result))
        self.assertEqual("PASS", result["events"][0]["verdict"])
        self.assertEqual("Read", result["events"][1]["tool"])
        self.assertEqual(143, result["events"][3]["exit"])
        self.assertIsNone(result["events"][-1]["cost_usd"])
        self.assertEqual(31, result["events"][-1]["usage"]["input_tokens"])

    def test_native_cli_shape_matrix(self):
        # Failure-space tuples: CLI × complete/interrupted × sensitive location.
        fixtures = {
            "claude": [
                {
                    "type": "assistant",
                    "message": {"content": [{"type": "thinking", "thinking": CANARY}]},
                }
            ],
            "codex": [
                {
                    "type": "item.completed",
                    "item": {"type": "reasoning", "id": "a", "text": CANARY},
                }
            ],
            "opencode": [{"type": "reasoning", "part": {"id": "b", "text": CANARY}}],
            "pi": [
                {
                    "type": "message_end",
                    "message": {
                        "role": "assistant",
                        "content": [{"type": "thinking", "thinking": CANARY}],
                    },
                }
            ],
        }
        for cli, native in fixtures.items():
            for interrupted in (False, True):
                with self.subTest(cli=cli, interrupted=interrupted):
                    events = native + [
                        {
                            "type": "run_metadata",
                            "exit": 143 if interrupted else 0,
                            "stderr": CANARY,
                        }
                    ]
                    result = safe_events(normalize(events))
                    self.assertNotIn(CANARY, json.dumps(result))
                    metadata = next(
                        e for e in result["events"] if e["kind"] == "run_metadata"
                    )
                    self.assertEqual(143 if interrupted else 0, metadata["exit"])

    def test_unknowns_and_malicious_values_do_not_cross(self):
        events = [
            {
                "type": "assistant",
                "timestamp": CANARY,
                "message": {
                    "content": [
                        {"type": "tool_use", "name": CANARY, "input": {}},
                        {"type": "text", "text": "PHASE_VERDICT:PASS extra " + CANARY},
                    ]
                },
            },
            {
                "type": "result",
                "status": CANARY,
                "total_cost_usd": float("nan"),
                "usage": {"input_tokens": True},
            },
        ]
        result = safe_events(events)
        self.assertNotIn(CANARY, json.dumps(result))
        self.assertEqual("Other tool", result["events"][0]["tool"])
        self.assertIsNone(result["events"][1]["usage"]["input_tokens"])
        self.assertIsNone(result["events"][1]["cost_usd"])
        self.assertIsNone(result["events"][1]["status"])

    def test_report_projection_excludes_grading_prose(self):
        report = (
            "| Challenge | Status | Private | Score | Cost |\n|---|---|---|---|---|\n| demo | PASS | FAIL | 0 | $2.5 |\n\n"
            + CANARY
        )
        rows = report_metrics(report)
        self.assertEqual("FAIL", rows[0]["Private"])
        self.assertEqual(0, rows[0]["Score"])
        self.assertEqual(2.5, rows[0]["Cost"])
        self.assertIsNone(rows[0]["Align"])
        self.assertNotIn(CANARY, json.dumps(rows))
        self.assertIsNone(report_metrics(report.replace("$2.5", "N/A"))[0]["Cost"])
        for invalid in ("NaN", "-1", "secret", "2.3 USD"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                report_metrics(report.replace("$2.5", invalid))

    def test_malformed_report_rejected(self):
        for text in (
            "",
            "| Challenge | Status | Private |\n|---|---|---|\n| a | PASS |",
            "| Challenge | Status | Private |\n|---|---|---|\n| a | MAYBE | PASS |",
        ):
            with self.subTest(text=text), self.assertRaises(ValueError):
                report_metrics(text)

    def test_catalog_receipts_and_findings(self):
        data = dataset()
        self.assertEqual(13, len(data["runs"]))
        self.assertEqual(4, sum(r["classification"] == "stopped" for r in data["runs"]))
        runs = {r["id"]: r for r in data["runs"]}
        local = [r for r in data["runs"] if r["id"].startswith("local-")]
        self.assertEqual(4, len(local))
        self.assertEqual(25, sum(len(r["transcripts"]) for r in local))
        self.assertEqual(21, data["archive_import"]["counts"]["duplicate_occurrences"])
        self.assertEqual("-", runs["local-time-series-report-only-20260915"]["private"])
        self.assertIsNone(runs["local-bank-terra-20260915"]["metrics"]["Cost"])
        self.assertEqual(10, len(runs["local-bank-astra-20260915"]["transcripts"]))
        self.assertEqual(
            3, len(runs["local-bank-astra-20260915"]["evidence"][1]["origins"])
        )
        for name, verdict, score in (
            ("bank-transfer-20260916", "PASS", 100),
            ("time-series-20260916", "FAIL", 0),
        ):
            run = runs[name]
            self.assertEqual("completed", run["classification"])
            self.assertEqual("PASS", run["status"])
            self.assertEqual(verdict, run["private"])
            self.assertEqual(score, run["metrics"]["Score"])
            self.assertEqual([], run["phases"])
            self.assertNotIn("events_file", run)
            self.assertEqual(2, len(run["evidence"]))
        for r in data["runs"]:
            if r["classification"] == "stopped":
                self.assertEqual("UNKNOWN", r["private"])
                self.assertIsNone(r["metrics"].get("Cost"))
            for e in r["evidence"]:
                raw = (ROOT / "review/evidence" / e["file"]).read_bytes()
                self.assertEqual(e["export_sha256"], hashlib.sha256(raw).hexdigest())
                if e["kind"] in ("transferred", "transferred + reviewed source"):
                    self.assertEqual(e["sha256"], e["export_sha256"])
        for f in data["findings"]:
            for name, start, end in f["sources"]:
                self.assertIn(name, SOURCES)
                self.assertGreaterEqual(end, start)
                self.assertLessEqual(start, len((ROOT / name).read_text().splitlines()))


class ServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.server = make_server(0, Path(cls.tmp.name) / "labels.sqlite3")
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}"
        cls.data = dataset()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.tmp.cleanup()

    def get(self, path):
        return urlopen(self.url + path)

    def post(self, body, headers=True):
        return urlopen(
            Request(
                self.url + "/api/annotations",
                data=json.dumps(body).encode(),
                headers={"Content-Type": "application/json", "X-Review-Request": "1"}
                if headers
                else {},
            )
        )

    def payload(self):
        return {
            "reviewer": str(uuid.uuid4()),
            "dataset": self.data["dataset_hash"],
            "run": "auction-20260916",
            "label": "Defer",
            "note": "Synthetic review marker",
        }

    def test_only_allowlisted_files_are_served(self):
        for path in (
            "/catalog.json",
            "/.git/config",
            "/../SCORING_RUBRIC.md",
            "/evidence/../../SCORING_RUBRIC.md",
            "/evidence/%2e%2e/catalog.json",
            "/api/source?name=challenges/attack/test-private/anything.clj",
            "/.state/annotations.sqlite3",
        ):
            with self.subTest(path=path), self.assertRaises(HTTPError) as error:
                self.get(path)
            self.assertEqual(404, error.exception.code)
        with self.get("/api/source?name=SCORING_RUBRIC.md") as response:
            self.assertIn("Structural Alignment", json.load(response)["text"])

    def test_stage_rollup_preserves_units_unknowns_and_blockers(self):
        with self.get("/api/sweep") as response:
            sweep = json.load(response)
        self.assertEqual(41, len(sweep["attempts"]))
        self.assertEqual(
            "8d1ba3893a171bf6cc2e638a947f098617fdc80b1309d8ef5a92bf52248db525",
            hashlib.sha256(json.dumps(sweep["attempts"], sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
        )
        self.assertEqual(
            "c4286ad9946a36a31b4599d9d47182e655b37c4312e1ee2b227a21dde549a656",
            hashlib.sha256(json.dumps(sweep["stage_rollup"], sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
        )
        overlay = sweep["latest_confirmed_start_overlay"]
        self.assertTrue(overlay["historical_stage_tables_unchanged"])
        self.assertEqual(0, overlay["detailed_receipt_records_added"])
        self.assertEqual(0, overlay["continuations_add_scored_starts"])
        self.assertEqual(
            [("Go Rama", 17, 16), ("Go HLD", 17, 15), ("Claude", 21, 20)],
            [(lane["lane"], lane["scored_starts"], lane["cells"]) for lane in overlay["lanes"]],
        )
        rama_overlay = overlay["lanes"][0]
        self.assertEqual(4, len(rama_overlay["additional_starts"]))
        self.assertEqual(["attack", "auction-module", "bank-transfer-module", "chat-app"], [start["challenge"] for start in rama_overlay["additional_starts"]])
        self.assertIn("Overall FAIL / score 0", rama_overlay["additional_starts"][0]["status"])
        self.assertIn("native isolated log was not retained", rama_overlay["additional_starts"][1]["native_signal_status"])
        self.assertIn("showed no quota signal", rama_overlay["additional_starts"][3]["native_signal_status"])
        self.assertTrue(all(start["receipt_copy_status"].endswith("not present in this checkout.") for start in rama_overlay["additional_starts"]))
        self.assertEqual("attack", rama_overlay["not_launched"][0]["challenge"])
        self.assertEqual("A2", rama_overlay["not_launched"][0]["attempt"])
        self.assertTrue(all(start["event_time"] is None for start in rama_overlay["additional_starts"]))
        hld_overlay = overlay["lanes"][1]
        self.assertEqual("hld-web-crawler", hld_overlay["additional_starts"][0]["challenge"])
        self.assertEqual(2, len(hld_overlay["not_launched"]))
        continuations = sweep["continuations"]
        self.assertEqual("2026-09-24T22:13:34Z", sweep["snapshot_at"])
        self.assertEqual(sweep["snapshot_at"], overlay["as_of"])
        self.assertEqual(sweep["snapshot_at"], continuations["as_of"])
        self.assertEqual(sweep["snapshot_at"], continuations["event_audit_as_of"])
        panels = {panel["id"]: panel for panel in continuations["completion_panels"]}
        self.assertEqual(8, len(panels))
        self.assertEqual(0, continuations["continuations_add_scored_invocations"])
        self.assertEqual(3, continuations["strict_isolated_completion_count"])
        self.assertEqual({"hld-feature-flag-service-a1-continuation", "hld-file-sync-a1-continuation", "hld-stock-exchange-a1-continuation"}, set(continuations["strict_isolated_completion_ids"]))
        self.assertEqual("timeout-cause-unverified", next(a for a in sweep["attempts"] if a["challenge"] == "hld-feature-flag-service" and a["provider"] == "opencode-go")["outcome"])
        self.assertEqual("timeout-cause-unverified", next(a for a in sweep["attempts"] if a["challenge"] == "hld-file-sync" and a["provider"] == "opencode-go")["outcome"])
        flags_cont = panels["hld-feature-flag-service-a1-continuation"]
        self.assertTrue(flags_cont["same_original_attempt"])
        self.assertFalse(flags_cont["new_scored_invocation"])
        self.assertEqual(4.186195, flags_cont["cli_estimated_usd"])
        self.assertEqual(["claude-opus-5-5", "claude-fable-5-1"], [event["model"] for event in flags_cont["model_phase_events"]])
        self.assertFalse(flags_cont["model_phase_events"][0]["native_effort_confirmed"])
        file_cont = panels["hld-file-sync-a1-continuation"]
        self.assertEqual(9.905451, file_cont["cli_estimated_usd"])
        self.assertIsNone(file_cont["billed_usd"])
        self.assertIn("command-framework-and-blocks", file_cont["model_phase_events"][0]["phase"])
        for challenge in ("almost-equal", "atcoder-cards"):
            rama_cont = next(p for p in continuations["completion_panels"] if p["challenge"] == challenge)
            self.assertFalse(rama_cont["new_scored_invocation"])
            self.assertTrue(any("aggregate/alignment score absent" in c for c in rama_cont["checks"]))
            self.assertEqual("opencode-go/glm-5.3-flash", rama_cont["model_phase_events"][0]["model"])
            self.assertEqual("FULL-SPEC REVIEW only", rama_cont["model_phase_events"][0]["phase"])
        for challenge in ("hld-rate-limiter", "hld-url-shortener"):
            event = next(p for p in continuations["completion_panels"] if p["challenge"] == challenge)
            self.assertFalse(event["model_phase_events"][0]["preflight"])
            self.assertEqual("claude-opus-5-5", event["model_phase_events"][0]["model"])
            self.assertEqual("BUILD", event["model_phase_events"][0]["phase"])
            self.assertIn("no phase verdict", event["model_phase_events"][0]["result"])
        payment_cont = panels["hld-payment-system-a1-continuation"]
        self.assertEqual("opencode-go/muse-spark-1.3-contributor", payment_cont["model_phase_events"][0]["model"])
        self.assertIsNone(payment_cont["model_phase_events"][1]["model"])
        stock_cont = panels["hld-stock-exchange-a1-continuation"]
        self.assertFalse(stock_cont["new_scored_invocation"])
        self.assertEqual("claude-fable-5-1", stock_cont["model_phase_events"][1]["model"])
        self.assertEqual("PASS", stock_cont["model_phase_events"][1]["result"])
        self.assertEqual(".amp/in/artifacts/hld-stock-exchange-A1-full-spec-review/FINAL-RECEIPT.md", stock_cont["receipt_location"])
        self.assertIn("not locally read back", stock_cont["receipt_copy_status"])
        self.assertIn("No build-time hash", stock_cont["source_identity_caveat"])
        self.assertEqual(4, len(continuations["preflights"]))
        self.assertTrue(all(not p["scored_invocation"] for p in continuations["preflights"]))
        self.assertTrue(any(p["id"] == "go-rama-almost-equal-staging-hash-preflight" for p in continuations["preflights"]))
        self.assertTrue(any(p["id"] == "bws-runtime-and-key-preflight" for p in continuations["preflights"]))
        exchange_cont = continuations["reconciliation_panels"][0]
        self.assertNotIn("continuation_retry", exchange_cont)
        self.assertIn("launch-history reconciliation remains separate", exchange_cont["status"])
        self.assertFalse(exchange_cont["new_scored_invocation"])
        self.assertIn("stopped after build/public checks", exchange_cont["prior_owner_reported_disposition"])
        self.assertIn("separate completed continuation", exchange_cont["receipt_reconciliation_warning"])
        audits = continuations["process_audits"]
        self.assertEqual(6, len(audits))
        self.assertTrue(any("no global no-process conclusion" in a["result"] for a in audits))
        rollup = sweep["stage_rollup"]
        self.assertEqual("2026-09-24T09:55:00Z", rollup["source_at"])
        rama_source = rollup["sources"][0]
        self.assertFalse(rama_source["stale"])
        self.assertEqual("2026-09-24T10:35:00Z", rama_source["as_of"])
        self.assertEqual("2026-09-24T10:35:00Z", rama_source["observation_at"])
        self.assertEqual([16, 15, 20], rollup["inventory"][0][1:])
        self.assertEqual([13, 16, 21], rollup["inventory"][1][1:])
        rama = next(lane for lane in sweep["lanes"] if lane["id"] == "go-rama")
        self.assertEqual("2026-09-24T13:37:00Z", rama["observational_status_at"])
        self.assertEqual(["attack", "auction-module", "bank-transfer-module", "chat-app"], rama["pending_launch_challenges"])
        self.assertEqual(19, rama["unassigned_targets_reported"])
        self.assertEqual(13, rama["confirmed_attempts_reported"])
        self.assertFalse(rama["capacity_probe"]["scored"])
        self.assertFalse(rama["capacity_probe"]["quota_reset_verified"])
        self.assertEqual([(0, 1, True), (0, 1, True)], [(p["exit"], p["completions"], p["step_finish"]) for p in rama["capacity_probe"]["lanes"]])
        self.assertEqual([0, 0, 1, 3, 0, 8, 0, 0, 9, 0, 0, 0, 0, 0, 0, 0], [r[3] for r in rollup["current"]])
        current = {r[0]: r[1:] for r in rollup["current"]}
        self.assertEqual([0, 0, 8], current["Plan validation / phase 2"])
        self.assertEqual([0, 4, 0], current["Terminal validation TIMEOUT (cause unverified)"])
        self.assertEqual([0, 0, 0], current["Validation quota-blocked, runner ACTIVE"])
        self.assertEqual([0, 0, 9], current["Build / implementation"])
        self.assertEqual([0, 3, 3], current["Planning / phase 1"])
        self.assertEqual([0, 4, 0], current["Terminal planning TIMEOUT (cause unverified)"])
        self.assertEqual([12, 4, 0], current["Provider-limit STOPPED"])
        self.assertEqual([13, 16, 21], [sum(r[c] for r in rollup["current"]) for c in (1, 2, 3)])
        cumulative = {r[0]: r[1:] for r in rollup["cumulative"]}
        self.assertEqual([4, 4, 9], cumulative["Build / implementation entered"])
        self.assertEqual([2, None, None], cumulative["Full-spec review"])
        self.assertEqual([12, "≥9", 17], cumulative["Plan validation / phase 2 entered"])
        self.assertEqual([1, 4, None], cumulative["Actual private suite executions"])
        self.assertEqual([0, 4, 3], cumulative["Private assessment with implementation"])
        self.assertEqual([0, 0, 0], cumulative["Successful full runner complete"])
        self.assertEqual([12, 6, 18], rollup["blocked"][0][1:])
        self.assertEqual([0, 0, 0], rollup["blocked"][1][1:])
        self.assertEqual([0, 0, 0], rollup["blocked"][2][1:])
        self.assertEqual([13, 16, 21], rollup["blocked"][-1][1:])
        notification = next(a for a in sweep["attempts"] if a["provider"] == "opencode-go" and a["challenge"] == "hld-notification-system")
        self.assertEqual("timeout-cause-unverified", notification["outcome"])
        self.assertEqual("2026-09-24T12:26:32Z", notification["finished_at"])
        self.assertEqual("NOT RUN", notification["private_verdict"])
        self.assertFalse(notification["implementation_reached"])
        self.assertEqual(1, notification["provisional_failed_attempts"])
        self.assertEqual(2.1096, notification["cli_reported_cost_usd"])
        self.assertEqual(2.10191728, notification["estimated_spend_usd"])
        self.assertIsNone(notification["billed_spend_usd"])
        hld = next(lane for lane in sweep["lanes"] if lane["id"] == "go-hld")
        self.assertEqual(0, hld["active_attempts_reported"])
        probe = hld["capacity_probe"]
        self.assertFalse(probe["scored"])
        self.assertFalse(probe["sustained_capacity_verified"])
        self.assertFalse(probe["quota_reset_verified"])
        self.assertEqual([(0, 1, "high"), (0, 1, "high")], [(p["exit"], p["completions"], p["effort"]) for p in probe["lanes"]])
        self.assertEqual(16, hld["confirmed_attempts_reported"])
        self.assertNotIn("hld-notification-system", hld["active_challenges_reported"])
        self.assertNotIn("hld-url-shortener", hld["active_challenges_reported"])
        url = next(a for a in sweep["attempts"] if a["provider"] == "opencode-go" and a["challenge"] == "hld-url-shortener")
        self.assertEqual("timeout-cause-unverified", url["outcome"])
        self.assertEqual("NOT RUN", url["private_verdict"])
        self.assertEqual(0.16435924, url["cli_reported_cost_usd"])
        self.assertEqual(0.1356346, url["estimated_spend_usd"])
        self.assertIsNone(url["billed_spend_usd"])
        autocomplete = next(a for a in sweep["attempts"] if a["provider"] == "opencode-go" and a["challenge"] == "hld-search-autocomplete")
        self.assertEqual("timeout-cause-unverified", autocomplete["outcome"])
        self.assertEqual("2026-09-24T12:38:10Z", autocomplete["finished_at"])
        self.assertEqual("NOT RUN", autocomplete["private_verdict"])
        self.assertEqual(0.15261943, autocomplete["cli_reported_cost_usd"])
        self.assertEqual(0.13947593, autocomplete["estimated_spend_usd"])
        self.assertIsNone(autocomplete["billed_spend_usd"])
        self.assertNotIn("hld-search-autocomplete", hld["active_challenges_reported"])
        go = [a for a in sweep["attempts"] if a["provider"] == "opencode-go"]
        for name, number, end in (("hld-file-sync", 1, "12:51:29Z"), ("hld-ticketing-system", 1, "12:52:01Z"), ("hld-hotel-reservation", 2, "12:52:48Z"), ("hld-rate-limiter", 1, "12:57:48Z")):
            receipt = next(a for a in go if a["challenge"] == name and a["attempt_number"] == number)
            self.assertEqual("timeout-cause-unverified", receipt["outcome"])
            self.assertEqual("2026-09-24T" + end, receipt["finished_at"])
            self.assertEqual("NOT RUN", receipt["private_verdict"])
            self.assertEqual(1, receipt["provisional_failed_attempts"])
            self.assertNotIn(name, hld["active_challenges_reported"])
        hotel1 = next(a for a in go if a["challenge"] == "hld-hotel-reservation" and a["attempt_number"] == 1)
        self.assertEqual("provider-infrastructure-blocked", hotel1["outcome"])
        scheduler = next(a for a in go if a["challenge"] == "hld-job-scheduler")
        self.assertEqual("quota-blocked", scheduler["outcome"])
        self.assertEqual("SKIP", scheduler["private_verdict"])
        self.assertIsNone(scheduler["tests"])
        self.assertEqual({"verdict": "FAIL", "exit": 1, "tests": 3, "assertions": 3, "failures": 0, "errors": 3, "positive_passes": 0, "completed_scored_verdict": False}, scheduler["host_private"])
        self.assertEqual(0, scheduler["counted_solver_failures"])
        self.assertFalse(scheduler["implementation_complete"])
        flags = next(a for a in go if a["challenge"] == "hld-feature-flag-service")
        self.assertEqual("timeout-cause-unverified", flags["outcome"])
        self.assertTrue(flags["implementation_reached"])
        self.assertFalse(flags["implementation_complete"])
        self.assertEqual(1, flags["provisional_failed_attempts"])
        self.assertIsNone(flags["private_verdict"])
        self.assertEqual({"verdict": "FAIL", "exit": 1, "tests": 2, "assertions": 18, "failures": 0, "errors": 18, "completed_scored_verdict": False}, flags["host_private"])
        self.assertEqual("2026-09-24T13:05:47Z", flags["finished_at"])
        self.assertEqual([], hld["active_challenges_reported"])
        click = next(a for a in go if a["challenge"] == "hld-ad-click-aggregation")
        self.assertEqual("quota-blocked", click["outcome"])
        self.assertEqual("2026-09-24T13:08:13Z", click["finished_at"])
        self.assertEqual(0, click["counted_solver_failures"])
        self.assertIsNone(click["private_verdict"])
        self.assertEqual("-", click["native_private_verdict"])
        self.assertEqual({"verdict": "FAIL", "exit": 1, "tests": 6, "assertions": 6, "failures": 0, "errors": 6, "completed_scored_verdict": False}, click["host_private"])
        payment = next(a for a in go if a["challenge"] == "hld-payment-system")
        self.assertEqual("quota-blocked", payment["outcome"])
        self.assertIsNone(payment["private_verdict"])
        self.assertEqual({"verdict": "FAIL", "exit": 1, "tests": 4, "assertions": 194, "failures": 130, "errors": 0, "completed_scored_verdict": False}, payment["host_private"])
        self.assertEqual(0, payment["counted_solver_failures"])
        self.assertNotIn(payment["challenge"], hld["active_challenges_reported"])
        exchange = next(a for a in go if a["challenge"] == "hld-stock-exchange")
        self.assertEqual("quota-blocked", exchange["outcome"])
        self.assertEqual(0, exchange["counted_solver_failures"])
        self.assertEqual(91, exchange["native_model_turns"])
        self.assertEqual("NOT RUN", exchange["private_verdict"])
        self.assertNotIn(exchange["challenge"], hld["active_challenges_reported"])
        rag = next(a for a in sweep["attempts"] if a["provider"] == "opencode-go" and a["challenge"] == "hld-enterprise-rag")
        self.assertEqual("quota-blocked", rag["outcome"])
        self.assertEqual(143, rag["shell_exit"])
        self.assertEqual("NOT RUN", rag["private_verdict"])
        self.assertEqual(0, rag["counted_solver_failures"])

    def test_sweep_is_separate_from_historical_annotations(self):
        before = self.data["dataset_hash"]
        payload = self.payload()
        self.post(payload).close()
        with self.get("/api/sweep") as response:
            sweep = json.load(response)
        self.assertGreaterEqual(len(sweep["attempts"]), 3)
        self.assertEqual(20, len(sweep["dispatches"]))
        for attempt in sweep["attempts"][:1]:
            self.assertEqual("quota-blocked", attempt["outcome"])
            self.assertEqual("FAIL", attempt["native_private_verdict"])
            self.assertIsNone(attempt["private_verdict"])
            self.assertIsNone(attempt["tests"])
            self.assertIsNone(attempt["billed_spend_usd"])
        claude = [a for a in sweep["attempts"] if a["provider"] == "Claude CLI"]
        self.assertEqual(21, len(claude))
        model_audit = sweep["claude_model_audit"]
        self.assertEqual(21, model_audit["attempts_audited"])
        self.assertEqual(9, len(model_audit["fast_transcript_attempt_1_challenges"]))
        self.assertNotIn("hld-search-autocomplete", model_audit["fast_transcript_attempt_1_challenges"])
        self.assertIn("hld-rate-limiter", model_audit["fast_transcript_attempt_1_challenges"])
        self.assertEqual(12, model_audit["stopped_before_fast_count"])
        self.assertEqual(0, model_audit["requested_model_mismatches"])
        self.assertFalse(model_audit["provider_applied_model_effort_verified"])
        self.assertEqual(18, sum(a["outcome"] == "quota-blocked" for a in claude))
        self.assertEqual(3, sweep["private_verdict_count"])
        losers = [a for a in sweep["attempts"] if a["challenge"] == "find-the-losers-of-the-circular-game"]
        self.assertEqual(2, len(losers))
        self.assertEqual("preimplementation-solver-failure", losers[0]["outcome"])
        self.assertEqual("FAIL", losers[0]["private_verdict"])
        self.assertEqual((1, 1, 0, 1), tuple(losers[0][k] for k in ("tests", "assertions", "failures", "errors")))
        self.assertEqual("quota-stopped-owner-confirmed", losers[0]["next_attempt_status"])
        self.assertEqual("quota-blocked", losers[1]["outcome"])
        self.assertEqual("2026-09-24T10:07:38Z", losers[1]["provider_limit_signal_at"])
        self.assertEqual("SKIP", losers[1]["private_verdict"])
        self.assertIsNone(losers[1]["finished_at"])
        self.assertEqual(1, losers[1]["retryOf"])
        self.assertEqual(losers[0]["worker_receipt"], losers[1]["worker_receipt"])
        self.assertIsNone(losers[1]["started_at"])
        crawlers = [a for a in sweep["attempts"] if a["challenge"] == "hld-web-crawler" and a["provider"] == "opencode-go"]
        self.assertEqual(1, len(crawlers))  # Pending A2 is not a scored start.
        self.assertEqual("preimplementation-planning-failure", crawlers[0]["outcome"])
        self.assertEqual("NOT RUN", crawlers[0]["private_verdict"])
        self.assertEqual(0, crawlers[0]["tests"])
        self.assertEqual("go-authorized-launch-receipt-pending", crawlers[0]["next_attempt_status"])
        self.assertNotEqual(crawlers[0]["worker_receipt"], crawlers[0]["next_worker_receipt"])
        payment = next(a for a in sweep["attempts"] if a["challenge"] == "hld-payment-system" and a["provider"] == "opencode-go")
        self.assertEqual("quota-blocked", payment["outcome"])
        self.assertIn("TIMEOUT", payment["runner_status"])
        self.assertTrue(payment["implementation_reached"])
        self.assertEqual("2026-09-24T10:06:05Z", payment["implementation_observed_at"])
        self.assertNotIn("next_attempt_number", payment)
        self.assertEqual(0, payment["counted_solver_failures"])
        self.assertEqual("2026-09-24T12:59:38Z", payment["finished_at"])
        for field in ("shell_exit", "private_verdict", "tests"):
            self.assertIsNone(payment[field])
        for name, tests, assertions in (("hld-feature-flag-service", 2, 120), ("hld-rate-limiter", 4, 360)):
            receipt = next(a for a in claude if a["challenge"] == name)
            self.assertEqual("quota-blocked", receipt["outcome"])
            self.assertEqual("PASS", receipt["private_verdict"])
            self.assertEqual((tests, assertions, 0, 0), (receipt["tests"], receipt["assertions"], receipt["failures"], receipt["errors"]))
        family = next(a for a in claude if a["challenge"] == "family-tree")
        self.assertEqual("FAIL", family["native_private_verdict"])
        self.assertIsNone(family["private_verdict"])
        self.assertIsNone(family["tests"])
        retry = next(a for a in claude if a["attempt_number"] == 2)
        self.assertEqual(1, retry["retryOf"])
        self.assertIsNone(retry["private_verdict"])
        self.assertEqual(7.1903, retry["estimated_spend_usd"])
        hotel = next(
            a for a in sweep["attempts"] if a["challenge"] == "hld-hotel-reservation"
        )
        self.assertEqual("NOT RUN", hotel["private_verdict"])
        self.assertEqual("provider-infrastructure-blocked", hotel["outcome"])
        self.assertEqual(0, hotel["shell_exit"])
        self.assertIsNone(hotel["tests"])
        self.assertIsNone(hotel["billed_spend_usd"])
        self.assertNotEqual(
            hotel["estimated_spend_usd"], hotel["cli_reported_cost_usd"]
        )
        blocked = next(
            a for a in sweep["attempts"] if a["challenge"] == "hld-search-autocomplete" and a["attempt_number"] == 1 and a["provider"] == "Claude CLI"
        )
        self.assertEqual("SKIP", blocked["private_verdict"])
        self.assertEqual(0, blocked["shell_exit"])
        self.assertEqual("FAIL", blocked["runner_status"])
        self.assertFalse(blocked["implementation_reached"])
        self.assertIsNone(blocked["tests"])
        self.assertEqual(1.62567725, blocked["estimated_spend_usd"])
        for name, start, end, cost in (
            ("music-catalog-migration", "09:17:49Z", "09:32:48Z", 9.1333),
            ("profile-module", "09:17:41Z", "09:33:05Z", 8.89937465),
        ):
            receipt = next(a for a in sweep["attempts"] if a["challenge"] == name)
            self.assertEqual(1, receipt["attempt_number"])
            self.assertEqual("safeguard/logistics-blocked", receipt["outcome"])
            self.assertFalse(receipt["implementation_reached"])
            self.assertEqual("2026-09-24T" + start, receipt["started_at"])
            self.assertEqual("2026-09-24T" + end, receipt["finished_at"])
            self.assertEqual(cost, receipt["estimated_spend_usd"])
            for field in ("private_verdict", "tests", "assertions", "billed_spend_usd"):
                self.assertIsNone(receipt[field])
            self.assertEqual(1, sum(a["challenge"] == name for a in sweep["attempts"]))
        self.assertEqual(0, sum(l["scored_attempts"] for l in sweep["lanes"]))
        self.assertEqual(283, sweep["reference_assertions"])
        self.assertIsNone(sweep["billed_spend_usd"])
        for lane in sweep["lanes"][:2]:
            self.assertIn("slow glm-5.3-flash / high", lane["configuration"])
            self.assertIn(
                "fast muse-spark-1.3-contributor / high", lane["configuration"]
            )
        with self.get("/api/data") as response:
            audit = json.load(response)
        self.assertEqual(before, audit["dataset_hash"])
        self.assertNotIn("sweep", audit)
        self.assertEqual(13, len(audit["runs"]))
        with self.get("/api/annotations?reviewer=" + payload["reviewer"]) as response:
            self.assertEqual("Defer", json.load(response)[payload["run"]]["label"])

    def test_annotations_read_back_and_reviewer_separation(self):
        payload = self.payload()
        with self.post(payload) as response:
            self.assertTrue(json.load(response)["saved"])
        payload["label"] = "Fail"
        payload["note"] = "Second synthetic marker <script>not executable</script>"
        self.post(payload).close()
        with self.get("/api/annotations?reviewer=" + payload["reviewer"]) as response:
            saved = json.load(response)[payload["run"]]
        self.assertEqual("Fail", saved["label"])
        self.assertEqual(payload["note"], saved["note"])
        with self.get("/api/annotations?reviewer=" + str(uuid.uuid4())) as response:
            self.assertEqual({}, json.load(response))

    def test_stale_invalid_and_cross_origin_writes_rejected(self):
        for field, value in (
            ("dataset", "stale"),
            ("run", "fake"),
            ("label", "Probably"),
            ("note", "x" * 10001),
        ):
            payload = self.payload()
            payload[field] = value
            with self.subTest(field=field), self.assertRaises(HTTPError) as error:
                self.post(payload)
            self.assertEqual(400, error.exception.code)
        with self.assertRaises(HTTPError) as error:
            self.post(self.payload(), headers=False)
        self.assertEqual(403, error.exception.code)


if __name__ == "__main__":
    unittest.main()
