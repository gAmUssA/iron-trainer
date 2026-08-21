#!/usr/bin/env bash
# Upgrade test (bean yijb): prove that a self-hoster who runs `docker compose pull`
# keeps their training history.
#
# Every other failure mode in the self-host milestone costs someone an hour. A bad
# migration costs them their training history, and they will discover it long after
# the backup they never took. So this is the one path that gets an automated gate.
#
#   OLD image + empty DB  ->  seed data  ->  NEW image + SAME DB  ->  data still there
#
# Deliberately runs the REAL published artifacts against a REAL Postgres rather than
# unit-testing Flyway: the thing that breaks is the combination (image, migrations,
# existing rows), and only an actual upgrade exercises it.
#
# Usage:
#   upgrade-test.sh <old-image> <new-image>
#
# Local example (old = last release, new = whatever you just built):
#   backend-v2/scripts/upgrade-test.sh ghcr.io/gamussa/iron-trainer:0.1 iron-trainer-jvm:dev
set -euo pipefail

OLD_IMAGE="${1:?usage: upgrade-test.sh <old-image> <new-image>}"
NEW_IMAGE="${2:?usage: upgrade-test.sh <old-image> <new-image>}"

NET=upgradetest
DB=upgradetest-db
APP=upgradetest-app
PORT=18080
PGPASS=iron

cleanup() {
  docker rm -f "$APP" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "UPGRADE TEST FAILED: $*" >&2; docker logs "$APP" 2>&1 | tail -40 >&2 || true; exit 1; }

wait_healthy() {
  local what="$1"
  for _ in $(seq 1 60); do
    if curl -sf "http://localhost:${PORT}/q/health" >/dev/null 2>&1; then
      echo "  $what is healthy"
      return 0
    fi
    sleep 3
  done
  fail "$what never became healthy"
}

schema_version() {
  docker exec "$DB" psql -U iron -d iron -tAc \
    "select max(version::numeric) from flyway_schema_history where success" 2>/dev/null | tr -d ' '
}

echo "=== upgrade test: $OLD_IMAGE -> $NEW_IMAGE ==="

cleanup
docker network create "$NET" >/dev/null
docker run -d --name "$DB" --network "$NET" \
  -e POSTGRES_USER=iron -e POSTGRES_PASSWORD="$PGPASS" -e POSTGRES_DB=iron \
  postgres:17 >/dev/null
until docker exec "$DB" pg_isready -U iron -d iron >/dev/null 2>&1; do sleep 2; done
echo "  postgres ready"

run_app() {
  docker run -d --name "$APP" --network "$NET" \
    -e DATABASE_URL="postgres://iron:${PGPASS}@${DB}:5432/iron" \
    -e SESSION_SECRET=upgrade-test \
    -p "${PORT}:8080" "$1" >/dev/null
}

# ── 1. old version on an empty database ──────────────────────────────────────
echo "-- booting OLD ($OLD_IMAGE)"
run_app "$OLD_IMAGE"
wait_healthy "old version"
OLD_SCHEMA=$(schema_version)
echo "  schema version after old boot: ${OLD_SCHEMA:-none}"
[ -n "$OLD_SCHEMA" ] || fail "old version did not create a Flyway history"

# ── 2. seed real data through the real API ───────────────────────────────────
# The athlete row has to exist first. On the SaaS deployment it is created by Strava
# OAuth / Apple sign-in / device pairing; a fresh self-host install has none, and
# DEFAULT_ATHLETE_ID=1 points at a row that was never inserted, so every write 500s
# on a foreign key. That is a real bug (bean zvc2, under the local-mode epic) — but
# it is not what THIS test is for, so create the row directly and carry on testing
# migrations.
#
# zvc2 is fixed as of the next release, but this seeding must STAY until the
# PREVIOUS release — the OLD image booted below — also contains the fix. Remove it
# only once the release being upgraded FROM creates its own athlete; if the test
# still passes then, the bootstrap is doing its job.
docker exec "$DB" psql -U iron -d iron -q -c \
  "insert into athlete (id, name) values (1, 'Upgrade Test') on conflict (id) do nothing" \
  || fail "could not seed the athlete row"

# A recorded fitness test is a good canary: it is athlete-owned row data written
# through the normal write path, not something hand-inserted into a table that a
# migration might legitimately reshape.
echo "-- seeding data through the API"
SEED=$(curl -sf -X POST "http://localhost:${PORT}/api/tests/result" \
  -H 'Content-Type: application/json' \
  -d '{"test_slug":"bike-ftp-20","date":"2026-01-05","inputs":{"avg_power_w":240}}') \
  || fail "could not seed a test result against the old version"
SEED_ID=$(echo "$SEED" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "  seeded fitness_test_result id=$SEED_ID (ftp=$(echo "$SEED" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["ftp"])'))"

BEFORE_COUNT=$(docker exec "$DB" psql -U iron -d iron -tAc "select count(*) from fitness_test_result" | tr -d ' ')
[ "$BEFORE_COUNT" -ge 1 ] || fail "seed did not reach the database"

# ── 3. upgrade: same volume, same database, new image ────────────────────────
echo "-- stopping OLD, booting NEW ($NEW_IMAGE) against the SAME database"
docker rm -f "$APP" >/dev/null
run_app "$NEW_IMAGE"
# Flyway runs at startup, so "healthy" here already means migrations succeeded —
# a failed migration aborts the boot and this times out.
wait_healthy "new version"

NEW_SCHEMA=$(schema_version)
echo "  schema version after upgrade: ${NEW_SCHEMA:-none}"
awk -v a="$OLD_SCHEMA" -v b="$NEW_SCHEMA" 'BEGIN{exit !(b>=a)}' \
  || fail "schema version went BACKWARDS: $OLD_SCHEMA -> $NEW_SCHEMA"

# ── 4. the data must still be there, and still readable through the API ──────
AFTER_COUNT=$(docker exec "$DB" psql -U iron -d iron -tAc "select count(*) from fitness_test_result" | tr -d ' ')
[ "$AFTER_COUNT" = "$BEFORE_COUNT" ] \
  || fail "row count changed across the upgrade: $BEFORE_COUNT -> $AFTER_COUNT"

# Reading it back through the API (not just SQL) also proves the new code can still
# deserialize rows the old version wrote — a migration can leave a table intact and
# still break the mapping.
FOUND=$(curl -sf "http://localhost:${PORT}/api/tests/results" \
  | python3 -c "import json,sys; print(sum(1 for r in json.load(sys.stdin)['results'] if r['id']==$SEED_ID))")
[ "$FOUND" = "1" ] || fail "seeded result id=$SEED_ID is not readable through the new version's API"

FAILED_MIGRATIONS=$(docker exec "$DB" psql -U iron -d iron -tAc \
  "select count(*) from flyway_schema_history where not success" | tr -d ' ')
[ "$FAILED_MIGRATIONS" = "0" ] || fail "$FAILED_MIGRATIONS migration(s) recorded as failed"

echo "=== PASS: $BEFORE_COUNT row(s) survived, schema $OLD_SCHEMA -> $NEW_SCHEMA, data readable via API ==="
