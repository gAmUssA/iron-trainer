import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

export type DistanceUnit = "mi" | "km";

const UnitsCtx = createContext<{ unit: DistanceUnit; toggle: () => void }>({
  unit: "mi",
  toggle: () => {},
});

function initialUnit(): DistanceUnit {
  try {
    const saved = localStorage.getItem("iron-units");
    if (saved === "mi" || saved === "km") return saved;
    // Default to miles for US locales, km elsewhere.
    const loc = navigator.language || "";
    if (/^en-US|^en-GB/.test(loc)) return "mi";
  } catch {
    /* ignore */
  }
  return "km";
}

export function UnitsProvider({ children }: { children: ReactNode }) {
  const [unit, setUnit] = useState<DistanceUnit>(initialUnit);
  useEffect(() => {
    try {
      localStorage.setItem("iron-units", unit);
    } catch {
      /* ignore */
    }
  }, [unit]);
  const toggle = () => setUnit((u) => (u === "mi" ? "km" : "mi"));
  return <UnitsCtx.Provider value={{ unit, toggle }}>{children}</UnitsCtx.Provider>;
}

export const useUnits = () => useContext(UnitsCtx);

// ── conversions (backend is canonical: meters, seconds, sec/km) ──────────────────
const M_PER_MI = 1609.344;

/** meters → the display value in the chosen unit (number). */
export function metersToUnit(m: number, unit: DistanceUnit): number {
  return unit === "mi" ? m / M_PER_MI : m / 1000;
}
/** a distance the user typed (in the chosen unit) → meters. */
export function unitToMeters(value: number, unit: DistanceUnit): number {
  return Math.round(unit === "mi" ? value * M_PER_MI : value * 1000);
}

/** seconds → human time: "M:SS" under an hour, "H:MM:SS" at/over. */
export function secsToHMS(total: number): string {
  const s = Math.round(total);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const ss = sec.toString().padStart(2, "0");
  if (h > 0) return `${h}:${m.toString().padStart(2, "0")}:${ss}`;
  return `${m}:${ss}`;
}
/** "H:MM:SS" | "M:SS" | raw seconds → seconds. Returns null on blank/garbage. */
export function hmsToSecs(text: string): number | null {
  const t = text.trim();
  if (!t) return null;
  if (t.includes(":")) {
    const parts = t.split(":").map((p) => parseInt(p, 10));
    if (parts.some(Number.isNaN)) return null;
    return parts.reduce((acc, p) => acc * 60 + p, 0);
  }
  const n = parseFloat(t);
  return Number.isNaN(n) ? null : n;
}

/** sec/km → a pace string in the chosen unit, e.g. "8:39/mi" or "5:23/km". */
export function paceInUnit(secPerKm: number | null, unit: DistanceUnit): string {
  if (!secPerKm) return "—";
  const per = unit === "mi" ? secPerKm * (M_PER_MI / 1000) : secPerKm;
  const m = Math.floor(per / 60);
  const s = Math.round(per % 60).toString().padStart(2, "0");
  return `${m}:${s}/${unit}`;
}
