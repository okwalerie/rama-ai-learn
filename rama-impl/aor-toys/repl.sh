#!/bin/bash
# Start the dev nREPL on :7888 with secrets pulled from Bitwarden Secrets
# Manager (see ../../bin/with-bws). Optional names fall back to the caller's
# environment; without TYPESAFE_API_KEY the Jev client runs in stub mode.
cd "$(dirname "$0")"
exec with-bws '?TYPESAFE_API_KEY' '?CLAUDE_CODE_OAUTH_TOKEN' -- clojure -M:nrepl
