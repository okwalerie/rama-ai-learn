#!/usr/bin/env python3
"""Check CLI model/effort metadata without submitting a completion request."""
import argparse
import json
import subprocess
import tempfile


def opencode_models(output):
    models = {}
    decoder = json.JSONDecoder()
    remaining = output.strip()
    while remaining:
        name, _, remaining = remaining.partition("\n")
        model, end = decoder.raw_decode(remaining.lstrip())
        models[name] = set(model.get("variants", {}))
        remaining = remaining.lstrip()[end:].strip()
    return models


def claude_models(output):
    models = {}
    for line in output.splitlines():
        event = json.loads(line)
        if event.get("type") != "control_response":
            continue
        response = event.get("response", {}).get("response", {})
        for model in response.get("models", []):
            efforts = set(model.get("supportedEffortLevels", []))
            for key in ("value", "resolvedModel"):
                if model.get(key):
                    models[model[key]] = efforts
    return models


def check_pairs(models, pairs):
    for model, effort in pairs:
        if model not in models:
            raise ValueError(f"Model not advertised by installed CLI: {model}")
        if effort not in models[model]:
            raise ValueError(f"{model} does not advertise effort {effort}; "
                             f"supported: {', '.join(sorted(models[model])) or '(none)'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent", choices=("claude", "opencode"), required=True)
    parser.add_argument("--pair", nargs=2, action="append", required=True,
                        metavar=("MODEL", "EFFORT"))
    opts = parser.parse_args()
    if opts.agent == "claude":
        # Agent SDK initialize is a control message, not a user/model message.
        request = {"type": "control_request", "request_id": "model-metadata",
                   "request": {"subtype": "initialize"}}
        result = subprocess.run(["claude", "--print", "--input-format", "stream-json",
                                 "--output-format", "stream-json", "--verbose"],
                                input=json.dumps(request) + "\n", text=True,
                                capture_output=True, check=True, timeout=60)
        models = claude_models(result.stdout)
    else:
        models = {}
        for provider in sorted({model.split("/")[0] for model, _ in opts.pair}):
            # OpenCode can exit before a large piped catalog flushes. A regular
            # file avoids truncated JSON without accepting incomplete metadata.
            with tempfile.TemporaryFile(mode="w+") as output:
                subprocess.run(["opencode", "models", provider, "--verbose"],
                               text=True, stdout=output, stderr=subprocess.PIPE,
                               check=True, timeout=60)
                output.seek(0)
                models.update(opencode_models(output.read()))
    check_pairs(models, opts.pair)
    for model, effort in opts.pair:
        print(f"CLI metadata supports {opts.agent}: {model} [{effort}]")
    print("No completion requested; metadata is not proof of provider entitlement or applied effort.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.SubprocessError) as exc:
        # Do not print captured provider output, config, or account fields.
        raise SystemExit(f"Model preflight failed: {exc}")
