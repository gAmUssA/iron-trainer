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


def compute(metrics_rows: list[dict], *, today: date | None = None,
            recovery: list[dict] | None = None) -> dict:
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

    # ── Recovery modifiers (Health Auto Export data, when fresh) ─────────────
    # Load says one thing; the body may say another. Short sleep, suppressed
    # HRV, or elevated resting HR (each vs the athlete's OWN baseline) can
    # downgrade a green day to easy — never upgrade. Same signal-not-noise
    # rule: silent unless something is genuinely off.
    rec_flags = _recovery_flags(recovery or [], today)
    if rec_flags:
        reasons.extend(rec_flags)
        if call == "hard":
            call, level = "easy", "amber"

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


# User-facing labels — keep in sync with the web/iOS pill copy.
_CALL_LABELS = {"hard": "GO HARD", "easy": "GO EASY", "rest": "REST"}


def story_line(r: dict) -> str | None:
    """One check-in story sentence, or None when there's nothing worth saying."""
    if r.get("status") != "ok":
        return None
    call = str(r.get("call") or "")
    label = _CALL_LABELS.get(call, call.upper())
    reason = (r.get("reasons") or [""])[0]
    return f"Today's call: {label} — {reason}"


RECOVERY_FRESH_DAYS = 2
SHORT_SLEEP_H = 6.0
HRV_SUPPRESSED_RATIO = 0.80
RHR_ELEVATED_BPM = 5.0
MIN_BASELINE_SAMPLES = 5


def _recovery_flags(recovery: list[dict], today: date | None) -> list[str]:
    """Flags from the athlete's own recovery baselines. `recovery` is
    repo.recent_recovery() output: newest-first dicts with date/sleep_h/
    hrv_ms/rhr_bpm. Stale data (no row within RECOVERY_FRESH_DAYS) says
    nothing — a phone that stopped pushing is not a bad night's sleep."""
    today = today or date.today()
    by_date = []
    for r in recovery:
        try:
            d = date.fromisoformat(str(r.get("date"))[:10])
        except (ValueError, TypeError):
            continue
        if d <= today:
            by_date.append((d, r))
    by_date.sort(key=lambda t: t[0], reverse=True)
    if not by_date or (today - by_date[0][0]).days > RECOVERY_FRESH_DAYS:
        return []

    latest_day, latest = by_date[0]
    flags: list[str] = []

    sleep = latest.get("sleep_h")
    if isinstance(sleep, (int, float)) and 0 < sleep < SHORT_SLEEP_H:
        flags.append(f"Short sleep: {sleep:.1f}h last night — recovery took the hit before training did.")

    def _baseline(field: str) -> float | None:
        vals = [r.get(field) for d, r in by_date
                if d < latest_day and isinstance(r.get(field), (int, float))]
        return sum(vals) / len(vals) if len(vals) >= MIN_BASELINE_SAMPLES else None

    hrv = latest.get("hrv_ms")
    hrv_base = _baseline("hrv_ms")
    if isinstance(hrv, (int, float)) and hrv_base and hrv < HRV_SUPPRESSED_RATIO * hrv_base:
        flags.append(
            f"HRV suppressed: {hrv:.0f} ms vs your ~{hrv_base:.0f} ms baseline "
            f"({hrv / hrv_base:.0%}) — the nervous system wants an easy day."
        )

    rhr = latest.get("rhr_bpm")
    rhr_base = _baseline("rhr_bpm")
    if isinstance(rhr, (int, float)) and rhr_base and rhr > rhr_base + RHR_ELEVATED_BPM:
        flags.append(
            f"Resting HR elevated: {rhr:.0f} bpm vs your ~{rhr_base:.0f} bpm baseline — "
            "watch for illness or accumulated fatigue."
        )
    return flags
