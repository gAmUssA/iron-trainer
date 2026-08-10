import { useCallback, useEffect, useState } from "react";
import { timeAgo } from "../api";
import { MiniSpark } from "../components/Dashboards";
import {
  adminApi,
  AdminUnauthorized,
  type AdminJob,
  type AdminJobsPage,
  type AdminUser,
  type AdminJobHealth,
  type AdminIngestsPage,
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

type Tab = "health" | "ingests" | "users" | "jobs";

function AdminShell({ onLogout }: { onLogout: () => void }) {
  const [tab, setTab] = useState<Tab>("health");

  async function logout() {
    await adminApi.logout();
    onLogout();
  }

  return (
    <div className="admin-wrap admin-console">
      <header className="admin-header">
        <nav className="admin-nav">
          <button className={`btn tiny ${tab === "health" ? "primary" : ""}`} onClick={() => setTab("health")}>Health</button>
          <button className={`btn tiny ${tab === "ingests" ? "primary" : ""}`} onClick={() => setTab("ingests")}>Ingests</button>
          <button className={`btn tiny ${tab === "users" ? "primary" : ""}`} onClick={() => setTab("users")}>Users</button>
          <button className={`btn tiny ${tab === "jobs" ? "primary" : ""}`} onClick={() => setTab("jobs")}>Jobs</button>
        </nav>
        <button className="btn tiny" onClick={logout}>Logout</button>
      </header>
      {tab === "health" && <HealthView onLogout={onLogout} />}
      {tab === "ingests" && <IngestsView onLogout={onLogout} />}
      {tab === "users" && <UsersView onLogout={onLogout} />}
      {tab === "jobs" && <JobsView onLogout={onLogout} />}
    </div>
  );
}

// ── Health ingests (HAE / native) ────────────────────────────────────────────

const SOURCES = ["", "hae", "native", "unknown"];

function IngestsView({ onLogout }: { onLogout: () => void }) {
  const [days, setDays] = useState(7);
  const [source, setSource] = useState("");
  const [okFilter, setOkFilter] = useState("");
  const [offset, setOffset] = useState(0);
  const [page, setPage] = useState<AdminIngestsPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);
  const limit = 50;

  useEffect(() => {
    let cancelled = false;
    setError(null);
    adminApi.ingests({ days, source, ok: okFilter, limit, offset })
      .then((p) => { if (!cancelled) setPage(p); })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof AdminUnauthorized) onLogout();
        else setError(String(e));
      });
    return () => { cancelled = true; };
  }, [days, source, okFilter, offset, nonce, onLogout]);

  const rows = page?.ingests ?? [];
  const total = page?.total ?? 0;
  const attention = (page?.last_by_source ?? []).filter((l) => ingestStatus(l).tone !== "succeeded").length;

  return (
    <>
      <div className="admin-filters">
        <span className="muted small">Window:</span>
        {WINDOWS.map((w) => (
          <button key={w} className={`btn tiny ${days === w ? "primary" : ""}`} onClick={() => { setOffset(0); setDays(w); }}>{w}d</button>
        ))}
        <select aria-label="Filter by source" value={source} onChange={(e) => { setOffset(0); setSource(e.target.value); }}>
          {SOURCES.map((s) => <option key={s} value={s}>{s || "all sources"}</option>)}
        </select>
        <select aria-label="Filter by result" value={okFilter} onChange={(e) => { setOffset(0); setOkFilter(e.target.value); }}>
          <option value="">ok + failed</option>
          <option value="true">ok only</option>
          <option value="false">failed only</option>
        </select>
        <button className="btn tiny" onClick={() => setNonce((n) => n + 1)}>Refresh</button>
      </div>

      {error && <div className="card error" role="alert">{error}</div>}

      <h3 className="admin-subhead">
        Last ingest per client
        {attention > 0 && <span className="pill pill-failed" style={{ marginLeft: 8 }}>{attention} {attention === 1 ? "needs" : "need"} attention</span>}
      </h3>
      <table className="admin-table">
        <thead>
          <tr><th>source</th><th>athlete</th><th>last seen</th><th>status</th><th>days</th><th>records</th></tr>
        </thead>
        <tbody>
          {(page?.last_by_source ?? []).map((l) => {
            const st = ingestStatus(l);
            return (
              <tr key={l.id}>
                <td>{l.source}</td>
                <td>{l.athlete_id ?? "—"}</td>
                <td className="mono" title={fmt(l.received_at)}>{timeAgo(l.received_at) ?? "—"}</td>
                <td><span className={`pill pill-${st.tone}`}>{st.label}</span></td>
                <td className="mono">{l.days_stored ?? "—"}</td>
                <td className="mono">{l.records ?? "—"}</td>
              </tr>
            );
          })}
          {page && page.last_by_source.length === 0 && <tr><td colSpan={6} className="muted">No ingests recorded yet.</td></tr>}
        </tbody>
      </table>

      <h3 className="admin-subhead">Recent ingests</h3>
      <table className="admin-table">
        <thead>
          <tr><th>id</th><th>source</th><th>athlete</th><th>when</th><th>ok</th><th>days</th><th>records</th><th>unknown</th><th>bad dates</th><th>error</th></tr>
        </thead>
        <tbody>
          {rows.map((l) => (
            <tr key={l.id}>
              <td>{l.id}</td>
              <td>{l.source}</td>
              <td>{l.athlete_id ?? "—"}</td>
              <td className="mono">{fmt(l.received_at)}</td>
              <td>{l.ok ? <span className="pill pill-succeeded">ok</span> : <span className="pill pill-failed">fail</span>}</td>
              <td className="mono">{l.days_stored ?? "—"}</td>
              <td className="mono">{l.records ?? "—"}</td>
              <td className="mono">{(l.unknown_metrics ?? 0) > 0 ? <span className="pill pill-running">{l.unknown_metrics}</span> : "0"}</td>
              <td className="mono">{(l.bad_dates ?? 0) > 0 ? <span className="pill pill-failed">{l.bad_dates}</span> : "0"}</td>
              <td className="err" title={l.error ?? ""}>{l.error ? l.error.slice(0, 80) : ""}</td>
            </tr>
          ))}
          {rows.length === 0 && page && <tr><td colSpan={10} className="muted">No ingests match.</td></tr>}
          {!page && !error && <tr><td colSpan={10} className="muted">Loading…</td></tr>}
        </tbody>
      </table>

      <div className="admin-pager">
        <span className="muted small">{total === 0 ? 0 : offset + 1}–{Math.min(offset + limit, total)} of {total}</span>
        <button className="btn tiny" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - limit))}>Prev</button>
        <button className="btn tiny" disabled={offset + limit >= total} onClick={() => setOffset(offset + limit)}>Next</button>
      </div>
    </>
  );
}

// ── Sync health ───────────────────────────────────────────────────────────────

const WINDOWS = [1, 7, 30];

function HealthView({ onLogout }: { onLogout: () => void }) {
  const [days, setDays] = useState(7);
  const [data, setData] = useState<AdminJobHealth | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0); // bump to force a refresh

  // `cancelled` guards against a slower earlier request (e.g. a 1d fetch during
  // a rapid switch to 30d) overwriting the newer window's data or firing logout.
  useEffect(() => {
    let cancelled = false;
    setError(null);
    adminApi.jobHealth(days)
      .then((d) => { if (!cancelled) setData(d); })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof AdminUnauthorized) onLogout();
        else setError(String(e));
      });
    return () => { cancelled = true; };
  }, [days, nonce, onLogout]);

  return (
    <>
      <div className="admin-filters">
        <span className="muted small">Window:</span>
        {WINDOWS.map((w) => (
          <button key={w} className={`btn tiny ${days === w ? "primary" : ""}`} onClick={() => setDays(w)}>{w}d</button>
        ))}
        <button className="btn tiny" onClick={() => setNonce((n) => n + 1)}>Refresh</button>
      </div>

      {error && <div className="card error" role="alert">{error}</div>}

      <h3 className="admin-subhead">Per-kind failure rate</h3>
      <table className="admin-table">
        <thead>
          <tr><th>kind</th><th>failure rate</th><th>total</th><th>ok</th><th>failed</th><th>running</th><th>queued</th><th>p50</th><th>p95</th></tr>
        </thead>
        <tbody>
          {(data?.kinds ?? []).map((k) => (
            <tr key={k.kind}>
              <td>{k.kind}</td>
              <td><FailureBar rate={k.failure_rate} /></td>
              <td className="mono">{k.total}</td>
              <td className="mono">{k.succeeded}</td>
              <td className="mono">{k.failed > 0 ? <span className="pill pill-failed">{k.failed}</span> : "0"}</td>
              <td className="mono">{k.running}</td>
              <td className="mono">{k.queued}</td>
              <td className="mono" title={k.timed ? `over ${k.timed} run(s)` : "no timed runs"}>{fmtMs(k.p50_ms)}</td>
              <td className="mono" title={k.timed ? `over ${k.timed} run(s)` : "no timed runs"}>{fmtMs(k.p95_ms)}</td>
            </tr>
          ))}
          {data && data.kinds.length === 0 && <tr><td colSpan={9} className="muted">No jobs in this window.</td></tr>}
          {!data && !error && <tr><td colSpan={9} className="muted">Loading…</td></tr>}
        </tbody>
      </table>

      {(data?.daily?.length ?? 0) > 0 && (
        <>
          <h3 className="admin-subhead">Daily failure trend</h3>
          <div className="card">
            <MiniSpark
              title="Failure rate"
              unit="%"
              color="#ef4444"
              data={(data?.daily ?? []).map((d) => ({ x: d.date, v: Math.round(d.failure_rate * 1000) / 10 }))}
            />
          </div>
        </>
      )}

      <h3 className="admin-subhead">Recent failures</h3>
      <table className="admin-table">
        <thead>
          <tr><th>id</th><th>kind</th><th>athlete</th><th>when</th><th>error</th></tr>
        </thead>
        <tbody>
          {(data?.recent_failures ?? []).map((f) => (
            <tr key={f.id}>
              <td>{f.id}</td>
              <td>{f.kind}</td>
              <td>{f.athlete_id ?? "—"}</td>
              <td className="mono">{fmt(f.created_at)}</td>
              <td className="err" title={f.error ?? ""}>{f.error ? f.error.slice(0, 120) : ""}</td>
            </tr>
          ))}
          {data && data.recent_failures.length === 0 && <tr><td colSpan={5} className="muted">No failures 🎉</td></tr>}
        </tbody>
      </table>
    </>
  );
}

/** A tiny inline bar — fill width = failure rate, colored by severity; the
 * percentage sits beside the bar (not overlaid) so it stays readable. */
function FailureBar({ rate }: { rate: number }) {
  const pct = Math.round(rate * 100);
  const tone = pct >= 25 ? "bad" : pct >= 5 ? "warn" : "ok";
  return (
    <span className="failbar-wrap" aria-label={`${pct}% failing`}>
      <span className="failbar" aria-hidden="true">
        <span className={`failbar-fill failbar-${tone}`} style={{ width: `${pct}%` }} />
      </span>
      <span className="failbar-label mono">{pct}%</span>
    </span>
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

/** Milliseconds → compact human duration (job p50/p95). Round to whole seconds
 * before splitting so boundaries don't render "1m60s" / "60.0s". */
function fmtMs(ms: number | null): string {
  if (ms == null) return "—";
  if (ms < 1000) return `${ms}ms`;
  const totalSec = Math.round(ms / 1000);
  if (totalSec < 60) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(totalSec / 60)}m${totalSec % 60}s`;
}

// Silent-sync / stale-ingest detection (bean vcf4): a client is "stale" if its
// last ingest was over STALE_DAYS ago, "failing" if that last ingest errored.
// Relative-time display reuses timeAgo() from ../api; only the numeric threshold
// lives here.
const STALE_DAYS = 3;

function ageDays(ts: string | null): number | null {
  if (!ts) return null;
  const ms = Date.now() - Date.parse(ts);
  return Number.isNaN(ms) ? null : ms / 86_400_000;
}

function ingestStatus(l: { ok: boolean; received_at: string | null }): { tone: string; label: string } {
  if (!l.ok) return { tone: "failed", label: "failing" };
  const d = ageDays(l.received_at);
  if (d != null && d > STALE_DAYS) return { tone: "running", label: `stale ${Math.floor(d)}d` };
  return { tone: "succeeded", label: "ok" };
}

function duration(start: string | null, end: string | null): string {
  if (!start || !end) return "—";
  const ms = Date.parse(end) - Date.parse(start);
  if (Number.isNaN(ms) || ms < 0) return "—";
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}
