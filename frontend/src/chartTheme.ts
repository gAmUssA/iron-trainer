// Shared Recharts theming — previously duplicated verbatim in Dashboards.tsx
// and TrendsView.tsx. Recharts takes JS values (not CSS vars), so colors and
// the light/dark palette live here and are consumed via the useChart() hook.
import { useTheme } from "./theme";

// Sport / metric colors, vivid enough for both themes. Union of everything the
// dashboard and trends charts reference (swim/bike/run + PMC ctl/atl/tsb +
// the projection accent).
export const COLORS = {
  swim: "#38bdf8",
  bike: "#ffb454",
  run: "#4ade80",
  ctl: "#4ade80",
  atl: "#f87171",
  tsb: "#38bdf8",
  proj: "#a78bfa",
};

const CHART_THEME = {
  dark: { grid: "rgba(255,255,255,0.06)", tick: "#5b6270", tipBg: "#14161c", tipBorder: "#2a3441", tipText: "#eceef2" },
  light: { grid: "rgba(0,0,0,0.09)", tick: "#9099a3", tipBg: "#ffffff", tipBorder: "#d8dce2", tipText: "#161a20" },
};

/** Theme-derived chart colors (Recharts takes JS values, not CSS vars). */
export function useChart() {
  const { theme } = useTheme();
  const c = CHART_THEME[theme];
  return {
    grid: c.grid,
    tick: { fill: c.tick, fontSize: 10, fontFamily: "'IBM Plex Mono', monospace" },
    tooltip: { background: c.tipBg, border: `1px solid ${c.tipBorder}`, borderRadius: 8, color: c.tipText, fontSize: 12 },
  };
}
