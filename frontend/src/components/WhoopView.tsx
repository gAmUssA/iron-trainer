import { useEffect, useRef, useState, type ChangeEvent } from "react";
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  api,
  DEFAULT_CHART_DAYS,
  type PmcDay,
  type RecoveryDay,
  type WhoopDay,
  type WhoopInsightsData,
  type WhoopStatus,
} from "../api";
import { useChart } from "../chartTheme";
import { RangePicker } from "./RangePicker";

// WHOOP-page accents. WHOOP series get the green family; Apple-Health series
// reuse the Recovery tab's colors so the same signal looks the same app-wide.
const C = {
  whoop: "#22c55e",       // WHOOP recovery / brand green
  whoopAlt: "#86efac",    // WHOOP secondary (lighter green)
  appleHrv: "#2dd4bf",    // teal — matches Recovery tab HRV
  appleRhr: "#f87171",    // rose — matches Recovery tab RHR
  strain: "#a78bfa",
  tss: "#ffb454",
};

// Codes the backend callback sets on the redirect. Anything unmapped is shown
// raw rather than swallowed — an unknown code is a bug worth seeing.
const WHOOP_OAUTH_ERRORS: Record<string, string> = {
  access_denied: "you declined access at WHOOP.",
  no_code: "WHOOP did not return an authorization code.",
  invalid_state: "the security check failed. Start the connection again.",
  exchange_failed: "WHOOP rejected the authorization code.",
};

const DAY_MS = 86_400_000;
const day = (d: string) => d.slice(5); // MM-DD

/** Trailing rolling mean over the last `windowDays` CALENDAR days (same
 * semantics as RecoveryTrendsView — gaps must not stretch the window). */
function rollingMean<T extends { date: string }>(
  rows: T[],
  valueOf: (r: T) => number | null | undefined,
  windowDays: number,
): (number | null)[] {
  return rows.map((row, i) => {
    const start = Date.parse(row.date) - (windowDays - 1) * DAY_MS;
    let sum = 0;
    let n = 0;
    for (let j = i; j >= 0; j--) {
      if (Date.parse(rows[j].date) < start) break;
      const v = valueOf(rows[j]);
      if (v != null) {
        sum += v;
        n += 1;
      }
    }
    return n ? sum / n : null;
  });
}

interface MergedDay {
  date: string;
  x: string;
  rec: number | null;
  recTrend: number | null;
  wHrv: number | null;
  aHrv: number | null;
  wRhr: number | null;
  aRhr: number | null;
  strain: number | null;
  tss: number | null;
}

/** Zip the three daily series by calendar date (union of dates, oldest→newest). */
function merge(whoop: WhoopDay[], apple: RecoveryDay[], pmc: PmcDay[]): MergedDay[] {
  const w = new Map(whoop.map((r) => [r.date, r]));
  const a = new Map(apple.map((r) => [r.date, r]));
  const p = new Map(pmc.map((r) => [r.date, r]));
  const dates = [...new Set([...w.keys(), ...a.keys()])].sort();
  const base = dates.map((date) => ({ date, rec: w.get(date)?.recovery_score ?? null }));
  const recTrend = rollingMean(base, (r) => r.rec, 7);
  return dates.map((date, i) => ({
    date,
    x: day(date),
    rec: w.get(date)?.recovery_score ?? null,
    recTrend: recTrend[i],
    wHrv: w.get(date)?.hrv_rmssd_ms ?? null,
    aHrv: a.get(date)?.hrv_ms ?? null,
    wRhr: w.get(date)?.rhr_bpm ?? null,
    aRhr: a.get(date)?.rhr_bpm ?? null,
    strain: w.get(date)?.day_strain ?? null,
    tss: p.get(date)?.tss ?? null,
  }));
}

export function WhoopView() {
  const ch = useChart();
  const [days, setDays] = useState(DEFAULT_CHART_DAYS);
  const [whoop, setWhoop] = useState<WhoopDay[]>([]);
  const [apple, setApple] = useState<RecoveryDay[]>([]);
  const [pmc, setPmc] = useState<PmcDay[]>([]);
  const [insights, setInsights] = useState<WhoopInsightsData | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [status, setStatus] = useState<WhoopStatus | null>(null);
  const [syncing, setSyncing] = useState(false);
  const req = useRef(0);

  const loadStatus = () =>
    // Never blanks the page: a self-host install with no WHOOP credentials is a
    // supported configuration, not an error, and the ZIP path below still works.
    api.whoopStatus().then(setStatus).catch(() => setStatus(null));

  // Consume the callback's outcome, then clean the URL so a refresh does not
  // re-announce a connection that happened minutes ago.
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    const ok = q.get("whoop_connected");
    const err = q.get("whoop_error");
    // Accurate again: the callback now QUEUES the first import and redirects
    // immediately, because running it inline blew through Cloudflare's 100s edge
    // timeout and handed the athlete a 524 on a connection that had actually
    // succeeded. The poll below follows the job to completion.
    if (ok) setMsg("WHOOP connected. Importing your history in the background…");
    if (err) setMsg(`WHOOP connection failed: ${WHOOP_OAUTH_ERRORS[err] ?? err}`);
    if (ok || err) window.history.replaceState({}, "", window.location.pathname);
  }, []);

  useEffect(() => {
    loadStatus();
  }, []);

  // While a sync is in flight — the one queued at connect, or the daily job
  // landing while the page is open — keep the panel honest rather than leaving it
  // reading "No day has synced yet" until the athlete thinks to refresh. Stops as
  // soon as the job reaches a terminal state, so an idle page does no polling.
  const syncRunning =
    status?.last_sync?.status === "running" || status?.last_sync?.status === "queued";
  useEffect(() => {
    if (!syncRunning) return;
    const t = window.setInterval(() => {
      loadStatus().then(() => load(days));
    }, 5000);
    return () => window.clearInterval(t);
  }, [syncRunning]); // eslint-disable-line react-hooks/exhaustive-deps

  async function doSync(full: boolean) {
    setSyncing(true);
    setMsg(full ? "Re-walking your full WHOOP history…" : "Syncing WHOOP…");
    try {
      const { result, alreadyRunning } = await api.whoopSync(full);
      if (alreadyRunning) {
        setMsg("A sync is already running — the full re-sync was not started. "
          + "Try again once it finishes.");
      } else if (result) {
        setMsg(`Synced ${result.written} of ${result.cycles} days `
          + `(${result.skipped} already current).`);
      }
      load(days);
      loadStatus();
    } catch (err) {
      setMsg(`Sync failed: ${err}`);
      loadStatus();   // a 409 here means the token died — refresh the panel to say so
    } finally {
      setSyncing(false);
    }
  }

  async function doDisconnect() {
    if (!window.confirm(
      "Disconnect WHOOP? Daily syncing stops and reconnecting means signing in "
      + "at WHOOP again. Data already imported is kept.",
    )) return;
    setSyncing(true);
    try {
      const r = await api.whoopDisconnect();
      setMsg(r.message);
      loadStatus();
    } catch (err) {
      setMsg(`Disconnect failed: ${err}`);
    } finally {
      setSyncing(false);
    }
  }

  function load(range: number) {
    const seq = ++req.current;
    setLoading(true);
    const d = range === 0 ? 365 : range;
    Promise.all([
      api.whoopCycles(d),
      // Comparison sources are garnish — their failure must not blank the page.
      api.recovery(d).catch(() => ({ days: [] as RecoveryDay[] })),
      api.pmc(d).catch(() => ({ days: [] as PmcDay[] })),
      api.whoopInsights().catch(() => null),
    ])
      .then(([wr, ar, pr, ins]) => {
        if (seq !== req.current) return; // stale response
        // whoop/recovery arrive newest-first; pmc oldest-first. merge() sorts anyway.
        setWhoop(wr.days);
        setApple(ar.days);
        setPmc(pr.days);
        setInsights(ins);
      })
      .catch(() => {
        if (seq === req.current) setWhoop([]);
      })
      .finally(() => {
        if (seq === req.current) setLoading(false);
      });
  }

  useEffect(() => load(days), [days]); // eslint-disable-line react-hooks/exhaustive-deps

  async function doImport(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-selecting the same file
    if (!file) return;
    setBusy(true);
    setMsg("Importing WHOOP export…");
    try {
      const r = await api.importWhoop(file);
      setMsg(
        r.cycles > 0
          ? `Imported ${r.cycles} days (${r.first_date} → ${r.last_date})` +
            (r.journal_answers ? ` and ${r.journal_answers} journal answers.` : ".")
          : "The export contained no scored days.",
      );
      load(days);
    } catch (err) {
      setMsg(`Import failed: ${err}`);
    } finally {
      setBusy(false);
    }
  }

  async function doAnalyze() {
    setAnalyzing(true);
    try {
      await api.whoopAnalyze();
      // Refetch rather than merge — picks up the new analysis AND the
      // decremented runs-left counter in one go.
      setInsights(await api.whoopInsights());
    } catch (err) {
      setMsg(`Analysis failed: ${err}`);
    } finally {
      setAnalyzing(false);
    }
  }

  // Only worth showing when it FAILED or is still running — a succeeded job
  // adds nothing over "newest synced day", which the athlete actually cares about.
  const lastSync = status?.last_sync;
  const lastSyncLine =
    lastSync?.status === "failed"
      ? `Last sync failed: ${lastSync.error ?? "unknown error"}.`
      : lastSync?.status === "running" || lastSync?.status === "queued"
        ? "Importing now — a first full history sync takes a few minutes."
        : null;

  // Read from config, never assumed: WHOOP_SYNC_CRON can be retimed or switched
  // off, and promising a daily sync that a deployment has disabled is worse than
  // saying nothing. An unrecognised cron is shown verbatim rather than described.
  const scheduleLine = !status?.sync_cron
    ? "Automatic syncing is off — use Sync now."
    : status.sync_hour != null
      ? `Syncing daily at ${String(status.sync_hour).padStart(2, "0")}:00.`
      : `Automatic sync: ${status.sync_cron}.`;

  const rows = merge(whoop, apple, pmc);
  const hasWhoop = whoop.length > 0;
  const behaviors = insights?.behaviors ?? [];
  const bedtime = insights?.bedtime;
  const trend = insights?.trend_28d;

  const delta = (v: number | null | undefined, unit: string) =>
    v == null ? "—" : `${v > 0 ? "+" : ""}${v}${unit}`;
  const deltaColor = (v: number | null | undefined) =>
    v == null ? undefined : v < 0 ? C.appleRhr : C.whoop;
  const arrow = (now: number | null | undefined, prev: number | null | undefined) =>
    now == null || prev == null ? "" : now > prev ? " ↑" : now < prev ? " ↓" : " →";

  return (
    <>
      <div className="card">
        <div className="card-title">WHOOP Data</div>

        {status?.configured && (
          <div className="whoop-conn">
            {!status.connected ? (
              <>
                <div className="card-sub">
                  Connect WHOOP to sync recovery, strain and sleep automatically every
                  morning. Your history is imported once on connect.
                </div>
                <div className="btn-row">
                  <a className="btn primary" href="/api/whoop/connect">Connect WHOOP</a>
                </div>
              </>
            ) : status.reconnect_required ? (
              <>
                {/* Deliberately hedged. The flag is raised for ANY rejected refresh,
                    including a WHOOP outage or a network blip, so asserting the
                    sign-in has expired would tell people to redo a connection that
                    is fine. Retry first — a success clears the flag by itself — and
                    offer Reconnect as the fix when it does not. */}
                <div className="card-sub warn">
                  WHOOP refused the last token refresh, so syncing has stopped. This is
                  usually an expired sign-in, but a WHOOP outage looks the same — try
                  syncing first, and reconnect if that fails. Nothing already imported
                  is lost.
                </div>
                <div className="btn-row">
                  <button type="button" className="btn" disabled={syncing}
                          onClick={() => doSync(false)}>
                    {syncing ? "Retrying…" : "Retry sync"}
                  </button>
                  <a className="btn primary" href="/api/whoop/connect">Reconnect WHOOP</a>
                  <button type="button" className="btn" disabled={syncing}
                          onClick={doDisconnect}>Disconnect</button>
                </div>
              </>
            ) : (
              <>
                <div className="card-sub">
                  Connected. {scheduleLine}{" "}
                  {status.latest_api_date
                    ? `Newest synced day: ${status.latest_api_date}.`
                    : "No day has synced yet."}
                  {lastSyncLine && <> {lastSyncLine}</>}
                </div>
                <div className="btn-row">
                  <button type="button" className="btn" disabled={syncing}
                          onClick={() => doSync(false)}>
                    {syncing ? "Syncing…" : "Sync now"}
                  </button>
                  <button type="button" className="btn" disabled={syncing}
                          title="Re-walk the entire history — repairs gaps, safe to repeat"
                          onClick={() => doSync(true)}>Full re-sync</button>
                  <button type="button" className="btn" disabled={syncing}
                          onClick={doDisconnect}>Disconnect</button>
                </div>
              </>
            )}
            <div className="whoop-conn-sep" />
          </div>
        )}

        <div className="card-sub">
          {status?.connected ? "Or upload" : "Upload"} the ZIP from WHOOP&nbsp;app → App
          Settings → Data Export → Create Export (arrives by email). Re-uploading a newer
          export updates existing days.
          {status?.connected && (
            <> The export also carries journal entries, which the API cannot provide.</>
          )}
        </div>
        <div className="btn-row">
          <label className={`btn${busy ? " disabled" : ""}`} title="Upload your WHOOP data export">
            Upload WHOOP export (.zip)
            <input type="file" accept=".zip,application/zip" disabled={busy} onChange={doImport}
                   style={{ display: "none" }} />
          </label>
          <span style={{ marginLeft: "auto" }}>
            <RangePicker value={days} onChange={setDays} />
          </span>
        </div>
        {msg && <div className="hint">{msg}</div>}
      </div>

      {loading && !hasWhoop ? (
        <div className="card">
          <p className="muted">Loading WHOOP data…</p>
        </div>
      ) : !hasWhoop ? (
        <div className="card">
          <p className="muted">
            No WHOOP data yet — upload your export above to overlay WHOOP Recovery, HRV and
            Strain against your Apple&nbsp;Health metrics and Strava training load.
          </p>
        </div>
      ) : (
        <>
          {/* 1 — Recovery % */}
          <div className="card">
            <div className="card-title">WHOOP Recovery</div>
            <div className="card-sub">
              WHOOP’s proprietary readiness score (0–100%) — dashed = 7-day mean
            </div>
            <ResponsiveContainer width="100%" height={220}>
              <LineChart data={rows} margin={{ top: 12, right: 4, left: -18, bottom: 0 }}>
                <CartesianGrid stroke={ch.grid} />
                <XAxis dataKey="x" tick={ch.tick} stroke={ch.grid} minTickGap={40} />
                <YAxis tick={ch.tick} stroke={ch.grid} domain={[0, 100]} />
                <Tooltip contentStyle={ch.tooltip} />
                <Line dataKey="rec" name="Recovery %" stroke={C.whoop} strokeWidth={1.6} dot={false} connectNulls />
                <Line dataKey="recTrend" name="7d mean" stroke={C.whoop} strokeWidth={2.2} strokeDasharray="6 4" dot={false} connectNulls />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className="grid-2">
            {/* 2 — HRV overlay */}
            <div className="card">
              <div className="card-title">HRV — WHOOP vs Apple Health</div>
              <div className="card-sub">
                Different algorithms (WHOOP = RMSSD, Apple = SDNN) — compare trends, not values
              </div>
              <ResponsiveContainer width="100%" height={220}>
                <LineChart data={rows} margin={{ top: 12, right: 4, left: -18, bottom: 0 }}>
                  <CartesianGrid stroke={ch.grid} />
                  <XAxis dataKey="x" tick={ch.tick} stroke={ch.grid} minTickGap={40} />
                  <YAxis tick={ch.tick} stroke={ch.grid} domain={["auto", "auto"]} />
                  <Tooltip contentStyle={ch.tooltip} />
                  <Line dataKey="wHrv" name="WHOOP HRV (RMSSD ms)" stroke={C.whoop} strokeWidth={1.6} dot={false} connectNulls />
                  <Line dataKey="aHrv" name="Apple HRV (SDNN ms)" stroke={C.appleHrv} strokeWidth={1.6} dot={false} connectNulls />
                </LineChart>
              </ResponsiveContainer>
            </div>

            {/* 3 — RHR overlay (same unit → direct comparison) */}
            <div className="card">
              <div className="card-title">Resting HR — WHOOP vs Apple Health</div>
              <div className="card-sub">Same unit (bpm) — divergence means the devices disagree</div>
              <ResponsiveContainer width="100%" height={220}>
                <LineChart data={rows} margin={{ top: 12, right: 4, left: -18, bottom: 0 }}>
                  <CartesianGrid stroke={ch.grid} />
                  <XAxis dataKey="x" tick={ch.tick} stroke={ch.grid} minTickGap={40} />
                  <YAxis tick={ch.tick} stroke={ch.grid} domain={["auto", "auto"]} />
                  <Tooltip contentStyle={ch.tooltip} />
                  <Line dataKey="wRhr" name="WHOOP RHR" stroke={C.whoopAlt} strokeWidth={1.6} dot={false} connectNulls />
                  <Line dataKey="aRhr" name="Apple RHR" stroke={C.appleRhr} strokeWidth={1.6} dot={false} connectNulls />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* 4 — Strain vs training load */}
          <div className="card">
            <div className="card-title">WHOOP Strain vs Training Load (TSS)</div>
            <div className="card-sub">
              WHOOP Day Strain (line, 0–21, all-day cardiovascular load) vs Strava-derived TSS
              (bars, workout load) — days where they disagree show non-workout strain
            </div>
            <ResponsiveContainer width="100%" height={220}>
              <ComposedChart data={rows} margin={{ top: 12, right: 4, left: -18, bottom: 0 }}>
                <CartesianGrid stroke={ch.grid} vertical={false} />
                <XAxis dataKey="x" tick={ch.tick} stroke={ch.grid} minTickGap={40} />
                <YAxis yAxisId="strain" tick={ch.tick} stroke={ch.grid} domain={[0, 21]} />
                <YAxis yAxisId="tss" orientation="right" tick={ch.tick} stroke={ch.grid} />
                <Tooltip contentStyle={ch.tooltip} cursor={{ fill: "rgba(128,128,128,0.12)" }} />
                <Bar yAxisId="tss" dataKey="tss" name="TSS" fill={C.tss} fillOpacity={0.5} radius={[2, 2, 0, 0]} />
                <Line yAxisId="strain" dataKey="strain" name="Day Strain" stroke={C.strain} strokeWidth={1.8} dot={false} connectNulls />
              </ComposedChart>
            </ResponsiveContainer>
          </div>

          {/* 5 — Behavior impact (journal correlations, computed server-side) */}
          {behaviors.length > 0 && (
            <div className="card">
              <div className="card-title">Behavior Impact</div>
              <div className="card-sub">
                Journal answers vs same-day recovery, averaged over your whole history — observational,
                ranked by impact
              </div>
              <table className="data-table" style={{ width: "100%", fontSize: 13 }}>
                <thead>
                  <tr style={{ textAlign: "left" }}>
                    <th>Behavior</th>
                    <th>Days yes / no</th>
                    <th>Recovery yes vs no</th>
                    <th>Δ Recovery</th>
                    <th>Δ HRV</th>
                  </tr>
                </thead>
                <tbody>
                  {behaviors.map((b) => (
                    <tr key={b.question}>
                      <td>{b.question}</td>
                      <td className="muted">{b.yes_days} / {b.no_days}</td>
                      <td className="muted">{b.recovery_yes}% vs {b.recovery_no}%</td>
                      <td style={{ color: deltaColor(b.recovery_delta), fontWeight: 600 }}>
                        {delta(b.recovery_delta, " pts")}
                      </td>
                      <td style={{ color: deltaColor(b.hrv_delta) }}>{delta(b.hrv_delta, " ms")}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* 6 — Consistency & 28-day direction */}
          {(bedtime || trend) && (
            <div className="grid-2">
              {bedtime && (
                <div className="card">
                  <div className="card-title">Bedtime Consistency</div>
                  <div className="card-sub">
                    Spread (±min) of your bedtime — variance often costs more recovery than short sleep
                  </div>
                  <p style={{ fontSize: 15 }}>
                    Last 28 nights: <b>±{bedtime.stddev_min_28d ?? "—"} min</b>
                    <span className="muted"> ({bedtime.nights_28d} nights)</span>
                    <br />
                    All time: <b>±{bedtime.stddev_min_all ?? "—"} min</b>
                  </p>
                </div>
              )}
              {trend && (
                <div className="card">
                  <div className="card-title">28-Day Direction</div>
                  <div className="card-sub">Last 28 days vs the 28 before — is load outpacing recovery?</div>
                  <p style={{ fontSize: 15 }}>
                    Strain: <b>{trend.strain_28d ?? "—"}</b>
                    <span className="muted"> vs {trend.strain_prev_28d ?? "—"}{arrow(trend.strain_28d, trend.strain_prev_28d)}</span>
                    <br />
                    Recovery: <b>{trend.recovery_28d ?? "—"}%</b>
                    <span className="muted"> vs {trend.recovery_prev_28d ?? "—"}%{arrow(trend.recovery_28d, trend.recovery_prev_28d)}</span>
                    <br />
                    HRV: <b>{trend.hrv_28d ?? "—"} ms</b>
                    <span className="muted"> vs {trend.hrv_prev_28d ?? "—"} ms{arrow(trend.hrv_28d, trend.hrv_prev_28d)}</span>
                  </p>
                </div>
              )}
            </div>
          )}

          {/* 7 — AI analysis (staged, persisted) */}
          <div className="card">
            <div className="card-title">AI Analysis</div>
            <div className="card-sub">
              Staged read of your baselines, behavior correlations and load trend — performance
              analysis, not medical advice
            </div>
            {insights?.ai_available ? (
              (insights.analyze_runs_left ?? 1) > 0 ? (
                <div className="btn-row">
                  <button className="btn" disabled={analyzing} onClick={doAnalyze}>
                    {analyzing
                      ? "Analyzing… (can take a minute)"
                      : insights.analysis
                        ? "Regenerate analysis"
                        : "Analyze my WHOOP data"}
                  </button>
                </div>
              ) : (
                <p className="muted">Daily analysis limit reached — try again tomorrow.</p>
              )
            ) : (
              <p className="muted">Configure ANTHROPIC_API_KEY on the backend to enable analysis.</p>
            )}
            {insights?.analysis && (
              <>
                <p className="muted" style={{ fontSize: 12 }}>
                  Generated {insights.analysis.created_at.slice(0, 10)}
                </p>
                <div style={{ whiteSpace: "pre-wrap", fontSize: 14, lineHeight: 1.55 }}>
                  {insights.analysis.text}
                </div>
              </>
            )}
          </div>
        </>
      )}
    </>
  );
}
