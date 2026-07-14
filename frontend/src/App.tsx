import { useCallback, useEffect, useState } from "react";
import {
  api,
  DEFAULT_CHART_DAYS,
  stravaErrorMessage,
  type AppStatus,
  type AthleteResponse,
  type PmcDay,
  type PlanResponse,
  type Readiness,
  type ReadinessToday,
  type Trends,
  type WeekCompliance,
  type WeekVolume,
} from "./api";
import { PmcChart, ReadinessCard } from "./components/Dashboards";
import { LoginScreen } from "./components/LoginScreen";
import { NutritionView } from "./components/NutritionView";
import { PlanView } from "./components/PlanView";
import { RaceCard } from "./components/RaceCard";
import { CheckinCard } from "./components/CheckinCard";
import { ConnectCard, ProfileEditor, ZonesCard } from "./components/Setup";
import { TestsView } from "./components/TestsView";
import { TodayCall } from "./components/TodayCall";
import { TrendsView } from "./components/TrendsView";
import { useTheme } from "./theme";
import { useUnits } from "./units";
import { maybeAutoStartTour, startTour } from "./tour";

type Tab = "dashboard" | "plan" | "nutrition" | "trends" | "tests" | "settings";

const TABS: { id: Tab; label: string }[] = [
  { id: "dashboard", label: "Dashboard" },
  { id: "plan", label: "Training Plan" },
  { id: "nutrition", label: "Nutrition" },
  { id: "trends", label: "Trends" },
  { id: "tests", label: "Tests" },
  { id: "settings", label: "Thresholds" },
];

function daysUntil(dateStr: string): number {
  const race = new Date(dateStr + "T00:00:00");
  return Math.ceil((race.getTime() - Date.now()) / (1000 * 60 * 60 * 24));
}
function fmtRaceDate(dateStr: string): string {
  return new Date(dateStr + "T00:00:00").toLocaleDateString(undefined, {
    weekday: "short", month: "short", day: "numeric",
  });
}

export default function App() {
  const { theme, toggle } = useTheme();
  const { unit, toggle: toggleUnit } = useUnits();
  const [tab, setTab] = useState<Tab>("dashboard");
  const [status, setStatus] = useState<AppStatus | null>(null);
  const [athlete, setAthlete] = useState<AthleteResponse | null>(null);
  const [pmc, setPmc] = useState<PmcDay[]>([]);
  const [pmcTotal, setPmcTotal] = useState(0);
  const [pmcRange, setPmcRange] = useState(DEFAULT_CHART_DAYS);
  const [trendsRange, setTrendsRange] = useState(DEFAULT_CHART_DAYS);
  const [weekly, setWeekly] = useState<WeekVolume[]>([]);
  const [trends, setTrends] = useState<Trends | null>(null);
  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [todayCall, setTodayCall] = useState<ReadinessToday | null>(null);
  const [plan, setPlan] = useState<PlanResponse | null>(null);
  const [compliance, setCompliance] = useState<WeekCompliance[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [stravaNotice, setStravaNotice] = useState<string | null>(null);

  // Surface the OAuth outcome the backend redirected us back with, then clean the URL.
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    const err = q.get("strava_error");
    if (err) setStravaNotice(stravaErrorMessage(err));
    if (err || q.get("connected")) {
      window.history.replaceState({}, "", window.location.pathname);
    }
  }, []);

  const loadData = useCallback(async () => {
    const [a, p, w, t, r, rt, pl, comp] = await Promise.all([
      api.athlete(),
      api.pmc(pmcRange),
      api.weekly(),
      api.trends(trendsRange),
      api.readiness(),
      // Best-effort: the banner is optional garnish — a transient failure (or
      // an older backend without the endpoint) must not reject the whole load.
      api.readinessToday().catch(() => null),
      api.plan(),
      api.compliance(),
    ]);
    setAthlete(a);
    setPmc(p.days);
    setPmcTotal(p.total_days);
    setWeekly(w.weeks);
    setTrends(t);
    setReadiness(r);
    setTodayCall(rt);
    setPlan(pl);
    setCompliance(comp.weeks);
  }, [pmcRange, trendsRange]);

  // Range changes refetch only the affected series — no full dashboard reload.
  const changePmcRange = useCallback((days: number) => {
    setPmcRange(days);
    api.pmc(days)
      .then((r) => { setPmc(r.days); setPmcTotal(r.total_days); })
      .catch((e) => setError(String(e)));
  }, []);
  const changeTrendsRange = useCallback((days: number) => {
    setTrendsRange(days);
    api.trends(days).then(setTrends).catch((e) => setError(String(e)));
  }, []);

  // Post-action refreshes (sync, save, plan changes) are fire-and-forget from the
  // child components, so errors must be caught HERE or they vanish as unhandled
  // rejections and the UI silently keeps stale data.
  const safeLoad = useCallback(() => {
    loadData()
      .then(() => setError(null))
      .catch((e) => setError(String(e)));
  }, [loadData]);

  const reload = useCallback(() => {
    Promise.all([api.status().then(setStatus), loadData()])
      .then(() => setError(null))
      .catch((e) => setError(String(e)));
  }, [loadData]);

  useEffect(() => {
    api
      .status()
      .then((st) => {
        setStatus(st);
        if (!st.auth_required || st.authenticated) {
          loadData()
            .then(() => maybeAutoStartTour())
            .catch((e) => setError(String(e)));
        }
      })
      .catch((e) => setError(String(e)));
  }, [loadData]);

  function launchTour() {
    setTab("dashboard");
    window.setTimeout(startTour, 350);
  }

  async function logout() {
    await api.logout();
    window.location.reload();
  }

  // The plan records the weekly-hours target it was generated for; changing the
  // target does NOT rebuild the plan (that would discard completion history), so
  // surface the mismatch and point at Generate instead.
  const planBaseHours = plan?.plan?.base_weekly_hours;
  const targetHours = athlete?.profile.weekly_hours_target;
  const hoursMismatch =
    planBaseHours != null && targetHours != null && Math.abs(planBaseHours - targetHours) > 0.05;
  const hoursBanner = hoursMismatch ? (
    <div className="card stale-banner" role="status">
      <span>
        ⚠️ Your plan was built for <b>{planBaseHours} h/week</b>, but your target is now{" "}
        <b>{targetHours} h/week</b>. Regenerate the plan (Training Plan tab) to apply it — note
        this replaces the current plan and its completion history.
      </span>
    </div>
  ) : null;

  // Login gate (only when the server requires auth and nobody is signed in).
  if (status && status.auth_required && !status.authenticated) {
    return <LoginScreen status={status} notice={stravaNotice} />;
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <div className="brand-bars" aria-hidden>
            <span className="b1" />
            <span className="b2" />
            <span className="b3" />
          </div>
          <div className="brand-name">Iron Trainer</div>
        </div>
        <div className="topbar-right">
          <button
            className="btn tiny"
            onClick={toggleUnit}
            title={`Distance units: ${unit} (tap to switch)`}
            aria-label="Toggle distance units"
          >
            {unit}
          </button>
          <button
            className="btn tiny"
            onClick={toggle}
            title={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
            aria-label="Toggle theme"
          >
            {theme === "dark" ? "☀" : "☾"}
          </button>
          <button className="btn tiny" onClick={launchTour} title="Guided tour of the app">
            ? Tour
          </button>
          {status?.auth_required && status.authenticated && (
            <button className="btn tiny" onClick={logout} title="Sign out">
              Logout
            </button>
          )}
          {status && (
            <div className="countdown" id="tour-countdown">
              <span className="race-name">
                {status.race.name} · {fmtRaceDate(status.race.date)}
              </span>
              <span className="days">
                <span className="n">{daysUntil(status.race.date)}</span> <span className="lbl">days to go</span>
              </span>
            </div>
          )}
        </div>
      </header>

      <nav className="tabs" id="tour-nav">
        {TABS.map((t) => (
          <button
            key={t.id}
            className={`tab${tab === t.id ? " active" : ""}`}
            onClick={() => setTab(t.id)}
          >
            {t.label}
            <span className="tab-underline" />
          </button>
        ))}
      </nav>

      <main className="content">
        {error && <div className="card error">Could not reach API: {error}</div>}
        {stravaNotice && (
          <div className="card error" role="alert">
            {stravaNotice}
            <button className="btn tiny" style={{ marginLeft: 12 }} onClick={() => setStravaNotice(null)}>
              Dismiss
            </button>
          </div>
        )}

        {tab === "dashboard" && (
          <div className="tab-panel">
            {todayCall && <TodayCall readiness={todayCall} />}
            <div className="dash-top">
              {status && athlete && (
                <ConnectCard status={status} athlete={athlete} onSynced={safeLoad} />
              )}
              {readiness && status && (
                <ReadinessCard readiness={readiness} raceName={status.race.name} />
              )}
            </div>
            {plan?.plan && <CheckinCard onDone={safeLoad} />}
            {pmcTotal > 0 ? (
              <PmcChart days={pmc} range={pmcRange} onRange={changePmcRange} />
            ) : (
              <div className="card">
                <p className="muted">Connect Strava and sync to populate your fitness chart.</p>
              </div>
            )}
          </div>
        )}

        {tab === "trends" && trends && (
          <div className="tab-panel">
            <TrendsView
              trends={trends}
              weekly={weekly}
              pmc={pmc}
              range={trendsRange}
              onRange={changeTrendsRange}
              connected={athlete?.connected ?? false}
              onSynced={safeLoad}
            />
          </div>
        )}

        {tab === "plan" && status && plan && (
          <div className="tab-panel">
            {hoursBanner}
            <PlanView
              plan={plan}
              compliance={compliance}
              anthropicReady={status.anthropic_configured}
              onChanged={safeLoad}
            />
          </div>
        )}

        {tab === "nutrition" && (
          <div className="tab-panel">
            <NutritionView
              anthropicReady={status?.anthropic_configured ?? false}
              hasBodyWeight={!!athlete?.profile.body_weight_kg}
              onGoToSettings={() => setTab("settings")}
            />
          </div>
        )}

        {tab === "tests" && (
          <div className="tab-panel">
            <TestsView onChanged={safeLoad} />
          </div>
        )}

        {tab === "settings" && athlete && (
          <div className="tab-panel">
            {hoursBanner}
            {status && <RaceCard status={status} onChanged={reload} />}
            <ProfileEditor profile={athlete.profile} onSaved={safeLoad} />
            <ZonesCard thresholdHr={athlete.profile.threshold_hr} maxHr={athlete.profile.max_hr} />
          </div>
        )}
      </main>

      <footer className="app-footer">
        <a href="https://www.strava.com" target="_blank" rel="noopener noreferrer" aria-label="Powered by Strava">
          <img src="/strava/powered_by_strava.svg" alt="Powered by Strava" height="20" />
        </a>
      </footer>
    </div>
  );
}
