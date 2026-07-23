import { useState } from "react";
import { api, type AppStatus } from "../api";
import { getAppleIdentityToken } from "../appleSignIn";
import { AppleButton } from "./AppleButton";

export function LoginScreen({ status, notice }: { status: AppStatus; notice?: string | null }) {
  const [appleBusy, setAppleBusy] = useState(false);
  const [appleError, setAppleError] = useState<string | null>(null);

  async function signInWithApple() {
    setAppleBusy(true);
    setAppleError(null);
    try {
      const token = await getAppleIdentityToken();
      await api.appleWeb(token);
      window.location.reload(); // session cookie set → re-enter authenticated
    } catch (e) {
      if (!(e instanceof Error && e.message === "cancelled")) {
        setAppleError(`Apple sign-in failed: ${e instanceof Error ? e.message : e}`);
      }
      setAppleBusy(false);
    }
  }

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
            AI-adaptive triathlon training. Sign in with Strava to load your activities,
            or with Apple to start fresh — your data stays private to your account.
          </p>
          {(notice || appleError) && (
            <div className="card error" role="alert" style={{ marginBottom: 18, textAlign: "left" }}>
              {notice || appleError}
            </div>
          )}
          {status.strava_configured ? (
            <>
              <a href={api.connectUrl} aria-label="Connect with Strava">
                <img
                  src="/strava/btn_strava_connect.svg"
                  alt="Connect with Strava"
                  style={{ height: 48, width: "100%", maxWidth: 260 }}
                />
              </a>
              <div className="login-or" aria-hidden>or</div>
              <AppleButton label="Sign in with Apple" onClick={signInWithApple} busy={appleBusy} />
              <p className="muted small" style={{ marginTop: 16, lineHeight: 1.5 }}>
                Strava access is limited to athletes approved on this instance. Apple
                sign-in creates a Strava-free account.
              </p>
            </>
          ) : (
            <>
              <AppleButton label="Sign in with Apple" onClick={signInWithApple} busy={appleBusy} />
              <p className="muted small" style={{ marginTop: 16, lineHeight: 1.5 }}>
                Strava isn't configured on this server (<code>STRAVA_CLIENT_ID</code> /{" "}
                <code>STRAVA_CLIENT_SECRET</code>). You can still sign in with Apple.
              </p>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
