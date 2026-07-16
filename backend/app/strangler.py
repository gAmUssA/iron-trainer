"""Strangler proxy: forward configured GET paths to backend-v2, in one place.

The Quarkus migration ports one vertical at a time. This middleware is the
single seam that decides which request paths backend-v2 now owns — replacing the
per-handler `_maybe_proxy` calls that used to live in the export router.

A request is proxied only when ALL of these hold:
  * method is GET (writes never proxy),
  * EXPORT_PROXY_URL is set (the flip is on; empty = instant local rollback),
  * the path matches PROXY_PATHS (the allowlist of what backend-v2 implements),
  * the caller authenticated with a bearer token (backend-v2 re-authenticates it).

Everything else — unmatched paths, non-GET, session-cookie (web) traffic, an
unreachable backend, OR a 5xx from backend-v2 — is served locally. Cookie traffic
stays local until Phase 7 regardless. The guarantee: a backend-v2 failure (down,
timeout, or 5xx) can never break a client that worked yesterday. 4xx responses
ARE forwarded — those are legitimate, parity-matched outcomes (404/400/401), not
malfunctions.

Implemented as a pure ASGI middleware (not BaseHTTPMiddleware) so that the vast
majority of traffic — everything not proxied, including the SPA served by
StaticFiles and any StreamingResponse — passes straight through untouched, with
no response buffering.
"""

from __future__ import annotations

import httpx
from starlette.requests import Request
from starlette.responses import Response
from starlette.types import ASGIApp, Receive, Scope, Send

from .config import get_settings
from .logging_config import get_logger

log = get_logger("strangler")

_PROXY_TIMEOUT_S = 30.0
# RFC 7230 hop-by-hop headers, plus content-length (recomputed) and
# content-encoding (httpx transparently decompresses, so forwarding the original
# encoding header would mislabel an already-decoded body).
_DROP_HEADERS = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "transfer-encoding", "upgrade",
    "content-length", "content-encoding",
}

# One shared client (keep-alive pool) for all proxied requests, created lazily so
# tests that stub `fetch` never open a socket. Closed on app shutdown via
# aclose_client() (wired in main.lifespan).
_client: httpx.AsyncClient | None = None


def _get_client() -> httpx.AsyncClient:
    global _client
    if _client is None:
        _client = httpx.AsyncClient(timeout=_PROXY_TIMEOUT_S)
    return _client


async def aclose_client() -> None:
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None


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
    return await _get_client().get(url, headers={"Authorization": authz})


class StranglerProxyMiddleware:
    """Forwards allowlisted bearer GETs to backend-v2; serves everything else
    locally, untouched. Pure ASGI so pass-through traffic isn't buffered."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        response = await self._proxied_response(scope, receive)
        if response is None:
            await self.app(scope, receive, send)
        else:
            await response(scope, receive, send)

    async def _proxied_response(self, scope: Scope, receive: Receive) -> Response | None:
        """The proxied Response, or None to serve locally. Reads only request
        metadata (never the body), so the untouched `receive` is safe to hand to
        the downstream app on the local path."""
        if scope["type"] != "http" or scope.get("method") != "GET":
            return None
        s = get_settings()
        base = s.export_proxy_url.rstrip("/")
        if not base:
            return None
        request = Request(scope, receive)
        if not path_matches(request.url.path, s.proxy_path_list):
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
        if up.status_code >= 500:
            # A backend-v2 malfunction must not break a client that worked
            # yesterday — fall back to the local handler. (4xx pass through.)
            log.warning("Proxy %s returned %d — serving locally", url, up.status_code)
            return None
        log.info("Proxied to backend-v2: %s -> %d", request.url.path, up.status_code)
        # Rebuild headers with .append so multi-valued headers (e.g. Set-Cookie)
        # survive — a dict comprehension would collapse them to the last value.
        # Response() already set a correct content-length from the body.
        response = Response(content=up.content, status_code=up.status_code)
        for key, value in up.headers.multi_items():
            if key.lower() not in _DROP_HEADERS:
                response.headers.append(key, value)
        return response
