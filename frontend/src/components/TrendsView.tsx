import { useState } from "react";
import {
  Area,
  Bar,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api, pace100, type PmcDay, type SportInsight, type Trends, type WeekVolume } from "../api";
import { useUnits, metersToUnit, paceInUnit } from "../units";
import { useTheme } from "../theme";
import { WeeklyVolumeChart } from "./Dashboards";

const COLORS = { swim: "#38bdf8", bike: "#ffb454", run: "#4ade80", ctl: "#4ade80", proj: "#a78bfa" };
const IF_COLORS = { easy: "#4ade80", endurance: "#38bdf8", tempo: "#ffb454", hard: "#f87171", unknown: "#5b6270" };

const CHART_THEME = {
  dark: { grid: "rgba(255,255,255,0.06)", tick: "#5b6270", tipBg: "#14161c", tipBorder: "#2a3441", tipText: "#eceef2" },
  light: { grid: "rgba(0,0,0,0.09)", tick: "#9099a3", tipBg: "#ffffff", tipBorder: "#d8dce2", tipText: "#161a20" },
};

function useChart() {
  const { theme } = useTheme();
  const c = CHART_THEME[theme];
  return {
    grid: c.grid,
    tick: { fill: c.tick, fontSize: 10, fontFamily: "'IBM Plex Mono', monospace" },
    tooltip: { background: c.tipBg, border: `1px solid ${c.tipBorder}`, borderRadius: 8, color: c.tipText, fontSize: 12 },
  };
}

// ── Freshness banner ──────────────────────────────────────────────────────────
function FreshnessBanner({
  lastActivity, daysStale, connected, onSynced,
}: {
  lastActivity: string | null; daysStale: number | null; connected: boolean; onSynced: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  if (daysStale == null || daysStale < 3) return null;

  async function syncNow() {
    setBusy(true);
    setMsg(null);
    try {
      const r = await api.sync(false);
      setMsg(`Synced ${r.fetched} new activities.`);
      onSynced();
    } catch (e) {
      setMsg(`Sync failed: ${e}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card stale-banner" role="status">
      <span>
        ⏳ Data ends <b>{lastActivity}</b> — {daysStale} days ago. Charts only move when new
        activities are synced.
      </span>
      {connected && (
        <button className="btn tiny" disabled={busy} onClick={syncNow}>
          {busy ? "Syncing…" : "Sync now"}
        </button>
      )}
      {msg && <span className="hint">{msg}</span>}
    </div>
  );
}

// ── Verdict badges ────────────────────────────────────────────────────────────
const METRIC_LABEL: Record<string, Record<string, string>> = {
  Bike: { ef: "efficiency (W/beat)", power: "threshold power" },
  Run: { ef: "efficiency (speed:HR)", pace: "pace" },
  Swim: { pace: "pace" },
};

function VerdictBadge({ sport, ins }: { sport: "Bike" | "Run" | "Swim"; ins: SportInsight }) {
  const cls = ins.verdict === "improving" ? "good" : ins.verdict === "declining" ? "bad" : "neutral";
  const arrow = ins.verdict === "improving" ? "▲" : ins.verdict === "declining" ? "▼" : "▶";
  const label = METRIC_LABEL[sport]?.[ins.metric] ?? ins.metric;
  const dot = sport === "Bike" ? COLORS.bike : sport === "Run" ? COLORS.run : COLORS.swim;
  return (
    <span className={`verdict ${cls}`} title={`12-week regression on ${label}`}>
      <span className="sw" style={{ background: dot }} />
      {sport} {label}: {arrow} {ins.verdict}
      {ins.change_pct != null && ` (${ins.change_pct > 0 ? "+" : ""}${ins.change_pct}% / 12 wk)`}
    </span>
  );
}

// ── Sport trend chart: raw sessions as dots + 28-day rolling trendline ───────
function SportTrendChart({
  title, unit, color, points, rolling, invert, fmt,
}: {
  title: string; unit: string; color: string;
  points: { date: string; value: number }[];
  rolling: { date: string; value: number }[];
  invert?: boolean; fmt?: (v: number) => string;
}) {
  const ch = useChart();
  const rollByDate = new Map(rolling.map((r) => [r.date, r.value]));
  const data = points.map((p) => ({
    x: p.date.slice(5),
    session: p.value,
    trend: rollByDate.get(p.date) ?? null,
  }));
  return (
    <div className="card">
      <div className="chart-head">
        <div>
          <div className="card-title">{title}</div>
          <div className="card-sub">{unit} — dots are sessions, line is the 28-day trend</div>
        </div>
      </div>
      {data.length ? (
        <ResponsiveContainer width="100%" height={190}>
          <ComposedChart data={data} margin={{ top: 10, right: 8, left: -14, bottom: 0 }}>
            <CartesianGrid stroke={ch.grid} />
            <XAxis dataKey="x" tick={ch.tick} stroke={ch.grid} minTickGap={40} />
            <YAxis
              tick={ch.tick} stroke={ch.grid} domain={["auto", "auto"]} reversed={invert}
              tickFormatter={(v: number) => (fmt ? fmt(v) : String(v))} width={62}
            />
            <Tooltip
              contentStyle={ch.tooltip}
              formatter={(v: number, name: string) => [fmt ? fmt(v) : v, name]}
            />
            <Line dataKey="session" name="session" stroke="none"
                  dot={{ r: 2.2, fill: color, fillOpacity: 0.45, strokeWidth: 0 }} isAnimationActive={false} />
            <Line dataKey="trend" name="28-day trend" stroke={color} strokeWidth={2.2}
                  dot={false} connectNulls />
          </ComposedChart>
        </ResponsiveContainer>
      ) : (
        <p className="muted small">No data yet</p>
      )}
    </div>
  );
}

// ── Weekly intensity mix ──────────────────────────────────────────────────────
function IntensityMixChart({ weeks }: { weeks: Trends["insights"]["intensity_weeks"] }) {
  const ch = useChart();
  const data = weeks.map((w) => ({ week: w.week_start.slice(5), ...w }));
  return (
    <div className="card">
      <div className="card-title">Intensity Mix</div>
      <div className="card-sub">
        Weekly hours by session intensity — most volume should be easy/endurance, quality on top
      </div>
      <ResponsiveContainer width="100%" height={300}>
        <ComposedChart data={data} margin={{ top: 14, right: 8, left: -22, bottom: 0 }}>
          <CartesianGrid stroke={ch.grid} vertical={false} />
          <XAxis dataKey="week" tick={ch.tick} stroke={ch.grid} minTickGap={20} />
          <YAxis tick={ch.tick} stroke={ch.grid} />
          <Tooltip contentStyle={ch.tooltip} cursor={{ fill: "rgba(128,128,128,0.12)" }} />
          <Bar dataKey="easy" stackId="i" fill={IF_COLORS.easy} />
          <Bar dataKey="endurance" stackId="i" fill={IF_COLORS.endurance} />
          <Bar dataKey="tempo" stackId="i" fill={IF_COLORS.tempo} />
          <Bar dataKey="hard" stackId="i" fill={IF_COLORS.hard} radius={[2, 2, 0, 0]} />
        </ComposedChart>
      </ResponsiveContainer>
      <div className="chart-legend">
        <span><span className="sw" style={{ background: IF_COLORS.easy }} />Easy &lt;0.70 IF</span>
        <span><span className="sw" style={{ background: IF_COLORS.endurance }} />Endurance 0.70–0.85</span>
        <span><span className="sw" style={{ background: IF_COLORS.tempo }} />Tempo 0.85–0.95</span>
        <span><span className="sw" style={{ background: IF_COLORS.hard }} />Hard ≥0.95</span>
      </div>
    </div>
  );
}

// ── CTL trajectory to race day ────────────────────────────────────────────────
function CtlTrajectoryCard({ pmc, traj }: { pmc: PmcDay[]; traj: NonNullable<Trends["insights"]["ctl_trajectory"]> }) {
  const ch = useChart();
  type Pt = { date: string; ctl: number | null; proj: number | null };
  const past: Pt[] = pmc.slice(-120).map((d) => ({ date: d.date, ctl: d.ctl, proj: null }));
  const proj: Pt[] = (traj.projection ?? []).map((p) => ({ date: p.date, ctl: null, proj: p.ctl }));
  if (past.length && proj.length) proj[0] = { ...proj[0], ctl: past[past.length - 1].ctl }; // join the lines
  const data = [...past, ...proj].map((d) => ({ ...d, x: d.date.slice(5) }));
  return (
    <div className="card">
      <div className="chart-head">
        <div>
          <div className="card-title">Fitness Trajectory</div>
          <div className="card-sub">CTL so far + where the current ramp lands you on race day</div>
        </div>
        <div className="kpi-legend">
          <span className="kpi">Now <span className="val">{Math.round(traj.current)}</span></span>
          {traj.ramp_per_week != null && (
            <span className="kpi">Ramp <span className="val">
              {traj.ramp_per_week > 0 ? "+" : ""}{traj.ramp_per_week}/wk</span></span>
          )}
          {traj.race_day_projection != null && (
            <span className="kpi">Race day <span className="val">{Math.round(traj.race_day_projection)}</span></span>
          )}
        </div>
      </div>
      <ResponsiveContainer width="100%" height={220}>
        <ComposedChart data={data} margin={{ top: 12, right: 8, left: -18, bottom: 0 }}>
          <defs>
            <linearGradient id="trajFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={COLORS.ctl} stopOpacity={0.16} />
              <stop offset="100%" stopColor={COLORS.ctl} stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid stroke={ch.grid} />
          <XAxis dataKey="x" tick={ch.tick} stroke={ch.grid} minTickGap={48} />
          <YAxis tick={ch.tick} stroke={ch.grid} />
          <Tooltip contentStyle={ch.tooltip} />
          {traj.race_date && (
            <ReferenceLine x={traj.race_date.slice(5)} stroke={COLORS.proj}
                           label={{ value: "RACE", fill: COLORS.proj, fontSize: 10 }} />
          )}
          <Area dataKey="ctl" name="Fitness (CTL)" stroke={COLORS.ctl} strokeWidth={1.8}
                fill="url(#trajFill)" dot={false} connectNulls={false} />
          <Line dataKey="proj" name="Projected" stroke={COLORS.proj} strokeWidth={1.8}
                strokeDasharray="6 4" dot={false} connectNulls />
        </ComposedChart>
      </ResponsiveContainer>
      {traj.race_day_projection != null && traj.ramp_per_week != null && (
        <div className="hint">
          Straight-line projection at the current 4-week ramp — recovery weeks and taper will bend
          it. Typical 70.3 age-group race-day CTL is ~60–90.
        </div>
      )}
    </div>
  );
}

// ── Personal records ──────────────────────────────────────────────────────────
function PrCards({ prs }: { prs: Trends["insights"]["prs"] }) {
  const { unit } = useUnits();
  const entries: { label: string; pr: (typeof prs)[string]; fmt: (v: number) => string }[] = [
    { label: "Best bike power (≥40 min)", pr: prs.bike_best_power_40min, fmt: (v) => `${v} W` },
    { label: "Fastest run pace (≥5 km)", pr: prs.run_fastest_pace_5k, fmt: (v) => paceInUnit(v, unit) },
    { label: "Fastest swim pace (≥1 km)", pr: prs.swim_fastest_pace_1k, fmt: pace100 },
    { label: "Longest ride", pr: prs.longest_ride_m, fmt: (v) => `${metersToUnit(v, unit).toFixed(1)} ${unit}` },
    { label: "Longest run", pr: prs.longest_run_m, fmt: (v) => `${metersToUnit(v, unit).toFixed(1)} ${unit}` },
  ];
  const have = entries.filter((e) => e.pr);
  if (!have.length) return null;
  return (
    <div className="card">
      <div className="card-title">Personal Records</div>
      <div className="card-sub">Bests from your synced history</div>
      <div className="pr-grid">
        {have.map((e) => (
          <div className="pr-card" key={e.label}>
            <div className="pr-value">{e.fmt(e.pr!.value)}</div>
            <div className="pr-label">{e.label}</div>
            <div className="pr-meta">{e.pr!.date}{e.pr!.name ? ` · ${e.pr!.name}` : ""}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── The Trends tab ────────────────────────────────────────────────────────────
export function TrendsView({
  trends, weekly, pmc, connected, onSynced,
}: {
  trends: Trends; weekly: WeekVolume[]; pmc: PmcDay[]; connected: boolean; onSynced: () => void;
}) {
  const { unit } = useUnits();
  const ins = trends.insights;
  return (
    <>
      <FreshnessBanner
        lastActivity={ins.freshness.last_activity}
        daysStale={ins.freshness.days_stale}
        connected={connected}
        onSynced={onSynced}
      />

      <div className="badge-row">
        {(["Bike", "Run", "Swim"] as const).map((s) => (
          <VerdictBadge key={s} sport={s} ins={ins.sports[s]} />
        ))}
      </div>

      <div className="grid-2">
        <SportTrendChart
          title="Bike Power" unit="weighted watts per session" color={COLORS.bike}
          points={trends.Bike.map((p) => ({ date: p.date, value: p.power }))}
          rolling={ins.sports.Bike.rolling}
        />
        <SportTrendChart
          title="Run Pace" unit={`threshold pace /${unit}`} color={COLORS.run} invert
          fmt={(v) => paceInUnit(v, unit)}
          points={trends.Run.map((p) => ({ date: p.date, value: p.pace }))}
          rolling={ins.sports.Run.rolling}
        />
        <SportTrendChart
          title="Swim Pace" unit="pace /100m" color={COLORS.swim} invert fmt={pace100}
          points={trends.Swim.map((p) => ({ date: p.date, value: p.pace }))}
          rolling={ins.sports.Swim.rolling}
        />
        {ins.ctl_trajectory && <CtlTrajectoryCard pmc={pmc} traj={ins.ctl_trajectory} />}
      </div>

      <div className="grid-2">
        <WeeklyVolumeChart weeks={weekly} />
        <IntensityMixChart weeks={ins.intensity_weeks} />
      </div>

      <PrCards prs={ins.prs} />
    </>
  );
}
