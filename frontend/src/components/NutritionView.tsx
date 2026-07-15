import { useCallback, useEffect, useState } from "react";
import { api, type DailyNutrition, type RaceDayItem, type RaceDayPlan } from "../api";

const PHASE_COLOR: Record<string, string> = {
  pre_race: "#a78bfa",
  swim: "#38bdf8",
  t1: "#9aa0ac",
  bike: "#ffb454",
  t2: "#9aa0ac",
  run: "#4ade80",
  post_race: "#f472b6",
};

function fmtOffset(min: number | undefined): string {
  if (min === undefined || min === null) return "";
  const sign = min < 0 ? "-" : "+";
  const abs = Math.abs(min);
  const h = Math.floor(abs / 60);
  const m = abs % 60;
  const hm = h > 0 ? `${h}:${m.toString().padStart(2, "0")}` : `${m}m`;
  return min < 0 ? `${sign}${hm}` : `${sign}${hm}`;
}

function Amounts({ item }: { item: RaceDayItem }) {
  const parts: string[] = [];
  if (item.carbs_g) parts.push(`${item.carbs_g} g carbs`);
  if (item.fluid_ml) parts.push(`${item.fluid_ml} mL fluid`);
  if (item.sodium_mg) parts.push(`${item.sodium_mg} mg sodium`);
  if (!parts.length) return null;
  return <span className="nut-amounts">{parts.join(" · ")}</span>;
}

export function NutritionView({
  anthropicReady,
  hasBodyWeight,
  onGoToSettings,
}: {
  anthropicReady: boolean;
  hasBodyWeight: boolean;
  onGoToSettings: () => void;
}) {
  const [daily, setDaily] = useState<DailyNutrition | null>(null);
  const [plan, setPlan] = useState<RaceDayPlan | null>(null);
  const [loading, setLoading] = useState(true);
  const [regenerating, setRegenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [d, p] = await Promise.all([api.nutritionDaily(), api.raceDayNutrition()]);
      setDaily(d);
      setPlan(p);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function regenerate() {
    setRegenerating(true);
    setError(null);
    try {
      setPlan(await api.regenerateRaceDayNutrition());
    } catch (e) {
      setError(String(e));
    } finally {
      setRegenerating(false);
    }
  }

  if (loading) return <div className="card"><p className="muted">Loading nutrition…</p></div>;

  return (
    <>
      {!hasBodyWeight && (
        <div className="card">
          <div className="card-title">Set your body weight</div>
          <div className="card-sub">
            Hydration, sodium and daily-carb targets need your body weight. Carb targets during
            exercise are shown regardless (they're gut-limited, not weight-based).
          </div>
          <div className="save-row">
            <button className="btn primary" onClick={onGoToSettings}>Go to Settings</button>
          </div>
        </div>
      )}

      {error && <div className="card error">Could not load nutrition: {error}</div>}

      {daily && daily.daily_carb_g != null && (
        <div className="card">
          <div className="card-title">Daily fueling</div>
          <div className="card-sub">
            Scaled to your body weight and current training volume.
          </div>
          <div className="nut-stats">
            <div className="nut-stat">
              <span className="n">{daily.daily_carb_g}</span>
              <span className="lbl">g carbs / day</span>
            </div>
            {daily.pre_race && (
              <div className="nut-stat">
                <span className="n">{daily.pre_race.meal_3h_g}</span>
                <span className="lbl">g pre-race meal (3–4 h)</span>
              </div>
            )}
            {daily.pre_race && (
              <div className="nut-stat">
                <span className="n">{daily.pre_race.snack_1h_g}</span>
                <span className="lbl">g pre-race snack (1 h)</span>
              </div>
            )}
            {daily.recovery_carb_g != null && (
              <div className="nut-stat">
                <span className="n">{daily.recovery_carb_g}</span>
                <span className="lbl">g recovery (within 30 min)</span>
              </div>
            )}
          </div>
        </div>
      )}

      {plan && (
        <div className="card">
          <div className="plan-head">
            <div>
              <div className="card-title">
                Race-day fueling{plan.race.name ? ` — ${plan.race.name}` : ""}
              </div>
              <div className="card-sub">{plan.summary}</div>
            </div>
            {anthropicReady && (
              <button className="btn" onClick={regenerate} disabled={regenerating}>
                {regenerating ? "Generating…" : plan.llm_used ? "Regenerate" : "Generate with AI"}
              </button>
            )}
          </div>

          {plan.llm_used && <div className="small muted" style={{ marginTop: 6 }}>AI-generated timeline · safety-validated</div>}

          {plan.adjustments?.length > 0 && (
            <ul className="nut-adjustments">
              {plan.adjustments.map((a, i) => (
                <li key={i}>{a}</li>
              ))}
            </ul>
          )}

          <div className="nut-timeline">
            {plan.items.map((item, i) => (
              <div className="nut-item" key={i}>
                <span className="nut-dot" style={{ background: PHASE_COLOR[item.phase] ?? "#9aa0ac" }} />
                <span className="nut-time">{fmtOffset(item.offset_min)}</span>
                <div className="nut-body">
                  <div className="nut-line">
                    <span className="nut-label">{item.label}</span>
                    <Amounts item={item} />
                  </div>
                  {item.notes && <div className="nut-notes">{item.notes}</div>}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </>
  );
}
