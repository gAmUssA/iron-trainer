import { useCallback, useEffect, useState } from "react";
import { adminApi, AdminUnauthorized, type AdminJob, type AdminJobsPage } from "./adminApi";

/** Password-gated ops console (bean gfb3): inspect background jobs across all
 * athletes to debug Strava / Apple Health / dedup / check-in sync failures. */
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
  return <AdminConsole onLogout={() => setAuthed(false)} />;
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

const STATUSES = ["", "queued", "running", "succeeded", "failed"];

function AdminConsole({ onLogout }: { onLogout: () => void }) {
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

  async function logout() {
    await adminApi.logout();
    onLogout();
  }

  const jobs = page?.jobs ?? [];
  const total = page?.total ?? 0;

  return (
    <div className="admin-wrap admin-console">
      <header className="admin-header">
        <h2>Admin · Jobs</h2>
        <button className="btn tiny" onClick={logout}>Logout</button>
      </header>

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
              <td>{j.id}</td>
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

      {selected && <JobDetail id={selected.id} onClose={() => setSelected(null)} />}
    </div>
  );
}

function JobDetail({ id, onClose }: { id: number; onClose: () => void }) {
  const [detail, setDetail] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setDetail(null);
    adminApi.job(id).then(setDetail).catch((e) => setError(String(e)));
  }, [id]);

  return (
    <div className="admin-drawer">
      <div className="admin-drawer-head">
        <strong>Job {id}</strong>
        <button className="btn tiny" onClick={onClose}>Close</button>
      </div>
      {error && <div className="card error">{error}</div>}
      {!detail && !error && <p className="muted">Loading…</p>}
      {detail && <pre className="admin-json">{JSON.stringify(detail, null, 2)}</pre>}
    </div>
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
