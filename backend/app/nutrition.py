"""Fueling math: carbohydrate, hydration and sodium targets for workouts and race day.

Pure functions (no I/O) following the same design as planning/validator.py so the
numbers are cheap to unit-test and easy to audit. Sources:

  * Carbs during exercise are gut-limited, NOT body-weight dependent
    (Jeukendrup 2014): 30-60 g/h for 1-2 h, 60-90 g/h beyond 2.5 h, and above
    ~60 g/h a glucose:fructose blend (multiple transportable carbohydrates) is
    required for absorption.
  * Hydration IS body-size dependent: sweat rate ~0.5-2 L/h (ACSM / NATA 2017),
    replace ~80% of losses, never exceed sweat rate, and cap plain-fluid intake
    to reduce hyponatremia risk (<~750 mL/h without sodium; Noakes).
  * Sodium: ~300-1000 mg/h scaled by sweat rate (typical sweat ~500 mg Na/L).
  * Daily / pre-race / recovery carbs scale with body weight (Burke 2011).
"""

from __future__ import annotations

import math

# ── Constants (guard rails & defaults) ────────────────────────────────────────

DEFAULT_GEL_CARB_G = 25.0  # a standard gel (GU/SiS ~22 g, Maurten 100 = 25 g)
HIGH_CARB_GEL_G = 40.0  # high-carb gels (Maurten 160, SiS Beta Fuel)

MAX_CARB_G_H = 120.0  # ceiling even for trained guts (Podlogar 2022)
MTC_THRESHOLD_G_H = 60.0  # above this, glucose+fructose blend required
MIN_FUELED_DURATION_S = 45 * 60  # sessions shorter than this need no fuel

BASE_SWEAT_L_H_PER_KG = 0.012  # ~0.84 L/h for a 70 kg athlete, temperate
SWEAT_RATE_MIN_L_H = 0.4
SWEAT_RATE_MAX_L_H = 2.5
HOT_WEATHER_FACTOR = 1.5  # applied at/above HOT_TEMP_C
HOT_TEMP_C = 27.0

FLUID_REPLACE_FRACTION = 0.8  # replace ~80% of sweat losses
MAX_FLUID_ML_H = 1000.0  # hard ceiling (hyponatremia risk beyond)
PLAIN_WATER_SAFE_ML_H = 750.0  # above this, sodium is a must

SODIUM_MG_PER_L_SWEAT = 500.0
SODIUM_MIN_MG_H = 300.0
SODIUM_MAX_MG_H = 1000.0

# Duration-based carb targets (upper bound of each bracket in hours -> g/h).
_CARB_BRACKETS: list[tuple[float, float]] = [
    (0.75, 0.0),
    (1.25, 30.0),
    (2.0, 45.0),
    (2.5, 60.0),
    (3.0, 75.0),
    (float("inf"), 90.0),
]

_INTENSITY_SWEAT_FACTOR = {
    "recovery": 0.8,
    "endurance": 1.0,
    "tempo": 1.15,
    "threshold": 1.3,
    "vo2": 1.3,
    "test": 1.2,
}

_INTENSITY_CARB_FACTOR = {
    "recovery": 0.8,
    "endurance": 1.0,
    "tempo": 1.0,
    "threshold": 1.1,
    "vo2": 1.1,
    "test": 1.0,
}

# Daily carbohydrate needs, g per kg body weight per day (Burke 2011).
_DAILY_CARB_G_KG = [
    (1.0, 5.0),   # < 1 h/day training: 3-5 g/kg (use upper)
    (3.0, 8.0),   # 1-3 h/day: 6-10 g/kg (use middle)
    (float("inf"), 11.0),  # > 3 h/day: 10-12 g/kg
]

PRE_RACE_MEAL_G_KG = 2.5  # 3-4 h before the start (2-3 g/kg)
PRE_RACE_SNACK_G_KG = 1.0  # ~1 h before
RECOVERY_G_KG = 1.2  # within 30 min post


# ── Core targets ──────────────────────────────────────────────────────────────


def carb_target_per_hour(duration_s: float, intensity: str = "endurance") -> float:
    """Carbs to consume per hour of exercise, in grams (gut-limited, not per-kg)."""
    hours = max(duration_s, 0) / 3600.0
    base = 0.0
    for upper, g_h in _CARB_BRACKETS:
        if hours < upper:
            base = g_h
            break
    target = base * _INTENSITY_CARB_FACTOR.get(intensity, 1.0)
    return round(min(target, MAX_CARB_G_H))


def needs_mtc(carb_g_h: float) -> bool:
    """Above ~60 g/h a glucose:fructose (2:1) blend is required for absorption."""
    return carb_g_h > MTC_THRESHOLD_G_H


def estimate_sweat_rate(
    body_weight_kg: float,
    intensity: str = "endurance",
    temp_c: float | None = None,
) -> float:
    """Estimated sweat rate in L/h from body weight, intensity and temperature.
    A starting point only — athletes should override with a measured rate."""
    rate = body_weight_kg * BASE_SWEAT_L_H_PER_KG
    rate *= _INTENSITY_SWEAT_FACTOR.get(intensity, 1.0)
    if temp_c is not None and temp_c >= HOT_TEMP_C:
        rate *= HOT_WEATHER_FACTOR
    return round(max(SWEAT_RATE_MIN_L_H, min(rate, SWEAT_RATE_MAX_L_H)), 2)


def hydration_target_per_hour(sweat_rate_l_h: float) -> float:
    """Fluid to drink per hour, in mL: ~80% of sweat losses, safety-capped."""
    ml = sweat_rate_l_h * 1000.0 * FLUID_REPLACE_FRACTION
    return round(min(ml, MAX_FLUID_ML_H))


def sodium_target_per_hour(sweat_rate_l_h: float) -> float:
    """Sodium to replace per hour, in mg, scaled by sweat rate."""
    mg = sweat_rate_l_h * SODIUM_MG_PER_L_SWEAT
    return round(max(SODIUM_MIN_MG_H, min(mg, SODIUM_MAX_MG_H)))


def gel_count(carb_g_h: float, gel_carb_g: float = DEFAULT_GEL_CARB_G) -> int:
    """Gels per hour (rounded up) to hit the hourly carb target."""
    if carb_g_h <= 0 or gel_carb_g <= 0:
        return 0
    return math.ceil(carb_g_h / gel_carb_g)


# ── Body-weight-scaled daily / pre-race / recovery carbs ─────────────────────


def daily_carb_target(body_weight_kg: float, weekly_hours: float) -> float:
    """Total daily carbohydrate in grams, scaled by training volume."""
    daily_hours = max(weekly_hours or 0.0, 0.0) / 7.0
    for upper, g_kg in _DAILY_CARB_G_KG:
        if daily_hours < upper:
            return round(body_weight_kg * g_kg)
    return round(body_weight_kg * _DAILY_CARB_G_KG[-1][1])


def pre_race_meal_target(body_weight_kg: float) -> dict:
    """Carbs for the pre-race meal (3-4 h out) and snack (~1 h out), grams."""
    return {
        "meal_3h_g": round(body_weight_kg * PRE_RACE_MEAL_G_KG),
        "snack_1h_g": round(body_weight_kg * PRE_RACE_SNACK_G_KG),
    }


def recovery_target(body_weight_kg: float) -> float:
    """Carbs within ~30 min after a long/hard session, grams (1.2 g/kg)."""
    return round(body_weight_kg * RECOVERY_G_KG)


# ── Per-workout fueling ───────────────────────────────────────────────────────


def compute_workout_fueling(workout: dict, profile: dict) -> dict:
    """Full fueling targets for one planned workout.

    `workout` needs duration_s (+ optional intensity); `profile` may carry
    body_weight_kg, sweat_rate_l_h (measured override) and gel_carb_g.
    Hydration/sodium are omitted (None) when body weight is unknown.
    """
    duration_s = float(workout.get("duration_s") or 0)
    intensity = workout.get("intensity") or "endurance"
    hours = duration_s / 3600.0

    if duration_s < MIN_FUELED_DURATION_S:
        return {
            "needed": False,
            "duration_s": int(duration_s),
            "note": "Under 45 min — no in-session fueling needed. Start well-hydrated.",
        }

    carb_g_h = carb_target_per_hour(duration_s, intensity)
    gel_g = float(profile.get("gel_carb_g") or DEFAULT_GEL_CARB_G)
    gels_h = gel_count(carb_g_h, gel_g)

    weight = profile.get("body_weight_kg")
    sweat = profile.get("sweat_rate_l_h")
    fluid_ml_h = sodium_mg_h = None
    if sweat is None and weight:
        sweat = estimate_sweat_rate(float(weight), intensity)
    if sweat:
        fluid_ml_h = hydration_target_per_hour(float(sweat))
        sodium_mg_h = sodium_target_per_hour(float(sweat))

    out = {
        "needed": carb_g_h > 0 or fluid_ml_h is not None,
        "duration_s": int(duration_s),
        "intensity": intensity,
        "carb_g_h": carb_g_h,
        "carb_total_g": round(carb_g_h * hours),
        "mtc_required": needs_mtc(carb_g_h),
        "gel_carb_g": gel_g,
        "gels_per_hour": gels_h,
        "gels_total": math.ceil(carb_g_h * hours / gel_g) if carb_g_h else 0,
        "high_carb_gels_total": math.ceil(carb_g_h * hours / HIGH_CARB_GEL_G) if carb_g_h else 0,
        "sweat_rate_l_h": round(float(sweat), 2) if sweat else None,
        "fluid_ml_h": fluid_ml_h,
        "fluid_total_ml": round(fluid_ml_h * hours) if fluid_ml_h else None,
        "sodium_mg_h": sodium_mg_h,
        "sodium_total_mg": round(sodium_mg_h * hours) if sodium_mg_h else None,
    }
    if weight:
        out["recovery_carb_g"] = recovery_target(float(weight))
    else:
        out["note"] = "Set body weight in Thresholds for hydration & sodium targets."
    return out


def fueling_note(fueling: dict) -> str:
    """One-line fueling summary to append to a workout description / export."""
    if not fueling.get("needed"):
        return ""
    parts = [f"Fuel: {fueling['carb_g_h']:.0f} g carbs/h (~{fueling['gels_per_hour']} gels/h)"]
    if fueling.get("mtc_required"):
        parts.append("use glucose:fructose blend")
    if fueling.get("fluid_ml_h"):
        parts.append(f"{fueling['fluid_ml_h']:.0f} mL fluid/h")
    if fueling.get("sodium_mg_h"):
        parts.append(f"{fueling['sodium_mg_h']:.0f} mg sodium/h")
    return " · ".join(parts)


# ── Race-day plan (deterministic fallback / LLM prior) ───────────────────────

# Fallback projected leg durations (seconds) when readiness has no projection.
_DEFAULT_LEGS_S = {
    "70.3": {"swim": 45 * 60, "bike": 3 * 3600, "run": 2 * 3600},
    "140.6": {"swim": 80 * 60, "bike": 6 * 3600 + 30 * 60, "run": 4 * 3600 + 30 * 60},
}


def compute_race_day_plan(profile: dict, race: dict, readiness: dict | None = None) -> dict:
    """Deterministic race-day fueling timeline. Used directly when the LLM is
    unavailable and as the structural prior handed to the LLM otherwise."""
    weight = profile.get("body_weight_kg")
    gel_g = float(profile.get("gel_carb_g") or DEFAULT_GEL_CARB_G)
    distance = str(race.get("distance") or "70.3")
    legs = dict(_DEFAULT_LEGS_S.get(distance, _DEFAULT_LEGS_S["70.3"]))
    for name in ("swim", "bike", "run"):
        proj = ((readiness or {}).get("legs") or {}).get(name, {}).get("seconds")
        if proj:
            legs[name] = int(proj)

    sweat = profile.get("sweat_rate_l_h")
    if sweat is None and weight:
        sweat = estimate_sweat_rate(float(weight), "tempo")

    items: list[dict] = []
    adjustments: list[str] = []

    if weight:
        pre = pre_race_meal_target(float(weight))
        items.append({
            "phase": "pre_race", "offset_min": -210, "label": "Pre-race meal",
            "carbs_g": pre["meal_3h_g"], "fluid_ml": 500, "sodium_mg": None,
            "notes": "Familiar, low-fibre carbs: oatmeal, toast, banana. 3-4 h before the start.",
        })
        items.append({
            "phase": "pre_race", "offset_min": -60, "label": "Pre-race snack",
            "carbs_g": pre["snack_1h_g"], "fluid_ml": 300, "sodium_mg": 300,
            "notes": "Energy bar or sports drink; sip electrolytes until the start.",
        })
    else:
        adjustments.append("Body weight not set — pre-race and recovery amounts omitted.")

    items.append({
        "phase": "swim", "offset_min": 0, "label": "Swim",
        "carbs_g": 0, "fluid_ml": 0, "sodium_mg": 0,
        "notes": "No fueling. Optionally one gel with a sip of water 15 min before the gun.",
    })

    bike_h = legs["bike"] / 3600.0
    bike_carb_h = carb_target_per_hour(legs["bike"], "tempo")
    bike_fluid_h = hydration_target_per_hour(float(sweat)) if sweat else None
    bike_sodium_h = sodium_target_per_hour(float(sweat)) if sweat else None
    items.append({
        "phase": "t1", "offset_min": round(legs["swim"] / 60), "label": "T1",
        "carbs_g": round(gel_g), "fluid_ml": 200, "sodium_mg": None,
        "notes": "One gel + water while transitioning.",
    })
    items.append({
        "phase": "bike", "offset_min": round(legs["swim"] / 60) + 5, "label": "Bike",
        "phase_duration_s": legs["bike"],
        "carbs_g": round(bike_carb_h * bike_h),
        "fluid_ml": round(bike_fluid_h * bike_h) if bike_fluid_h else None,
        "sodium_mg": round(bike_sodium_h * bike_h) if bike_sodium_h else None,
        "notes": (
            f"{bike_carb_h:.0f} g/h — one {gel_g:.0f} g gel every "
            f"{max(round(60 * gel_g / bike_carb_h), 15) if bike_carb_h else 60} min"
            + (" (glucose:fructose blend above 60 g/h)" if needs_mtc(bike_carb_h) else "")
            + ". The bike is where most of the eating happens."
        ),
    })

    run_h = legs["run"] / 3600.0
    run_carb_h = min(carb_target_per_hour(legs["run"], "tempo"), 60.0)  # gut is harder on the run
    run_fluid_h = hydration_target_per_hour(float(sweat)) if sweat else None
    run_sodium_h = sodium_target_per_hour(float(sweat)) if sweat else None
    bike_done_min = round((legs["swim"] + 5 * 60 + legs["bike"]) / 60)
    items.append({
        "phase": "t2", "offset_min": bike_done_min, "label": "T2",
        "carbs_g": round(gel_g), "fluid_ml": 200, "sodium_mg": None,
        "notes": "One gel + water heading out on the run.",
    })
    items.append({
        "phase": "run", "offset_min": bike_done_min + 3, "label": "Run",
        "phase_duration_s": legs["run"],
        "carbs_g": round(run_carb_h * run_h),
        "fluid_ml": round(run_fluid_h * run_h) if run_fluid_h else None,
        "sodium_mg": round(run_sodium_h * run_h) if run_sodium_h else None,
        "notes": f"{run_carb_h:.0f} g/h — gel or chews every 20-25 min, fluid at every aid station.",
    })

    if weight:
        items.append({
            "phase": "post_race", "offset_min": bike_done_min + 3 + round(run_h * 60) + 15,
            "label": "Recovery", "carbs_g": recovery_target(float(weight)),
            "fluid_ml": 500, "sodium_mg": 500,
            "notes": "1.2 g/kg carbs + ~25 g protein within 30 min of finishing.",
        })

    total_carbs = sum(i.get("carbs_g") or 0 for i in items if i["phase"] in ("t1", "bike", "t2", "run"))
    plan = {
        "race": {"name": race.get("name"), "date": race.get("date"), "distance": distance},
        "summary": (
            f"~{total_carbs} g carbs across the race "
            f"(bike {bike_carb_h:.0f} g/h, run {run_carb_h:.0f} g/h)"
            + (f", fluid ~{bike_fluid_h:.0f} mL/h" if bike_fluid_h else "")
            + ". Practice this in training — nothing new on race day."
        ),
        "gel_carb_g": gel_g,
        "items": items,
        "llm_used": False,
    }
    plan, notes = validate_fueling(plan)
    plan["adjustments"] = adjustments + notes
    return plan


# ── Safety validation ─────────────────────────────────────────────────────────


# Absolute per-item ceilings for phases with no duration, where hourly rates
# don't apply (transitions are a minute or two; meals are single sittings).
_TRANSITION_PHASES = {"t1", "t2"}
_TRANSITION_MAX_CARB_G = 80.0
_TRANSITION_MAX_FLUID_ML = 500.0
_MEAL_MAX_CARB_G = 300.0  # pre/post-race items; 2.5 g/kg tops out below this
_MEAL_MAX_FLUID_ML = 1000.0


def validate_fueling(plan: dict) -> tuple[dict, list[str]]:
    """Clamp unsafe fueling values; return (corrected_plan, adjustments).

    Bounds: carbs <= 120 g/h; fluid <= 1000 mL/h; plain fluid > 750 mL/h needs
    sodium alongside. Mirrors planning/validator.py: the LLM proposes, this
    disposes.

    Rates are checked per PHASE, summed across the phase's items — the LLM may
    split a leg into several timeline entries, each individually under the cap
    while their sum is not. Items in phases with no duration (transitions,
    pre/post-race meals) get absolute per-item ceilings instead.
    """
    notes: list[str] = []
    out = dict(plan)
    items = [dict(i) for i in plan.get("items", [])]

    # A phase's duration is defined by any of its items carrying phase_duration_s.
    phase_dur_h: dict[str, float] = {}
    for i in items:
        d = _phase_hours(i)
        if d and i.get("phase") and i["phase"] not in phase_dur_h:
            phase_dur_h[i["phase"]] = d

    # 1) Phases with a duration: rate-check the phase totals, scale members to fit.
    for phase, dur_h in phase_dur_h.items():
        members = [i for i in items if i.get("phase") == phase]

        carbs = sum(i.get("carbs_g") or 0 for i in members)
        if carbs > MAX_CARB_G_H * dur_h:
            scale = (MAX_CARB_G_H * dur_h) / carbs
            for i in members:
                if i.get("carbs_g"):
                    i["carbs_g"] = round(i["carbs_g"] * scale)
            notes.append(f"{phase}: carbs capped at {MAX_CARB_G_H:.0f} g/h.")
            carbs = MAX_CARB_G_H * dur_h
        if carbs / dur_h > MTC_THRESHOLD_G_H:
            for i in members:
                if i.get("carbs_g") and "fructose" not in (i.get("notes") or "").lower():
                    i["notes"] = ((i.get("notes") or "")
                                  + " Use a glucose:fructose blend above 60 g/h.").strip()

        fluid = sum(i.get("fluid_ml") or 0 for i in members)
        if fluid > MAX_FLUID_ML_H * dur_h:
            scale = (MAX_FLUID_ML_H * dur_h) / fluid
            for i in members:
                if i.get("fluid_ml"):
                    i["fluid_ml"] = round(i["fluid_ml"] * scale)
            notes.append(f"{phase}: fluid capped at {MAX_FLUID_ML_H:.0f} mL/h (hyponatremia risk).")
            fluid = MAX_FLUID_ML_H * dur_h
        if fluid / dur_h > PLAIN_WATER_SAFE_ML_H and not any(i.get("sodium_mg") for i in members):
            notes.append(f"{phase}: >750 mL/h fluid without sodium — add electrolytes.")

    # 2) Duration-less items: absolute ceilings so a rogue amount can't hide
    #    where no rate check applies.
    for i in items:
        phase = i.get("phase")
        if phase in phase_dur_h or phase == "swim":
            continue
        label = i.get("label") or phase
        is_transition = phase in _TRANSITION_PHASES
        carb_cap = _TRANSITION_MAX_CARB_G if is_transition else _MEAL_MAX_CARB_G
        fluid_cap = _TRANSITION_MAX_FLUID_ML if is_transition else _MEAL_MAX_FLUID_ML
        if (i.get("carbs_g") or 0) > carb_cap:
            i["carbs_g"] = round(carb_cap)
            notes.append(f"{label}: carbs capped at {carb_cap:.0f} g.")
        if (i.get("fluid_ml") or 0) > fluid_cap:
            i["fluid_ml"] = round(fluid_cap)
            notes.append(f"{label}: fluid capped at {fluid_cap:.0f} mL.")

    out["items"] = items
    return out, notes


def apply_llm_timeline(base_plan: dict, llm_out: dict) -> dict:
    """Overlay an LLM-generated timeline onto the deterministic plan.

    The deterministic plan is the safety prior: we keep each phase's duration so
    the safety validator can still rate-check the LLM's amounts, then re-clamp.
    """
    durations = {
        i.get("phase"): i.get("phase_duration_s")
        for i in base_plan.get("items", [])
        if i.get("phase_duration_s")
    }
    items: list[dict] = []
    for raw in llm_out.get("items", []):
        item = dict(raw)
        dur = durations.get(item.get("phase"))
        if dur:
            item["phase_duration_s"] = dur
        items.append(item)

    plan = dict(base_plan)
    plan["items"] = items or base_plan.get("items", [])
    if llm_out.get("summary"):
        plan["summary"] = llm_out["summary"]
    plan["llm_used"] = True
    plan, notes = validate_fueling(plan)
    plan["adjustments"] = notes
    return plan


def _phase_hours(item: dict) -> float | None:
    """Approximate the duration an item's totals are spread over (for rate checks).
    Only the long phases carry meaningful hourly rates."""
    dur = item.get("duration_s") or item.get("phase_duration_s")
    if dur:
        return float(dur) / 3600.0
    return None
