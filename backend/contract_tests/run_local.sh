#!/usr/bin/env bash
# Boot a throwaway backend and run the contract suite against it over HTTP.
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${PORT:-8123}"
DATA_DIR="$(mktemp -d)"
export DATA_DIR
uv run uvicorn app.main:app --port "$PORT" >"$DATA_DIR/server.log" 2>&1 &
SERVER_PID=$!
cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  rm -rf "$DATA_DIR"
}
trap cleanup EXIT

up=0
for _ in $(seq 1 30); do
  if curl -sf "http://127.0.0.1:$PORT/api/health" >/dev/null; then up=1; break; fi
  sleep 1
done
if [ "$up" -ne 1 ]; then
  echo "Backend never became healthy on port $PORT — server.log:" >&2
  cat "$DATA_DIR/server.log" >&2
  exit 1
fi

CONTRACT_BASE_URL="http://127.0.0.1:$PORT" uv run pytest contract_tests -q "$@"
