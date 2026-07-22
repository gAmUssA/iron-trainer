import type { PlannedWorkout } from "../api";
import { SPORT, fmtDur } from "../sport";

/** Local calendar date as YYYY-MM-DD (workout.date is a local date string from
 * plan generation; toISOString would shift by the UTC offset). */
function localToday(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/**
 * Today-view answer to "what do I do today?" — derived from the already-fetched
 * plan (no API call). Rendered only when a plan exists; a rest day still shows
 * the card (recover) so the day never looks empty. (bean iron-trainer-6xpm)
 */
export function TodaySessionCard({
  workouts,
  onOpenPlan,
}: {
  workouts: PlannedWorkout[];
  onOpenPlan: () => void;
}) {
  const today = localToday();
  const todays = workouts.filter((w) => w.date === today);

  return (
    <div className="card">
      <div className="card-title">Today’s session</div>
      {todays.length === 0 ? (
        <p className="muted" style={{ margin: "4px 0 0" }}>
          Rest day — recover.{" "}
          <button type="button" className="linklike" onClick={onOpenPlan}>
            See the week →
          </button>
        </p>
      ) : (
        <>
          <div style={{ display: "flex", flexDirection: "column", gap: 12, marginTop: 4 }}>
            {todays.map((wo) => {
              const sp = SPORT[wo.sport] ?? { color: "#9aa0ac", label: wo.sport.toUpperCase() };
              return (
                <div key={wo.id} style={{ display: "flex", gap: 10, alignItems: "flex-start" }}>
                  <span
                    className="sport-badge"
                    style={{ color: sp.color, border: `1px solid ${sp.color}` }}
                  >
                    {sp.label}
                  </span>
                  <div style={{ minWidth: 0 }}>
                    <div className="session-title">{wo.title}</div>
                    <div className="today-session-meta">
                      {fmtDur(wo.duration_s)}
                      {wo.intensity ? ` · ${wo.intensity}` : ""}
                      {wo.planned_tss != null ? ` · ${wo.planned_tss} TSS` : ""}
                    </div>
                    {wo.description && (
                      <div className="muted small" style={{ marginTop: 2 }}>
                        {wo.description}
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
          <button
            type="button"
            className="linklike"
            style={{ marginTop: 10 }}
            onClick={onOpenPlan}
          >
            Open in Training Plan →
          </button>
        </>
      )}
    </div>
  );
}
