import { type ChangeEvent, useEffect, useRef, useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import {
  api,
  pollJob,
  timeAgo,
  type AppStatus,
  type AthleteResponse,
  type HrZones,
  type JobsSummary,
  type Profile,
} from "../api";

/** "Connect iOS app": mint a pairing code and show it as a QR the helper app scans. */
function ConnectAppPairing() {
  const [code, setCode] = useState<string | null>(null);
  // Count down from the server's TTL locally — immune to client-clock skew,
  // and seeded before render so a fresh code never flashes as "expired".
  const [left, setLeft] = useState(0);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!code || left <= 0) return;
    const t = setInterval(() => setLeft((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(t);
  }, [code, left > 0]); // eslint-disable-line react-hooks/exhaustive-deps

  async function generate() {
    setBusy(true);
    setErr(null);
    try {
      const r = await api.createPairingCode();
      setLeft(r.expires_in ?? Math.max(1, Math.round(r.expires_at - Date.now() / 1000)));
      setCode(r.code);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  const expired = code != null && left <= 0;
  const payload =
    code && `irontrainer://pair?server=${encodeURIComponent(window.location.origin)}&code=${code}`;

  return (
    <div style={{ marginTop: 16, borderTop: "1px solid var(--border)", paddingTop: 14 }}>
      <div className="card-label">Connect iOS app</div>
      {!code || expired ? (
        <>
          <div className="hint" style={{ marginTop: 6 }}>
            Scan a one-time code with the Iron Trainer iOS app to sync your plan to Apple Watch.
          </div>
          <div className="actions" style={{ marginTop: 10 }}>
            <button className="btn" disabled={busy} onClick={generate}>
              {busy ? "Working…" : expired ? "New code" : "Connect iOS app"}
            </button>
          </div>
          {err && <div className="hint">Couldn’t create a code: {err}</div>}
        </>
      ) : (
        <div style={{ display: "flex", gap: 16, alignItems: "center", marginTop: 10 }}>
          <div style={{ background: "#fff", padding: 8, borderRadius: 10 }}>
            <QRCodeSVG value={payload as string} size={120} />
          </div>
          <div>
            <div className="mono" style={{ fontSize: 22, letterSpacing: "0.12em" }}>{code}</div>
            <div className="hint" style={{ marginTop: 4 }}>
              In the app: Settings → Scan QR (or enter the code). Expires in {left}s.
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export function ConnectCard({
  status,
  athlete,
  onSynced,
}: {
  status: AppStatus;
  athlete: AthleteResponse;
  onSynced: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [jobs, setJobs] = useState<JobsSummary | null>(null);

  const refreshJobs = () => { api.jobsSummary().then(setJobs).catch(() => {}); };
  // athlete is replaced on every app-wide refresh (sync/check-in/etc.), so it
  // doubles as the "something happened, re-read job history" signal.
  useEffect(refreshJobs, [athlete]);

  // A page load mid-job must still observe completion: watch every active job
  // (any kind) to its end, then refresh the app — without this, the
  // "running in background" line lingers and the data stays stale forever.
  const watched = useRef<Set<number>>(new Set());
  useEffect(() => {
    for (const j of Object.values(jobs?.active ?? {})) {
      if (watched.current.has(j.id)) continue;
      watched.current.add(j.id);
      pollJob(j.id)
        .catch(() => {})
        .finally(() => {
          watched.current.delete(j.id);
          refreshJobs();
          onSynced();
        });
    }
  }, [jobs]); // eslint-disable-line react-hooks/exhaustive-deps

  /** Newest Strava-touching call across sync/import/dedup/check-in jobs. */
  const lastStravaCall = (() => {
    const kinds = ["sync", "import", "dedup", "checkin"];
    const times = kinds
      .map((k) => jobs?.latest[k]?.finished_at)
      .filter((t): t is string => !!t)
      .sort();
    return times.length ? timeAgo(times[times.length - 1]) : null;
  })();
  const activeKinds = Object.keys(jobs?.active ?? {});

  async function doSync(full: boolean) {
    setBusy(true);
    setMsg(null);
    try {
      const r = await api.sync(full);
      setMsg(
        `Synced ${r.fetched} activities (${r.total_activities} total)` +
          (r.duplicates_removed ? ` · ${r.duplicates_removed} duplicates removed` : "") +
          (r.pruned_old ? ` · ${r.pruned_old} older than retention pruned` : "") +
          (r.profile_seeded ? " · thresholds inferred" : "")
      );
      onSynced();
    } catch (e) {
      setMsg(`Sync failed: ${e}`);
    } finally {
      setBusy(false);
      refreshJobs();
    }
  }

  async function doDedup() {
    setBusy(true);
    setMsg(null);
    try {
      const r = await api.dedup(true);
      const more = r.device_remaining ? ` · ${r.device_remaining} lookups left — click again` : "";
      setMsg(`De-dup: ${r.duplicates} duplicates across ${r.clusters} events${more}`);
      onSynced();
    } catch (e) {
      setMsg(`De-dup failed: ${e}`);
    } finally {
      setBusy(false);
      refreshJobs();
    }
  }

  async function doImport(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-selecting the same file
    if (!file) return;
    setBusy(true);
    setMsg("Importing archive… (large exports can take a while)");
    try {
      const r = await api.importArchive(file);
      setMsg(
        `Imported ${r.parsed} activities (${r.with_streams} with power streams)` +
          (r.duplicates_removed ? ` · ${r.duplicates_removed} duplicates removed` : "") +
          (r.profile_seeded ? " · thresholds inferred" : "")
      );
      onSynced();
    } catch (err) {
      setMsg(`Import failed: ${err}`);
    } finally {
      setBusy(false);
      refreshJobs();
    }
  }

  async function doDisconnect() {
    if (!window.confirm(
      "Disconnect Strava and delete your synced activities and derived data? " +
      "Your manually-entered thresholds are kept. This revokes the app's Strava access."
    )) return;
    setBusy(true);
    setMsg(null);
    try {
      const r = await api.disconnect();
      setMsg(`${r.message} (${r.deleted_activities} activities, ${r.deleted_metrics} metric days removed.)`);
      onSynced();
    } catch (e) {
      setMsg(`Disconnect failed: ${e}`);
    } finally {
      setBusy(false);
    }
  }

  const rows = [
    { on: status.strava_configured, label: "Strava API keys configured" },
    {
      on: athlete.connected,
      label: `Strava account connected${athlete.profile.name ? ` (${athlete.profile.name})` : ""}`,
    },
    { on: status.anthropic_configured, label: "Claude (AI planner) configured" },
  ];

  return (
    <div className="card" id="tour-setup" style={{ display: "flex", flexDirection: "column" }}>
      <div className="card-label">Setup</div>
      <div className="status-list">
        {rows.map((r, i) => (
          <div className={`status-row${r.on ? " on" : ""}`} key={i}>
            <span className={`status-dot${r.on ? " ok" : ""}`} />
            <span className="status-text">{r.label}</span>
          </div>
        ))}
      </div>

      <div className="actions" style={{ marginTop: 18, alignItems: "center" }}>
        {!athlete.connected ? (
          <a href={api.connectUrl} aria-label="Connect with Strava"
             style={{ pointerEvents: status.strava_configured ? "auto" : "none", opacity: status.strava_configured ? 1 : 0.5 }}>
            <img src="/strava/btn_strava_connect.svg" alt="Connect with Strava" style={{ height: 44 }} />
          </a>
        ) : (
          <>
            <button className="btn primary" disabled={busy} onClick={() => doSync(false)}>
              {busy ? "Working…" : "Sync new"}
            </button>
            <button className="btn" disabled={busy} onClick={() => doSync(true)}>
              Full backfill
            </button>
            <button className="btn" disabled={busy} onClick={doDedup} title="Bike → Garmin Edge, swim/run → Apple Watch">
              Re-run de-dup
            </button>
            {athlete.profile.strava_athlete_id && (
              <a className="strava-link" href={`https://www.strava.com/athletes/${athlete.profile.strava_athlete_id}`}
                 target="_blank" rel="noopener noreferrer">
                View on Strava
              </a>
            )}
            <button className="btn danger" disabled={busy} onClick={doDisconnect}
                    title="Revoke Strava access and delete your Strava data">
              Disconnect & delete data
            </button>
          </>
        )}
      </div>

      <div className="actions" style={{ marginTop: 10, alignItems: "center" }}>
        <label className={`btn${busy ? " disabled" : ""}`} title="Bulk-load your full history from a Strava data export">
          Import Strava archive (.zip)
          <input type="file" accept=".zip,application/zip" disabled={busy} onChange={doImport}
                 style={{ display: "none" }} />
        </label>
        <a className="strava-link" href="https://www.strava.com/athlete/delete_your_account"
           target="_blank" rel="noopener noreferrer" title="Strava → Settings → Download your data">
          How to get your archive
        </a>
      </div>

      <div className="hint">
        {msg ??
          (status.strava_configured
            ? "Sync pulls recent history; or bulk-load everything from a Strava data export (bypasses API limits)."
            : "Add STRAVA_CLIENT_ID / STRAVA_CLIENT_SECRET to .env and restart the backend.")}
      </div>
      {(lastStravaCall || activeKinds.length > 0) && (
        <div className="hint">
          {activeKinds.length > 0
            ? `⏳ Running in background: ${activeKinds.join(", ")} — safe to leave this page.`
            : `Strava last called ${lastStravaCall}.`}
        </div>
      )}

      {status.authenticated && <ConnectAppPairing />}
    </div>
  );
}

// Returns NaN for non-empty text that doesn't parse — save() refuses NaN instead
// of silently sending garbage (JSON.stringify would turn NaN into null).
function parsePace(text: string): number | null {
  const t = text.trim();
  if (!t) return null;
  if (t.includes(":")) {
    const match = /^(\d+):([0-5]\d)$/.exec(t); // strict m:ss — "4:99" must fail, not normalize
    if (!match) return NaN;
    return parseInt(match[1], 10) * 60 + parseInt(match[2], 10);
  }
  return parseFloat(t);
}
function parseNum(text: string, int = false): number | null {
  const t = text.trim();
  if (!t) return null;
  return int ? parseInt(t, 10) : parseFloat(t);
}
function fmtPace(sec: number | null): string {
  if (!sec) return "";
  const total = Math.round(sec); // round first so :60 can't appear
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

export function ProfileEditor({ profile, onSaved }: { profile: Profile; onSaved: () => void }) {
  const [ftp, setFtp] = useState(profile.ftp?.toString() ?? "");
  const [thr, setThr] = useState(profile.threshold_hr?.toString() ?? "");
  const [maxhr, setMaxhr] = useState(profile.max_hr?.toString() ?? "");
  const [runPace, setRunPace] = useState(fmtPace(profile.threshold_pace_run));
  const [css, setCss] = useState(fmtPace(profile.css_swim));
  const [hours, setHours] = useState(profile.weekly_hours_target?.toString() ?? "");
  const [weight, setWeight] = useState(profile.body_weight_kg?.toString() ?? "");
  const [gelCarb, setGelCarb] = useState(profile.gel_carb_g?.toString() ?? "");
  const [sweat, setSweat] = useState(profile.sweat_rate_l_h?.toString() ?? "");
  const [gi, setGi] = useState(profile.gi_tolerance ?? "");
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [savedNote, setSavedNote] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  async function save() {
    const body = {
      ftp: parseNum(ftp),
      threshold_hr: parseNum(thr, true),
      max_hr: parseNum(maxhr, true),
      threshold_pace_run: parsePace(runPace),
      css_swim: parsePace(css),
      weekly_hours_target: parseNum(hours),
      body_weight_kg: parseNum(weight),
      gel_carb_g: parseNum(gelCarb),
      sweat_rate_l_h: parseNum(sweat),
      gi_tolerance: gi || null,
    };
    const bad = Object.values(body).some((v) => typeof v === "number" && !Number.isFinite(v));
    if (bad) {
      setSaveError("Check the highlighted formats — numbers only, paces as m:ss.");
      return;
    }
    setSaving(true);
    setSaved(false);
    setSaveError(null);
    try {
      const res = await api.updateProfile(body);
      onSaved();
      setSavedNote(
        res.plan_weeks_refreshed
          ? `Saved — upcoming workouts re-targeted (${res.plan_weeks_refreshed} weeks)`
          : null
      );
      setSaved(true);
      window.setTimeout(() => setSaved(false), 3600);
    } catch (e) {
      setSaveError(`Save failed: ${e}`);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="card" id="tour-thresholds" style={{ maxWidth: 760 }}>
      <div className="card-title">Thresholds</div>
      <div className="card-sub">
        Seeded from your last ~12 weeks of Strava. Edit to refine — these drive TSS, training zones and
        the race projection.
      </div>
      <div className="thr-grid">
        <Field label="FTP (W)" value={ftp} onChange={setFtp} placeholder="e.g. 228" />
        <Field label="Threshold HR" value={thr} onChange={setThr} placeholder="bpm" />
        <Field label="Max HR" value={maxhr} onChange={setMaxhr} placeholder="bpm" />
        <Field label="Run threshold pace /km" value={runPace} onChange={setRunPace} placeholder="m:ss" />
        <Field label="Swim CSS /100m" value={css} onChange={setCss} placeholder="m:ss" />
        <Field label="Weekly hours" value={hours} onChange={setHours} placeholder="e.g. 9" />
      </div>
      <div className="save-row">
        <button className="btn primary" onClick={save} disabled={saving}>
          {saving ? "Saving…" : "Save thresholds"}
        </button>
        {saved && (
          <span className="saved-note">
            <span className="dot" />{savedNote ?? "Saved — zones & projection recomputed"}
          </span>
        )}
        {saveError && <span className="hint">{saveError}</span>}
      </div>

      <div className="card-title" style={{ marginTop: 26 }}>Nutrition</div>
      <div className="card-sub">
        Body weight is the only required input — it drives hydration, sodium and daily-carb targets.
        Everything else has sensible defaults.
        {weight ? ` (~${(parseFloat(weight) * 2.2046).toFixed(0)} lb)` : ""}
      </div>
      <div className="thr-grid">
        <Field label="Body weight (kg)" value={weight} onChange={setWeight} placeholder="e.g. 70" />
        <Field label="Carbs per gel (g)" value={gelCarb} onChange={setGelCarb} placeholder="default 25" />
        <Field label="Measured sweat rate (L/h)" value={sweat} onChange={setSweat} placeholder="optional" />
        <label className="field">
          <span>GI tolerance</span>
          <select value={gi} onChange={(e) => setGi(e.target.value)}>
            <option value="">default (medium)</option>
            <option value="low">low</option>
            <option value="medium">medium</option>
            <option value="high">high</option>
          </select>
        </label>
      </div>
    </div>
  );
}

/** HR-zone calculator: pure derivation of the saved thresholds, refetched
 * whenever LTHR / max HR change. Z1–Z5 are the same vocabulary the planner
 * uses in workout descriptions. */
export function ZonesCard({ thresholdHr, maxHr }: { thresholdHr: number | null; maxHr: number | null }) {
  const [zones, setZones] = useState<HrZones | null>(null);

  useEffect(() => {
    let alive = true;
    api.zones().then((z) => { if (alive) setZones(z); }).catch(() => {});
    return () => { alive = false; };
  }, [thresholdHr, maxHr]);

  if (!zones) return null;
  if (!zones.zones.length) {
    return (
      <div className="card" style={{ maxWidth: 760 }}>
        <div className="card-title">Heart-Rate Zones</div>
        <div className="card-sub">
          Set your Threshold HR (or Max HR) above and the five training zones are computed
          automatically — the planner prescribes workouts in these zones.
        </div>
      </div>
    );
  }
  const zoneColors = ["#4ade80", "#38bdf8", "#ffb454", "#fb923c", "#f87171"];
  return (
    <div className="card" style={{ maxWidth: 760 }}>
      <div className="card-title">Heart-Rate Zones</div>
      <div className="card-sub">
        {zones.basis === "lthr"
          ? `From your threshold HR of ${zones.threshold_hr} bpm (Coggan LTHR model).`
          : `Estimated from your max HR of ${zones.max_hr} bpm — set a threshold HR (LTHR test) for sharper zones.`}
        {" "}The plan's Z1–Z5 labels refer to these ranges.
      </div>
      <div className="zone-table">
        {zones.zones.map((z, i) => (
          <div className="zone-row" key={z.zone}>
            <span className="zone-chip" style={{ background: zoneColors[i] }}>{z.zone}</span>
            <span className="zone-name">{z.name}</span>
            <span className="zone-range mono">{z.low}–{z.high} bpm</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function Field({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <input value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
    </label>
  );
}


/** Health data ingest: mint a bearer token for the Health Auto Export app and
 * show the exact setup. The token is displayed ONCE — only its hash is stored. */
export function HealthIngestCard() {
  const [token, setToken] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const url = `${window.location.origin}/api/health/ingest`;

  async function mint() {
    setBusy(true);
    setError(null);
    try {
      const out = await api.ingestToken();
      setToken(out.token);
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card">
      <div className="card-title">Health data (Apple Health)</div>
      <div className="card-sub" style={{ marginBottom: 10 }}>
        Sleep, HRV and resting heart rate feed your readiness call. Push them from your
        phone with the Health Auto Export app (Premium) — no third-party APIs involved.
      </div>
      {!token ? (
        <button className="btn primary" type="button" disabled={busy} onClick={mint}>
          {busy ? "Working…" : "Create ingest token"}
        </button>
      ) : (
        <ol className="ingest-steps">
          <li>In Health Auto Export: Automations → New → <b>REST API</b></li>
          <li>URL: <code>{url}</code></li>
          <li>Add a header named <code>Authorization</code> with value{" "}
            <code>Bearer {token}</code>
            <button className="btn tiny" type="button" style={{ marginLeft: 8 }}
              onClick={() => void navigator.clipboard?.writeText(`Bearer ${token}`)}>
              Copy value
            </button>
          </li>
          <li>Select Sleep Analysis, Heart Rate Variability, Resting Heart Rate ·
            format <b>JSON</b> · aggregate <b>days</b> · <b>Summarize ON</b> · <b>Batch ON</b></li>
        </ol>
      )}
      {token && (
        <div className="hint">Shown once — we store only a hash. Re-mint any time; revoke
          all tokens from the disconnect flow.</div>
      )}
      {error && <div className="hint">Failed: {error}</div>}
    </div>
  );
}
