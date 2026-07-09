import { useState } from "react";
import { api, type CheckinResult } from "../api";

/** One-tap adaptive loop: sync → reconcile → replan next week, narrated.
 * The story lines come from the backend so web and (later) iOS tell the
 * same tale. */
export function CheckinCard({ onDone }: { onDone: () => void }) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<CheckinResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      const r = await api.checkin();
      setResult(r);
      onDone();
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
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
        <button className="btn primary" disabled={busy} onClick={run}>
          {busy ? "Checking in…" : result ? "Run again" : "Run check-in"}
        </button>
      </div>
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
