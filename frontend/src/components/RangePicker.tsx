import { CHART_RANGES } from "../api";

/** Compact 3m / 6m / 1y / All toggle for chart time windows (days; 0 = all). */
export function RangePicker({ value, onChange }: { value: number; onChange: (days: number) => void }) {
  return (
    <div className="range-picker" role="group" aria-label="Chart time range">
      {CHART_RANGES.map((r) => (
        <button
          key={r.days}
          className={`range-btn${value === r.days ? " active" : ""}`}
          onClick={() => onChange(r.days)}
          aria-pressed={value === r.days}
        >
          {r.label}
        </button>
      ))}
    </div>
  );
}
