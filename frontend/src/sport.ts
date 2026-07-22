// Shared sport styling + duration formatting. Extracted from PlanView so the
// Today view's session card reuses the exact same badge colors/labels rather
// than adding a third copy (bean uywr flags SPORT/SPORT_COLOR/COLORS drift;
// PR3 unifies the rest).

export const SPORT: Record<string, { color: string; label: string }> = {
  Swim: { color: "#38bdf8", label: "SWIM" },
  Bike: { color: "#ffb454", label: "BIKE" },
  Run: { color: "#4ade80", label: "RUN" },
  Brick: { color: "#9aa0ac", label: "BRICK" },
  Strength: { color: "#9aa0ac", label: "STR" },
};

export function fmtDur(s: number | null): string {
  if (!s) return "—";
  const h = Math.floor(s / 3600);
  const m = Math.round((s % 3600) / 60);
  return h ? `${h}h${m.toString().padStart(2, "0")}` : `${m}min`;
}
