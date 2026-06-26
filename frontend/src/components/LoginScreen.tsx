import { api, type AppStatus } from "../api";

export function LoginScreen({ status }: { status: AppStatus }) {
  return (
    <div className="app">
      <div className="login-wrap">
        <div className="login-card">
          <div className="brand" style={{ justifyContent: "center", marginBottom: 18 }}>
            <div className="brand-bars" aria-hidden>
              <span className="b1" />
              <span className="b2" />
              <span className="b3" />
            </div>
            <div className="brand-name" style={{ fontSize: 22 }}>Iron Trainer</div>
          </div>
          <h2 style={{ marginBottom: 8 }}>Sign in</h2>
          <p className="muted" style={{ marginBottom: 22, lineHeight: 1.5 }}>
            AI-adaptive triathlon training. Log in with Strava to load your activities,
            plan and dashboards — your data stays private to your account.
          </p>
          {status.strava_configured ? (
            <a className="btn primary login-btn" href={api.connectUrl}>
              Login with Strava
            </a>
          ) : (
            <p className="muted small">
              Strava isn't configured on this server. Set <code>STRAVA_CLIENT_ID</code> /{" "}
              <code>STRAVA_CLIENT_SECRET</code>.
            </p>
          )}
        </div>
      </div>
    </div>
  );
}
