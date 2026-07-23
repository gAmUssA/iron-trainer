import { useState } from "react";
import { api } from "../api";
import { getAppleIdentityToken } from "../appleSignIn";
import { AppleButton } from "./AppleButton";

/**
 * Settings card (bean 3e6w) letting a logged-in athlete link an Apple account, so
 * they can sign in with Apple later (e.g. on another device / the web) and land on
 * the same account. The backend links Apple onto the current session's athlete when
 * a valid session cookie is present.
 */
export function AppleLinkCard() {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [linked, setLinked] = useState(false);

  async function link() {
    setBusy(true);
    setMsg(null);
    try {
      const token = await getAppleIdentityToken();
      await api.appleWeb(token);
      setLinked(true);
      setMsg("Apple account linked. You can now sign in with Apple.");
    } catch (e) {
      if (e instanceof Error && e.message === "cancelled") {
        // user closed the popup — no error to show
      } else {
        setMsg(`Couldn't link Apple account: ${e instanceof Error ? e.message : e}`);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card" style={{ display: "flex", flexDirection: "column" }}>
      <div className="card-label">Apple account</div>
      <p className="muted small" style={{ margin: "6px 0 14px", lineHeight: 1.5 }}>
        Link Apple to this account to sign in with Apple later — on another device or
        on the web — without Strava.
      </p>
      {linked ? (
        <div className="card ok" role="status" style={{ textAlign: "left" }}>
          {msg}
        </div>
      ) : (
        <>
          <AppleButton label="Continue with Apple" onClick={link} busy={busy} />
          {msg && (
            <div className="card error" role="alert" style={{ marginTop: 12, textAlign: "left" }}>
              {msg}
            </div>
          )}
        </>
      )}
    </div>
  );
}
