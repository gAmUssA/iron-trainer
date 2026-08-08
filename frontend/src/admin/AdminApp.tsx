import { useCallback, useEffect, useState } from "react";
import {
  adminApi,
  AdminUnauthorized,
  type AdminJob,
  type AdminJobsPage,
  type AdminUser,
} from "./adminApi";

/** Password-gated ops console (admin epic 18n4): inspect users (bean y8b2) and
 * background jobs (bean gfb3) across all athletes to debug sync failures. */
export function AdminApp() {
  const [authed, setAuthed] = useState<boolean | null>(null); // null = checking

  // Probe the session on mount: a 200 from /jobs means we're already logged in.
  useEffect(() => {
    adminApi.jobs({ limit: 1 })
      .then(() => setAuthed(true))
      // Only a confirmed 200 means authed. 401 → login; any other error (500/network)
      // → also fall back to login rather than showing a broken console shell.
      .catch(() => setAuthed(false));
  }, []);

  if (authed === null) return <div className="admin-wrap"><p className="muted">Loading…</p></div>;
  if (!authed) return <AdminLogin onSuccess={() => setAuthed(true)} />;
  return <AdminShell onLogout={() => setAuthed(false)} />;
}

function AdminLogin({ onSuccess }: { onSuccess: () => void }) {
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const status = await adminApi.login(password);
      if (status === 200) onSuccess();
      else if (status === 503) setError("Admin console isn't configured (ADMIN_PASSWORD not set).");
      else setError("Incorrect password.");
    } catch {
      setError("Login failed — couldn't reach the server.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="admin-wrap">
      <form className="card admin-login" onSubmit={submit}>
        <h2>Admin</h2>
        <p className="muted small">Ops console — password required.</p>
        <input
          type="password"
          value={password}
          autoFocus
          placeholder="Admin password"
          onChange={(e) => setPassword(e.target.value)}
        />
        {error && <div className="card error" role="alert">{error}</div>}
        <button className="btn primary" disabled={busy || !password}>{busy ? "…" : "Sign in"}</button>
      </form>
    </div>
  );
}

type Tab = "users" | "jobs";

function AdminShell({ onLogout }: { onLogout: () => void }) {
  const [tab, setTab] = useState<Tab>("users");

  async function logout() {
    await adminApi.logout();
    onLogout();
  }

  return (
    <div className="admin-wrap admin-console">
      <header className="admin-header">
        <nav className="admin-nav">
          <button className={`btn tiny ${tab === "users" ? "primary" : ""}`} onClick={() => setTab("users")}>Users</button>
          <button className={`btn tiny ${tab === "jobs" ? "primary" : ""}`} onClick={() => setTab("jobs")}>Jobs</button>
        </nav>
        <button className="btn tiny" onClick={logout}>Logout</button>
      </header>
      {tab === "users" ? <UsersView onLogout={onLogout} /> : <JobsView onLogout={onLogout} />}
    </div>
  );
}

// ── Users ───────────────────────────────────────────────────────────────────

function UsersView({ onLogout }: { onLogout: () => void }) {
  const [users, setUsers] = useState<AdminUser[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<number | null>(null);

  const load = useCallback(() => {
    setError(null);
    adminApi.users()
      .then((r) => setUsers(r.users))
      .catch((e) => { if (e instanceof AdminUnauthorized) onLogout(); else setError(String(e)); });
  }, [onLogout]);

  useEffect(() => { load(); }, [load]);

  return (
    <>
      {error && <div className="card error" role="alert">{error}</div>}
      <table className="admin-table">
        <thead>
          <tr><th>id</th><th>name</th><th>strava</th><th>apple</th><th>activities</th><th>jobs</th><th>failed</th></tr>
        </thead>
        <tbody>
          {(users ?? []).map((u) => (
            <tr key={u.id} className={selected === u.id ? "sel" : ""} onClick={() => setSelected(u.id)}>
              <td><button className="admin-rowbtn" onClick={() => setSelected(u.id)}>{u.id}</button></td>
              <td>{u.name || <span className="muted">—</span>}</td>
              <td>{u.connected
                ? <span className="pill pill-succeeded">connected</span>
                : <span className="muted">—</span>}</td>
              <td>{u.apple_linked
                ? <span className="pill pill-running">linked</span>
                : <span className="muted">—</span>}</td>
              <td className="mono">{u.activities}</td>
              <td className="mono">{u.jobs}</td>
              <td className="mono">{u.failed_jobs > 0
                ? <span className="pill pill-failed">{u.failed_jobs}</span>
                : "0"}</td>
            </tr>
          ))}
          {users && users.length === 0 && <tr><td colSpan={7} className="muted">No users.</td></tr>}
          {!users && !error && <tr><td colSpan={7} className="muted">Loading…</td></tr>}
        </tbody>
      </table>

      {selected != null && <DetailDrawer id={selected} title={`User ${selected}`} load={adminApi.user} onClose={() => setSelected(null)} onUnauthorized={onLogout} />}
    </>
  );
}

/** Shared JSON detail drawer (users + jobs). `load` / `onUnauthorized` are stable
 * adminApi/handler references, so keying the fetch on them never loops. */
function DetailDrawer({ id, title, load, onClose, onUnauthorized }: {
  id: number;
  title: string;
  load: (id: number) => Promise<Record<string, unknown>>;
  onClose: () => void;
  onUnauthorized: () => void;
}) {
  const [detail, setDetail] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // `cancelled` guards against a slower earlier request overwriting a newer
    // selection's detail (or firing logout/error after the drawer moved on).
    let cancelled = false;
    setDetail(null);
    setError(null);
    load(id)
      .then((d) => { if (!cancelled) setDetail(d); })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof AdminUnauthorized) onUnauthorized();
        else setError(String(e));
      });
    return () => { cancelled = true; };
  }, [id, load, onUnauthorized]);

  return (
    <div className="admin-drawer">
      <div className="admin-drawer-head">
        <strong>{title}</strong>
        <button className="btn tiny" onClick={onClose}>Close</button>
      </div>
      {error && <div className="card error">{error}</div>}
      {!detail && !error && <p className="muted">Loading…</p>}
      {detail && <pre className="admin-json">{JSON.stringify(detail, null, 2)}</pre>}
    </div>
  );
}

// ── Jobs ────────────────────────────────────────────────────────────────────

const STATUSES = ["", "queued", "running", "succeeded", "failed"];

function JobsView({ onLogout }: { onLogout: () => void }) {
  const [kind, setKind] = useState("");
  const [status, setStatus] = useState("");
  const [athleteId, setAthleteId] = useState("");
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<AdminJobsPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<AdminJob | null>(null);
  const limit = 50;

  const load = useCallback(() => {
    setError(null);
    adminApi.jobs({ kind, status, athlete_id: athleteId, limit, offset })
      .then(setPage)
      .catch((e) => {
        if (e instanceof AdminUnauthorized) onLogout();
        else setError(String(e));
      });
  }, [kind, status, athleteId, offset, onLogout]);

  useEffect(() => { load(); }, [load]);

  const jobs = page?.jobs ?? [];
  const total = page?.total ?? 0;

  return (
    <>
      <div className="admin-filters">
        <input placeholder="kind (e.g. strava_sync)" value={kind}
               onChange={(e) => { setOffset(0); setKind(e.target.value.trim()); }} />
        <select value={status} onChange={(e) => { setOffset(0); setStatus(e.target.value); }}>
          {STATUSES.map((s) => <option key={s} value={s}>{s || "all statuses"}</option>)}
        </select>
        <input placeholder="athlete id" value={athleteId} inputMode="numeric"
               onChange={(e) => { setOffset(0); setAthleteId(e.target.value.trim()); }} />
        <button className="btn tiny" onClick={load}>Refresh</button>
      </div>

      {error && <div className="card error" role="alert">{error}</div>}

      <table className="admin-table">
        <thead>
          <tr><th>id</th><th>athlete</th><th>kind</th><th>status</th><th>created</th><th>duration</th><th>error</th></tr>
        </thead>
        <tbody>
          {jobs.map((j) => (
            <tr key={j.id} className={selected?.id === j.id ? "sel" : ""} onClick={() => setSelected(j)}>
              <td><button className="admin-rowbtn" onClick={() => setSelected(j)}>{j.id}</button></td>
              <td>{j.athlete_id ?? "—"}</td>
              <td>{j.kind}</td>
              <td><span className={`pill pill-${j.status}`}>{j.status}</span></td>
              <td className="mono">{fmt(j.created_at)}</td>
              <td className="mono">{duration(j.started_at, j.finished_at)}</td>
              <td className="err">{j.error ? j.error.slice(0, 80) : ""}</td>
            </tr>
          ))}
          {jobs.length === 0 && <tr><td colSpan={7} className="muted">No jobs match.</td></tr>}
        </tbody>
      </table>

      <div className="admin-pager">
        <span className="muted small">{offset + 1}–{Math.min(offset + limit, total)} of {total}</span>
        <button className="btn tiny" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - limit))}>Prev</button>
        <button className="btn tiny" disabled={offset + limit >= total} onClick={() => setOffset(offset + limit)}>Next</button>
      </div>

      {selected && <DetailDrawer id={selected.id} title={`Job ${selected.id}`} load={adminApi.job} onClose={() => setSelected(null)} onUnauthorized={onLogout} />}
    </>
  );
}

function fmt(ts: string | null): string {
  if (!ts) return "—";
  return ts.replace("T", " ").slice(0, 19);
}

function duration(start: string | null, end: string | null): string {
  if (!start || !end) return "—";
  const ms = Date.parse(end) - Date.parse(start);
  if (Number.isNaN(ms) || ms < 0) return "—";
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}
