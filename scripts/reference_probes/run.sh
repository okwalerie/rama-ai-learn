#!/bin/bash
# Contract-edge probes used by docs/REFERENCE_COMPARISON.md.
# Usage, from the repo root:
#   scripts/reference_probes/run.sh <challenge> <implementation-src-dir> <probe-ns>
# Example:
#   scripts/reference_probes/run.sh bank-transfer-module \
#     challenges/bank-transfer-module/test-resources probe.bank
# Prints one {:probe ...} EDN line per probe. Reads the implementation only.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
c=$1; impl=$(cd "$2" && pwd); ns=$3
d=$(mktemp -d)
trap 'rm -rf "$d"' EXIT
cat > "$d/deps.edn" <<EOD
{:mvn/repos {"nexus-releases" {:url "https://nexus.redplanetlabs.com/repository/maven-public-releases"}}
 :deps {org.clojure/clojure {:mvn/version "1.12.4"}
        rama-challenges/rama-deps {:local/root "$root/lib/rama-deps"}
        rama-challenges/harness {:local/root "$root/lib/harness"}}
 :paths ["$root/challenges/$c/src" "$impl" "$root/scripts/reference_probes/src"]}
EOD
cd "$d"
clojure -J--add-opens=java.base/java.lang=ALL-UNNAMED -J--enable-native-access=ALL-UNNAMED \
  -M -m "$ns" 2>&1 | grep -E '^\{:probe|Exception'
