// Sign in with Apple (web) — bean 3e6w. Loads Apple's JS SDK on demand, runs the
// popup sign-in, and returns the identity token (a JWT) for the backend to verify
// against Apple's JWKS (POST /api/auth/apple/web). Used both for Strava-free login
// (LoginScreen) and for linking Apple onto a logged-in account (settings).
//
// ⚠ CLIENT_ID must be the Apple **Services ID**; keep it in sync with the backend
// `apple.audiences` env var. The redirectURI is the CURRENT page origin (computed at
// call time) — in popup/web_message mode Apple posts the token back to the
// redirect_uri's origin, so it must equal the page origin or the browser blocks the
// postMessage ("Unable to post message ... Recipient has origin ..."). EVERY origin
// the app is served on (https://irontrainer.app/, https://www.irontrainer.app/) must
// therefore be registered as a Return URL in the Services ID → Configure dialog.
const CLIENT_ID = "io.gamov.irontrainer.web";
const SDK_URL =
  "https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js";

interface AppleAuthResponse {
  authorization: { code: string; id_token: string; state?: string };
  user?: unknown;
}
interface AppleIDAuth {
  init(cfg: Record<string, unknown>): void;
  signIn(): Promise<AppleAuthResponse>;
}
declare global {
  interface Window {
    AppleID?: { auth: AppleIDAuth };
  }
}

let sdkPromise: Promise<void> | null = null;
function loadSdk(): Promise<void> {
  if (window.AppleID) return Promise.resolve();
  if (!sdkPromise) {
    sdkPromise = new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = SDK_URL;
      s.async = true;
      s.onload = () => resolve();
      s.onerror = () => {
        sdkPromise = null; // allow a retry on the next attempt
        reject(new Error("Could not load Apple sign-in."));
      };
      document.head.appendChild(s);
    });
  }
  return sdkPromise;
}

/**
 * Run the Apple popup sign-in and return the identity token. Throws an Error with
 * message "cancelled" when the user closes the popup, so callers can stay quiet on
 * a deliberate cancel.
 */
export async function getAppleIdentityToken(): Promise<string> {
  await loadSdk();
  const auth = window.AppleID!.auth;
  // Match the page origin so the popup's postMessage lands (see header note). Must be
  // a registered Return URL on whatever domain the user loaded (apex or www).
  const redirectURI = window.location.origin + "/";
  auth.init({ clientId: CLIENT_ID, scope: "name", redirectURI, usePopup: true });
  try {
    const res = await auth.signIn();
    const token = res?.authorization?.id_token;
    if (!token) throw new Error("Apple did not return an identity token.");
    return token;
  } catch (e: unknown) {
    // Apple rejects a user-closed popup with { error: "popup_closed_by_user" }.
    if (e && typeof e === "object" && (e as { error?: string }).error === "popup_closed_by_user") {
      throw new Error("cancelled");
    }
    throw e instanceof Error ? e : new Error("Apple sign-in failed.");
  }
}
