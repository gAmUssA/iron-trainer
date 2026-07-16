"""Strangler proxy: forward configured GET paths to backend-v2, in one place.

The Quarkus migration ports one vertical at a time. This middleware is the
single seam that decides which request paths backend-v2 now owns — replacing the
per-handler `_maybe_proxy` calls that used to live in the export router.

A request is proxied only when ALL of these hold:
  * method is GET (writes never proxy),
  * EXPORT_PROXY_URL is set (the flip is on; empty = instant local rollback),
  * the path matches PROXY_PATHS (the allowlist of what backend-v2 implements),
  * the caller authenticated with a bearer token (backend-v2 re-authenticates it).

Everything else — unmatched paths, non-GET, session-cookie (web) traffic, or an
unreachable backend — is served locally. Cookie traffic stays local until Phase 7
regardless. A proxy failure can never break a client that worked yesterday.
"""

from __future__ import annotations

import httpx
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from .config import get_settings
from .logging_config import get_logger

log = get_logger("strangler")

_PROXY_TIMEOUT_S = 30.0
# RFC 7230 hop-by-hop headers, plus content-length (recomputed) and
# content-encoding (httpx transparently decompresses, so forwarding the original
# encoding header would mislabel an already-decoded body).
_DROP_HEADERS = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade",
    "content-length", "content-encoding",
}


def path_matches(path: str, patterns: list[str]) -> bool:
    """True when `path` is owned by backend-v2 per the allowlist. A pattern
    ending in '*' matches by prefix; otherwise it must equal the path exactly.
    Prefix patterns must be specific enough to exclude siblings backend-v2 does
    NOT implement (e.g. '/api/export/workout/*' proxies the file exports but not
    the '/api/export/*.zip' bundles, which live under different path segments)."""
    for p in patterns:
        if p.endswith("*"):
            if path.startswith(p[:-1]):
                return True
        elif path == p:
            return True
    return False


async def fetch(url: str, authz: str) -> httpx.Response:
    """Forward the GET to backend-v2. Module-level so tests can stub the network."""
    async with httpx.AsyncClient(timeout=_PROXY_TIMEOUT_S) as client:
        return await client.get(url, headers={"Authorization": authz})


class StranglerProxyMiddleware(BaseHTTPMiddleware):
    """Forwards allowlisted bearer GETs to backend-v2; serves everything else
    locally. Runs outside Session/Auth so a proxied request skips them entirely."""

    async def dispatch(self, request: Request, call_next):
        proxied = await self._maybe_proxy(request)
        return proxied if proxied is not None else await call_next(request)

    async def _maybe_proxy(self, request: Request) -> Response | None:
        if request.method != "GET":
            return None
        s = get_settings()
        base = s.export_proxy_url.rstrip("/")
        if not base or not path_matches(request.url.path, s.proxy_path_list):
            return None
        authz = request.headers.get("authorization", "")
        if not authz.lower().startswith("bearer "):
            return None  # web/session traffic stays local until Phase 7
        url = base + request.url.path
        if request.url.query:
            url += "?" + request.url.query
        try:
            up = await fetch(url, authz)
        except httpx.HTTPError as e:
            log.warning("Proxy unreachable (%s) — serving locally: %s", url, e)
            return None
        log.info("Proxied to backend-v2: %s -> %d", request.url.path, up.status_code)
        headers = {k: v for k, v in up.headers.items() if k.lower() not in _DROP_HEADERS}
        return Response(content=up.content, status_code=up.status_code, headers=headers)
