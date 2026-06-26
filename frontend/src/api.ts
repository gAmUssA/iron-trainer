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
  updated_at: string | null;
}

export interface AthleteResponse {
  connected: boolean;
  profile: Profile;
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

export interface Trends {
  Bike: { date: string; power: number; hr: number | null; ef: number | null }[];
  Run: { date: string; pace: number; hr: number | null; ef: number | null }[];
  Swim: { date: string; pace: number; hr: number | null }[];
}

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
export interface ReconcileResult {
  matched: { completed: number; skipped: number; upcoming: number };
  compliance: RecentCompliance;
  weeks_replanned: string[];
  form_flag: string;
}
export interface PlanResponse {
  plan: { id: number; summary: string; weeks: PlanWeek[] } | null;
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

async function getJSON<T>(path: string): Promise<T> {
  const res = await fetch(path, { headers: { Accept: "application/json" }, credentials: "include" });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json() as Promise<T>;
}

async function send<T>(path: string, method: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    credentials: "include",
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json() as Promise<T>;
}

export const api = {
  status: () => getJSON<AppStatus>("/api/status"),
  me: () => getJSON<Me>("/api/me"),
  logout: () => send<{ ok: boolean }>("/api/auth/logout", "POST"),
  athlete: () => getJSON<AthleteResponse>("/api/athlete"),
  updateProfile: (p: Partial<Profile>) => send<AthleteResponse>("/api/athlete/profile", "PUT", p),
  sync: (full = false) => send<SyncResult>(`/api/strava/sync?full=${full}`, "POST"),
  dedup: (fetch = true) => send<DedupResult>(`/api/strava/dedup?fetch=${fetch}`, "POST"),
  pmc: () => getJSON<{ days: PmcDay[] }>("/api/metrics/pmc"),
  weekly: () => getJSON<{ weeks: WeekVolume[] }>("/api/metrics/weekly"),
  trends: () => getJSON<Trends>("/api/metrics/trends"),
  readiness: () => getJSON<Readiness>("/api/metrics/readiness"),
  races: () => getJSON<{ races: Race[] }>("/api/races"),
  setRace: (body: { race_id?: number; name?: string; race_date?: string; distance?: string }) =>
    send<{ race: { name: string; date: string } }>("/api/athlete/race", "PUT", body),
  plan: () => getJSON<PlanResponse>("/api/plan"),
  generatePlan: (useLlm = true) => send<GenerateResult>(`/api/plan/generate?use_llm=${useLlm}`, "POST"),
  reconcile: (weeksAhead = 1) =>
    send<ReconcileResult>(`/api/plan/reconcile?weeks_ahead=${weeksAhead}`, "POST"),
  compliance: () =>
    getJSON<{ weeks: WeekCompliance[]; recent: RecentCompliance | null }>("/api/plan/compliance"),
  connectUrl: "/api/strava/connect",
  planZipUrl: "/api/export/plan.zip",
  weekZipUrl: (weekStart: string) => `/api/export/week/${weekStart}.zip`,
  workoutFitUrl: (id: number) => `/api/export/workout/${id}.fit`,
  workoutZwoUrl: (id: number) => `/api/export/workout/${id}.zwo`,
};

// ── formatting helpers ────────────────────────────────────────────────────────
export function paceKm(secPerKm: number | null): string {
  if (!secPerKm) return "—";
  const m = Math.floor(secPerKm / 60);
  const s = Math.round(secPerKm % 60);
  return `${m}:${s.toString().padStart(2, "0")}/km`;
}
export function pace100(secPer100: number | null): string {
  if (!secPer100) return "—";
  const m = Math.floor(secPer100 / 60);
  const s = Math.round(secPer100 % 60);
  return `${m}:${s.toString().padStart(2, "0")}/100m`;
}
