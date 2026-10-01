#!/usr/bin/env bash
# Load test with ghz (https://ghz.sh). Usage: loadtest/run.sh [host:port]
# Tunables via env: DURATION (default 30s), CONCURRENCY (50), CONNECTIONS (10), PLAYERS (10000).
set -euo pipefail

TARGET="${1:-localhost:9090}"
DURATION="${DURATION:-30s}"
CONCURRENCY="${CONCURRENCY:-50}"
CONNECTIONS="${CONNECTIONS:-10}"
PLAYERS="${PLAYERS:-10000}"

cd "$(dirname "$0")/.."
PROTO=src/main/proto/leaderboard.proto
RUN_ID="$(date +%Y%m%d-%H%M%S)"
BOARD="load-${RUN_ID}"            # fresh leaderboard per run, so runs don't interfere
OUT="loadtest/results/${RUN_ID}"
mkdir -p "$OUT"

ghz_run() {  # ghz_run <name> <method> <data> <extra ghz args...>
  local name=$1 method=$2 data=$3
  shift 3
  echo "==> ${name}"
  ghz --insecure --proto "$PROTO" \
      --call "leaderboard.v1.LeaderboardService/${method}" \
      --connections "$CONNECTIONS" -c "$CONCURRENCY" \
      -d "$data" --format json -o "${OUT}/${name}.json" "$@" "$TARGET"
}

# Note: ghz parses -d as JSON before expanding templates, so numeric templates are quoted;
# proto3 JSON accepts int64 fields as strings.

# 1. Seed: one submission per player so every read below hits an existing player.
#    Doubles as JVM warm-up (JIT) and is not part of the reported results.
ghz_run seed SubmitScore \
  "{\"player_id\":\"player-{{.RequestNumber}}\",\"leaderboard_id\":\"${BOARD}\",\"score\":\"{{randomInt 0 1000000}}\",\"idempotency_key\":\"seed-${BOARD}-{{.RequestNumber}}\"}" \
  -n "$PLAYERS"

# 2. Writes: random players, random scores, unique idempotency keys.
ghz_run SubmitScore SubmitScore \
  "{\"player_id\":\"player-{{randomInt 0 ${PLAYERS}}}\",\"leaderboard_id\":\"${BOARD}\",\"score\":\"{{randomInt 0 1000000}}\",\"idempotency_key\":\"{{newUUID}}\"}" \
  -z "$DURATION"

# 3. Reads.
ghz_run GetTopN GetTopN \
  "{\"leaderboard_id\":\"${BOARD}\",\"n\":10}" \
  -z "$DURATION"

ghz_run GetPlayerRank GetPlayerRank \
  "{\"leaderboard_id\":\"${BOARD}\",\"player_id\":\"player-{{randomInt 0 ${PLAYERS}}}\",\"neighbors\":5}" \
  -z "$DURATION"

ghz_run GetPlayerStats GetPlayerStats \
  "{\"player_id\":\"player-{{randomInt 0 ${PLAYERS}}}\"}" \
  -z "$DURATION"

python3 loadtest/summarize.py "$OUT" "$TARGET" "$DURATION" "$CONCURRENCY" "$CONNECTIONS" | tee "${OUT}/summary.md"
