#!/usr/bin/env bash
# Parity gate: boot FastAPI AND backend-v2 against ONE fresh Postgres, seed
# through the FastAPI API, pair a bearer, run the contract suite (incl. the
# parity module) against both. Requires Docker + Java 21.
set -euo pipefail
cd "$(dirname "$0")/.."
PG_PORT="${PG_PORT:-55432}"
V1_PORT="${V1_PORT:-8123}"
V2_PORT="${V2_PORT:-8124}"

PG_ID=$(docker run -d --rm -e POSTGRES_PASSWORD=iron -e POSTGRES_DB=iron -p "$PG_PORT:5432" postgres:17)
cleanup() {
  kill "${V1_PID:-0}" "${V2_PID:-0}" 2>/dev/null || true
  docker stop "$PG_ID" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT
WORK=$(mktemp -d)

until docker exec "$PG_ID" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done

DATA_DIR="$WORK" DATABASE_URL="postgresql+psycopg://postgres:iron@127.0.0.1:$PG_PORT/iron" \
  uv run uvicorn app.main:app --port "$V1_PORT" >"$WORK/v1.log" 2>&1 &
V1_PID=$!

( cd ../backend-v2 && ./mvnw -q -DskipTests package )
DATABASE_JDBC_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/iron" \
  DATABASE_USERNAME=postgres DATABASE_PASSWORD=iron \
  QUARKUS_HTTP_PORT="$V2_PORT" IRONTRAINER_AUTH_REQUIRED=true \
  java -jar ../backend-v2/target/quarkus-app/quarkus-run.jar >"$WORK/v2.log" 2>&1 &
V2_PID=$!

for name in "$V1_PORT/api/health" "$V2_PORT/q/health"; do
  ok=0
  for _ in $(seq 1 45); do
    curl -sf "http://127.0.0.1:$name" >/dev/null && ok=1 && break
    sleep 1
  done
  if [ "$ok" -ne 1 ]; then
    echo "backend on $name never became healthy" >&2
    tail -40 "$WORK"/v1.log "$WORK"/v2.log >&2 || true
    exit 1
  fi
done

# Metrics + recovery for the readiness/PMC parity tests are seeded from a pytest
# fixture (test_parity_exports.py::seeded_metrics), NOT here: the `seeded`
# fixture's profile PUT calls rebuild_metrics() which DELETEs the athlete's
# metrics_daily rows, so anything seeded before pytest gets wiped. The fixture
# runs after `seeded` and connects with PARITY_DB_DSN below.
CONTRACT_BASE_URL="http://127.0.0.1:$V1_PORT" V2_BASE_URL="http://127.0.0.1:$V2_PORT" \
  PARITY_PG_ID="$PG_ID" \
  uv run pytest contract_tests -q "$@"
