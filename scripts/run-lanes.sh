#!/usr/bin/env bash
# Run challenges in parallel lanes on one host, one git worktree per lane.
#
# Each lane is a detached worktree of the current HEAD at ../<repo>-lane<i>,
# so challenge encryption, implementations/ and clj-kondo state never collide
# (a single checkout cannot host two runners: each run encrypts every other
# challenge's private files). Lanes share ../reports and ../transcripts;
# report names and results.edn appends are safe under concurrency.
#
# Challenges matching GLOB are dealt round-robin to lanes. Each lane runs its
# share sequentially, one `bb run-challenges -f <name>` per challenge, and
# everything after `--` is passed to every invocation. Lanes run the
# committed HEAD; uncommitted changes in this checkout are not used.
#
# Usage:
#   CHALLENGE_KEY=... scripts/run-lanes.sh [-n LANES] [-f GLOB] [--dry-run] -- <run-challenges args>
#
# Example:
#   CHALLENGE_KEY=... scripts/run-lanes.sh -n 2 -f 'hld-*' -- -a codex --isolate \
#     --slow-model gpt-6-astra --slow-effort high --fast-model gpt-5.6-sol --fast-effort medium
set -euo pipefail

original_args=("$@")
lanes=2
glob='*'
dry_run=0
passthrough=()
while [ $# -gt 0 ]; do
  case "$1" in
    -n) lanes="$2"; shift 2 ;;
    -f) glob="$2"; shift 2 ;;
    --dry-run) dry_run=1; shift ;;
    --) shift; passthrough=("$@"); break ;;
    -h|--help) sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1 (run-challenges args go after --)" >&2; exit 2 ;;
  esac
done
[[ "$lanes" =~ ^[1-9][0-9]*$ ]] || { echo "-n must be a positive integer" >&2; exit 2; }

# Keep the machine awake for the whole run; re-exec once under the inhibitor.
if [ "$dry_run" -eq 0 ] && [ -z "${RUN_LANES_INHIBITED:-}" ] && command -v systemd-inhibit >/dev/null; then
  exec systemd-inhibit --what=sleep:idle --who=run-lanes --why="Rama challenge lanes" \
    env RUN_LANES_INHIBITED=1 "$0" "${original_args[@]}"
fi

repo_root="$(git rev-parse --show-toplevel)"
parent="$(dirname "$repo_root")"
head="$(git -C "$repo_root" rev-parse HEAD)"
if [ -n "$(git -C "$repo_root" status --porcelain --untracked-files=no)" ]; then
  echo "Note: uncommitted changes in $repo_root are not used; lanes run $(git -C "$repo_root" rev-parse --short HEAD)." >&2
fi

challenges=()
for dir in "$repo_root"/challenges/*/; do
  name="$(basename "$dir")"
  # shellcheck disable=SC2053  # glob match is intended
  [[ "$name" == $glob ]] && challenges+=("$name")
done
[ ${#challenges[@]} -gt 0 ] || { echo "No challenges match '$glob'" >&2; exit 1; }
[ "$lanes" -le ${#challenges[@]} ] || lanes=${#challenges[@]}

declare -a lane_work
for i in "${!challenges[@]}"; do
  lane=$(( i % lanes + 1 ))
  lane_work[lane]="${lane_work[lane]:-} ${challenges[$i]}"
done

for lane in $(seq 1 "$lanes"); do
  echo "lane $lane:${lane_work[$lane]}"
done
if [ "$dry_run" -eq 1 ]; then
  echo "Each lane: (cd $parent/$(basename "$repo_root")-lane<i> && bb run-challenges -f <name> ${passthrough[*]})"
  exit 0
fi
[ -n "${CHALLENGE_KEY:-}" ] || { echo "CHALLENGE_KEY is required" >&2; exit 1; }

worktree_for() { echo "$parent/$(basename "$repo_root")-lane$1"; }

for lane in $(seq 1 "$lanes"); do
  wt="$(worktree_for "$lane")"
  if [ -e "$wt" ]; then
    # Encrypted (deleted) private files or stray edits show up here.
    if [ -n "$(git -C "$wt" status --porcelain --untracked-files=no)" ]; then
      echo "Worktree $wt has changes; restore it (e.g. CHALLENGE_KEY=... bb decrypt-challenges there) or remove it." >&2
      exit 1
    fi
    git -C "$wt" checkout --quiet --detach "$head"
  else
    git -C "$repo_root" worktree add --quiet --detach "$wt" "$head"
  fi
done

log_dir="$parent/lane-logs/$(date +%Y-%m-%d-%H%M%S)"
mkdir -p "$log_dir"
echo "Logs: $log_dir"

run_lane() {
  local lane="$1" wt status
  wt="$(worktree_for "$lane")"
  for name in ${lane_work[$lane]}; do
    echo "=== $(date -Is) lane $lane start $name" >> "$log_dir/lane$lane.log"
    status=0
    (cd "$wt" && bb run-challenges -f "$name" "${passthrough[@]}") >> "$log_dir/lane$lane.log" 2>&1 || status=$?
    echo "=== $(date -Is) lane $lane end $name exit=$status" >> "$log_dir/lane$lane.log"
    printf '%s\tlane%s\t%s\texit=%s\n' "$(date -Is)" "$lane" "$name" "$status" >> "$log_dir/summary.tsv"
  done
}

trap 'trap - INT TERM; kill 0' INT TERM
pids=()
for lane in $(seq 1 "$lanes"); do
  run_lane "$lane" &
  pids+=($!)
  # Stagger starts so preflights and dependency resolution do not pile up.
  if [ "$lane" -lt "$lanes" ]; then sleep 10; fi
done
wait "${pids[@]}"
echo "All lanes finished."
cat "$log_dir/summary.tsv"
