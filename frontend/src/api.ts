// Thin typed client for the Iron Trainer API. Uses same-origin /api (proxied
// to the backend in dev by vite.config.ts).

export interface AppStatus {
  version: string;
  race: { name: string; date: string; distance?: string | null };
  strava_configured: boolean;
  anthropic_configured: boolean;
  auth_required: boolean;
  authenticated: boolean;
}

export interface Me {
  authenticated: boolean;
  auth_required: boolean;
  athlete: { name: string | null; strava_athlete_id: number | null } | null;
}

export interface Race {
  id: number;
  slug: string;
  name: string;
  date: string;
  distance: string;
  city: string | null;
  country: string | null;
  cutoff_swim_s: number | null;
  cutoff_bike_s: number | null;
  cutoff_finish_s: number | null;
}

export interface Profile {
  strava_athlete_id: number | null;
  name: string | null;
  ftp: number | null;
  threshold_hr: number | null;
  max_hr: number | null;
  threshold_pace_run: number | null; // sec/km
  css_swim: number | null; // sec/100m
  weekly_hours_target: number | null;
  body_weight_kg: number | null;
  gel_carb_g: number | null; // carbs per gel (default 25)
  sweat_rate_l_h: number | null; // measured override, else estimated
  gi_tolerance: string | null; // "low" | "medium" | "high"
  updated_at: string | null;
}

export interface WorkoutFueling {
  needed: boolean;
  duration_s: number;
  note?: string;
  intensity?: string;
  carb_g_h?: number;
  carb_total_g?: number;
  mtc_required?: boolean;
  gel_carb_g?: number;
  gels_per_hour?: number;
  gels_total?: number;
  high_carb_gels_total?: number;
  sweat_rate_l_h?: number | null;
  fluid_ml_h?: number | null;
  fluid_total_ml?: number | null;
  sodium_mg_h?: number | null;
  sodium_total_mg?: number | null;
  recovery_carb_g?: number;
}

export interface RaceDayItem {
  phase: string;
  offset_min?: number;
  label: string;
  carbs_g: number | null;
  fluid_ml: number | null;
  sodium_mg: number | null;
  phase_duration_s?: number;
  notes: string | null;
}
export interface RaceDayPlan {
  race: { name: string | null; date: string | null; distance: string | null };
  summary: string;
  gel_carb_g: number;
  items: RaceDayItem[];
  llm_used: boolean;
  adjustments: string[];
}
export interface DailyNutrition {
  body_weight_kg: number | null;
  weekly_hours?: number;
  daily_carb_g: number | null;
  pre_race?: { meal_3h_g: number; snack_1h_g: number };
  recovery_carb_g?: number;
  note?: string;
}

export interface AthleteResponse {
  connected: boolean;
  profile: Profile;
  plan_weeks_refreshed?: number; // future weeks re-derived after a threshold change
}

export interface PmcDay {
  date: string;
  tss: number;
  ctl: number;
  atl: number;
  tsb: number;
}

export interface SportVolume {
  hours: number;
  distance_km: number;
  tss: number;
}
export interface WeekVolume {
  week_start: string;
  by_sport: Record<string, SportVolume>;
  total_hours: number;
  total_tss: number;
}

export interface RollingPoint {
  date: string;
  value: number;
}
export interface SportInsight {
  metric: string; // which series the verdict was computed on (ef | power | pace)
  change_pct: number | null; // % move over the 12-week regression window
  verdict: "improving" | "declining" | "steady" | "insufficient data";
  rolling: RollingPoint[]; // 28-day rolling mean of the charted metric
  rolling_ef: RollingPoint[];
}
export interface IntensityWeek {
  week_start: string;
  easy: number;
  endurance: number;
  tempo: number;
  hard: number;
  unknown: number;
}
export interface PrEntry {
  date: string;
  name: string | null;
  value: number;
}
export interface CtlTrajectory {
  current: number;
  ramp_per_week: number | null;
  race_date?: string;
  weeks_to_race?: number;
  race_day_projection?: number;
  projection?: { date: string; ctl: number }[];
}
export interface TrendInsights {
  sports: Record<"Bike" | "Run" | "Swim", SportInsight>;
  intensity_weeks: IntensityWeek[];
  prs: Record<string, PrEntry | null>;
  ctl_trajectory: CtlTrajectory | null;
  freshness: { last_activity: string | null; days_stale: number | null };
}
export interface Trends {
  Bike: { date: string; power: number; hr: number | null; ef: number | null }[];
  Run: { date: string; pace: number; hr: number | null; ef: number | null }[];
  Swim: { date: string; pace: number; hr: number | null }[];
  insights: TrendInsights;
  window_days?: number;
}

/** Chart time windows, in days; 0 = full history. Backend default is 180. */
export const CHART_RANGES = [
  { days: 90, label: "3m" },
  { days: 180, label: "6m" },
  { days: 365, label: "1y" },
  { days: 0, label: "All" },
] as const;
export const DEFAULT_CHART_DAYS = 180;

export interface Leg {
  seconds: number;
  display: string;
}
export interface CutoffCheck {
  checkpoint: string;
  limit_s: number;
  limit: string;
  projected_s: number | null;
  projected?: string;
  margin_s?: number;
  margin?: string;
  ok: boolean | null;
}
export interface Readiness {
  legs: Record<string, Leg>;
  transitions: Leg;
  total: Leg | null;
  current_ctl: number | null;
  missing: string[];
  cutoffs: CutoffCheck[];
  note: string;
}

/** Daily readiness call from acute:chronic load (go hard / go easy / rest). */
export interface ReadinessToday {
  status: "ok" | "insufficient_data";
  call: "hard" | "easy" | "rest" | null;
  level: "green" | "amber" | "red" | null;
  acwr?: number;
  acute_7d?: number;
  chronic_weekly?: number;
  tsb?: number | null;
  ctl?: number | null;
  hard_day_streak?: number;
  reasons: string[];
}

/** One ingested day of recovery data (Health Auto Export). */
export interface RecoveryDay {
  date: string;
  sleep_h: number | null;
  deep_h: number | null;
  rem_h: number | null;
  awake_h: number | null;
  hrv_ms: number | null;
  rhr_bpm: number | null;
  weight_kg: number | null;
}

export interface SyncResult {
  fetched: number;
  upserted: number;
  total_activities: number;
  metrics_days: number;
  profile_seeded: boolean;
  duplicates_removed?: number;
  device_remaining?: number;
  pruned_old?: number;
}

export interface DedupResult {
  clusters: number;
  duplicates: number;
  device_fetched: number;
  device_remaining: number;
  metrics_days: number;
}

export interface ImportResult {
  parsed: number;
  upserted: number;
  with_streams: number;
  pruned_old: number;
  total_activities: number;
  duplicates_removed: number;
  metrics_days: number;
  profile_seeded: boolean;
}

export interface PlanWeek {
  week_index: number;
  week_start: string;
  phase: string;
  is_recovery: boolean;
  focus: string;
  target_hours: number;
  target_tss?: number | null;
}
export interface PlannedWorkout {
  id: number;
  date: string;
  sport: string;
  title: string;
  description: string | null;
  duration_s: number | null;
  distance_m: number | null;
  planned_tss: number | null;
  intensity: string | null;
  status?: string | null; // planned | completed | skipped
  matched_activity_id?: number | null;
  steps: unknown[];
}

export interface WeekCompliance {
  week_start: string;
  planned_tss: number;
  actual_tss: number;
  planned_hours: number;
  actual_hours: number;
  completed: number;
  skipped: number;
  planned: number;
}
export interface RecentCompliance {
  completion_rate: number | null;
  load_ratio: number | null;
  completed_sessions: number;
  skipped_sessions: number;
  planned_sessions: number;
}
/** Subjective check-in inputs — 1-5, higher is better, all optional. */
export interface CheckinFeel {
  energy?: number;
  sleep?: number;
  body?: number;
  stress?: number;
  note?: string;
}

export interface CheckinResult {
  status?: "ok" | "no_plan";
  story: string[];
  synced: { fetched: number; total: number } | null;
  reconcile?: {
    matched: { completed: number; skipped: number; upcoming: number };
    compliance: RecentCompliance;
    weeks_replanned: string[];
    form_flag: string;
  };
  tests_due?: {
    slug: string;
    name: string;
    sport: string;
    last_tested: string | null;
    days_ago: number | null;
  }[];
  next_week?: { week_start: string; hours_before: number; hours_after: number };
  key_sessions?: { date: string; sport: string; title: string; duration_s: number | null }[];
}
export interface ReconcileResult {
  matched: { completed: number; skipped: number; upcoming: number };
  compliance: RecentCompliance;
  weeks_replanned: string[];
  form_flag: string;
}
export interface PlanResponse {
  plan: {
    id: number;
    summary: string;
    weeks: PlanWeek[];
    base_weekly_hours?: number | null; // hours target the plan was generated for
  } | null;
  workouts: PlannedWorkout[];
}
export interface GenerateResult {
  plan_id: number;
  llm_used: boolean;
  weeks: number;
  workouts: number;
  adjustments: string[];
  summary: string;
}

/** Build an error that carries the backend's reason (FastAPI `{"detail": ...}`),
 * not just "400 Bad Request" — import/validation failures are actionable. */
async function httpError(res: Response): Promise<Error> {
  let detail = "";
  try {
    const text = await res.text();
    try {
      const parsed = JSON.parse(text);
      detail = typeof parsed.detail === "string" ? parsed.detail : text;
    } catch {
      detail = text;
    }
  } catch {
    /* body unreadable — fall through to statusText */
  }
  return new Error(`${res.status} ${(detail || res.statusText).slice(0, 300)}`);
}

async function getJSON<T>(path: string): Promise<T> {
  const res = await fetch(path, { headers: { Accept: "application/json" }, credentials: "include" });
  if (!res.ok) throw await httpError(res);
  return res.json() as Promise<T>;
}

async function send<T>(path: string, method: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    credentials: "include",
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw await httpError(res);
  return res.json() as Promise<T>;
}

export interface TestInputSpec {
  field: string;
  label: string;
  unit: string;
}
export interface TestProtocol {
  slug: string;
  name: string;
  sport: string;
  measures: string[];
  description: string;
  inputs: TestInputSpec[];
  prefill_sport: string | null;
  last_tested: string | null;
  due: boolean;
}
export interface TestResult {
  id: number;
  test_slug: string;
  sport: string;
  date: string;
  inputs: Record<string, number | null>;
  result: Record<string, number>;
  applied: boolean;
}
export interface TestPrefill {
  activity_id: number;
  date: string;
  name: string | null;
  inputs: Record<string, number | null>;
}

export interface HrZone {
  zone: string;
  name: string;
  low: number;
  high: number;
}
export interface HrZones {
  basis: "lthr" | "max_hr" | null;
  zones: HrZone[];
  threshold_hr: number | null;
  max_hr: number | null;
}

export interface JobInfo {
  id: number;
  kind: string;
  status: "queued" | "running" | "succeeded" | "failed";
  created_at: string | null;
  started_at: string | null;
  finished_at: string | null;
  result: unknown;
  error: string | null;
  already_running?: boolean;
}
export interface JobsSummary {
  latest: Record<string, JobInfo>;
  active: Record<string, JobInfo>;
}

/** Poll a background job until it's terminal; resolve with its result or
 * throw its error. Interval is gentle — these are 10s–minutes operations —
 * and a few dropped polls must not fail an operation that's still running
 * server-side (one blip over a 10-minute loop is near-certain). */
export async function pollJob<T>(jobId: number, intervalMs = 1500, timeoutMs = 10 * 60_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let misses = 0;
  for (;;) {
    try {
      const job = await getJSON<JobInfo>(`/api/jobs/${jobId}`);
      misses = 0;
      if (job.status === "succeeded") return job.result as T;
      if (job.status === "failed") throw new Error(job.error ?? "operation failed");
    } catch (e) {
      // Definitive answers about THIS request (bad id, lost auth) fail fast;
      // 5xx/429 are proxy/overload noise and count toward the miss budget —
      // a lone 502 must not fail a job that's still running server-side.
      if (e instanceof Error && /^(401|403|404|422) /.test(e.message)) {
        throw e;
      }
      if (++misses > 4) throw e; // 5 consecutive failures — give up
    }
    if (Date.now() > deadline) throw new Error("operation timed out — check back later");
    await new Promise((r) => setTimeout(r, intervalMs));
  }
}

/** Start an async operation and wait for its result via job polling. */
async function viaJob<T>(start: () => Promise<{ job: JobInfo }>): Promise<T> {
  const { job } = await start();
  return pollJob<T>(job.id);
}

/** "3 min ago" / "2 h ago" / "5 d ago" from an ISO timestamp. */
export function timeAgo(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const ms = Date.now() - new Date(iso.endsWith("Z") || iso.includes("+") ? iso : iso + "Z").getTime();
  if (!Number.isFinite(ms) || ms < 0) return null;
  const min = Math.round(ms / 60_000);
  if (min < 1) return "just now";
  if (min < 60) return `${min} min ago`;
  const h = Math.round(min / 60);
  if (h < 48) return `${h} h ago`;
  return `${Math.round(h / 24)} d ago`;
}

export const api = {
  status: () => getJSON<AppStatus>("/api/status"),
  me: () => getJSON<Me>("/api/me"),
  logout: () => send<{ ok: boolean }>("/api/auth/logout", "POST"),
  createPairingCode: () =>
    send<{ code: string; expires_at: number; expires_in?: number }>(
      "/api/device/pairing-code", "POST", {}),
  athlete: () => getJSON<AthleteResponse>("/api/athlete"),
  zones: () => getJSON<HrZones>("/api/athlete/zones"),
  updateProfile: (p: Partial<Profile>) => send<AthleteResponse>("/api/athlete/profile", "PUT", p),
  sync: (full = false) =>
    viaJob<SyncResult>(() => send(`/api/strava/sync?full=${full}&async=1`, "POST")),
  dedup: (fetch = true) =>
    viaJob<DedupResult>(() => send(`/api/strava/dedup?fetch=${fetch}&async=1`, "POST")),
  importArchive: async (file: File): Promise<ImportResult> => {
    const fd = new FormData();
    fd.append("file", file);
    const res = await fetch("/api/strava/import?async=1", {
      method: "POST", credentials: "include", body: fd,
    });
    if (!res.ok) throw await httpError(res);
    const { job } = (await res.json()) as { job: JobInfo };
    return pollJob<ImportResult>(job.id);
  },
  disconnect: () =>
    send<{ deauthorized: boolean; deleted_activities: number; deleted_metrics: number; message: string }>(
      "/api/strava/disconnect", "POST"),
  pmc: (days = DEFAULT_CHART_DAYS) =>
    getJSON<{ days: PmcDay[]; window_days: number; total_days: number }>(
      `/api/metrics/pmc?days=${days}`),
  weekly: () => getJSON<{ weeks: WeekVolume[] }>("/api/metrics/weekly"),
  trends: (days = DEFAULT_CHART_DAYS) => getJSON<Trends>(`/api/metrics/trends?days=${days}`),
  readiness: () => getJSON<Readiness>("/api/metrics/readiness"),
  readinessToday: () => getJSON<ReadinessToday>("/api/metrics/readiness/today"),
  races: () => getJSON<{ races: Race[] }>("/api/races"),
  setRace: (body: { race_id?: number; name?: string; race_date?: string; distance?: string }) =>
    send<{ race: { name: string; date: string } }>("/api/athlete/race", "PUT", body),
  plan: () => getJSON<PlanResponse>("/api/plan"),
  generatePlan: (useLlm = true) =>
    viaJob<GenerateResult>(() => send(`/api/plan/generate?use_llm=${useLlm}&async=1`, "POST")),
  checkin: (inputs?: CheckinFeel) =>
    viaJob<CheckinResult>(() =>
      send("/api/plan/checkin?async=1", "POST", inputs ? { inputs } : undefined)),
  reconcile: (weeksAhead = 1) =>
    send<ReconcileResult>(`/api/plan/reconcile?weeks_ahead=${weeksAhead}`, "POST"),
  compliance: () =>
    getJSON<{ weeks: WeekCompliance[]; recent: RecentCompliance | null }>("/api/plan/compliance"),
  tests: () => getJSON<{ tests: TestProtocol[]; retest_days: number }>("/api/tests"),
  testResults: () => getJSON<{ results: TestResult[] }>("/api/tests/results"),
  recordTest: (test_slug: string, inputs: Record<string, number | null>, date?: string) =>
    send<TestResult>("/api/tests/result", "POST", { test_slug, inputs, date }),
  applyTest: (id: number) => send<TestResult>(`/api/tests/result/${id}/apply`, "POST"),
  scheduleTest: (slug: string, date: string) =>
    send<{ title: string }>(`/api/tests/${slug}/schedule`, "POST", { date }),
  testPrefill: (slug: string) =>
    getJSON<{ candidates: TestPrefill[] }>(`/api/tests/${slug}/prefill`),
  connectUrl: "/api/strava/connect",
  planZipUrl: "/api/export/plan.zip",
  weekZipUrl: (weekStart: string) => `/api/export/week/${weekStart}.zip`,
  workoutFitUrl: (id: number) => `/api/export/workout/${id}.fit`,
  workoutZwoUrl: (id: number) => `/api/export/workout/${id}.zwo`,
  workoutItwUrl: (id: number) => `/api/export/workout/${id}.itw`,
  workoutFueling: (id: number) =>
    getJSON<{ workout_id: number; fueling: WorkoutFueling }>(`/api/nutrition/workout/${id}`),
  nutritionDaily: () => getJSON<DailyNutrition>("/api/nutrition/daily"),
  raceDayNutrition: () => getJSON<RaceDayPlan>("/api/nutrition/race-day"),
  regenerateRaceDayNutrition: () =>
    viaJob<RaceDayPlan>(() => send("/api/nutrition/race-day/regenerate?async=1", "POST")),
  recovery: (days = 14) => getJSON<{ days: RecoveryDay[] }>(`/api/health/recovery?days=${days}`),
  ingestToken: () =>
    send<{ token: string; header: string; path: string }>("/api/device/ingest-token", "POST"),
  job: (id: number) => getJSON<JobInfo>(`/api/jobs/${id}`),
  jobsSummary: () => getJSON<JobsSummary>("/api/jobs/summary"),
};

// ── Strava OAuth error copy (codes set by the backend callback redirect) ────────
export function stravaErrorMessage(code: string | null): string | null {
  if (!code) return null;
  const map: Record<string, string> = {
    access_denied: "Strava sign-in was cancelled or access was denied.",
    no_code: "Strava sign-in didn’t complete — please try again.",
    invalid_state: "Strava sign-in expired — please try again.",
    exchange_failed: "Couldn’t complete Strava sign-in — please try again.",
    not_allowed: "This Strava account isn’t approved on this instance yet.",
    limit:
      "This app has reached its Strava connected-athlete limit — the owner needs to request an increase.",
  };
  return map[code] ?? "Strava sign-in failed — please try again.";
}

// ── formatting helpers ────────────────────────────────────────────────────────
export function paceKm(secPerKm: number | null): string {
  if (secPerKm == null || secPerKm <= 0) return "—"; // 0/negative pace is no pace

  const total = Math.round(secPerKm); // round first so :60 can't appear
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, "0")}/km`;
}
export function pace100(secPer100: number | null): string {
  if (secPer100 == null || secPer100 <= 0) return "—";
  const total = Math.round(secPer100);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, "0")}/100m`;
}
