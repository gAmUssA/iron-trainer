"""Claude-backed plan generation using structured tool output.

The LLM receives the deterministic template season as a starting prior plus the
athlete's fitness summary, and returns an adjusted plan in a fixed JSON schema
(forced via tool use). The caller always runs the result through the validator,
so the model can adapt focus/volume while safety stays guaranteed.
"""

from __future__ import annotations

import json

from ..config import get_settings
from .. import zones

STEP_SCHEMA = {
    "type": "object",
    "properties": {
        "type": {"type": "string", "enum": ["warmup", "interval", "recovery", "steady", "cooldown"]},
        "duration_s": {"type": ["integer", "null"]},
        "distance_m": {"type": ["number", "null"]},
        "repeat": {"type": ["integer", "null"]},
        "notes": {"type": ["string", "null"]},
        "target": {
            "type": "object",
            "properties": {
                "type": {"type": "string", "enum": ["power", "pace", "hr", "rpe", "open"]},
                "unit": {"type": "string"},
                "low": {"type": ["number", "null"]},
                "high": {"type": ["number", "null"]},
            },
            "required": ["type"],
        },
    },
    "required": ["type"],
}

WORKOUT_SCHEMA = {
    "type": "object",
    "properties": {
        "date": {"type": "string"},
        "sport": {"type": "string", "enum": ["Swim", "Bike", "Run", "Brick", "Strength"]},
        "title": {"type": "string"},
        "description": {"type": "string"},
        "intensity": {
            "type": "string",
            "enum": ["recovery", "endurance", "tempo", "threshold", "vo2"],
        },
        "duration_s": {"type": "integer"},
        "distance_m": {"type": ["number", "null"]},
        "planned_tss": {"type": ["number", "null"]},
        "steps": {"type": "array", "items": STEP_SCHEMA},
    },
    "required": ["date", "sport", "title", "intensity", "duration_s", "steps"],
}

SEASON_SCHEMA = {
    "type": "object",
    "properties": {
        "summary": {"type": "string"},
        "weeks": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "week_index": {"type": "integer"},
                    "week_start": {"type": "string"},
                    "phase": {"type": "string", "enum": ["base", "build", "peak", "taper"]},
                    "is_recovery": {"type": "boolean"},
                    "focus": {"type": "string"},
                    "target_hours": {"type": "number"},
                    "target_tss": {"type": ["number", "null"]},
                },
                "required": ["week_index", "week_start", "phase", "is_recovery", "focus", "target_hours"],
            },
        },
    },
    "required": ["summary", "weeks"],
}


NUTRITION_ITEM_SCHEMA = {
    "type": "object",
    "properties": {
        "phase": {
            "type": "string",
            "enum": ["pre_race", "swim", "t1", "bike", "t2", "run", "post_race"],
        },
        "offset_min": {"type": "integer"},
        "label": {"type": "string"},
        "carbs_g": {"type": ["integer", "null"]},
        "fluid_ml": {"type": ["integer", "null"]},
        "sodium_mg": {"type": ["integer", "null"]},
        "notes": {"type": ["string", "null"]},
    },
    "required": ["phase", "label", "notes"],
}

NUTRITION_PLAN_SCHEMA = {
    "type": "object",
    "properties": {
        "summary": {"type": "string"},
        "items": {"type": "array", "items": NUTRITION_ITEM_SCHEMA},
    },
    "required": ["summary", "items"],
}


class LLMUnavailable(Exception):
    pass


def _client():
    s = get_settings()
    if not s.anthropic_configured:
        raise LLMUnavailable("ANTHROPIC_API_KEY not set.")
    try:
        import anthropic
    except ImportError as e:  # pragma: no cover
        raise LLMUnavailable("anthropic package not installed.") from e
    return anthropic.Anthropic(api_key=s.anthropic_api_key)


def _call_tool(model: str, system: str, user: str, tool_name: str, schema: dict, max_tokens: int) -> dict:
    client = _client()
    resp = client.messages.create(
        model=model,
        max_tokens=max_tokens,
        system=system,
        tools=[{"name": tool_name, "description": f"Return the {tool_name}.", "input_schema": schema}],
        tool_choice={"type": "tool", "name": tool_name},
        messages=[{"role": "user", "content": user}],
    )
    for block in resp.content:
        if block.type == "tool_use":
            return block.input
    raise LLMUnavailable("Model did not return tool output.")


def adjust_season(template_season: dict, profile: dict, fitness: dict) -> dict:
    s = get_settings()
    system = (
        "You are an expert triathlon coach specializing in IRONMAN 70.3. "
        "You adapt a structurally-valid plan skeleton to the athlete's real fitness. "
        "Keep the same weeks and week_start dates. Adjust focus, target_hours and "
        "is_recovery to suit the athlete. Respect a progressive build with recovery "
        "weeks and a 2-week taper. Output via the tool."
    )
    hr = zones.hr_zones(profile.get("threshold_hr"), profile.get("max_hr"))
    user = (
        f"Athlete thresholds: {json.dumps(profile)}\n"
        f"Athlete HR zones ({hr.get('basis') or 'unknown'} basis): {json.dumps(hr.get('zones'))}\n"
        f"Recent fitness (CTL/ATL/TSB & weekly volume): {json.dumps(fitness)}\n\n"
        f"Starting plan skeleton to adapt:\n{json.dumps(template_season)}\n\n"
        "Return an adjusted season for this specific athlete."
    )
    out = _call_tool(s.season_model, system, user, "season_plan", SEASON_SCHEMA, max_tokens=8000)
    merged = dict(template_season)
    merged["summary"] = out.get("summary", template_season.get("summary"))
    merged["weeks"] = out.get("weeks") or template_season["weeks"]
    return merged


def generate_week_workouts(week: dict, profile: dict, context: dict) -> list[dict]:
    s = get_settings()
    system = (
        "You are an expert IRONMAN 70.3 coach. Design one week of concrete, "
        "structured swim/bike/run workouts with warmup/main/cooldown steps and "
        "explicit power (W / %FTP), pace (sec/km or sec/100m) or HR targets derived "
        "from the athlete's thresholds. Anchor every session's intensity to the "
        "athlete's HR zones (Z1 recovery … Z5 VO2max, provided in the context) and "
        "name the zone in the workout description (e.g. 'Z2 endurance ride'); when "
        "power/pace data is missing for a sport, prescribe the step targets as HR "
        "ranges from those zones. Total duration should match the week's "
        "target_hours. Adapt to the athlete's current state in the context: if "
        "form_flag is 'fatigued' (very negative TSB), cut intensity and volume and "
        "favor recovery; if they've been under-completing (low completion_rate or "
        "load_ratio < 1), ease the progression; if 'fresh' and compliant, you may "
        "progress. Output via the tool."
    )
    hr = zones.hr_zones(profile.get("threshold_hr"), profile.get("max_hr"))
    user = (
        f"Week: {json.dumps(week)}\n"
        f"Athlete thresholds: {json.dumps(profile)}\n"
        f"Athlete HR zones ({hr.get('basis') or 'unknown'} basis): {json.dumps(hr.get('zones'))}\n"
        f"Context (recent compliance/form): {json.dumps(context)}\n\n"
        "Return the week's workouts."
    )
    out = _call_tool(
        s.planner_model,
        system,
        user,
        "week_workouts",
        {"type": "object", "properties": {"workouts": {"type": "array", "items": WORKOUT_SCHEMA}}, "required": ["workouts"]},
        max_tokens=8000,
    )
    return out.get("workouts", [])


def generate_race_day_nutrition(
    profile: dict, race: dict, readiness: dict, fueling_targets: dict
) -> dict:
    """Ask the LLM for a concrete race-day fueling timeline, using the
    deterministic targets as the prior. Returns {summary, items}; the caller
    merges/validates it. Raises LLMUnavailable when the LLM can't be used."""
    s = get_settings()
    system = (
        "You are an expert sports nutritionist for IRONMAN 70.3 and 140.6 racing. "
        "You are given deterministic, physiology-based fueling targets (carbs, "
        "fluid, sodium per leg) computed from the athlete's body weight, projected "
        "splits and the research literature. Treat those numbers as a firm prior: "
        "produce a concrete timeline (pre-race meal, pre-race snack, swim, T1, bike, "
        "T2, run, recovery) with specific times (offset_min relative to the swim "
        "start, negative before the gun), real product suggestions, and amounts. "
        "Stay within the given per-hour rates — never exceed 120 g carbs/h or "
        "1000 mL/h, and use a glucose:fructose blend above 60 g/h. Output via the tool."
    )
    user = (
        f"Athlete profile: {json.dumps(profile)}\n"
        f"Race: {json.dumps(race)}\n"
        f"Projected splits / readiness: {json.dumps(readiness)}\n\n"
        f"Deterministic fueling prior to follow:\n{json.dumps(fueling_targets)}\n\n"
        "Return a concrete race-day fueling timeline."
    )
    out = _call_tool(
        s.planner_model, system, user, "race_day_nutrition", NUTRITION_PLAN_SCHEMA, max_tokens=4000
    )
    return {"summary": out.get("summary"), "items": out.get("items") or []}
