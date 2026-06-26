import { useEffect, useMemo, useState } from "react";
import { api, type AppStatus, type Race } from "../api";

const MONTHS = ["", "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December"];

function monthLabel(iso: string): string {
  const [y, m] = iso.split("-");
  return `${MONTHS[parseInt(m, 10)]} ${y}`;
}
function shortDate(iso: string): string {
  const d = new Date(iso + "T00:00:00");
  return d.toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" });
}

export function RaceCard({ status, onChanged }: { status: AppStatus; onChanged: () => void }) {
  const [races, setRaces] = useState<Race[]>([]);
  const [distance, setDistance] = useState<"all" | "70.3" | "140.6">("all");
  const [busy, setBusy] = useState(false);
  const [custom, setCustom] = useState(false);
  const [cName, setCName] = useState("");
  const [cDate, setCDate] = useState("");
  const [cDist, setCDist] = useState("70.3");

  useEffect(() => {
    api.races().then((r) => setRaces(r.races)).catch(() => setRaces([]));
  }, []);

  const filtered = useMemo(
    () => races.filter((r) => distance === "all" || r.distance === distance),
    [races, distance]
  );
  const byMonth = useMemo(() => {
    const m = new Map<string, Race[]>();
    for (const r of filtered) {
      const key = r.date.slice(0, 7);
      (m.get(key) ?? m.set(key, []).get(key)!).push(r);
    }
    return [...m.entries()].sort();
  }, [filtered]);

  async function pick(raceId: number) {
    setBusy(true);
    try {
      await api.setRace({ race_id: raceId });
      onChanged();
    } finally {
      setBusy(false);
    }
  }
  async function saveCustom() {
    if (!cName || !cDate) return;
    setBusy(true);
    try {
      await api.setRace({ name: cName, race_date: cDate, distance: cDist });
      setCustom(false);
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card" id="tour-race" style={{ marginBottom: 16 }}>
      <div className="card-title">Race</div>
      <div className="card-sub">
        Currently: <b style={{ color: "var(--text)" }}>{status.race.name}</b>
        {status.race.date ? ` · ${shortDate(status.race.date)}` : ""}
        {status.race.distance ? ` · ${status.race.distance}` : ""}
      </div>

      <div className="actions" style={{ margin: "14px 0 10px" }}>
        {(["all", "70.3", "140.6"] as const).map((d) => (
          <button
            key={d}
            className={`btn tiny${distance === d ? " primary" : ""}`}
            onClick={() => setDistance(d)}
          >
            {d === "all" ? "All" : d}
          </button>
        ))}
        <button className={`btn tiny${custom ? " primary" : ""}`} onClick={() => setCustom((v) => !v)}>
          Custom race
        </button>
      </div>

      {custom ? (
        <div className="thr-grid" style={{ marginTop: 4 }}>
          <label className="field">
            <span>Race name</span>
            <input value={cName} onChange={(e) => setCName(e.target.value)} placeholder="IRONMAN …" />
          </label>
          <label className="field">
            <span>Date</span>
            <input type="date" value={cDate} onChange={(e) => setCDate(e.target.value)} />
          </label>
          <label className="field">
            <span>Distance</span>
            <select value={cDist} onChange={(e) => setCDist(e.target.value)} className="race-select">
              <option value="70.3">70.3</option>
              <option value="140.6">140.6</option>
            </select>
          </label>
          <div style={{ display: "flex", alignItems: "flex-end" }}>
            <button className="btn primary" onClick={saveCustom} disabled={busy || !cName || !cDate}>
              Set custom race
            </button>
          </div>
        </div>
      ) : (
        <select
          className="race-select"
          value=""
          disabled={busy}
          onChange={(e) => e.target.value && pick(parseInt(e.target.value, 10))}
        >
          <option value="">Select a race from the IRONMAN catalog…</option>
          {byMonth.map(([month, list]) => (
            <optgroup key={month} label={monthLabel(`${month}-01`)}>
              {list.map((r) => (
                <option key={r.id} value={r.id}>
                  {shortDate(r.date)} · {r.name} ({r.distance}){r.country ? ` — ${r.country}` : ""}
                </option>
              ))}
            </optgroup>
          ))}
        </select>
      )}

      <p className="muted small" style={{ marginTop: 10 }}>
        Changing the race updates your countdown, cut-offs and projection. Regenerate your plan to
        match the new date.
      </p>
    </div>
  );
}
