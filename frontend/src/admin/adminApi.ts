// Thin client for the password-gated admin console (bean gfb3). All calls use the
// admin_session cookie (credentials: "include"); a 401 means "not logged in as admin".

export class AdminUnauthorized extends Error {}

export interface AdminJob {
  id: number;
  athlete_id: number | null;
  kind: string;
  status: string;
  created_at: string | null;
  started_at: string | null;
  finished_at: string | null;
  error: string | null;
}

export interface AdminJobsPage {
  jobs: AdminJob[];
  total: number;
  limit: number;
  offset: number;
}

export interface AdminJobsQuery {
  kind?: string;
  status?: string;
  athlete_id?: string;
  limit?: number;
  offset?: number;
}

export interface AdminUser {
  id: number;
  name: string | null;
  strava_athlete_id: number | null;
  connected: boolean;
  apple_linked: boolean;
  activities: number;
  jobs: number;
  failed_jobs: number;
}

// user(id) detail — a superset of AdminUser plus counts/last_sync/recent_jobs.
// Left loosely typed (Record) since the view renders it structurally.
export type AdminUserDetail = Record<string, unknown>;

export interface AdminKindHealth {
  kind: string;
  total: number;
  succeeded: number;
  failed: number;
  running: number;
  queued: number;
  other: number;
  failure_rate: number;
}

export interface AdminRecentFailure {
  id: number;
  kind: string;
  athlete_id: number | null;
  created_at: string | null;
  finished_at: string | null;
  error: string | null;
}

export interface AdminJobHealth {
  window_days: number;
  since: string;
  kinds: AdminKindHealth[];
  recent_failures: AdminRecentFailure[];
}

export interface AdminIngest {
  id: number;
  athlete_id: number | null;
  source: string;
  received_at: string | null;
  ok: boolean;
  days_stored: number | null;
  records: number | null;
  unknown_metrics: number | null;
  bad_dates: number | null;
  byte_size: number | null;
  error: string | null;
}

export interface AdminIngestsPage {
  window_days: number;
  since: string;
  total: number;
  limit: number;
  offset: number;
  ingests: AdminIngest[];
  last_by_source: AdminIngest[];
}

export interface AdminIngestsQuery {
  days?: number;
  athlete_id?: string;
  source?: string;
  ok?: string;
  limit?: number;
  offset?: number;
}

async function adminGet<T>(path: string): Promise<T> {
  const res = await fetch(path, { headers: { Accept: "application/json" }, credentials: "include" });
  if (res.status === 401) throw new AdminUnauthorized();
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json() as Promise<T>;
}

export const adminApi = {
  /** Returns the HTTP status: 200 = ok, 401 = wrong password, 503 = not configured. */
  async login(password: string): Promise<number> {
    const res = await fetch("/api/admin/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ password }),
    });
    return res.status;
  },

  logout: () => fetch("/api/admin/logout", { method: "POST", credentials: "include" }),

  jobs(q: AdminJobsQuery): Promise<AdminJobsPage> {
    const p = new URLSearchParams();
    if (q.kind) p.set("kind", q.kind);
    if (q.status) p.set("status", q.status);
    if (q.athlete_id) p.set("athlete_id", q.athlete_id);
    p.set("limit", String(q.limit ?? 50));
    p.set("offset", String(q.offset ?? 0));
    return adminGet<AdminJobsPage>(`/api/admin/jobs?${p.toString()}`);
  },

  job: (id: number) => adminGet<Record<string, unknown>>(`/api/admin/jobs/${id}`),

  users: () => adminGet<{ users: AdminUser[] }>("/api/admin/users"),

  user: (id: number) => adminGet<AdminUserDetail>(`/api/admin/users/${id}`),

  jobHealth: (days: number) => adminGet<AdminJobHealth>(`/api/admin/health/jobs?days=${days}`),

  ingests(q: AdminIngestsQuery): Promise<AdminIngestsPage> {
    const p = new URLSearchParams();
    p.set("days", String(q.days ?? 7));
    if (q.athlete_id) p.set("athlete_id", q.athlete_id);
    if (q.source) p.set("source", q.source);
    if (q.ok) p.set("ok", q.ok);
    p.set("limit", String(q.limit ?? 50));
    p.set("offset", String(q.offset ?? 0));
    return adminGet<AdminIngestsPage>(`/api/admin/health/ingests?${p.toString()}`);
  },
};
