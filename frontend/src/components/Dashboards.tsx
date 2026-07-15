import {
  Area,
  AreaChart,
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
import type { PmcDay, Readiness, Trends, WeekVolume } from "../api";
import { pace100 } from "../api";
import { useUnits, paceInUnit } from "../units";
import { COLORS, useChart } from "../chartTheme";
import { RangePicker } from "./RangePicker";

// ── Performance Management Chart ──────────────────────────────────────────────
export function PmcChart({
  days, range, onRange,
}: {
  days: PmcDay[]; range?: number; onRange?: (days: number) => void;
}) {
  const ch = useChart();
  const picker = range !== undefined && onRange ? <RangePicker value={range} onChange={onRange} /> : null;
  if (!days.length) {
    // History exists outside the window (the caller only renders this card
    // when there's data at all) — keep the picker reachable so "All" works.
    return (
      <div className="card" id="tour-pmc">
        <div className="chart-head">
          <div>
            <div className="card-title">Fitness &amp; Form</div>
            <div className="card-sub">No training in this window</div>
          </div>
          {picker}
        </div>
      </div>
    );
  }
  const last = days[days.length - 1];
  const tsb = Math.round(last.tsb);
  return (
    <div className="card" id="tour-pmc">
      <div className="chart-head">
        <div>
          <div className="card-title">Fitness &amp; Form</div>
          <div className="card-sub">Performance Management Chart — CTL is fitness, ATL is fatigue, TSB is form</div>
        </div>
        {picker}
        <div className="kpi-legend">
          <span className="kpi"><span className="sw" style={{ background: COLORS.ctl }} />Fitness <span className="val">{Math.round(last.ctl)}</span></span>
          <span className="kpi"><span className="sw" style={{ background: COLORS.atl }} />Fatigue <span className="val">{Math.round(last.atl)}</span></span>
          <span className="kpi"><span className="sw" style={{ background: COLORS.tsb }} />Form <span className="val">{tsb > 0 ? `+${tsb}` : tsb}</span></span>
        </div>
      </div>
      <ResponsiveContainer width="100%" height={280}>
        <ComposedChart data={days} margin={{ top: 14, right: 8, left: -18, bottom: 0 }}>
          <defs>
            <linearGradient id="ctlFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={COLORS.ctl} stopOpacity={0.16} />
              <stop offset="100%" stopColor={COLORS.ctl} stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid stroke={ch.grid} />
          <XAxis dataKey="date" tick={ch.tick} minTickGap={48} stroke={ch.grid} />
          <YAxis yAxisId="load" tick={ch.tick} stroke={ch.grid} />
          <YAxis yAxisId="form" orientation="right" tick={ch.tick} stroke={ch.grid} />
          <Tooltip contentStyle={ch.tooltip} />
          <ReferenceLine yAxisId="form" y={0} stroke="#3a4655" />
          <Area yAxisId="load" dataKey="ctl" name="Fitness" stroke={COLORS.ctl} strokeWidth={1.8} fill="url(#ctlFill)" dot={false} />
          <Line yAxisId="load" dataKey="atl" name="Fatigue" stroke={COLORS.atl} strokeWidth={1.6} dot={false} />
          <Line yAxisId="form" dataKey="tsb" name="Form" stroke={COLORS.tsb} strokeWidth={1.6} dot={false} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

// ── Weekly volume ─────────────────────────────────────────────────────────────
export function WeeklyVolumeChart({ weeks }: { weeks: WeekVolume[] }) {
  const data = weeks.map((w) => ({
    week: w.week_start.slice(5),
    Swim: w.by_sport.Swim?.hours ?? 0,
    Bike: w.by_sport.Bike?.hours ?? 0,
    Run: w.by_sport.Run?.hours ?? 0,
  }));
  const ch = useChart();
  return (
    <div className="card" id="tour-weekly">
      <div className="card-title">Weekly Volume</div>
      <div className="card-sub">Hours per sport per week — actual, from Strava</div>
      <ResponsiveContainer width="100%" height={300}>
        <ComposedChart data={data} margin={{ top: 14, right: 8, left: -22, bottom: 0 }}>
          <CartesianGrid stroke={ch.grid} vertical={false} />
          <XAxis dataKey="week" tick={ch.tick} stroke={ch.grid} minTickGap={20} />
          <YAxis tick={ch.tick} stroke={ch.grid} />
          <Tooltip contentStyle={ch.tooltip} cursor={{ fill: "rgba(128,128,128,0.12)" }} />
          <Bar dataKey="Swim" stackId="a" fill={COLORS.swim} radius={[0, 0, 0, 0]} />
          <Bar dataKey="Bike" stackId="a" fill={COLORS.bike} />
          <Bar dataKey="Run" stackId="a" fill={COLORS.run} radius={[2, 2, 0, 0]} />
        </ComposedChart>
      </ResponsiveContainer>
      <div className="chart-legend">
        <span><span className="sw" style={{ background: COLORS.swim }} />Swim</span>
        <span><span className="sw" style={{ background: COLORS.bike }} />Bike</span>
        <span><span className="sw" style={{ background: COLORS.run }} />Run</span>
      </div>
    </div>
  );
}

// ── Per-sport trends ──────────────────────────────────────────────────────────
export function TrendsChart({ trends }: { trends: Trends }) {
  const { unit } = useUnits();
  return (
    <div className="trend-stack" id="tour-trends">
      <MiniSpark
        title="Bike power" unit="avg threshold W" color={COLORS.bike}
        data={trends.Bike.map((p) => ({ x: p.date.slice(5), v: p.power }))}
      />
      <MiniSpark
        title="Run pace" unit={`threshold /${unit}`} color={COLORS.run} invert
        fmt={(v) => paceInUnit(v, unit)}
        data={trends.Run.map((p) => ({ x: p.date.slice(5), v: p.pace }))}
      />
      <MiniSpark
        title="Swim pace" unit="CSS /100m" color={COLORS.swim} invert fmt={pace100}
        data={trends.Swim.map((p) => ({ x: p.date.slice(5), v: p.pace }))}
      />
    </div>
  );
}

export function MiniSpark({
  title, unit, color, data, invert, fmt,
}: {
  title: string; unit: string; color: string;
  data: { x: string; v: number }[]; invert?: boolean; fmt?: (v: number) => string;
}) {
  const id = `sp-${title.replace(/\s/g, "")}`;
  const ch = useChart();
  return (
    <div className="mini">
      <div className="mini-head">
        <div className="mini-title"><span className="dot" style={{ background: color }} />{title}</div>
        <div className="mini-unit">{unit}</div>
      </div>
      {data.length ? (
        <ResponsiveContainer width="100%" height={88}>
          <AreaChart data={data} margin={{ top: 8, right: 4, left: 4, bottom: 0 }}>
            <defs>
              <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={color} stopOpacity={0.2} />
                <stop offset="100%" stopColor={color} stopOpacity={0} />
              </linearGradient>
            </defs>
            <YAxis hide domain={["dataMin", "dataMax"]} reversed={invert} />
            <Tooltip contentStyle={ch.tooltip} formatter={(v: number) => (fmt ? fmt(v) : v)} labelStyle={{ color: "var(--muted)" }} />
            <Area dataKey="v" stroke={color} strokeWidth={1.8} fill={`url(#${id})`} dot={{ r: 1.6, fill: color }} />
          </AreaChart>
        </ResponsiveContainer>
      ) : (
        <p className="muted small" style={{ marginTop: 8 }}>No data yet</p>
      )}
    </div>
  );
}

// ── Race readiness hero ───────────────────────────────────────────────────────
const SEG_COLOR: Record<string, string> = { swim: COLORS.swim, bike: COLORS.bike, run: COLORS.run };
const T1 = 300, T2 = 180;
const MISSING_LABEL: Record<string, string> = {
  css_swim: "Swim CSS",
  threshold_pace_run: "run threshold pace",
  bike_speed_history: "recent ride history",
};

export function ReadinessCard({ readiness, raceName }: { readiness: Readiness; raceName: string }) {
  const legs = readiness.legs;
  const incomplete = readiness.missing.length > 0;
  const missingLabels = readiness.missing.map((m) => MISSING_LABEL[m] ?? m).join(", ");
  const finishCut = readiness.cutoffs.find((c) => c.checkpoint === "Finish");
  const scale = finishCut?.limit_s ?? 30600;

  const pct = (s: number) => `${((s / scale) * 100).toFixed(2)}%`;
  const segs: { left: number; width: number; color: string }[] = [];
  if (legs.swim && legs.bike && legs.run) {
    const swim = legs.swim.seconds, bike = legs.bike.seconds, run = legs.run.seconds;
    const bikeStart = swim + T1;
    const runStart = bikeStart + bike + T2;
    segs.push({ left: 0, width: swim, color: SEG_COLOR.swim });
    segs.push({ left: bikeStart, width: bike, color: SEG_COLOR.bike });
    segs.push({ left: runStart, width: run, color: SEG_COLOR.run });
  }
  const marks = readiness.cutoffs
    .filter((c) => c.limit_s)
    .map((c) => ({ s: c.limit_s, label: c.checkpoint === "Finish" ? "Finish" : `${c.checkpoint} ${shortHM(c.limit_s)}` }));

  return (
    <div className="card readiness" id="tour-readiness">
      <div className="rd-label">Race Readiness — {raceName}</div>
      {readiness.total ? (
        <>
          <div className="rd-top">
            <div className="rd-finish">{readiness.total.display}</div>
            <div className="rd-cap">
              {incomplete ? (
                <>partial estimate<br />— missing a leg</>
              ) : (
                <>projected finish<br />at current fitness</>
              )}
            </div>
            <div className="rd-splits">
              {(["swim", "bike", "run"] as const).map((leg) => (
                <div className="rd-split" key={leg}>
                  <div className="k" style={{ textTransform: "capitalize" }}>{leg}</div>
                  <div className="v" style={legs[leg] ? undefined : { color: "var(--dim)" }}>
                    {legs[leg]?.display ?? "—"}
                  </div>
                </div>
              ))}
              <div className="rd-split">
                <div className="k">T1+T2</div>
                <div className="v">{readiness.transitions.display}</div>
              </div>
            </div>
          </div>

          {incomplete && (
            <div className="rd-missing">
              ⚠ Missing <b>{missingLabels}</b> — set it in <b>Settings → Thresholds</b> to project the
              {readiness.missing.includes("css_swim") ? " swim leg, " : " "}timeline and cut-offs.
            </div>
          )}

          {segs.length > 0 && (
            <div className="timeline">
              <div className="timeline-ends">
                <span>START</span>
                <span>FINISH CUT-OFF {finishCut?.limit ?? "8:30:00"}</span>
              </div>
              <div className="timeline-track">
                {segs.map((s, i) => (
                  <div key={i} className="timeline-seg" style={{ left: pct(s.left), width: pct(s.width), background: s.color }} />
                ))}
                {marks.map((m, i) => (
                  <div key={i} className="timeline-mark" style={{ left: pct(m.s) }} />
                ))}
              </div>
              <div className="timeline-labels">
                {marks.map((m, i) => (
                  <span key={i} style={{ left: pct(m.s) }}>{m.label}</span>
                ))}
              </div>
            </div>
          )}

          {readiness.cutoffs.length > 0 && (
            <div className="cutoffs">
              <div className="cutoff-grid cutoff-head">
                <span>Checkpoint</span><span className="mono">Projected</span><span className="mono">Limit</span><span className="mono">Margin</span>
              </div>
              {readiness.cutoffs.map((c) => (
                <div className="cutoff-grid cutoff-row" key={c.checkpoint}>
                  <span className="name"><span className="dot" style={{ background: dotFor(c.checkpoint) }} />{c.checkpoint}</span>
                  <span className="proj">{c.projected ?? "—"}</span>
                  <span className="lim">{c.limit}</span>
                  <span className={`margin ${c.ok ? "ok" : c.ok === false ? "bad" : ""}`}>
                    {c.ok == null ? "—" : `${c.ok ? "✓" : "✗"} ${c.margin}`}
                  </span>
                </div>
              ))}
            </div>
          )}
        </>
      ) : (
        <p className="muted small" style={{ marginTop: 10 }}>
          Need thresholds + ride history to project. Missing: {readiness.missing.join(", ")}
        </p>
      )}
    </div>
  );
}

function dotFor(checkpoint: string): string {
  return checkpoint === "Swim" ? COLORS.swim : checkpoint === "Bike" ? COLORS.bike : COLORS.run;
}
function shortHM(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.round((seconds % 3600) / 60);
  return `${h}:${m.toString().padStart(2, "0")}`;
}
