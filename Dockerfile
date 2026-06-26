# ── Stage 1: build the React frontend ─────────────────────────────────────────
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm install
COPY frontend/ ./
RUN npm run build

# ── Stage 2: Python backend + built frontend ──────────────────────────────────
FROM python:3.12-slim AS runtime
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

WORKDIR /app/backend
COPY backend/ ./
# Editable so the package resolves from /app/backend/app and REPO_ROOT stays /app
# (where the built frontend is copied below).
RUN uv pip install --system -e .

# Built static assets land where main.py expects them (REPO_ROOT/frontend/dist).
COPY --from=frontend /app/frontend/dist /app/frontend/dist
ENV DATA_DIR=/data
EXPOSE 8000
# No VOLUME here: production (Railway + Supabase) is stateless (data in Postgres,
# exports streamed in-memory), and Railway rejects the Dockerfile VOLUME directive.
# Local `docker compose` still persists SQLite via its own `iron_data:/data` mount.
# Honor Railway's injected $PORT (falls back to 8000 for local docker-compose).
# init_db() runs `alembic upgrade head` on startup, so migrations apply automatically.
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
