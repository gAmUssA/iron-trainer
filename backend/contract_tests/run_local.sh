#!/usr/bin/env bash
# Boot a throwaway backend and run the contract suite against it over HTTP.
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${PORT:-8123}"
DATA_DIR="$(mktemp -d)"
export DATA_DIR
uv run uvicorn app.main:app --port "$PORT" >"$DATA_DIR/server.log" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do
  curl -sf "http://127.0.0.1:$PORT/api/health" >/dev/null && break
  sleep 1
done
CONTRACT_BASE_URL="http://127.0.0.1:$PORT" uv run pytest contract_tests -q "$@"
