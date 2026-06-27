"""Fitness-test library: standard protocols an athlete performs to measure the
thresholds that drive their training zones (ftp / threshold_hr / threshold_pace_run
/ css_swim). Each protocol is both:

  - a *result recorder* — `compute(slug, inputs)` turns raw entry into thresholds, and
  - a *schedulable workout* — `to_workout(slug)` builds the structured session.

This is a small, fixed set, so the catalog lives here as a constant (no DB table),
co-located with the math and the workout builder. Units match the athlete profile:
power W, HR bpm, run pace sec/km, swim CSS sec/100m.
"""

from __future__ import annotations

RETEST_DAYS = 35  # "due for re-test" after ~5 weeks (the 4–6 week window)


# --- compute functions: raw inputs -> partial profile (thresholds) ---------------

def ftp_20min(avg_power_w: float) -> dict:
    """FTP ≈ 95% of a 20-minute max-effort average power."""
    return {"ftp": round(avg_power_w * 0.95)}


def run_lthr_tt(distance_m: float, time_s: float, avg_hr_last20: float) -> dict:
    """30-min run time trial: LTHR = avg HR of the final 20 min; threshold pace =
    average pace over the TT (sec/km)."""
    out: dict = {"threshold_hr": round(avg_hr_last20)}
    if distance_m and distance_m > 0:
        out["threshold_pace_run"] = round(time_s / (distance_m / 1000.0))
    return out


def swim_css(t400_s: float, t200_s: float) -> dict:
    """Critical Swim Speed pace (sec/100m) = (t400 − t200) / 2."""
    return {"css_swim": round((t400_s - t200_s) / 2.0)}


# --- catalog ---------------------------------------------------------------------
# `compute` maps the input dict to one of the functions above. `inputs` describes the
# fields the UI collects. `prefill_sport` (if set) is the Strava sport to suggest from.

TESTS: list[dict] = [
    {
        "slug": "bike-ftp-20",
        "name": "Bike — 20-min FTP",
        "sport": "Bike",
        "measures": ["ftp"],
        "description": "After a thorough warm-up, ride 20 minutes all-out at the "
                       "highest power you can sustain. FTP = 95% of your average power.",
        "inputs": [{"field": "avg_power_w", "label": "20-min avg power", "unit": "W"}],
        "prefill_sport": "Bike",
        "_fn": lambda i: ftp_20min(i["avg_power_w"]),
    },
    {
        "slug": "run-lthr-30",
        "name": "Run — 30-min LTHR test",
        "sport": "Run",
        "measures": ["threshold_hr", "threshold_pace_run"],
        "description": "Run 30 minutes as a steady, hard time trial. LTHR is your "
                       "average heart rate over the final 20 minutes; threshold pace "
                       "is your average pace for the effort.",
        "inputs": [
            {"field": "distance_m", "label": "Distance covered", "unit": "m"},
            {"field": "time_s", "label": "Duration", "unit": "s"},
            {"field": "avg_hr_last20", "label": "Avg HR (final 20 min)", "unit": "bpm"},
        ],
        "prefill_sport": "Run",
        "_fn": lambda i: run_lthr_tt(i["distance_m"], i["time_s"], i["avg_hr_last20"]),
    },
    {
        "slug": "swim-css-400-200",
        "name": "Swim — CSS (400/200)",
        "sport": "Swim",
        "measures": ["css_swim"],
        "description": "Swim a maximal 400 m and (after full recovery) a maximal "
                       "200 m. CSS pace per 100 m = (400 time − 200 time) / 2.",
        "inputs": [
            {"field": "t400_s", "label": "400 m time", "unit": "s"},
            {"field": "t200_s", "label": "200 m time", "unit": "s"},
        ],
        "prefill_sport": None,  # two separate efforts → no single-activity prefill
        "_fn": lambda i: swim_css(i["t400_s"], i["t200_s"]),
    },
]

_BY_SLUG = {t["slug"]: t for t in TESTS}


def get(slug: str) -> dict | None:
    return _BY_SLUG.get(slug)


def catalog() -> list[dict]:
    """Public protocol defs (without the internal compute lambda)."""
    return [{k: v for k, v in t.items() if not k.startswith("_")} for t in TESTS]


def compute(slug: str, inputs: dict) -> dict:
    """Raw inputs -> computed thresholds (partial profile). Raises on unknown slug."""
    t = _BY_SLUG[slug]
    return t["_fn"](inputs)


# --- schedulable workout ---------------------------------------------------------

def _open(notes: str, *, duration_s: int | None = None, distance_m: float | None = None) -> dict:
    step: dict = {"type": "interval", "notes": notes,
                  "target": {"type": "open", "unit": "", "low": None, "high": None}}
    if duration_s is not None:
        step["duration_s"] = duration_s
    if distance_m is not None:
        step["distance_m"] = distance_m
    return step


def _easy(duration_s: int, label: str) -> dict:
    return {"type": label, "duration_s": duration_s,
            "target": {"type": "open", "unit": "", "low": None, "high": None}}


def to_workout(slug: str) -> dict:
    """Build the structured test workout (warmup → test effort → cooldown). Targets
    are intentionally open (max/steady-hard) — the point is to measure, not to hold a
    zone. Marked `intensity="test"` so the UI can badge it; it still exports and
    schedules like any workout."""
    t = _BY_SLUG[slug]
    sport = t["sport"]
    if sport == "Bike":
        steps = [_easy(900, "warmup"),
                 _open("20-min test: all-out, highest sustainable power", duration_s=1200),
                 _easy(600, "cooldown")]
        dur = 900 + 1200 + 600
    elif sport == "Run":
        steps = [_easy(900, "warmup"),
                 _open("30-min test: steady, hard time-trial effort", duration_s=1800),
                 _easy(600, "cooldown")]
        dur = 900 + 1800 + 600
    else:  # Swim CSS
        steps = [_easy(300, "warmup"),
                 _open("400 m time trial — maximal", distance_m=400),
                 _easy(180, "recovery"),
                 _open("200 m time trial — maximal", distance_m=200),
                 _easy(200, "cooldown")]
        dur = 300 + 180 + 200  # plus the two TTs (open duration)
    return {
        "sport": sport,
        "title": f"{t['name']} (test)",
        "description": t["description"],
        "intensity": "test",
        "steps": steps,
        "duration_s": dur,
    }
