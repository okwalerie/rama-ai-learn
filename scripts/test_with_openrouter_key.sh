#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/bws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$BWS_ACCESS_TOKEN" == test-token && "$1" == secret ]] || exit 1
case "$2" in
  list)
    [[ "$#" -eq 2 || ( "$#" -eq 3 && "$3" == test-project ) ]] || exit 1
    case "$MOCK_MODE" in
      missing) printf '[{"id":"other-id","key":"OPENROUTER_API_KEY_BACKUP","value":"decoy"}]\n' ;;
      duplicate) printf '[{"id":"test-id","key":"OPENROUTER_API_KEY","value":"test-only-placeholder"},{"id":"other-id","key":"OPENROUTER_API_KEY","value":"decoy"}]\n' ;;
      list-failure) echo 'private diagnostic' >&2; exit 1 ;;
      *) printf '[{"id":"other-id","key":"OPENROUTER_API_KEY_BACKUP","value":"decoy"},{"id":"test-id","key":"OPENROUTER_API_KEY","value":"test-only-placeholder"}]\n' ;;
    esac
    ;;
  get)
    [[ "$#" -eq 3 && "$3" == test-id ]] || exit 1
    [[ "$MOCK_MODE" != get-failure ]] || { echo 'private diagnostic' >&2; exit 1; }
    printf '{"id":"test-id","key":"%s","value":"test-only-placeholder"}\n' "${TEST_KEY:-OPENROUTER_API_KEY}"
    ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$tmp/bws"

run() {
  env -i HOME="$tmp" PATH="$tmp:/usr/bin:/bin" MOCK_MODE="${MOCK_MODE:-normal}" \
    TEST_KEY="${TEST_KEY:-OPENROUTER_API_KEY}" BWS_API_KEY=test-token \
    OPENROUTER_BWS_SECRET_ID="${OPENROUTER_BWS_SECRET_ID:-}" \
    OPENROUTER_BWS_PROJECT_ID="${OPENROUTER_BWS_PROJECT_ID:-}" \
    "$repo_root/scripts/with-openrouter-key" "$@"
}
consumer=(bash -c '[[ "$OPENROUTER_API_KEY" == test-only-placeholder && -z "${BWS_API_KEY:-}" && -z "${BWS_ACCESS_TOKEN:-}" ]]')
run "${consumer[@]}" # exact name, not the similarly prefixed decoy
OPENROUTER_BWS_PROJECT_ID=test-project run "${consumer[@]}"
OPENROUTER_BWS_SECRET_ID=test-id MOCK_MODE=list-failure run "${consumer[@]}" # ID override skips list

if env -i HOME="$tmp" PATH="$tmp:/usr/bin:/bin" \
  "$repo_root/scripts/with-openrouter-key" true >"$tmp/out" 2>&1; then
  echo 'Expected missing token to fail.' >&2; exit 1
fi
grep -q 'Missing BWS_API_KEY' "$tmp/out"

expect_failure() {
  local expected="$1"
  shift
  if "$@" >"$tmp/out" 2>&1; then
    echo "Expected $expected failure." >&2; exit 1
  fi
  grep -q "$expected" "$tmp/out"
  ! grep -q 'test-only-placeholder\|test-token\|private diagnostic' "$tmp/out"
}
MOCK_MODE=missing expect_failure 'resolve exactly one' run true
MOCK_MODE=duplicate expect_failure 'resolve exactly one' run true
MOCK_MODE=list-failure expect_failure 'resolve exactly one' run true
MOCK_MODE=get-failure expect_failure 'lookup failed' run true
TEST_KEY=WRONG_KEY expect_failure 'lookup failed' run true
echo 'OpenRouter wrapper offline tests passed.'
