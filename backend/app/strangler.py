"""Strangler proxy: forward configured paths to backend-v2, in one place.

The Quarkus migration ports one vertical at a time. This middleware is the
single seam that decides which request paths backend-v2 now owns — replacing the
per-handler `_maybe_proxy` calls that used to live in the export router.

Two independent allowlists:
  * READS (GET) proxy when the path matches PROXY_PATHS and the caller sent a
    bearer token. Web/session GETs stay local.
  * WRITES (POST/PUT/DELETE/PATCH) proxy when the path matches PROXY_WRITE_PATHS
    and the caller sent a bearer token OR a session cookie (backend-v2 verifies
    the cookie — ADR 0022). Both are empty-guarded: a request proxies only when
    EXPORT_PROXY_URL is set (the flip is on; empty = instant local rollback).

Fallback differs by method, because writes are not idempotent:
  * READ: an unreachable backend OR a 5xx is served locally — a backend-v2
    failure can never break a client that worked yesterday.
  * WRITE: only an UNREACHABLE backend (connect error — the request was never
    delivered) falls back locally. A 5xx (or any post-send failure) is NOT
    retried locally: backend-v2 may have already committed, and a local retry
    would double-apply the mutation. The 5xx is forwarded to the client as-is.
In both cases 4xx responses are forwarded — those are legitimate,
parity-matched outcomes (404/400/401/422), not malfunctions.

Implemented as a pure ASGI middleware (not BaseHTTPMiddleware) so that the vast
majority of traffic — everything not proxied, including the SPA served by
StaticFiles and any StreamingResponse — passes straight through untouched, with
no response buffering. For a proxied write the request body is buffered (so it
can be forwarded, or replayed to the local app on an unreachable-backend
fallback); read bodies are never touched.
"""

from __future__ import annotations

import json

import httpx
from starlette.requests import Request
from starlette.responses import Response
from starlette.types import ASGIApp, Receive, Scope, Send

from .config import get_settings
from .logging_config import get_logger

log = get_logger("strangler")

_PROXY_TIMEOUT_S = 30.0
_WRITE_METHODS = frozenset({"POST", "PUT", "DELETE", "PATCH"})
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


async def fetch_write(
    url: str, method: str, body: bytes, headers: dict[str, str]
) -> httpx.Response:
    """Forward a write (POST/PUT/DELETE/PATCH) to backend-v2. Module-level so
    tests can stub the network."""
    return await _get_client().request(method, url, content=body, headers=headers)


async def _read_body(receive: Receive) -> bytes:
    """Drain the ASGI request body into a single bytes buffer."""
    chunks: list[bytes] = []
    while True:
        msg = await receive()
        if msg["type"] == "http.request":
            chunks.append(msg.get("body", b""))
            if not msg.get("more_body", False):
                break
        elif msg["type"] == "http.disconnect":
            break
    return b"".join(chunks)


def _replay_receive(body: bytes) -> Receive:
    """A one-shot ASGI receive that replays a buffered body, so the local app can
    serve a request whose body we already drained."""
    sent = False

    async def receive() -> dict:
        nonlocal sent
        if not sent:
            sent = True
            return {"type": "http.request", "body": body, "more_body": False}
        return {"type": "http.disconnect"}

    return receive


def _build_response(up: httpx.Response) -> Response:
    # Rebuild headers with .append so multi-valued headers (e.g. Set-Cookie)
    # survive — a dict comprehension would collapse them to the last value.
    # Response() already set a correct content-length from the body.
    response = Response(content=up.content, status_code=up.status_code)
    for key, value in up.headers.multi_items():
        if key.lower() not in _DROP_HEADERS:
            response.headers.append(key, value)
    return response


def _error_response(status: int, detail: str) -> Response:
    return Response(
        content=json.dumps({"detail": detail}).encode(),
        status_code=status,
        media_type="application/json",
    )


def _target(base: str, request: Request) -> str:
    url = base + request.url.path
    if request.url.query:
        url += "?" + request.url.query
    return url


class StranglerProxyMiddleware:
    """Forwards allowlisted reads (bearer GET) and writes (bearer/cookie
    POST/PUT/DELETE/PATCH) to backend-v2; serves everything else locally,
    untouched. Pure ASGI so pass-through traffic isn't buffered."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        response, local_receive = await self._handle(scope, receive)
        if response is not None:
            await response(scope, receive, send)
        else:
            # local_receive replays a drained write body; None = body untouched.
            await self.app(scope, local_receive or receive, send)

    async def _handle(
        self, scope: Scope, receive: Receive
    ) -> tuple[Response | None, Receive | None]:
        """Returns (proxied_response, local_receive). A non-None response is sent
        to the client; otherwise the request is served locally with local_receive
        (or the original receive when that is None)."""
        if scope["type"] != "http":
            return None, None
        method = scope.get("method")
        base = get_settings().export_proxy_url.rstrip("/")
        if not base:
            return None, None
        request = Request(scope, receive)
        if method == "GET":
            return await self._proxy_read(request, base), None
        if method in _WRITE_METHODS:
            return await self._proxy_write(request, base, method, receive)
        return None, None

    async def _proxy_read(self, request: Request, base: str) -> Response | None:
        """A GET is proxied on the read allowlist for bearer callers; an
        unreachable backend OR a 5xx falls back locally (reads are idempotent).
        Reads only request metadata — never the body."""
        s = get_settings()
        if not path_matches(request.url.path, s.proxy_path_list):
            return None
        authz = request.headers.get("authorization", "")
        if not authz.lower().startswith("bearer "):
            return None  # web/session reads stay local
        url = _target(base, request)
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
        return _build_response(up)

    async def _proxy_write(
        self, request: Request, base: str, method: str, receive: Receive
    ) -> tuple[Response | None, Receive | None]:
        """A write is proxied on the write allowlist for bearer OR session-cookie
        callers. Only an unreachable backend (connect error, never delivered)
        falls back locally — a 5xx is forwarded, never retried, to avoid
        double-applying a non-idempotent mutation."""
        s = get_settings()
        if not path_matches(request.url.path, s.proxy_write_path_list):
            return None, None  # not flipped → local, body untouched
        authz = request.headers.get("authorization", "")
        cookie = request.headers.get("cookie", "")
        has_bearer = authz.lower().startswith("bearer ")
        has_session = "session=" in cookie
        if not (has_bearer or has_session):
            return None, None  # unauthenticated here → local, body untouched
        # Committed to proxying: from here the body must be read to forward it.
        body = await _read_body(receive)
        headers: dict[str, str] = {}
        if has_bearer:
            headers["Authorization"] = authz
        if cookie:
            headers["Cookie"] = cookie
        ct = request.headers.get("content-type")
        if ct:
            headers["Content-Type"] = ct
        url = _target(base, request)
        try:
            up = await fetch_write(url, method, body, headers)
        except (httpx.ConnectError, httpx.ConnectTimeout) as e:
            # Never delivered → safe to serve locally, replaying the body.
            log.warning("Proxy write unreachable (%s) — serving locally: %s", url, e)
            return None, _replay_receive(body)
        except httpx.HTTPError as e:
            # Delivered; outcome unknown. Retrying a non-idempotent write locally
            # could double-apply it, so surface an error instead of falling back.
            log.error(
                "Proxy write %s %s failed after send (%s) — NOT retrying locally",
                method, url, e)
            return _error_response(502, "backend-v2 write failed"), None
        # Any HTTP response (incl 5xx) is forwarded as-is: no local fallback for
        # writes (a 5xx may follow a partial commit; a local retry double-applies).
        log.info("Proxied WRITE to backend-v2: %s %s -> %d",
                 method, request.url.path, up.status_code)
        return _build_response(up), None
