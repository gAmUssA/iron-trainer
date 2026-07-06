"""Unit tests for the pure fueling math in app.nutrition."""

from app import nutrition as n


# ── Carb targets by duration ──────────────────────────────────────────────────


def test_carbs_short_session_none():
    assert n.carb_target_per_hour(30 * 60) == 0  # 30 min
    assert n.carb_target_per_hour(44 * 60) == 0


def test_carbs_one_hour():
    assert n.carb_target_per_hour(60 * 60) == 30


def test_carbs_ninety_minutes():
    assert n.carb_target_per_hour(90 * 60) == 45


def test_carbs_two_hours():
    assert n.carb_target_per_hour(2 * 3600) == 60


def test_carbs_two_and_three_quarters():
    assert n.carb_target_per_hour(2.75 * 3600) == 75


def test_carbs_three_hours_plus():
    assert n.carb_target_per_hour(3 * 3600) == 90
    assert n.carb_target_per_hour(5 * 3600) == 90


def test_carbs_recovery_intensity_reduced():
    assert n.carb_target_per_hour(2 * 3600, "recovery") == 48  # 60 * 0.8


def test_carbs_threshold_intensity_bumped_but_capped():
    assert n.carb_target_per_hour(3 * 3600, "threshold") == 99  # 90 * 1.1 < 120
    assert n.carb_target_per_hour(3 * 3600, "vo2") <= n.MAX_CARB_G_H


def test_mtc_required_above_60():
    assert not n.needs_mtc(60)
    assert n.needs_mtc(61)
    assert n.needs_mtc(90)


# ── Hydration by body weight ──────────────────────────────────────────────────


def test_sweat_rate_70kg_temperate():
    assert n.estimate_sweat_rate(70) == 0.84  # 70 * 0.012


def test_sweat_rate_55kg_and_85kg():
    assert n.estimate_sweat_rate(55) == 0.66
    assert n.estimate_sweat_rate(85) == 1.02


def test_sweat_rate_hot_weather_multiplier():
    assert n.estimate_sweat_rate(70, temp_c=30) == 1.26  # 0.84 * 1.5


def test_sweat_rate_clamped():
    assert n.estimate_sweat_rate(20) == n.SWEAT_RATE_MIN_L_H
    assert n.estimate_sweat_rate(200, "vo2", temp_c=35) == n.SWEAT_RATE_MAX_L_H


def test_hydration_replaces_80_percent():
    assert n.hydration_target_per_hour(1.0) == 800


def test_hydration_capped_at_1L():
    assert n.hydration_target_per_hour(2.5) == n.MAX_FLUID_ML_H


# ── Sodium ────────────────────────────────────────────────────────────────────


def test_sodium_scales_with_sweat():
    assert n.sodium_target_per_hour(1.0) == 500
    assert n.sodium_target_per_hour(1.6) == 800


def test_sodium_clamped():
    assert n.sodium_target_per_hour(0.4) == n.SODIUM_MIN_MG_H
    assert n.sodium_target_per_hour(3.0) == n.SODIUM_MAX_MG_H


# ── Gel math ──────────────────────────────────────────────────────────────────


def test_gel_count_90g_standard():
    assert n.gel_count(90, 25) == 4


def test_gel_count_90g_high_carb():
    assert n.gel_count(90, 40) == 3


def test_gel_count_zero_carbs():
    assert n.gel_count(0) == 0


# ── Daily / pre-race / recovery ───────────────────────────────────────────────


def test_daily_carbs_moderate_volume():
    # 10 h/week -> ~1.4 h/day -> 8 g/kg -> 560 g for 70 kg
    assert n.daily_carb_target(70, 10) == 560


def test_daily_carbs_low_volume():
    # 5 h/week -> <1 h/day -> 5 g/kg
    assert n.daily_carb_target(70, 5) == 350


def test_pre_race_meal():
    pre = n.pre_race_meal_target(70)
    assert pre["meal_3h_g"] == 175  # 2.5 g/kg
    assert pre["snack_1h_g"] == 70  # 1 g/kg


def test_recovery_target():
    assert n.recovery_target(70) == 84  # 1.2 g/kg


# ── Per-workout fueling ───────────────────────────────────────────────────────


def _profile(**kw) -> dict:
    return {"body_weight_kg": 70, "gel_carb_g": 25, **kw}


def test_workout_fueling_short_session_not_needed():
    f = n.compute_workout_fueling({"duration_s": 40 * 60}, _profile())
    assert f["needed"] is False


def test_workout_fueling_long_ride():
    f = n.compute_workout_fueling(
        {"duration_s": 3 * 3600, "intensity": "endurance"}, _profile()
    )
    assert f["needed"] is True
    assert f["carb_g_h"] == 90
    assert f["carb_total_g"] == 270
    assert f["mtc_required"] is True
    assert f["gels_per_hour"] == 4
    assert f["gels_total"] == 11  # ceil(270/25)
    assert f["high_carb_gels_total"] == 7  # ceil(270/40)
    assert f["fluid_ml_h"] == 672  # 0.84 L/h * 0.8
    assert f["sodium_mg_h"] == 420
    assert f["recovery_carb_g"] == 84


def test_workout_fueling_no_body_weight_still_gives_carbs():
    f = n.compute_workout_fueling({"duration_s": 2 * 3600}, {"gel_carb_g": 25})
    assert f["carb_g_h"] == 60
    assert f["fluid_ml_h"] is None
    assert f["sodium_mg_h"] is None
    assert "body weight" in f["note"].lower()


def test_workout_fueling_measured_sweat_rate_override():
    f = n.compute_workout_fueling(
        {"duration_s": 2 * 3600}, _profile(sweat_rate_l_h=1.5)
    )
    assert f["sweat_rate_l_h"] == 1.5
    assert f["fluid_ml_h"] == 1000  # 1200 capped


def test_fueling_note_mentions_gels_and_blend():
    f = n.compute_workout_fueling({"duration_s": 3 * 3600}, _profile())
    note = n.fueling_note(f)
    assert "90 g carbs/h" in note
    assert "4 gels/h" in note
    assert "glucose:fructose" in note


def test_fueling_note_empty_when_not_needed():
    f = n.compute_workout_fueling({"duration_s": 30 * 60}, _profile())
    assert n.fueling_note(f) == ""


# ── Race-day plan ─────────────────────────────────────────────────────────────


def test_race_day_plan_structure():
    plan = n.compute_race_day_plan(
        _profile(), {"name": "IRONMAN 70.3 New York", "date": "2026-09-26", "distance": "70.3"}
    )
    phases = [i["phase"] for i in plan["items"]]
    for expected in ("pre_race", "swim", "t1", "bike", "t2", "run", "post_race"):
        assert expected in phases
    assert plan["llm_used"] is False
    bike = next(i for i in plan["items"] if i["phase"] == "bike")
    assert bike["carbs_g"] > 0
    assert bike["fluid_ml"] > 0


def test_race_day_plan_uses_readiness_projection():
    readiness = {"legs": {"bike": {"seconds": 4 * 3600}}}
    plan = n.compute_race_day_plan(_profile(), {"distance": "70.3"}, readiness)
    bike = next(i for i in plan["items"] if i["phase"] == "bike")
    assert bike["phase_duration_s"] == 4 * 3600


def test_race_day_plan_without_weight_flags_it():
    plan = n.compute_race_day_plan({"gel_carb_g": 25}, {"distance": "70.3"})
    assert any("weight" in a.lower() for a in plan["adjustments"])
    assert not any(i["phase"] == "post_race" for i in plan["items"])


# ── Safety validation ─────────────────────────────────────────────────────────


def test_validate_caps_excessive_carbs():
    plan = {"items": [{"phase": "bike", "label": "Bike", "phase_duration_s": 3600,
                       "carbs_g": 200, "notes": ""}]}
    fixed, notes = n.validate_fueling(plan)
    assert fixed["items"][0]["carbs_g"] == 120
    assert any("capped" in x for x in notes)


def test_validate_caps_excessive_fluid():
    plan = {"items": [{"phase": "run", "label": "Run", "phase_duration_s": 3600,
                       "fluid_ml": 1500, "sodium_mg": 500}]}
    fixed, notes = n.validate_fueling(plan)
    assert fixed["items"][0]["fluid_ml"] == 1000
    assert any("hyponatremia" in x.lower() for x in notes)


def test_validate_flags_plain_water_over_750():
    plan = {"items": [{"phase": "run", "label": "Run", "phase_duration_s": 3600,
                       "fluid_ml": 800, "sodium_mg": None}]}
    _, notes = n.validate_fueling(plan)
    assert any("sodium" in x.lower() or "electrolyte" in x.lower() for x in notes)


def test_validate_adds_mtc_note_above_60():
    plan = {"items": [{"phase": "bike", "label": "Bike", "phase_duration_s": 3600,
                       "carbs_g": 90, "notes": "eat lots"}]}
    fixed, _ = n.validate_fueling(plan)
    assert "fructose" in fixed["items"][0]["notes"].lower()


# ── LLM timeline overlay ──────────────────────────────────────────────────────


def test_apply_llm_timeline_keeps_durations_and_validates():
    base = n.compute_race_day_plan(
        _profile(), {"distance": "70.3", "name": "Test"}
    )
    llm_out = {
        "summary": "Coach-written plan",
        "items": [
            # LLM proposes an unsafe bike carb load with no duration of its own.
            {"phase": "bike", "label": "Bike", "carbs_g": 999, "fluid_ml": 1200,
             "sodium_mg": 800, "notes": "eat everything"},
        ],
    }
    plan = n.apply_llm_timeline(base, llm_out)
    assert plan["llm_used"] is True
    assert plan["summary"] == "Coach-written plan"
    bike = plan["items"][0]
    # duration was carried over from the deterministic prior so the validator can
    # rate-check and clamp the LLM's unsafe numbers.
    assert bike["phase_duration_s"] > 0
    assert bike["carbs_g"] <= n.MAX_CARB_G_H * (bike["phase_duration_s"] / 3600.0) + 1
    assert bike["fluid_ml"] <= n.MAX_FLUID_ML_H * (bike["phase_duration_s"] / 3600.0) + 1


def test_apply_llm_timeline_falls_back_to_base_items_when_empty():
    base = n.compute_race_day_plan(_profile(), {"distance": "70.3"})
    plan = n.apply_llm_timeline(base, {"items": []})
    assert plan["items"] == base["items"]
    assert plan["llm_used"] is True
