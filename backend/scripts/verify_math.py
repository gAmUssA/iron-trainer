"""Independent numerical validation of every domain calculation, run against the
REAL app code (not reimplementations, except where a brute-force reference is the
point). Each check prints PASS/FAIL with the numbers so a human can eyeball them.
Run: uv run python scripts/verify_math.py
"""

import math

failures = []


def check(name, got, want, tol=1e-6):
    ok = (got is None and want is None) or (
        got is not None and want is not None and abs(got - want) <= tol
    )
    print(f"{'PASS' if ok else 'FAIL':4}  {name}: got={got} want={want}")
    if not ok:
        failures.append(name)


print("── Normalized Power (metrics.normalized_power) " + "─" * 30)
from app.metrics import normalized_power

# Reference: independent brute-force NP (30-sample rolling mean, ^4, mean, ^0.25).
def np_reference(vals):
    rolled = [sum(vals[i - 29 : i + 1]) / 30 for i in range(29, len(vals))]
    return round((sum(r**4 for r in rolled) / len(rolled)) ** 0.25)

const = [200.0] * 3600
check("NP constant 200W == 200", normalized_power(const), 200)
check("NP constant == brute-force ref", normalized_power(const), np_reference(const))

# 30s alternating 150/250W blocks — NP must exceed the 200W average (variability
# costs more) and match the independent reference exactly.
alt = ([150.0] * 30 + [250.0] * 30) * 30
check("NP alternating == brute-force ref", normalized_power(alt), np_reference(alt))
assert normalized_power(alt) > 200, "NP of variable power must exceed average"
print(f"      (avg=200, NP={normalized_power(alt)} — correctly > avg)")

# Hand-computed closed form: half the 30s windows at 150W, half at 250W is wrong —
# windows straddle blocks. But an extreme case has a closed form: 0W/400W step
# function with window fully inside each half.
step = [0.0] * 1800 + [400.0] * 1800
ref = np_reference(step)
check("NP step-function == brute-force ref", normalized_power(step), ref)
check("NP <30 samples → None", normalized_power([100.0] * 29), None)

print("── TSS / IF (metrics.compute_tss) " + "─" * 44)
from app.metrics import Thresholds, compute_tss

th = Thresholds(ftp=250, threshold_hr=165, threshold_pace_run=300.0, css_swim=105.0)
# Canonical definition: 1 h at exactly FTP → IF=1.0, TSS=100.
tss, if_, method = compute_tss("Bike", moving_time=3600, distance=None,
                               weighted_power=250, avg_power=250, avg_hr=None, th=th)
check("bike 1h @FTP → TSS 100", tss, 100, tol=0.5)
check("bike 1h @FTP → IF 1.0", if_, 1.0, tol=0.005)
# 2 h at 0.7 IF → 2*0.49*100 = 98.
tss2, if2, _ = compute_tss("Bike", moving_time=7200, distance=None,
                           weighted_power=175, avg_power=175, avg_hr=None, th=th)
check("bike 2h @0.7IF → TSS 98", tss2, 98, tol=0.5)
# Run: 1 h at threshold pace (300 s/km → 12 km in 1 h) → rTSS ≈ 100.
tssr, ifr, mr = compute_tss("Run", moving_time=3600, distance=12000, weighted_power=None,
                            avg_power=None, avg_hr=None, th=th)
check(f"run 1h @threshold pace ({mr}) → TSS 100", tssr, 100, tol=1)
# Swim: 1 h at CSS (105 s/100m → 3600/105*100 = 3428.6 m) → sTSS ≈ 100.
tsss, _, ms = compute_tss("Swim", moving_time=3600, distance=3600 / 105 * 100,
                          weighted_power=None, avg_power=None, avg_hr=None, th=th)
check(f"swim 1h @CSS ({ms}) → TSS 100", tsss, 100, tol=1)
# Degenerate inputs must not blow up.
t0 = compute_tss("Bike", moving_time=0, distance=0, weighted_power=None,
                 avg_power=None, avg_hr=None, th=th)
print(f"      zero moving_time → {t0} (no crash)")
tn = compute_tss("Run", moving_time=3600, distance=12000, weighted_power=None,
                 avg_power=None, avg_hr=None, th=Thresholds())
print(f"      no thresholds set → {tn} (no crash)")

print("── CTL/ATL exponential averages (metrics.performance_management) " + "─" * 12)
from datetime import date, timedelta

from app import metrics as m

print("      app constants: CTL τ=%d, ATL τ=%d" % (m.CTL_TIME_CONSTANT, m.ATL_TIME_CONSTANT))
d0 = date(2026, 1, 1)
acts = [(d0 + timedelta(days=i), 100.0) for i in range(300)]
out = m.performance_management(acts, end=d0 + timedelta(days=299))
check("CTL steady-state @100 TSS/day → 100", out[-1].ctl, 100, tol=1.5)
check("ATL steady-state @100 TSS/day → 100", out[-1].atl, 100, tol=1.5)
# Closed form from 0 after k days: 100·(1−(1−1/42)^k) — day k is index k−1.
expect = 100 * (1 - (1 - 1 / 42) ** 42)
check("CTL day-42 matches closed form (63.6%)", out[41].ctl, expect, tol=0.1)
check("ATL day-7 matches closed form (66.0%)", out[6].atl,
      100 * (1 - (1 - 1 / 7) ** 7), tol=0.1)
check("TSB uses YESTERDAY's ctl−atl (day 100)", out[100].tsb,
      round(out[99].ctl - out[99].atl, 1), tol=0.11)
# Rest-day decay: stop training, CTL must decay by factor (1−1/42)/day.
acts2 = [(d0 + timedelta(days=i), 100.0) for i in range(100)]
out2 = m.performance_management(acts2, end=d0 + timedelta(days=130))
decay = out2[110].ctl / out2[109].ctl
check("CTL decays ×(1−1/42) on rest days", decay, 1 - 1 / 42, tol=0.002)

print("── Fitness tests (fitness_tests.compute) " + "─" * 37)
from app import fitness_tests as ft

r = ft.compute("bike-ftp-20", {"avg_power_w": 300})
check("FTP = 0.95 × 20-min power (300→285)", r["ftp"], 285)
r = ft.compute("run-lthr-30", {"time_s": 1800, "distance_m": 6000, "avg_hr_last20": 172})
check("run threshold pace = t/(d/1000) (30min/6km→300 s/km)", r["threshold_pace_run"], 300)
check("LTHR = final-20min HR", r["threshold_hr"], 172)
r = ft.compute("swim-css-400-200", {"t400_s": 360, "t200_s": 168})
# CSS pace/100m = (T400-T200)/(400-200)m = (360-168)/2 per 100m = 96
check("CSS = (T400−T200)/2 per 100m (360,168→96)", r["css_swim"], 96)

print("── Nutrition: carb brackets vs Jeukendrup 2014 " + "─" * 31)
from app import nutrition as n

# Literature: <45min none; ~1h 30g/h; 1-2h 30-60; 2-2.5h 60; >2.5h up to 90.
for dur_h, want in [(0.5, 0), (1.0, 30), (1.5, 45), (2.25, 60), (2.75, 75), (4.0, 90)]:
    check(f"carbs @{dur_h}h endurance → {want} g/h",
          n.carb_target_per_hour(dur_h * 3600), want)
check("threshold bump 2.25h → 66 g/h (60×1.1)", n.carb_target_per_hour(2.25 * 3600, "threshold"), 66)
check("ceiling: 4h threshold → 99 ≤ 120", n.carb_target_per_hour(4 * 3600, "threshold"), 99)
assert n.needs_mtc(61) and not n.needs_mtc(60), "MTC threshold must be >60 strictly"
print("      MTC required strictly above 60 g/h: OK")

print("── Nutrition: hydration & sodium vs ACSM/Noakes bounds " + "─" * 23)
sw = n.estimate_sweat_rate(70)
check("sweat 70kg temperate → 0.84 L/h", sw, 0.84)
check("sweat 70kg hot (30°C) → 0.84×1.5=1.26", n.estimate_sweat_rate(70, temp_c=30), 1.26)
check("sweat clamps at 2.5 L/h (150kg, hot, threshold)",
      n.estimate_sweat_rate(150, "threshold", 35), 2.5)
check("sweat floor 0.4 L/h (30kg recovery)", n.estimate_sweat_rate(30, "recovery"), 0.4)
check("hydration = 80% of 1.0 L/h → 800 mL", n.hydration_target_per_hour(1.0), 800)
check("hydration capped 1000 mL/h (2.5 L/h sweat)", n.hydration_target_per_hour(2.5), 1000)
check("sodium 1.0 L/h → 500 mg/h", n.sodium_target_per_hour(1.0), 500)
check("sodium floor 300 (0.4 L/h→200→300)", n.sodium_target_per_hour(0.4), 300)
check("sodium cap 1000 (2.5 L/h→1250→1000)", n.sodium_target_per_hour(2.5), 1000)
check("gels: 75 g/h ÷ 25 g → 3/h", n.gel_count(75), 3)
check("gels round UP: 61 g/h ÷ 25 g → 3/h", n.gel_count(61), 3)

print("── Nutrition: daily carbs vs Burke 2011 g/kg " + "─" * 33)
check("70kg, 5h/wk (0.71h/d <1) → 5 g/kg = 350", n.daily_carb_target(70, 5), 350)
check("70kg, 10h/wk (1.43h/d 1-3) → 8 g/kg = 560", n.daily_carb_target(70, 10), 560)
check("70kg, 25h/wk (3.6h/d >3) → 11 g/kg = 770", n.daily_carb_target(70, 25), 770)
check("pre-race meal 2.5 g/kg (70kg→175)", n.pre_race_meal_target(70)["meal_3h_g"], 175)
check("recovery 1.2 g/kg (70kg→84)", n.recovery_target(70), 84)

print("── Race-day plan internal consistency (70.3, 70kg) " + "─" * 27)
plan = n.compute_race_day_plan({"body_weight_kg": 70}, {"distance": "70.3", "name": "x", "date": "d"})
items = {i["phase"]: i for i in plan["items"]}
bike = items["bike"]
bike_h = bike["phase_duration_s"] / 3600
carb_rate = bike["carbs_g"] / bike_h
fluid_rate = bike["fluid_ml"] / bike_h
print(f"      bike: {bike['carbs_g']} g over {bike_h:.2f} h = {carb_rate:.1f} g/h; "
      f"fluid {fluid_rate:.0f} mL/h; sodium {bike['sodium_mg'] / bike_h:.0f} mg/h")
assert carb_rate <= 120.5, "bike carb rate exceeds physiological ceiling"
assert fluid_rate <= 1000.5, "bike fluid rate exceeds hyponatremia cap"
run = items["run"]
run_rate = run["carbs_g"] / (run["phase_duration_s"] / 3600)
check("run carb rate capped at 60 g/h", min(run_rate, 60.0), run_rate, tol=0.5)
# Timeline monotonicity: offsets strictly increase after the gun.
offs = [i["offset_min"] for i in plan["items"]]
assert all(a <= b for a, b in zip(offs, offs[1:])), f"timeline offsets not ordered: {offs}"
print(f"      timeline offsets ordered: {offs}")

print("── Validator adversarial re-check (post-fix) " + "─" * 33)
# Split-phase evasion now caught:
p = {"items": [{"phase": "bike", "phase_duration_s": 3 * 3600, "carbs_g": 150, "label": f"h{i}"}
               for i in range(3)]}
fixed, notes = n.validate_fueling(p)
total = sum(i["carbs_g"] for i in fixed["items"])
check("3×150g over 3h (150 g/h) → clamped to ≤360 (120 g/h)", min(total, 360), total, tol=2)
# Transition bomb now caught:
p = {"items": [{"phase": "t1", "label": "T1", "carbs_g": 400, "fluid_ml": 2000}]}
fixed, _ = n.validate_fueling(p)
check("T1 400g → 80g clamp", fixed["items"][0]["carbs_g"], 80)
check("T1 2000mL → 500mL clamp", fixed["items"][0]["fluid_ml"], 500)

print("── Pace→speed (fit_export, mirrored in iOS) " + "─" * 34)
from app.export.fit_export import _pace_to_speed

check("5:00/km = 300 s/km → 3.333 m/s", _pace_to_speed(300, "sec_per_km"), 1000 / 300, tol=1e-9)
check("1:45/100m = 105 s → 0.952 m/s", _pace_to_speed(105, "sec_per_100m"), 100 / 105, tol=1e-9)
# Range semantics: faster pace (smaller sec) must map to HIGHER speed.
assert _pace_to_speed(280, "sec_per_km") > _pace_to_speed(320, "sec_per_km")
print("      faster pace → higher m/s: OK (iOS ItwToWorkoutKit uses same formula + min/max swap)")

print()
if failures:
    print(f"❌ {len(failures)} FAILED: {failures}")
    raise SystemExit(1)
print("✅ all numerical checks passed")
