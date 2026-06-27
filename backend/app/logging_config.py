"""Lightweight logging setup for the app.

We log under the ``iron`` namespace and route it through uvicorn's handlers when
they exist (so app logs share the server's format/stream), or fall back to a basic
config when running standalone (tests, scripts, CLI). Level comes from LOG_LEVEL.
"""

from __future__ import annotations

import logging

from .config import get_settings

_configured = False


def setup_logging() -> None:
    """Configure the ``iron`` logger once. Safe to call repeatedly."""
    global _configured
    if _configured:
        return
    level = getattr(logging, get_settings().log_level.upper(), logging.INFO)

    logger = logging.getLogger("iron")
    logger.setLevel(level)
    # Reuse uvicorn's handler so app logs match the server's output; otherwise
    # attach our own so standalone runs still print.
    uvicorn = logging.getLogger("uvicorn")
    if uvicorn.handlers:
        logger.handlers = uvicorn.handlers
        logger.propagate = False
    elif not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
        logger.addHandler(handler)
        logger.propagate = False
    _configured = True


def get_logger(name: str) -> logging.Logger:
    """A child of the ``iron`` logger, e.g. get_logger("strava") -> iron.strava."""
    setup_logging()
    return logging.getLogger(f"iron.{name}")
