import { useEffect, useState } from "react";
import {
  timeAgo,
  type JobInfo,
  api,
  type GenerateResult,
  type PlannedWorkout,
  type PlanResponse,
  type ReconcileResult,
  type WeekCompliance,
  type WorkoutFueling,
} from "../api";
import { SPORT, fmtDur } from "../sport";

function phaseColor(phase: string): string {
  if (phase === "base") return "#4ade80";
  if (phase === "build") return "#ffb454";
  if (phase === "peak") return "#ff5d3b";
  return "#38bdf8"; // taper
}
function glow(hex: string): string {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},0.18)`;
}
const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

const STATUS: Record<string, { mark: string; cls: string }> = {
  completed: { mark: "✓", cls: "done" },
  skipped: { mark: "✗", cls: "skip" },
  planned: { mark: "○", cls: "todo" },
};

export function PlanView({
  plan,
  compliance,
  anthropicReady,
  onChanged,
}: {
  plan: PlanResponse;
  compliance: WeekCompliance[];
  anthropicReady: boolean;
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [lastGenerate, setLastGenerate] = useState<JobInfo | null>(null);
  useEffect(() => {
    api.jobsSummary().then((j) => setLastGenerate(j.latest["generate_plan"] ?? null)).catch(() => {});
  }, [plan]);
  const generatedLine = (() => {
    if (!lastGenerate || lastGenerate.status !== "succeeded") return null;
    const when = timeAgo(lastGenerate.finished_at);
    const how = (lastGenerate.result as { llm_used?: boolean } | null)?.llm_used
      ? "Claude" : "template";
    return when ? `Plan generated ${when} · ${how}` : null;
  })();
  const [result, setResult] = useState<GenerateResult | null>(null);
  const [reconciled, setReconciled] = useState<ReconcileResult | null>(null);
  const [openWeek, setOpenWeek] = useState<string | null>(null);

  const complByWeek = new Map(compliance.map((c) => [c.week_start, c]));
  const weeks = plan.plan?.weeks ?? [];

  async function generate() {
    setBusy(true);
    try {
      setResult(await api.generatePlan(anthropicReady));
      onChanged();
    } finally {
      setBusy(false);
    }
  }
  async function reconcile() {
    setBusy(true);
    try {
      setReconciled(await api.reconcile(1));
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  const sessionsFor = (ws: string): PlannedWorkout[] => {
    const end = new Date(new Date(ws).getTime() + 6 * 86400000).toISOString().slice(0, 10);
    return plan.workouts.filter((w) => w.date >= ws && w.date <= end);
  };

  return (
    <div className="card" id="tour-plan">
      <div className="plan-head">
        <div>
          <div className="card-title">{weeks.length ? `${weeks.length}-Week Plan to Race Day` : "Training Plan"}</div>
          <div className="card-sub">
            Base → Build → Peak → 2-week taper · validated &amp; ramp-capped · tap a week to expand
            {generatedLine ? ` · ${generatedLine}` : ""}
          </div>
        </div>
        <div className="actions">
          {plan.plan && <a className="btn" href={api.planZipUrl}>↓ Full plan (.zip)</a>}
          {plan.plan && (
            <button className="btn" onClick={reconcile} disabled={busy} title="Match completed workouts, re-plan next week from latest fitness">
              {busy ? "Working…" : "↻ Update plan"}
            </button>
          )}
          <button className="btn primary" onClick={generate} disabled={busy}>
            {busy ? "Working…" : plan.plan ? "Regenerate" : "Generate plan"}
          </button>
        </div>
      </div>

      {!anthropicReady && <p className="muted small">Claude not configured — plan uses the deterministic template.</p>}

      {reconciled && (
        <p className="muted small" style={{ marginTop: 6 }}>
          Reconciled: {reconciled.matched.completed} completed · {reconciled.matched.skipped} skipped · form{" "}
          <b>{reconciled.form_flag}</b>
          {reconciled.weeks_replanned.length ? ` · re-planned week ${reconciled.weeks_replanned.join(", ")}` : ""}
          {reconciled.compliance.completion_rate != null
            ? ` · last 3wk ${Math.round(reconciled.compliance.completion_rate * 100)}%`
            : ""}
        </p>
      )}

      {result && (
        <div className="gen-result">
          <p className="muted small">
            {result.llm_used ? "🤖 AI-adapted" : "📋 Template"} · {result.weeks} weeks · {result.workouts} workouts
          </p>
          {result.adjustments.length > 0 && (
            <details>
              <summary className="muted small">{result.adjustments.length} safety adjustments applied</summary>
              <ul className="muted small">{result.adjustments.map((a, i) => <li key={i}>{a}</li>)}</ul>
            </details>
          )}
        </div>
      )}

      {!plan.plan && !result && (
        <p className="muted" style={{ marginTop: 10 }}>No plan yet. Generate an AI-adaptive 70.3 plan from your current fitness.</p>
      )}

      {weeks.length > 0 && (
        <p className="muted small" style={{ marginTop: 8 }}>
          <b>.fit</b> → Garmin Connect (all sports: Workouts → Import → Send to Device).{" "}
          <b>.zwo</b> → TrainingPeaks (bike only; its import doesn’t accept .fit or run/swim).
        </p>
      )}

      {weeks.length > 0 && (
        <div className="weeks">
          {weeks.map((w) => {
            const open = openWeek === w.week_start;
            const color = phaseColor(w.phase);
            const cm = complByWeek.get(w.week_start);
            return (
              <div className="week" key={w.week_start}>
                <button className="week-row" onClick={() => setOpenWeek(open ? null : w.week_start)}>
                  <span className="week-dot" style={{ background: color, boxShadow: `0 0 0 3px ${glow(color)}` }} />
                  <span className="week-n">W{w.week_index}</span>
                  <span className="week-date">{w.week_start.slice(5)}</span>
                  <span className="week-phase">{cap(w.phase)}{w.is_recovery ? " · Recovery" : ""}</span>
                  {cm && (cm.completed || cm.skipped) ? (
                    <span className="week-compl">
                      {cm.actual_hours}/{cm.planned_hours}h{cm.skipped ? <span className="bad"> ✗{cm.skipped}</span> : null}
                    </span>
                  ) : null}
                  <span className="week-hours">{w.target_hours}h</span>
                  <span className={`week-caret${open ? " open" : ""}`}>▾</span>
                </button>
                {open && (
                  <div className="week-body">
                    <div className="week-note">{w.focus}</div>
                    <div className="sessions">
                      {sessionsFor(w.week_start).map((wo) => (
                        <SessionRow key={wo.id} wo={wo} />
                      ))}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function SessionRow({ wo }: { wo: PlannedWorkout }) {
  const sp = SPORT[wo.sport] ?? { color: "#9aa0ac", label: wo.sport.toUpperCase() };
  const st = STATUS[wo.status ?? "planned"] ?? STATUS.planned;
  const [open, setOpen] = useState(false);
  const [fueling, setFueling] = useState<WorkoutFueling | null>(null);
  const [loading, setLoading] = useState(false);
  // Anything ≥45 min is worth showing a fueling card for.
  const fuelable = (wo.duration_s ?? 0) >= 45 * 60;

  async function toggle() {
    const next = !open;
    setOpen(next);
    if (next && !fueling && !loading) {
      setLoading(true);
      try {
        const r = await api.workoutFueling(wo.id);
        setFueling(r.fueling);
      } finally {
        setLoading(false);
      }
    }
  }

  return (
    <>
      <div className="session">
        <span className={`session-status ${st.cls}`} title={wo.status ?? "planned"}>{st.mark}</span>
        <span className="sport-badge" style={{ color: sp.color, borderColor: sp.color, border: `1px solid ${sp.color}` }}>{sp.label}</span>
        <span className="session-date">{wo.date.slice(5)}</span>
        <span className="session-title">
          {wo.intensity === "test" && (
            <span className="sport-badge" style={{ color: "var(--accent)", border: "1px solid var(--accent)", marginRight: 6 }}>TEST</span>
          )}
          {wo.title}
        </span>
        <span className="session-meta">{fmtDur(wo.duration_s)} · {wo.intensity} · {wo.planned_tss} TSS</span>
        <span className="session-dl">
          {fuelable && (
            <button className="fuel-toggle" onClick={toggle} title="Fueling plan for this session">
              🍌{open ? " ▴" : " ▾"}
            </button>
          )}
          <a href={api.workoutFitUrl(wo.id)} title="Garmin Connect → Workouts → Import (all sports)">.fit</a>
          {(wo.sport === "Bike" || wo.sport === "Brick") && (
            <a href={api.workoutZwoUrl(wo.id)} title="TrainingPeaks → Workout Library → Workout Import (bike)">.zwo</a>
          )}
          <a href={api.workoutItwUrl(wo.id)} title="Apple Watch → open in the Iron Trainer helper app">.itw</a>
        </span>
      </div>
      {open && (
        <div className="fuel-card">
          {loading && <span className="muted small">Loading fueling…</span>}
          {fueling && !fueling.needed && (
            <span className="muted small">{fueling.note ?? "No in-session fueling needed."}</span>
          )}
          {fueling && fueling.needed && (
            <div className="fuel-grid">
              <div className="fuel-metric">
                <span className="n">{fueling.carb_g_h} g/h</span>
                <span className="lbl">carbs · {fueling.carb_total_g} g total{fueling.mtc_required ? " · glucose:fructose blend" : ""}</span>
              </div>
              <div className="fuel-metric">
                <span className="n">{fueling.gels_per_hour}/h</span>
                <span className="lbl">gels ({fueling.gel_carb_g} g) · {fueling.gels_total} total{fueling.high_carb_gels_total ? ` · or ${fueling.high_carb_gels_total} high-carb` : ""}</span>
              </div>
              {fueling.fluid_ml_h != null ? (
                <div className="fuel-metric">
                  <span className="n">{fueling.fluid_ml_h} mL/h</span>
                  <span className="lbl">fluid · {fueling.fluid_total_ml} mL total</span>
                </div>
              ) : null}
              {fueling.sodium_mg_h != null ? (
                <div className="fuel-metric">
                  <span className="n">{fueling.sodium_mg_h} mg/h</span>
                  <span className="lbl">sodium · {fueling.sodium_total_mg} mg total</span>
                </div>
              ) : null}
            </div>
          )}
          {fueling?.note && fueling.needed && <div className="muted small">{fueling.note}</div>}
        </div>
      )}
    </>
  );
}
