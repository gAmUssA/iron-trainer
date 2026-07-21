import { useEffect, useRef, useState } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api, DEFAULT_CHART_DAYS, type RecoveryDay } from "../api";
import { COLORS, useChart } from "../chartTheme";
import { MiniSpark } from "./Dashboards";
import { RangePicker } from "./RangePicker";

// Recovery-specific accents (kept local, like TrendsView's IF_COLORS).
const C = {
  hrv: "#2dd4bf", // teal
  rhr: COLORS.atl, // coral/rose
  weight: COLORS.proj,
  deep: "#4338ca",
  core: "#38bdf8",
  rem: "#a78bfa",
  awake: "#5b6270",
  steps: "#38bdf8",
  exercise: "#4ade80",
  vo2: "#4ade80",
  spo2: "#38bdf8",
  resp: "#ffb454",
};

const DAY_MS = 86_400_000;

/** Trailing rolling mean over the last `windowDays` CALENDAR days (keyed by each
 * row's date, not by array index — a gap in the daily series would otherwise let a
 * "7-day" window silently span far more than 7 days). rows are oldest→newest. */
function rollingMean(
  rows: RecoveryDay[],
  valueOf: (r: RecoveryDay) => number | null | undefined,
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

const day = (d: string) => d.slice(5); // MM-DD

export function RecoveryTrendsView() {
  const ch = useChart();
  const [days, setDays] = useState(DEFAULT_CHART_DAYS);
  const [rows, setRows] = useState<RecoveryDay[]>([]);
  const [loading, setLoading] = useState(true);
  const req = useRef(0);

  useEffect(() => {
    const seq = ++req.current;
    setLoading(true);
    // "All" (days=0) → the backend caps at 365; ask for the max explicitly since
    // days=0 there means "one row", not "everything".
    api
      .recovery(days === 0 ? 365 : days)
      .then((r) => {
        if (seq !== req.current) return; // stale response
        // API returns newest-first; charts read oldest→newest.
        setRows([...r.days].reverse());
      })
      .catch(() => {
        if (seq === req.current) setRows([]);
      })
      .finally(() => {
        if (seq === req.current) setLoading(false);
      });
  }, [days]);

  // ── panel datasets ──────────────────────────────────────────────────────────
  const hrvRoll = rollingMean(rows, (r) => r.hrv_ms, 7);
  const rhrRoll = rollingMean(rows, (r) => r.rhr_bpm, 7);
  const hrvData = rows.map((r, i) => ({
    x: day(r.date),
    hrv: r.hrv_ms,
    rhr: r.rhr_bpm,
    hrvTrend: hrvRoll[i],
    rhrTrend: rhrRoll[i],
  }));

  const sleep = rows.slice(-21).map((r) => ({
    x: day(r.date),
    deep: r.deep_h,
    rem: r.rem_h,
    // deep + core + rem = sleep_h (the asleep total). awake is time awake in bed,
    // NOT part of sleep_h, so it's a separate segment on top — the sleep portion
    // stays exactly sleep_h and the bar never overstates sleep.
    core: r.sleep_h != null ? Math.max(0, r.sleep_h - (r.deep_h ?? 0) - (r.rem_h ?? 0)) : null,
    awake: r.awake_h,
  }));

  const wRoll = rollingMean(rows, (r) => r.weight_kg, 7);
  const weight = rows.map((r, i) => ({ x: day(r.date), weight: r.weight_kg, trend: wRoll[i] }));

  const load = rows.map((r) => ({
    x: day(r.date),
    steps: r.step_count ?? null,
    exercise: r.exercise_min ?? null,
  }));

  // Vitals → the shared MiniSpark ({x,v}[], own auto-scaled axis each).
  const spark = (k: keyof RecoveryDay) =>
    rows
      .map((r) => ({ x: day(r.date), v: r[k] as number | null }))
      .filter((p): p is { x: string; v: number } => p.v != null);
  const vo2 = spark("vo2max");
  const spo2 = spark("spo2_pct");
  const resp = spark("respiratory_rate");

  const empty = !loading && rows.length === 0;

  return (
    <>
      <div className="badge-row">
        <span style={{ marginLeft: "auto" }}>
          <RangePicker value={days} onChange={setDays} />
        </span>
      </div>

      {loading && rows.length === 0 ? (
        <div className="card">
          <p className="muted">Loading recovery data…</p>
        </div>
      ) : empty ? (
        <div className="card">
          <p className="muted">
            No recovery data yet. Ingest sleep, HRV and resting-HR via Health Auto Export
            (Settings → Health) to populate these trends.
          </p>
        </div>
      ) : (
        <>
          <div className="grid-2">
            {/* 1 — HRV & Resting HR */}
            <div className="card">
              <div className="card-title">HRV &amp; Resting HR</div>
              <div className="card-sub">
                Dual axis — HRV (left) rises with recovery, RHR (right) falls; dashed = 7-day mean
              </div>
              <ResponsiveContainer width="100%" height={220}>
                <LineChart data={hrvData} margin={{ top: 12, right: 4, left: -18, bottom: 0 }}>
                  <CartesianGrid stroke={ch.grid} />
                  <XAxis dataKey="x" tick={ch.tick} stroke={ch.grid} minTickGap={40} />
                  <YAxis yAxisId="hrv" tick={ch.tick} stroke={ch.grid} domain={["auto", "auto"]} />
                  <YAxis yAxisId="rhr" orientation="right" tick={ch.tick} stroke={ch.grid} domain={["auto", "auto"]} />
                  <Tooltip contentStyle={ch.tooltip} />
                  <Line yAxisId="hrv" dataKey="hrv" name="HRV (ms)" stroke={C.hrv} strokeWidth={1.6} dot={false} connectNulls />
                  <Line yAxisId="hrv" dataKey="hrvTrend" name="HRV 7d" stroke={C.hrv} strokeWidth={2.2} strokeDasharray="6 4" dot={false} connectNulls />
                  <Line yAxisId="rhr" dataKey="rhr" name="RHR (bpm)" stroke={C.rhr} strokeWidth={1.6} dot={false} connectNulls />
                  <Line yAxisId="rhr" dataKey="rhrTrend" name="RHR 7d" stroke={C.rhr} strokeWidth={2.2} strokeDasharray="6 4" dot={false} connectNulls />
                </LineChart>
              </ResponsiveContainer>
            </div>

            {/* 2 — Sleep stages */}
            <div className="card">
              <div className="card-title">Sleep Stages</div>
              <div className="card-sub">Hours asleep by stage — last {sleep.length} nights (awake time shown separately)</div>
              <ResponsiveContainer width="100%" height={220}>
                <BarChart data={sleep} margin={{ top: 12, right: 8, left: -22, bottom: 0 }}>
                  <CartesianGrid stroke={ch.grid} vertical={false} />
                  <XAxis dataKey="x" tick={ch.tick} stroke={ch.grid} minTickGap={16} />
                  <YAxis tick={ch.tick} stroke={ch.grid} />
                  <Tooltip contentStyle={ch.tooltip} cursor={{ fill: "rgba(128,128,128,0.12)" }} />
                  <Bar dataKey="deep" stackId="s" name="Deep" fill={C.deep} />
                  <Bar dataKey="core" stackId="s" name="Core" fill={C.core} />
                  <Bar dataKey="rem" stackId="s" name="REM" fill={C.rem} />
                  <Bar dataKey="awake" stackId="s" name="Awake" fill={C.awake} fillOpacity={0.35} radius={[2, 2, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
              <div className="chart-legend">
                <span><span className="sw" style={{ background: C.deep }} />Deep</span>
                <span><span className="sw" style={{ background: C.core }} />Core</span>
                <span><span className="sw" style={{ background: C.rem }} />REM</span>
                <span><span className="sw" style={{ background: C.awake, opacity: 0.35 }} />Awake</span>
              </div>
            </div>

            {/* 3 — Body weight */}
            <div className="card">
              <div className="card-title">Body Weight</div>
              <div className="card-sub">kg — line is daily, dashed is the 7-day mean</div>
              <ResponsiveContainer width="100%" height={220}>
                <AreaChart data={weight} margin={{ top: 12, right: 8, left: -18, bottom: 0 }}>
                  <defs>
                    <linearGradient id="wtFill" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={C.weight} stopOpacity={0.16} />
                      <stop offset="100%" stopColor={C.weight} stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid stroke={ch.grid} />
                  <XAxis dataKey="x" tick={ch.tick} stroke={ch.grid} minTickGap={40} />
                  <YAxis tick={ch.tick} stroke={ch.grid} domain={["auto", "auto"]} width={40} />
                  <Tooltip contentStyle={ch.tooltip} />
                  <Area dataKey="weight" name="Weight (kg)" stroke={C.weight} strokeWidth={1.6} fill="url(#wtFill)" dot={false} connectNulls />
                  <Line dataKey="trend" name="7d mean" stroke={C.weight} strokeWidth={2} strokeDasharray="6 4" dot={false} connectNulls />
                </AreaChart>
              </ResponsiveContainer>
            </div>

            {/* 4 — Daily load */}
            <div className="card">
              <div className="card-title">Daily Load</div>
              <div className="card-sub">Steps (bars, left) and Apple-exercise minutes (line, right)</div>
              <ResponsiveContainer width="100%" height={220}>
                <ComposedChart data={load} margin={{ top: 12, right: 4, left: -18, bottom: 0 }}>
                  <CartesianGrid stroke={ch.grid} vertical={false} />
                  <XAxis dataKey="x" tick={ch.tick} stroke={ch.grid} minTickGap={40} />
                  <YAxis yAxisId="steps" tick={ch.tick} stroke={ch.grid} />
                  <YAxis yAxisId="min" orientation="right" tick={ch.tick} stroke={ch.grid} />
                  <Tooltip contentStyle={ch.tooltip} cursor={{ fill: "rgba(128,128,128,0.12)" }} />
                  <Bar yAxisId="steps" dataKey="steps" name="Steps" fill={C.steps} fillOpacity={0.55} radius={[2, 2, 0, 0]} />
                  <Line yAxisId="min" dataKey="exercise" name="Exercise (min)" stroke={C.exercise} strokeWidth={1.8} dot={false} connectNulls />
                </ComposedChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* 5 — Vitals */}
          {vo2.length + spo2.length + resp.length > 0 && (
            <div className="card">
              <div className="card-title">Vitals</div>
              <div className="card-sub">VO₂max, blood-oxygen and respiratory rate</div>
              <div className="grid-2">
                {vo2.length > 0 && <MiniSpark title="VO₂max" unit="ml/kg/min" color={C.vo2} data={vo2} />}
                {spo2.length > 0 && <MiniSpark title="SpO₂" unit="%" color={C.spo2} data={spo2} />}
                {resp.length > 0 && <MiniSpark title="Respiratory" unit="br/min" color={C.resp} data={resp} />}
              </div>
            </div>
          )}
        </>
      )}
    </>
  );
}
