"""Heart-rate training zones from the athlete's thresholds.

Pure functions (same design as metrics/nutrition/insights): the 5-zone model
keyed off LTHR (Coggan/Friel percentages) when a threshold HR is known, falling
back to the classic %max-HR bands otherwise. Zone vocabulary maps 1:1 onto the
planner's intensity levels, so template AND LLM prescriptions can speak "Z2".
"""

from __future__ import annotations

# Coggan LTHR-based bands: (low_frac, high_frac) of threshold HR.
_LTHR_BANDS = [
    ("Z1", "Recovery", 0.00, 0.81),
    ("Z2", "Endurance", 0.81, 0.89),
    ("Z3", "Tempo", 0.90, 0.93),
    ("Z4", "Threshold", 0.94, 0.99),
    ("Z5", "VO2max", 1.00, 1.06),
]

# Fallback %max-HR bands (classic 5-zone) when only max HR is known.
_MAXHR_BANDS = [
    ("Z1", "Recovery", 0.50, 0.60),
    ("Z2", "Endurance", 0.60, 0.70),
    ("Z3", "Tempo", 0.70, 0.80),
    ("Z4", "Threshold", 0.80, 0.90),
    ("Z5", "VO2max", 0.90, 1.00),
]

# Planner intensity level → zone. The two vocabularies are deliberately 1:1.
INTENSITY_ZONE = {
    "recovery": "Z1",
    "endurance": "Z2",
    "tempo": "Z3",
    "threshold": "Z4",
    "vo2": "Z5",
    "test": "Z4",
}


def hr_zones(threshold_hr: int | None, max_hr: int | None = None) -> dict:
    """Zone table + which basis produced it: 'lthr', 'max_hr', or None."""
    if threshold_hr:
        base, bands, basis = float(threshold_hr), _LTHR_BANDS, "lthr"
    elif max_hr:
        base, bands, basis = float(max_hr), _MAXHR_BANDS, "max_hr"
    else:
        return {"basis": None, "zones": []}
    zones = []
    for key, name, lo_f, hi_f in bands:
        hi = round(base * hi_f)
        if max_hr:
            hi = min(hi, int(max_hr))
        zones.append({
            "zone": key,
            "name": name,
            "low": round(base * lo_f),
            "high": hi,
        })
    return {"basis": basis, "zones": zones}


def hr_range_for_intensity(intensity: str, threshold_hr: int | None,
                           max_hr: int | None = None) -> tuple[int, int] | None:
    """The bpm band a workout of this intensity should sit in, if computable."""
    table = hr_zones(threshold_hr, max_hr)
    zone_key = INTENSITY_ZONE.get(intensity)
    if not zone_key or not table["zones"]:
        return None
    z = next((z for z in table["zones"] if z["zone"] == zone_key), None)
    if z is None:
        return None
    return int(z["low"]), int(z["high"])


def zone_label(intensity: str) -> str | None:
    return INTENSITY_ZONE.get(intensity)
