#!/bin/sh
# Railway provides DATABASE_URL as postgres://user:pass@host:port/db (shared
# with the FastAPI service via a reference variable). JDBC can't carry
# credentials in the URL, so split it here.
set -eu
# Railway injects PORT and healthchecks it — honor it over the image default.
export QUARKUS_HTTP_PORT="${PORT:-8080}"
if [ -n "${DATABASE_URL:-}" ] && [ -z "${DATABASE_JDBC_URL:-}" ]; then
  proto_stripped="${DATABASE_URL#*://}"
  creds="${proto_stripped%%@*}"
  hostpart="${proto_stripped#*@}"
  export DATABASE_USERNAME="${creds%%:*}"
  export DATABASE_PASSWORD="${creds#*:}"
  export DATABASE_JDBC_URL="jdbc:postgresql://${hostpart}"
fi
# Native binary when present (prod image); JVM fallback for local docker runs.
if [ -x /app/application ]; then
  exec /app/application
else
  exec java -jar quarkus-run.jar
fi
