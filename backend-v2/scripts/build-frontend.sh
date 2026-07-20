#!/usr/bin/env bash
# Rebuild the React SPA and sync it into backend-v2's static resources.
#
# backend-v2 (Quarkus) is the front door: it serves the SPA from
# src/main/resources/META-INF/resources/ (Quarkus serves that dir at "/",
# bundles it into the native image, and SpaFallback.java handles BrowserRouter
# deep links). Railway builds this service with context = backend-v2/, so the
# sibling frontend/ dir is NOT reachable in the Docker build — the built dist is
# committed here instead. Run this whenever the frontend changes, then commit
# the updated META-INF/resources/ alongside the frontend source.
#
# ponytail: committed build artifacts, low-risk given the locked build context.
# Upgrade path (bean iron-trainer-ghbo): move the Railway root dir to repo-root
# and build the SPA in a Docker stage so nothing is committed.
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

echo "✓ SPA synced ($(find "$dest" -type f | wc -l | tr -d ' ') files). Commit META-INF/resources/ with the frontend change."
