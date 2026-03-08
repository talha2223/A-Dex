#!/usr/bin/env bash
set -euo pipefail

export HOST="${HOST:-0.0.0.0}"
export PORT="${PORT:-7860}"

export DB_PATH="${DB_PATH:-/data/adex.db}"
export MEDIA_DIR="${MEDIA_DIR:-/data/media}"

export BACKEND_BASE_URL="${BACKEND_BASE_URL:-http://127.0.0.1:${PORT}}"
export BACKEND_WS_URL="${BACKEND_WS_URL:-ws://127.0.0.1:${PORT}/ws}"

mkdir -p "$(dirname "$DB_PATH")" "$MEDIA_DIR"

(
  cd /app/backend
  node src/server.js
) &
BACKEND_PID=$!

(
  cd /app/discord-bot
  python3 -m bot.main
) &
BOT_PID=$!

cleanup() {
  kill "$BACKEND_PID" "$BOT_PID" >/dev/null 2>&1 || true
}
trap cleanup SIGTERM SIGINT

wait -n "$BACKEND_PID" "$BOT_PID"
EXIT_CODE=$?
cleanup
exit "$EXIT_CODE"
