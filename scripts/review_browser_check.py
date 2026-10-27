#!/usr/bin/env python3
"""Chromium E2E via installed agent-browser; temporary DB, no real reviews changed."""

import argparse
import subprocess
import tempfile
import threading
import uuid
from pathlib import Path

from review_server import make_server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, required=True)
    args = parser.parse_args()
    args.artifacts.mkdir(parents=True, exist_ok=True)
    session = "review-" + uuid.uuid4().hex[:8]

    def browser(*command):
        result = subprocess.run(
            ["agent-browser", "--session", session, *command],
            text=True,
            capture_output=True,
            timeout=40,
            check=False,
        )
        if result.returncode:
            raise AssertionError(
                f"Browser command failed: {command[0]}\n{result.stderr}\n{result.stdout}"
            )
        return result.stdout.strip()

    def check(expression):
        browser(
            "eval",
            f'(() => {{ if (!({expression})) throw new Error("Assertion failed: {expression.replace(chr(34), chr(39))}"); return true; }})()',
        )

    def wait(expression):
        browser("wait", "--fn", expression)

    with tempfile.TemporaryDirectory() as temp:
        server = make_server(0, Path(temp) / "reviews.sqlite3")
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            browser("open", base)
            browser("set", "viewport", "1440", "1000", "2")
            wait('document.querySelectorAll(".run-button").length === 13')
            wait('document.querySelector("#sweep-count").textContent === "41 records"')
            check('document.querySelector("nav a").textContent.includes("Historical runs")')
            check('document.querySelector("#main").textContent.includes("13 historical review entries are listed here")')
            check('document.querySelector("#main").textContent.includes("41 imported attempt records")')
            check('document.querySelector("#detail").textContent.includes("FAIL")')
            check('document.querySelector("nav a").classList.contains("active")')
            browser("screenshot", str(args.artifacts / "review-desktop.png"))

            browser("click", '[data-label="Pass"]')
            browser("fill", "#note", "Synthetic E2E note <img src=x onerror=alert(1)>")
            browser("click", '[data-label="Fail"]')
            wait(
                'document.querySelector("#save-status").textContent.startsWith("Saved")'
            )
            browser("reload")
            wait(
                'document.querySelector("[data-label=Fail]").getAttribute("aria-pressed") === "true"'
            )
            check(
                'document.querySelector("#note").value.includes("Synthetic E2E note")'
            )
            check('document.querySelectorAll("#review img").length === 0')
            browser("click", '[data-label="Defer"]')
            browser("click", "#undo")
            check(
                'document.querySelector("[data-label=Fail]").getAttribute("aria-pressed") === "true"'
            )
            browser("click", "#next")
            check('location.hash.includes("social-20260916")')
            browser("press", "ArrowLeft")
            check('location.hash.includes("auction-20260916")')
            browser("press", "1")
            check(
                'document.querySelector("[data-label=Pass]").getAttribute("aria-pressed") === "true"'
            )
            browser("press", "2")
            browser("press", "d")
            browser("press", "u")
            check(
                'document.querySelector("[data-label=Fail]").getAttribute("aria-pressed") === "true"'
            )
            browser("press", "Control+s")
            browser("press", "Control+Enter")
            check('location.hash.includes("social-20260916")')
            browser("click", '[data-tab="events"]')
            wait('document.querySelectorAll(".event").length > 10')
            check(
                '!document.querySelector("#events").textContent.includes("SYNTHETIC_PRIVATE_CANARY")'
            )
            browser("select", "#event-filter", "claimed_verdict")
            check(
                'document.querySelector("#event-rows").textContent.includes("No exported records") || document.querySelectorAll(".claimed_verdict").length > 0'
            )
            browser("select", "#event-filter", "all")
            browser("screenshot", str(args.artifacts / "review-transcript.png"))

            browser("click", '[data-run="attack-20260916"]')
            browser("click", '[data-tab="evidence"]')
            browser("click", '[data-evidence="attack-solution.clj"]')
            wait(
                'document.querySelector("#viewer-body").textContent.includes("deframafn")'
            )
            check('document.querySelector("#viewer").open')
            browser("click", "#close-viewer")
            browser("click", '[data-run="A-rate-01"]')
            check(
                'document.querySelector("#tab-body").textContent.includes("No artifact transferred")'
            )
            browser("click", '[data-tab="metrics"]')
            check('document.querySelectorAll("#tab-body .unknown").length >= 10')
            browser("fill", "#search", "NO-SUCH-ATTEMPT")
            check('document.querySelectorAll(".run-button").length === 0')
            check(
                'document.querySelector("#detail").textContent.includes("No attempt selected")'
            )
            browser("screenshot", str(args.artifacts / "review-empty.png"))
            browser("fill", "#search", "")
            browser("select", "#filter", "stopped")
            check('document.querySelectorAll(".run-button").length === 4')
            browser("click", 'nav a[href="#compare"]')
            wait('document.querySelector("#comparison") !== null')
            check(
                'document.querySelector("#comparison").textContent.includes("Matched challenge")'
            )
            browser("select", "#compare-0", "auction-20260916")
            browser("select", "#compare-1", "social-20260916")
            check(
                'document.querySelector("#comparison").textContent.includes("Different challenges")'
            )
            check(
                'document.querySelector("#comparison").textContent.includes("34.3457")'
            )
            browser("screenshot", str(args.artifacts / "review-comparison.png"))

            browser("select", "#compare-0", "bank-transfer-20260916")
            browser("select", "#compare-1", "time-series-20260916")
            check(
                'document.querySelector("#comparison").textContent.includes("51.2740")'
            )
            check(
                'document.querySelector("#comparison").textContent.includes("52.1273")'
            )
            check('document.querySelectorAll("#comparison .pass").length === 1')
            check('document.querySelectorAll("#comparison .fail").length === 1')
            check('document.querySelectorAll("#comparison .timeline").length === 0')
            browser("screenshot", str(args.artifacts / "review-opus-comparison.png"))
            browser("open", base + "/#runs/time-series-20260916")
            wait(
                'document.querySelector("#detail").textContent.includes("time-series-module-hard")'
            )
            browser("click", '[data-tab="events"]')
            check(
                'document.querySelector("#tab-body").textContent.includes("No phase transcript transferred")'
            )
            check('document.querySelectorAll("#tab-body .event").length === 0')
            browser("click", '[data-tab="phases"]')
            check(
                'document.querySelector("#tab-body").textContent.includes("Phase timeline unavailable")'
            )
            browser("screenshot", str(args.artifacts / "review-missing-phases.png"))

            browser("click", '[data-tab="evidence"]')
            browser("click", '[data-evidence="time-series-opus-module.clj"]')
            wait(
                'document.querySelector("#viewer-body").textContent.includes("defmodule")'
            )
            browser("click", "#close-viewer")
            browser("open", base + "/#runs/local-bank-astra-20260915")
            wait(
                'document.querySelector("#detail").textContent.includes("local-bank-astra")'
            )
            browser("click", '[data-tab="events"]')
            wait('document.querySelectorAll("#event-rows .event").length === 20')
            check('document.querySelectorAll("#phase-capture option").length === 10')
            check(
                'document.querySelector("#events").textContent.includes("timestamps are unavailable")'
            )
            browser(
                "eval",
                '(() => {const s=document.querySelector("#phase-capture");s.selectedIndex=9;s.dispatchEvent(new Event("change"));})()',
            )
            wait('document.querySelectorAll("#event-rows .event").length === 28')
            browser("select", "#event-filter", "usage")
            check('document.querySelectorAll("#event-rows .usage").length > 0')
            browser("screenshot", str(args.artifacts / "review-local-transcript.png"))
            browser("open", base + "/#runs/local-time-series-report-only-20260915")
            wait(
                'document.querySelector("#detail").textContent.includes("does not establish a private test failure")'
            )
            browser("click", '[data-tab="events"]')
            check(
                'document.querySelector("#tab-body").textContent.includes("No phase transcript transferred")'
            )

            browser("click", 'nav a[href="#audit"]')
            wait('document.querySelector("#priority") !== null')
            browser("select", "#priority", "P0")
            check('document.querySelectorAll("#findings details").length === 2')
            browser(
                "click",
                'details[open] [data-source="scripts/run_challenges.bb"][data-start="846"]',
            )
            wait('document.querySelector("#viewer-body .target") !== null')
            browser("press", "Escape")
            check('!document.querySelector("#viewer").open')
            browser("screenshot", str(args.artifacts / "review-audit.png"))

            browser("click", 'nav a[href="#coverage"]')
            wait('document.querySelectorAll(".skill").length === 8')
            check('document.querySelectorAll(".skill").length === 8')
            check('document.querySelector("#main").textContent.includes("Unsupported")')
            check(
                'Array.from(document.querySelectorAll("a")).some(a => a.getAttribute("href") === "/evidence/archive-index.json")'
            )
            browser("screenshot", str(args.artifacts / "review-coverage.png"))
            browser("click", 'nav a[href="#sweep"]')
            wait('document.querySelectorAll(".sweep-lane").length === 3')
            check(
                'document.querySelector("#sweep").textContent.includes("Shipment confirmed")'
            )
            check(
                'document.querySelector("#sweep").textContent.includes("Stale pending")'
            )
            check('document.querySelector("#sweep time").dateTime === "2026-09-24T22:13:34Z"')
            check('document.querySelector("#latest-start-overlay").textContent.includes("As of 2026-09-24T22:13:34Z")')
            check('document.querySelectorAll("#sweep-attempts details").length >= 3')
            check('document.querySelectorAll("#stage-rollup table").length === 4')
            check('document.querySelectorAll("#latest-start-overlay .latest-start-panel").length === 3')
            check('document.querySelector("#latest-start-overlay").textContent.includes("17 scored starts / 16 cells")')
            check('document.querySelector("#latest-start-overlay").textContent.includes("17 scored starts / 15 cells")')
            check('document.querySelector("#latest-start-overlay").textContent.includes("21 scored starts / 20 cells")')
            check('document.querySelector("#latest-start-overlay").textContent.includes("Overall FAIL / score 0")')
            check('document.querySelector("#latest-start-overlay").textContent.includes(".amp/go-rama/attack-attempt-01.md")')
            check('document.querySelector("#latest-start-overlay").textContent.includes(".amp/go-rama/chat-app-attempt-01.md")')
            check('document.querySelector("#latest-start-overlay").textContent.includes("Receipt path supplied by owner but file is not present in this checkout")')
            check('document.querySelector("#latest-start-overlay").textContent.includes("native isolated log was not retained")')
            check('document.querySelector("#latest-start-overlay").textContent.includes("GO was overtaken")')
            check('document.querySelector("#latest-start-overlay").textContent.includes("HOLD; no launch")')
            check('document.querySelector("#latest-start-overlay").textContent.includes("Continuations add 0 scored starts")')
            check('document.querySelectorAll("#continuations .continuation-panel").length === 8')
            check('document.querySelector("#continuations").textContent.includes("two-worker IPC smoke PASS")')
            check('document.querySelectorAll("#continuations .reconciliation-panel").length === 1')
            check('document.querySelector("#continuations").textContent.includes("Strict-isolated completed continuations: 3")')
            check('document.querySelectorAll("#continuation-preflights .preflight-panel").length === 4')
            check('document.querySelector("#continuation-preflights").textContent.includes("almost-equal")')
            check('document.querySelector("#continuation-preflights").textContent.includes("BWS key lookup succeeded")')
            check('document.querySelector("#continuation-preflights").textContent.includes("One completion per lane does not establish sustained capacity or a provider quota reset")')
            check('document.querySelector("#continuation-preflights").textContent.includes("Scored invocationNo")')
            check('document.querySelector("#continuations").textContent.includes("claude-opus-5-5")')
            check('document.querySelector("#continuations").textContent.includes("FULL-SPEC REVIEW only")')
            check('document.querySelector("#continuations").textContent.includes("G.includes is not a function")')
            check('document.querySelector("#continuations").textContent.includes("Launcher receipt/sentinel say attempt2; REASONING says attempt3")')
            check('document.querySelector("#continuations").textContent.includes("5,182 / 5,112 / 5,177 / 5,152")')
            check('document.querySelector("#continuations").textContent.includes("not locally read back")')
            check('document.querySelector("#continuation-reconciliation").textContent.includes("launch-history reconciliation remains separate")')
            check('document.querySelector("#continuation-reconciliation").textContent.includes("stopped after build/public checks")')
            check('document.querySelector("#continuation-reconciliation").textContent.includes("initial scored-launch history")')
            check('document.querySelector("#continuation-reconciliation").textContent.includes("file is not locally readable here")')
            check('document.querySelector("#continuations").textContent.includes("no global no-process conclusion")')
            check('document.querySelector("#continuations").textContent.includes("origin/master 43abd3fccff8777d8995f8a29658f3c4c97df01a")')
            check('JSON.stringify([1,2,3].map(c => [...document.querySelectorAll("#stage-current tbody tr")].reduce((n,r) => n+Number(r.cells[c].textContent),0))) === JSON.stringify([13,16,21])')
            check('[...document.querySelectorAll("#stage-cumulative td")].some(c => c.textContent === "≥9")')
            check('[...document.querySelectorAll("#stage-cumulative td")].some(c => c.textContent === "Unknown")')
            check(
                'getComputedStyle(document.querySelector(".scope-strip")).display === "none"'
            )
            check('document.querySelector("#export").hidden')
            browser("screenshot", str(args.artifacts / "review-sweep-desktop.png"))
            browser("eval", 'document.querySelector("#latest-start-overlay").scrollIntoView()')
            browser("screenshot", str(args.artifacts / "review-latest-start-overlay.png"))
            browser("eval", 'document.querySelectorAll("#latest-start-overlay .latest-start-panel")[0].querySelector("li:last-child").scrollIntoView()')
            browser("screenshot", str(args.artifacts / "review-start-rows-detail.png"))
            browser("eval", 'document.querySelector("#continuations").scrollIntoView()')
            browser("screenshot", str(args.artifacts / "review-continuation-events.png"))
            browser("eval", 'document.querySelectorAll("#continuations .continuation-panel")[2].scrollIntoView()')
            browser("screenshot", str(args.artifacts / "review-stock-exchange-completion.png"))
            browser("eval", 'document.querySelector("#continuation-preflights").scrollIntoView()')
            browser("screenshot", str(args.artifacts / "review-continuation-preflights.png"))
            browser("eval", 'document.querySelectorAll("#continuation-preflights .preflight-panel")[3].scrollIntoView()')
            browser("screenshot", str(args.artifacts / "review-bws-preflight.png"))
            browser("eval", 'document.querySelector("#continuation-reconciliation").scrollIntoView()')
            browser("screenshot", str(args.artifacts / "review-stock-exchange-reconciliation.png"))
            browser("click", "#sweep-attempts details:first-child summary")
            check(
                'document.querySelectorAll("#sweep-attempts details:first-child .unknown").length >= 6'
            )
            check(
                'document.querySelector("#sweep-attempts details:first-child a").textContent.includes("Worker thread")'
            )
            browser(
                "eval", 'document.querySelector("#sweep-attempts").scrollIntoView()'
            )
            browser("screenshot", str(args.artifacts / "review-sweep-attempt.png"))
            browser("click", "#sweep-attempts details:last-child summary")
            check(
                'document.querySelector("#sweep-attempts details:last-child").textContent.includes("SKIP")'
            )
            check(
                'document.querySelector("#sweep-attempts details:last-child").textContent.includes("1.6257")'
            )
            browser(
                "eval",
                'document.querySelector("#sweep-attempts details:last-child").scrollIntoView()',
            )
            browser("screenshot", str(args.artifacts / "review-sweep-blocked.png"))
            for view in (
                "#runs/A-rate-01",
                "#runs/local-bank-astra-20260915",
                "#compare",
                "#audit",
                "#coverage",
                "#sweep",
            ):
                browser("open", base + "/" + view)
                browser("set", "viewport", "390", "844", "2")
                wait('document.querySelector("#main .paper") !== null')
                browser(
                    "eval",
                    "new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))",
                )
                check("document.documentElement.scrollWidth <= innerWidth")
                check("devicePixelRatio === 2")
                if view == "#sweep":
                    wait('document.querySelectorAll(".sweep-lane").length === 3')
                    check("document.documentElement.scrollWidth <= innerWidth")
                    browser(
                        "screenshot", str(args.artifacts / "review-sweep-mobile.png")
                    )
                elif view == "#runs/A-rate-01":
                    browser("click", '[data-tab="phases"]')
                    browser(
                        "eval", 'document.querySelector("#detail").scrollIntoView()'
                    )
                    browser("screenshot", str(args.artifacts / "review-mobile.png"))
                elif view == "#runs/local-bank-astra-20260915":
                    browser("click", '[data-tab="events"]')
                    wait(
                        'document.querySelectorAll("#event-rows .event").length === 20'
                    )
                    browser(
                        "eval",
                        'document.querySelector("#phase-capture").scrollIntoView()',
                    )
                    browser(
                        "screenshot", str(args.artifacts / "review-local-mobile.png")
                    )
                elif view == "#compare":
                    check('document.querySelector(".comparison").clientWidth >= 760')
                    browser(
                        "eval", 'document.querySelector("#comparison").scrollIntoView()'
                    )
                    browser(
                        "screenshot",
                        str(args.artifacts / "review-mobile-comparison.png"),
                    )
                    browser(
                        "eval",
                        'document.querySelector("#comparison .table-wrap").scrollLeft = 760',
                    )
                    check(
                        'document.querySelector("#comparison .table-wrap").scrollLeft > 0'
                    )
                    browser(
                        "screenshot",
                        str(args.artifacts / "review-mobile-comparison-right.png"),
                    )
            print(
                "PASS: historical inventory/reviews; evidence/source dialogs; Codex capture switching; missing states; comparisons; sweep dispatch/start separation and unknown verdicts; 390px checks on all 5 routes."
            )
            print(
                "Chromium width emulation only, not physical mobile or touch/Safari verification."
            )
        finally:
            browser("close")
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    main()
