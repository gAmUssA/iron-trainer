from datetime import date

from app import metrics
from app.metrics import Thresholds


def test_normalize_sport():
    assert metrics.normalize_sport("VirtualRide") == "Bike"
    assert metrics.normalize_sport("TrailRun") == "Run"
    assert metrics.normalize_sport("Swim") == "Swim"
    assert metrics.normalize_sport("WeightTraining") == "Other"


def test_power_tss_one_hour_at_ftp_is_100():
    th = Thresholds(ftp=250)
    tss, if_value, method = metrics.compute_tss(
        "Bike", moving_time=3600, distance=40000,
        weighted_power=250, avg_power=240, avg_hr=150, th=th,
    )
    assert method == "power"
    assert if_value == 1.0
    assert tss == 100.0


def test_run_pace_tss_at_threshold_is_100():
    # Threshold pace 300 s/km; run exactly at that pace for 1h -> IF 1.0
    th = Thresholds(threshold_pace_run=300)
    tss, if_value, method = metrics.compute_tss(
        "Run", moving_time=3600, distance=12000,  # 3600s / 12km = 300 s/km
        weighted_power=None, avg_power=None, avg_hr=160, th=th,
    )
    assert method == "pace"
    assert if_value == 1.0
    assert tss == 100.0


def test_duration_fallback_uses_default_if():
    tss, if_value, method = metrics.compute_tss(
        "Other", moving_time=3600, distance=None,
        weighted_power=None, avg_power=None, avg_hr=None, th=Thresholds(),
    )
    assert method == "duration"
    assert if_value == 0.7
    assert tss == 49.0  # 1h * 0.7^2 * 100


def test_zero_duration_is_zero_tss():
    tss, *_ = metrics.compute_tss(
        "Run", moving_time=0, distance=0,
        weighted_power=None, avg_power=None, avg_hr=None, th=Thresholds(),
    )
    assert tss == 0.0


def test_pmc_single_day_increments_ctl_by_tss_over_42():
    day = date(2026, 6, 1)
    series = metrics.performance_management([(day, 100.0)], end=day)
    assert len(series) == 1
    # CTL = 0 + (100 - 0)/42
    assert series[0].ctl == round(100 / 42, 1)
    assert series[0].atl == round(100 / 7, 1)
    assert series[0].tsb == 0.0  # form uses yesterday's (seed) values


def test_pmc_fills_rest_days_with_zero_tss():
    start = date(2026, 6, 1)
    end = date(2026, 6, 5)
    series = metrics.performance_management([(start, 100.0)], end=end, start=start)
    assert [d.day for d in series] == [date(2026, 6, d) for d in range(1, 6)]
    # CTL should decay after the single stimulus day.
    assert series[1].tss == 0.0
    assert series[1].ctl < series[0].ctl + 0.001
