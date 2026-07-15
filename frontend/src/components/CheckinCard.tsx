import { useState } from "react";
import { api, type CheckinFeel, type CheckinResult } from "../api";

type FeelKey = "energy" | "sleep" | "body" | "stress";

const FEEL_FIELDS: { key: FeelKey; label: string; low: string; high: string }[] = [
  { key: "energy", label: "Energy", low: "flat", high: "charged" },
  { key: "sleep", label: "Sleep", low: "poor", high: "great" },
  { key: "body", label: "Body", low: "beat up", high: "fresh" },
  { key: "stress", label: "Life stress", low: "maxed", high: "calm" },
];

/** One-tap adaptive loop: sync → reconcile → replan next week, narrated.
 * Before running, the athlete can (optionally, 10 seconds) say how the week
 * FELT — the backend reconciles feel against the data and the disagreement
 * is usually the most useful line in the story. Skippable on purpose. */
export function CheckinCard({ onDone }: { onDone: () => void }) {
  const [busy, setBusy] = useState(false);
  const [asking, setAsking] = useState(false);
  const [feel, setFeel] = useState<Partial<Record<FeelKey, number>>>({});
  const [note, setNote] = useState("");
  const [result, setResult] = useState<CheckinResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run(inputs?: CheckinFeel) {
    setAsking(false);
    setBusy(true);
    setError(null);
    try {
      const r = await api.checkin(inputs);
      setResult(r);
      setFeel({});
      setNote("");
      onDone();
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  function submitWithFeel() {
    const inputs: CheckinFeel = {};
    for (const f of FEEL_FIELDS) {
      const v = feel[f.key];
      if (typeof v === "number") inputs[f.key] = v;
    }
    if (note.trim()) inputs.note = note.trim();
    void run(Object.keys(inputs).length ? inputs : undefined);
  }

  return (
    <div className="card" id="tour-checkin">
      <div className="chart-head">
        <div>
          <div className="card-title">Weekly Check-in</div>
          <div className="card-sub">
            Sync Strava, fold in what you actually did, and adapt next week — one tap.
          </div>
        </div>
        <button className="btn primary" disabled={busy} onClick={() => setAsking(true)}>
          {busy ? "Checking in…" : result ? "Run again" : "Run check-in"}
        </button>
      </div>

      {asking && !busy && (
        <div className="feel-form">
          <div className="feel-title">
            How did the week feel? Optional — your answers get reconciled against the data.
          </div>
          {FEEL_FIELDS.map((f) => (
            <div className="feel-row" key={f.key}>
              <span className="feel-label">{f.label}</span>
              <span className="feel-scale" role="group" aria-label={f.label}>
                <span className="feel-end">{f.low}</span>
                {[1, 2, 3, 4, 5].map((v) => (
                  <button
                    key={v}
                    type="button"
                    className={`feel-dot${feel[f.key] === v ? " active" : ""}`}
                    aria-label={`${f.label} ${v} of 5`}
                    aria-pressed={feel[f.key] === v}
                    onClick={() =>
                      setFeel((prev) => ({
                        ...prev,
                        [f.key]: prev[f.key] === v ? undefined : v,
                      }))
                    }
                  >
                    {v}
                  </button>
                ))}
                <span className="feel-end">{f.high}</span>
              </span>
            </div>
          ))}
          <input
            className="feel-note"
            placeholder="Anything else? (a niggle, travel, a brutal work week…)"
            maxLength={280}
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
          <div className="feel-actions">
            <button type="button" className="btn" onClick={() => void run(undefined)}>
              Skip
            </button>
            <button type="button" className="btn primary" onClick={submitWithFeel}>
              Check in
            </button>
          </div>
        </div>
      )}

      {error && <div className="hint">Check-in failed: {error}</div>}
      {result && (
        <ul className="checkin-story">
          {result.story.map((line, i) => (
            <li key={i}>{line}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
