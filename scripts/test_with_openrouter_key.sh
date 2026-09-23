#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/bws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$BWS_ACCESS_TOKEN" == test-token && "$1 $2 $3" == 'secret get test-id' ]] || exit 1
printf '{"id":"test-id","key":"%s","value":"test-only-placeholder"}\n' "${TEST_KEY:-OPENROUTER_API_KEY}"
EOF
chmod +x "$tmp/bws"

run() {
  env -i HOME="$tmp" PATH="$tmp:/usr/bin:/bin" TEST_KEY="${TEST_KEY:-OPENROUTER_API_KEY}" BWS_API_KEY=test-token \
    OPENROUTER_BWS_SECRET_ID=test-id "$repo_root/scripts/with-openrouter-key" "$@"
}
run bash -c '[[ "$OPENROUTER_API_KEY" == test-only-placeholder && -z "${BWS_API_KEY:-}" && -z "${BWS_ACCESS_TOKEN:-}" ]]'

if env -i HOME="$tmp" PATH="$tmp:/usr/bin:/bin" \
  OPENROUTER_BWS_SECRET_ID=test-id "$repo_root/scripts/with-openrouter-key" true >"$tmp/out" 2>&1; then
  echo 'Expected missing token to fail.' >&2; exit 1
fi
grep -q 'Missing BWS_API_KEY' "$tmp/out"
if env -i HOME="$tmp" PATH="$tmp:/usr/bin:/bin" BWS_API_KEY=test-token \
  "$repo_root/scripts/with-openrouter-key" true >"$tmp/out" 2>&1; then
  echo 'Expected missing ID to fail.' >&2; exit 1
fi
grep -q 'Missing OPENROUTER_BWS_SECRET_ID' "$tmp/out"
if TEST_KEY=WRONG_KEY run true >"$tmp/out" 2>&1; then
  echo 'Expected wrong secret key to fail.' >&2; exit 1
fi
grep -q 'unexpected ID, key, or empty value' "$tmp/out"
! grep -q 'test-only-placeholder\|test-token' "$tmp/out"
echo 'OpenRouter wrapper offline tests passed.'
