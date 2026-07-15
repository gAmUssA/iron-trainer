import { Line, LineChart, ResponsiveContainer, YAxis } from "recharts";
import type { RecoveryDay } from "../api";

/** Sleep, HRV, resting HR from the athlete's own phone (Health Auto Export):
 * latest value, 7-day sparkline, and a delta vs the personal baseline — so a
 * sliding HRV is visible BEFORE it's bad enough to flip the readiness call.
 * Rendered only when data exists; the pipeline is optional. */
export function RecoveryCard({ days }: { days: RecoveryDay[] }) {
  // API is newest-first; charts read left→right oldest-first.
  const series = [...days].reverse();
  const latest = days[0];
  if (!latest) return null;

  const tiles = [
    {
      label: "Sleep",
      field: "sleep_h" as const,
      value: latest.sleep_h,
      unit: "h",
      fmt: (v: number) => v.toFixed(1),
      sub: latest.deep_h != null || latest.rem_h != null
        ? `deep ${latest.deep_h?.toFixed(1) ?? "–"}h · REM ${latest.rem_h?.toFixed(1) ?? "–"}h`
        : null,
      goodWhenUp: true,
      color: "#38bdf8",
    },
    {
      label: "HRV",
      field: "hrv_ms" as const,
      value: latest.hrv_ms,
      unit: "ms",
      fmt: (v: number) => v.toFixed(0),
      sub: null,
      goodWhenUp: true,
      color: "#4ade80",
    },
    {
      label: "Resting HR",
      field: "rhr_bpm" as const,
      value: latest.rhr_bpm,
      unit: "bpm",
      fmt: (v: number) => v.toFixed(0),
      sub: null,
      goodWhenUp: false,
      color: "#ffb454",
    },
  ];

  return (
    <div className="card">
      <div className="chart-head">
        <div>
          <div className="card-title">Recovery</div>
          <div className="card-sub">
            From your phone via Health Auto Export — feeds the readiness call
          </div>
        </div>
        <div className="card-sub">last push {latest.date}</div>
      </div>
      <div className="recovery-tiles">
        {tiles.map((t) => {
          const points = series
            .filter((d) => d[t.field] != null)
            .map((d) => ({ v: d[t.field] as number }));
          // Baseline = mean of everything before the latest sample.
          const prior = points.slice(0, -1).map((p) => p.v);
          const base = prior.length >= 3 ? prior.reduce((a, b) => a + b, 0) / prior.length : null;
          const delta = base != null && t.value != null ? t.value - base : null;
          const good = delta != null && (t.goodWhenUp ? delta >= 0 : delta <= 0);
          return (
            <div className="recovery-tile" key={t.label}>
              <div className="label">{t.label}</div>
              <div className="value">
                {t.value != null ? t.fmt(t.value) : "–"}
                <span className="unit">{t.unit}</span>
              </div>
              {t.sub && <div className="tile-sub">{t.sub}</div>}
              {delta != null && (
                <div className={`delta ${good ? "up" : "down"}`}>
                  {delta >= 0 ? "▲" : "▼"} {t.fmt(Math.abs(delta))} vs baseline
                </div>
              )}
              {points.length >= 3 && (
                <div className="spark">
                  <ResponsiveContainer width="100%" height={34}>
                    <LineChart data={points} margin={{ top: 4, right: 2, left: 2, bottom: 2 }}>
                      <YAxis hide domain={["dataMin", "dataMax"]} />
                      <Line dataKey="v" stroke={t.color} strokeWidth={1.6}
                        dot={false} isAnimationActive={false} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
