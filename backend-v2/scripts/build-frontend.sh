#!/usr/bin/env bash
# LOCAL DEV ONLY: build the React SPA into backend-v2's static resources so
# `quarkus:dev` serves it at "/" (SpaFallback.java handles BrowserRouter deep
# links). The output (src/main/resources/META-INF/resources/) is gitignored —
# do NOT commit it. In production the backend-v2 Dockerfile builds the SPA in a
# node stage (repo-root build context) and injects it before the native build,
# so nothing is committed (bean iron-trainer-ghbo).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # backend-v2/
frontend="$(cd "$here/../frontend" && pwd)"
dest="$here/src/main/resources/META-INF/resources"

echo "→ building frontend at $frontend"
( cd "$frontend" && npm install && npm run build )

echo "→ syncing dist → $dest"
rm -rf "$dest"
mkdir -p "$dest"
cp -R "$frontend/dist/." "$dest/"

echo "✓ SPA synced ($(find "$dest" -type f | wc -l | tr -d ' ') files) for local quarkus:dev. (gitignored — don't commit; prod builds it in Docker.)"
