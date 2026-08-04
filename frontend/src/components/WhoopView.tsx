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
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const req = useRef(0);

  function load(range: number) {
    const seq = ++req.current;
    setLoading(true);
    const d = range === 0 ? 365 : range;
    Promise.all([
      api.whoopCycles(d),
      // Comparison sources are garnish — their failure must not blank the page.
      api.recovery(d).catch(() => ({ days: [] as RecoveryDay[] })),
      api.pmc(d).catch(() => ({ days: [] as PmcDay[] })),
    ])
      .then(([wr, ar, pr]) => {
        if (seq !== req.current) return; // stale response
        // whoop/recovery arrive newest-first; pmc oldest-first. merge() sorts anyway.
        setWhoop(wr.days);
        setApple(ar.days);
        setPmc(pr.days);
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
          ? `Imported ${r.cycles} days (${r.first_date} → ${r.last_date}).`
          : "The export contained no scored days.",
      );
      load(days);
    } catch (err) {
      setMsg(`Import failed: ${err}`);
    } finally {
      setBusy(false);
    }
  }

  const rows = merge(whoop, apple, pmc);
  const hasWhoop = whoop.length > 0;

  return (
    <>
      <div className="card">
        <div className="card-title">WHOOP Data</div>
        <div className="card-sub">
          Upload the ZIP from WHOOP&nbsp;app → App Settings → Data Export → Create Export
          (arrives by email). Re-uploading a newer export updates existing days.
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
        </>
      )}
    </>
  );
}
