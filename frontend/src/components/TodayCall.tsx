import type { ReadinessToday } from "../api";

const CALL_LABEL: Record<string, string> = {
  hard: "GO HARD",
  easy: "GO EASY",
  rest: "REST",
};

/** Today's readiness call. Signal, not noise: a green day renders as one quiet
 * line; amber/red days get a highlighted banner with the why. Hidden entirely
 * while there isn't enough history for the ratio to mean anything. */
export function TodayCall({ readiness }: { readiness: ReadinessToday }) {
  if (readiness.status !== "ok" || !readiness.call || !readiness.level) return null;
  const label = CALL_LABEL[readiness.call] ?? readiness.call.toUpperCase();
  const reason = readiness.reasons[0] ?? "";
  return (
    <div className={`card today-call today-call-${readiness.level}`} role="status">
      <span className={`today-call-pill pill-${readiness.level}`}>{label}</span>
      <span className="today-call-reason">{reason}</span>
    </div>
  );
}
