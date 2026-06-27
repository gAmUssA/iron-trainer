import { useEffect, useState } from "react";
import {
  api,
  paceKm,
  pace100,
  type TestProtocol,
  type TestResult,
  type TestPrefill,
} from "../api";
import { MiniSpark } from "./Dashboards";

const SPORT_COLOR: Record<string, string> = { Bike: "#ffb454", Run: "#4ade80", Swim: "#38bdf8" };

/** Time fields are entered as m:ss (or raw seconds); everything else is numeric. */
function parseInput(text: string, unit: string): number | null {
  const t = text.trim();
  if (!t) return null;
  if (unit === "s" && t.includes(":")) {
    const [m, s] = t.split(":");
    return parseInt(m, 10) * 60 + parseInt(s || "0", 10);
  }
  return parseFloat(t);
}

const METRIC_LABEL: Record<string, string> = {
  ftp: "FTP", threshold_hr: "LTHR", threshold_pace_run: "Run threshold", css_swim: "Swim CSS",
};

function fmtMetric(field: string, v: number): string {
  if (field === "ftp") return `${Math.round(v)} W`;
  if (field === "threshold_hr") return `${Math.round(v)} bpm`;
  if (field === "threshold_pace_run") return paceKm(v);
  if (field === "css_swim") return pace100(v);
  return String(v);
}

export function TestsView({ onChanged }: { onChanged: () => void }) {
  const [protocols, setProtocols] = useState<TestProtocol[]>([]);
  const [results, setResults] = useState<TestResult[]>([]);

  const reload = async () => {
    const [t, r] = await Promise.all([api.tests(), api.testResults()]);
    setProtocols(t.tests);
    setResults(r.results);
  };
  useEffect(() => { reload().catch(() => {}); }, []);

  // History sparks: one per metric, oldest→newest.
  const metricSeries: Record<string, { x: string; v: number }[]> = {};
  for (const r of results) {
    for (const [field, val] of Object.entries(r.result)) {
      (metricSeries[field] ||= []).push({ x: r.date.slice(5), v: val });
    }
  }

  return (
    <>
      <div className="card">
        <div className="card-title">Fitness tests</div>
        <div className="card-sub">
          Perform a test to measure your thresholds, then apply them to set your zones.
          Re-test every 4–6 weeks. Add a test to your plan to schedule it (and sync it to Apple Watch).
        </div>
      </div>

      {protocols.map((p) => (
        <ProtocolCard key={p.slug} proto={p} onChanged={() => { reload(); onChanged(); }} />
      ))}

      {Object.keys(metricSeries).length > 0 && (
        <div className="card">
          <div className="card-title">History</div>
          <div className="trend-stack" style={{ marginTop: 12 }}>
            {Object.entries(metricSeries).map(([field, data]) => (
              <MiniSpark
                key={field}
                title={METRIC_LABEL[field] ?? field}
                unit={fmtMetric(field, data[data.length - 1].v)}
                color="#ff5d3b"
                data={data}
                invert={field.includes("pace") || field.includes("css")}
                fmt={(v) => fmtMetric(field, v)}
              />
            ))}
          </div>
        </div>
      )}
    </>
  );
}

function ProtocolCard({ proto, onChanged }: { proto: TestProtocol; onChanged: () => void }) {
  const [vals, setVals] = useState<Record<string, string>>({});
  const [recorded, setRecorded] = useState<TestResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [schedDate, setSchedDate] = useState("");
  const [candidates, setCandidates] = useState<TestPrefill[] | null>(null);
  const color = SPORT_COLOR[proto.sport] ?? "#9aa0ac";

  async function record() {
    setBusy(true);
    setMsg(null);
    try {
      const inputs: Record<string, number | null> = {};
      for (const f of proto.inputs) inputs[f.field] = parseInput(vals[f.field] ?? "", f.unit);
      setRecorded(await api.recordTest(proto.slug, inputs));
    } catch (e) {
      setMsg(`Couldn’t compute: ${e}`);
    } finally {
      setBusy(false);
    }
  }

  async function apply() {
    if (!recorded) return;
    setBusy(true);
    try {
      await api.applyTest(recorded.id);
      setMsg("Applied to your thresholds ✓");
      setRecorded(null);
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  async function addToPlan() {
    if (!schedDate) return;
    setBusy(true);
    setMsg(null);
    try {
      await api.scheduleTest(proto.slug, schedDate);
      setMsg(`Added to your plan on ${schedDate}.`);
      onChanged();
    } catch (e) {
      setMsg(`Couldn’t add to plan: ${e}`);
    } finally {
      setBusy(false);
    }
  }

  async function prefill() {
    setCandidates((await api.testPrefill(proto.slug)).candidates);
  }

  function applyCandidate(c: TestPrefill) {
    const next: Record<string, string> = {};
    for (const f of proto.inputs) {
      const v = c.inputs[f.field];
      next[f.field] = v == null ? "" : String(v);
    }
    setVals(next);
    setCandidates(null);
  }

  return (
    <div className="card" style={{ marginTop: 16 }}>
      <div className="chart-head">
        <div>
          <div className="card-title">
            <span className="sport-badge" style={{ color, borderColor: color, border: `1px solid ${color}`, marginRight: 8 }}>
              {proto.sport.toUpperCase()}
            </span>
            {proto.name}
          </div>
          <div className="card-sub">{proto.description}</div>
        </div>
        <div>
          {proto.due ? (
            <span className="sport-badge" style={{ color: "var(--accent)", border: "1px solid var(--accent)" }}>
              DUE
            </span>
          ) : (
            <span className="card-sub">last {proto.last_tested}</span>
          )}
        </div>
      </div>

      <div className="thr-grid" style={{ marginTop: 16 }}>
        {proto.inputs.map((f) => (
          <label className="field" key={f.field}>
            <span>{f.label} ({f.unit})</span>
            <input
              value={vals[f.field] ?? ""}
              placeholder={f.unit === "s" ? "m:ss" : ""}
              onChange={(e) => setVals({ ...vals, [f.field]: e.target.value })}
            />
          </label>
        ))}
      </div>

      <div className="actions" style={{ marginTop: 14 }}>
        <button className="btn primary" disabled={busy} onClick={record}>Compute</button>
        {proto.prefill_sport && (
          <button className="btn" disabled={busy} onClick={prefill}>Prefill from Strava</button>
        )}
      </div>

      {candidates && (
        <div className="hint" style={{ marginTop: 8 }}>
          {candidates.length === 0 ? "No matching recent activities." : candidates.map((c) => (
            <button className="btn tiny" key={c.activity_id} style={{ marginRight: 6, marginTop: 6 }} onClick={() => applyCandidate(c)}>
              {c.date} · {c.name ?? "activity"}
            </button>
          ))}
        </div>
      )}

      {recorded && (
        <div className="gen-result" style={{ marginTop: 12 }}>
          <p className="summary">
            Computed: {Object.entries(recorded.result).map(([f, v]) => `${METRIC_LABEL[f] ?? f} ${fmtMetric(f, v)}`).join(" · ")}
          </p>
          <button className="btn primary" disabled={busy} onClick={apply}>Apply to thresholds</button>
        </div>
      )}

      <div className="save-row">
        <label className="field" style={{ maxWidth: 180 }}>
          <span>Add to plan on</span>
          <input type="date" value={schedDate} onChange={(e) => setSchedDate(e.target.value)} />
        </label>
        <button className="btn" disabled={busy || !schedDate} onClick={addToPlan}>Add to plan</button>
      </div>

      {msg && <div className="hint">{msg}</div>}
    </div>
  );
}
