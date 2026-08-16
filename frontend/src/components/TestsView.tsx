import { useEffect, useRef, useState } from "react";
import {
  api,
  pace100,
  type Profile,
  type TestProtocol,
  type TestResult,
  type TestPrefill,
} from "../api";
import {
  useUnits,
  metersToUnit,
  unitToMeters,
  secsToHMS,
  hmsToSecs,
  paceInUnit,
  type DistanceUnit,
} from "../units";
import { MiniSpark } from "./Dashboards";

const SPORT_COLOR: Record<string, string> = { Bike: "#ffb454", Run: "#4ade80", Swim: "#38bdf8" };

/** A displayed value (in the chosen units) → the canonical value the API wants.
    `m` distances → meters; `s` durations → seconds (accepts h:mm:ss / m:ss). */
function toCanonical(text: string, inputUnit: string, distUnit: DistanceUnit): number | null {
  const t = text.trim();
  if (!t) return null;
  if (inputUnit === "m") {
    const n = parseFloat(t);
    return Number.isNaN(n) ? null : unitToMeters(n, distUnit);
  }
  if (inputUnit === "s") return hmsToSecs(t);
  const n = parseFloat(t);
  return Number.isNaN(n) ? null : n;
}

/** A canonical value (meters / seconds / raw) → a display string for the form. */
function toDisplay(value: number | null, inputUnit: string, distUnit: DistanceUnit): string {
  if (value == null) return "";
  if (inputUnit === "m") return metersToUnit(value, distUnit).toFixed(2);
  if (inputUnit === "s") return secsToHMS(value);
  return String(value);
}

/** Field label + placeholder reflect the active units. */
function fieldDisplay(input: { label: string; unit: string }, distUnit: DistanceUnit) {
  if (input.unit === "m") return { label: `Distance covered (${distUnit})`, placeholder: distUnit === "mi" ? "5.91" : "9.5" };
  if (input.unit === "s") return { label: input.label.replace(/\(s\)/i, "(h:mm:ss)"), placeholder: "m:ss" };
  return { label: `${input.label} (${input.unit})`, placeholder: "" };
}

const METRIC_LABEL: Record<string, string> = {
  ftp: "FTP", threshold_hr: "LTHR", threshold_pace_run: "Run threshold", css_swim: "Swim CSS",
};

function fmtMetric(field: string, v: number, distUnit: DistanceUnit): string {
  if (field === "ftp") return `${Math.round(v)} W`;
  if (field === "threshold_hr") return `${Math.round(v)} bpm`;
  if (field === "threshold_pace_run") return paceInUnit(v, distUnit);
  if (field === "css_swim") return pace100(v);
  return String(v);
}

/** The profile fields a test can write, in catalog order. */
const PROFILE_METRICS: { field: keyof Profile; label: string }[] = [
  { field: "ftp", label: "ftp" },
  { field: "threshold_hr", label: "threshold_hr" },
  { field: "threshold_pace_run", label: "threshold_pace_run" },
  { field: "css_swim", label: "css_swim" },
];

export function TestsView({ profile, onChanged }: { profile: Profile | null; onChanged: () => void }) {
  const { unit } = useUnits();
  const [protocols, setProtocols] = useState<TestProtocol[]>([]);
  const [results, setResults] = useState<TestResult[]>([]);
  const [applyingId, setApplyingId] = useState<number | null>(null);
  const [applyError, setApplyError] = useState<string | null>(null);

  const reload = async () => {
    const [t, r] = await Promise.all([api.tests(), api.testResults()]);
    setProtocols(t.tests);
    setResults(r.results);
  };
  useEffect(() => { reload().catch(() => {}); }, []);

  // Apply a PREVIOUSLY recorded result. Without this, a computed test that was
  // never applied in the same visit is orphaned — the Compute-time Apply button
  // is the only one, and it disappears on reload/tab switch.
  async function applyRecorded(id: number) {
    setApplyingId(id);
    setApplyError(null);
    try {
      await api.applyTest(id);
      await reload();
      onChanged();
    } catch (e) {
      setApplyError(`Couldn’t apply: ${e}`);
    } finally {
      setApplyingId(null);
    }
  }

  // Newest first; only the ones that never made it onto the profile.
  const unapplied = [...results].filter((r) => !r.applied).reverse();

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
        {/* Computing a test changes NOTHING until it's applied — show what the
            profile actually holds right now so "did that stick?" is answerable
            here instead of in Settings. */}
        {profile && (
          <div className="rd-splits" style={{ marginTop: 14 }}>
            {PROFILE_METRICS.map(({ field, label }) => {
              const v = profile[field] as number | null;
              return (
                <div className="rd-split" key={field}>
                  <div className="k">{METRIC_LABEL[label] ?? label}</div>
                  <div className="v" style={v == null ? { color: "var(--dim)" } : undefined}>
                    {v == null ? "—" : fmtMetric(label, v, unit)}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {unapplied.length > 0 && (
        <div className="card" style={{ marginTop: 16 }}>
          <div className="card-title">Recorded — not applied yet</div>
          <div className="card-sub">
            These tests were computed but never written to your thresholds, so they aren’t
            driving your zones, TSS or workout targets.
          </div>
          {unapplied.map((r) => (
            <div className="save-row" key={r.id}>
              <span className="card-sub" style={{ flex: 1 }}>
                {r.date} · {r.sport} ·{" "}
                {Object.entries(r.result)
                  .map(([f, v]) => `${METRIC_LABEL[f] ?? f} ${fmtMetric(f, v, unit)}`)
                  .join(" · ")}
              </span>
              <button
                className="btn primary"
                disabled={applyingId != null}
                onClick={() => applyRecorded(r.id)}
              >
                {applyingId === r.id ? "Applying…" : "Apply to thresholds"}
              </button>
            </div>
          ))}
          {applyError && <div className="hint">{applyError}</div>}
        </div>
      )}

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
                unit={fmtMetric(field, data[data.length - 1].v, unit)}
                color="#ff5d3b"
                data={data}
                invert={field.includes("pace") || field.includes("css")}
                fmt={(v) => fmtMetric(field, v, unit)}
              />
            ))}
          </div>
        </div>
      )}
    </>
  );
}

function ProtocolCard({ proto, onChanged }: { proto: TestProtocol; onChanged: () => void }) {
  const { unit } = useUnits();
  const [vals, setVals] = useState<Record<string, string>>({});
  const [recorded, setRecorded] = useState<TestResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [schedDate, setSchedDate] = useState("");
  const [candidates, setCandidates] = useState<TestPrefill[] | null>(null);
  const color = SPORT_COLOR[proto.sport] ?? "#9aa0ac";

  // Re-convert already-entered distance fields when the unit preference flips.
  const prevUnit = useRef(unit);
  useEffect(() => {
    if (prevUnit.current === unit) return;
    const from = prevUnit.current;
    prevUnit.current = unit;
    setVals((cur) => {
      const next = { ...cur };
      for (const f of proto.inputs) {
        if (f.unit === "m" && cur[f.field]?.trim()) {
          const n = parseFloat(cur[f.field]);
          if (!Number.isNaN(n)) next[f.field] = metersToUnit(unitToMeters(n, from), unit).toFixed(2);
        }
      }
      return next;
    });
  }, [unit, proto.inputs]);

  async function record() {
    setBusy(true);
    setMsg(null);
    try {
      const inputs: Record<string, number | null> = {};
      for (const f of proto.inputs) inputs[f.field] = toCanonical(vals[f.field] ?? "", f.unit, unit);
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
    setMsg(null);
    try {
      const res = await api.applyTest(recorded.id);
      // Without a catch a failed apply was completely silent: no message, the
      // button just re-enabled and the thresholds never moved.
      setMsg(
        res.plan_weeks_refreshed
          ? `Applied ✓ — zones, TSS and ${res.plan_weeks_refreshed} upcoming plan week(s) re-targeted`
          : "Applied to your thresholds ✓"
      );
      setRecorded(null);
      onChanged();
    } catch (e) {
      setMsg(`Couldn’t apply: ${e}`);
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
    for (const f of proto.inputs) next[f.field] = toDisplay(c.inputs[f.field], f.unit, unit);
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
        {proto.inputs.map((f) => {
          const fd = fieldDisplay(f, unit);
          return (
            <label className="field" key={f.field}>
              <span>{fd.label}</span>
              <input
                value={vals[f.field] ?? ""}
                placeholder={fd.placeholder}
                onChange={(e) => setVals({ ...vals, [f.field]: e.target.value })}
              />
            </label>
          );
        })}
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
            Computed: {Object.entries(recorded.result).map(([f, v]) => `${METRIC_LABEL[f] ?? f} ${fmtMetric(f, v, unit)}`).join(" · ")}
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
