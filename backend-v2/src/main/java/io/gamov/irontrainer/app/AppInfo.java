package io.gamov.irontrainer.app;

/** App-wide constants. */
public final class AppInfo {

    /** Matches FastAPI app.__version__ (backend/app/__init__.py) — reported by
     * /api/health and /api/status. Single source so the two can't drift. */
    public static final String VERSION = "0.1.0";

    private AppInfo() {
    }
}
