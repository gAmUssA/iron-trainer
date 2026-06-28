import { useCallback, useEffect, useState } from "react";
import {
  api,
  stravaErrorMessage,
  type AppStatus,
  type AthleteResponse,
  type PmcDay,
  type PlanResponse,
  type Readiness,
  type Trends,
  type WeekCompliance,
  type WeekVolume,
} from "./api";
import { PmcChart, ReadinessCard, TrendsChart, WeeklyVolumeChart } from "./components/Dashboards";
import { LoginScreen } from "./components/LoginScreen";
import { PlanView } from "./components/PlanView";
import { RaceCard } from "./components/RaceCard";
import { ConnectCard, ProfileEditor } from "./components/Setup";
import { TestsView } from "./components/TestsView";
import { useTheme } from "./theme";
import { useUnits } from "./units";
import { maybeAutoStartTour, startTour } from "./tour";

type Tab = "dashboard" | "plan" | "trends" | "tests" | "settings";

const TABS: { id: Tab; label: string }[] = [
  { id: "dashboard", label: "Dashboard" },
  { id: "plan", label: "Training Plan" },
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
  const [weekly, setWeekly] = useState<WeekVolume[]>([]);
  const [trends, setTrends] = useState<Trends | null>(null);
  const [readiness, setReadiness] = useState<Readiness | null>(null);
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
    const [a, p, w, t, r, pl, comp] = await Promise.all([
      api.athlete(),
      api.pmc(),
      api.weekly(),
      api.trends(),
      api.readiness(),
      api.plan(),
      api.compliance(),
    ]);
    setAthlete(a);
    setPmc(p.days);
    setWeekly(w.weeks);
    setTrends(t);
    setReadiness(r);
    setPlan(pl);
    setCompliance(comp.weeks);
  }, []);

  const reload = useCallback(async () => {
    await Promise.all([api.status().then(setStatus), loadData()]);
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
            <div className="dash-top">
              {status && athlete && (
                <ConnectCard status={status} athlete={athlete} onSynced={loadData} />
              )}
              {readiness && status && (
                <ReadinessCard readiness={readiness} raceName={status.race.name} />
              )}
            </div>
            {pmc.length > 0 ? (
              <PmcChart days={pmc} />
            ) : (
              <div className="card">
                <p className="muted">Connect Strava and sync to populate your fitness chart.</p>
              </div>
            )}
          </div>
        )}

        {tab === "trends" && (
          <div className="tab-panel grid-2">
            <WeeklyVolumeChart weeks={weekly} />
            {trends && <TrendsChart trends={trends} />}
          </div>
        )}

        {tab === "plan" && status && plan && (
          <div className="tab-panel">
            <PlanView
              plan={plan}
              compliance={compliance}
              anthropicReady={status.anthropic_configured}
              onChanged={loadData}
            />
          </div>
        )}

        {tab === "tests" && (
          <div className="tab-panel">
            <TestsView onChanged={loadData} />
          </div>
        )}

        {tab === "settings" && athlete && (
          <div className="tab-panel">
            {status && <RaceCard status={status} onChanged={reload} />}
            <ProfileEditor profile={athlete.profile} onSaved={loadData} />
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
