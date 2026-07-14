"""Daily readiness call: go hard / go easy / rest, from the athlete's own history.

The primary signal is the acute:chronic workload ratio (ACWR, Gabbett 2016):
the last 7 days of training load against the athlete's own 4-week weekly norm.
A ratio near 1.0 means the athlete is training at a load their body is adapted
to; the injury/overreaching literature puts the danger zone above ~1.3-1.5.
TSB (form, from the existing performance-management series) and back-to-back
hard days act as secondary modifiers so a "steady" ratio can still yield an
easy call when the athlete is digging a hole.

Design rule carried through every consumer: signal, not noise. A green call is
one quiet line; only amber/red days deserve attention.
"""

from __future__ import annotations

from datetime import date, timedelta

# ACWR bands (rolling-average flavour). Steady is ~0.8-1.3; risk climbs past
# 1.3 and sharply past 1.5.
ACWR_HIGH = 1.5
ACWR_ELEVATED = 1.3
ACWR_LOW = 0.8

# TSB thresholds — same conventions as planning.service._form_flag.
TSB_FATIGUED = -25.0
TSB_FRESH = 10.0

# A "hard day" for streak purposes: meaningfully above the athlete's own
# chronic daily average, and not a trivial absolute load.
HARD_DAY_FACTOR = 1.5
HARD_DAY_MIN_TSS = 50.0

# Below this many days of history (or a near-zero chronic load) the ratio is
# statistically meaningless — say so instead of faking a call.
MIN_HISTORY_DAYS = 14
MIN_CHRONIC_WEEKLY_TSS = 30.0


def compute(metrics_rows: list[dict], *, today: date | None = None) -> dict:
    """Compute today's readiness from the daily metrics series.

    `metrics_rows` is repo.get_metrics() output: dicts with ISO `date`, `tss`,
    `ctl`, `atl`, `tsb`, ordered by date. Pure function; no I/O.
    """
    today = today or date.today()
    by_day: dict[date, dict] = {}
    for r in metrics_rows:
        try:
            by_day[date.fromisoformat(str(r["date"])[:10])] = r
        except (KeyError, ValueError, TypeError):
            continue

    # History window ends yesterday: today's not-yet-done training must not
    # count against today's call.
    days_present = [d for d in by_day if d < today]
    if not days_present or (today - min(days_present)).days < MIN_HISTORY_DAYS:
        return {
            "status": "insufficient_data",
            "call": None,
            "level": None,
            "reasons": ["Not enough training history yet for a readiness call (need ~2 weeks of data)."],
        }

    def _tss(d: date) -> float:
        return float(by_day.get(d, {}).get("tss") or 0.0)

    acute = sum(_tss(today - timedelta(days=i)) for i in range(1, 8))
    total_28 = sum(_tss(today - timedelta(days=i)) for i in range(1, 29))
    chronic_weekly = total_28 / 4.0

    latest_day = max(days_present)
    latest = by_day[latest_day]
    tsb = latest.get("tsb")
    ctl = latest.get("ctl")
    # TSB decays toward zero the moment training stops, but the stored series
    # only advances when a sync rebuilds it. A row more than a few days old
    # can't veto today's call (a lapsed sync is not deep fatigue).
    tsb_current = (today - latest_day).days <= 3

    if chronic_weekly < MIN_CHRONIC_WEEKLY_TSS:
        return {
            "status": "insufficient_data",
            "call": None,
            "level": None,
            "acute_7d": round(acute, 1),
            "chronic_weekly": round(chronic_weekly, 1),
            "tsb": tsb,
            "ctl": ctl,
            "reasons": ["Recent training volume is too low to compute a meaningful load ratio."],
        }

    acwr = acute / chronic_weekly

    # Trailing streak of hard days (yesterday backwards).
    chronic_daily = chronic_weekly / 7.0
    hard_cut = max(HARD_DAY_MIN_TSS, HARD_DAY_FACTOR * chronic_daily)
    streak = 0
    d = today - timedelta(days=1)
    while _tss(d) >= hard_cut:
        streak += 1
        d -= timedelta(days=1)

    reasons: list[str] = []
    if acwr > ACWR_HIGH:
        call, level = "rest", "red"
        reasons.append(
            f"Load spike: your 7-day load ({acute:.0f} TSS) is {acwr:.2f}x your 4-week norm "
            f"({chronic_weekly:.0f}/wk) — injury-risk territory. Absorb it."
        )
    elif acwr > ACWR_ELEVATED:
        call, level = "easy", "amber"
        reasons.append(
            f"Load is ramping fast: 7-day load {acute:.0f} TSS vs a 4-week norm of "
            f"{chronic_weekly:.0f} (ratio {acwr:.2f}). Keep today easy and let it settle."
        )
    elif tsb_current and tsb is not None and float(tsb) < TSB_FATIGUED:
        call, level = "easy", "amber"
        reasons.append(
            f"Deep fatigue: form (TSB) is {float(tsb):+.0f}. The load ratio is fine "
            f"({acwr:.2f}), but you're digging a hole — go easy until form recovers."
        )
    elif streak >= 2:
        call, level = "easy", "amber"
        reasons.append(
            f"{streak} hard days in a row (each ≥{hard_cut:.0f} TSS). The adaptation "
            "happens in the easy day — take it."
        )
    else:
        call, level = "hard", "green"
        if acwr < ACWR_LOW:
            tsb_note = (
                f", TSB {float(tsb):+.0f}" if tsb_current and tsb is not None and float(tsb) > TSB_FRESH else ""
            )
            reasons.append(
                f"You're fresh — 7-day load {acute:.0f} TSS is well under your "
                f"{chronic_weekly:.0f}/wk norm (ratio {acwr:.2f}{tsb_note}). "
                "Prime day for a key session."
            )
        else:
            reasons.append(
                f"Load is steady (7-day {acute:.0f} TSS vs {chronic_weekly:.0f}/wk norm, "
                f"ratio {acwr:.2f}). Green light for quality if the plan calls for it."
            )

    return {
        "status": "ok",
        "call": call,
        "level": level,
        "acwr": round(acwr, 2),
        "acute_7d": round(acute, 1),
        "chronic_weekly": round(chronic_weekly, 1),
        "tsb": tsb,
        "ctl": ctl,
        "hard_day_streak": streak,
        "reasons": reasons,
    }


def story_line(r: dict) -> str | None:
    """One check-in story sentence, or None when there's nothing worth saying."""
    if r.get("status") != "ok":
        return None
    call = str(r.get("call") or "").upper()
    reason = (r.get("reasons") or [""])[0]
    return f"Today's call: {call} — {reason}"
