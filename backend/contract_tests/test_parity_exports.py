"""Cross-implementation parity: the SAME export requests against FastAPI and
backend-v2 must agree. Skipped unless V2_BASE_URL is set (the parity runner
provides it plus a real bearer paired through the FastAPI API)."""

import json
import os
import subprocess
from datetime import datetime, timedelta, timezone

import httpx
import pytest

V2_BASE_URL = os.environ.get("V2_BASE_URL")
pytestmark = pytest.mark.skipif(not V2_BASE_URL, reason="V2_BASE_URL not set (parity run only)")


@pytest.fixture(scope="session")
def bearer(api, seeded) -> str:
    code = api.post("/api/device/pairing-code").json()["code"]
    out = httpx.post(f"{api.base_url}/api/device/claim",
                     json={"code": code, "device_name": "parity"}, timeout=30)
    out.raise_for_status()
    return out.json()["token"]


def _psql(sql: str) -> str:
    """Run SQL in the parity Postgres container (PARITY_PG_ID) via docker exec.
    The parity harness owns that container; -v ON_ERROR_STOP=1 fails loudly."""
    pg = os.environ["PARITY_PG_ID"]
    out = subprocess.run(
        ["docker", "exec", "-i", pg, "psql", "-U", "postgres", "-d", "iron",
         "-tAq", "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        raise RuntimeError(f"psql failed: {out.stderr.strip()}")
    return out.stdout.strip()


@pytest.fixture(scope="session")
def seeded_metrics(bearer) -> bool:
    """Seed a steady 42-day load + suppressed-HRV recovery straight into the
    shared Postgres, for the bearer's athlete, AFTER `seeded` has run.

    Why direct DB and not the public API: metrics_daily is derived from Strava
    activities (no public write path), and — critically — the `seeded` fixture's
    profile PUT calls rebuild_metrics(), which DELETEs the athlete's rows. Any
    seed done before that wipe vanishes. This fixture depends on `bearer` (hence
    `seeded`), so it runs after the last rebuild. Requires PARITY_PG_ID, set by
    run_parity.sh.

    Returns True if it seeded, False if PARITY_PG_ID is absent (manual mode, e.g.
    two already-running backends). It is a NO-OP rather than a skip so that
    consumers which merely benefit from real data (test_pmc_parity) still run in
    manual mode against whatever the DB holds; consumers that strictly REQUIRE
    the seed (test_readiness_parity) skip themselves on a False return."""
    if not os.environ.get("PARITY_PG_ID"):
        return False
    # The bearer's athlete is the most-recently paired device_token row.
    aid = _psql("SELECT athlete_id FROM device_token ORDER BY id DESC LIMIT 1")
    assert aid.isdigit(), f"could not resolve bearer athlete id (got {aid!r})"

    # Dates are computed in UTC, matching how BOTH backends now resolve the
    # readiness "today" (FastAPI _utcnow / backend-v2 Clock.systemUTC — bean
    # readiness-tz). If the seed used a different basis (host-local, or Postgres
    # CURRENT_DATE) than the app, the suppressed-HRV "today" recovery row could
    # land in the app's future and _recovery_flags would drop it, breaking the
    # green→easy parity. UTC everywhere keeps seed and apps aligned on any host.
    today = datetime.now(timezone.utc).date()
    day = lambda g: (today - timedelta(days=g)).isoformat()
    # Mostly-steady 50 TSS/day, but bump the two most-recent days so the derived
    # numbers are FRACTIONAL, not round. This is the point of the parity test:
    # Py.java exists solely to make Python round()/format and Java BigDecimal
    # HALF_EVEN agree char-for-char. With day1=57, day2=53 the chronic weekly
    # works out to exactly 352.5 — a banker's-rounding TIE — so the reason
    # string's `{chronic_weekly:.0f}` ("352", ties-to-even) and `{acwr:.2f}`
    # ("1.02") only stay byte-identical across backends if HALF_EVEN is correct.
    # Both stay well under the hard-day cut, so the call is still green→easy.
    tss_of = {1: 57, 2: 53}
    metrics_vals = ",".join(
        f"({aid}, '{day(g)}', {tss_of.get(g, 50)}, 50, 50, 0)" for g in range(1, 43))
    base_vals = ",".join(f"({aid}, '{day(g)}', 7.5, 60, 46)" for g in range(1, 10))
    _psql(f"""
        INSERT INTO metrics_daily (athlete_id, date, tss, ctl, atl, tsb)
        VALUES {metrics_vals}
        ON CONFLICT (athlete_id, date) DO NOTHING;
        INSERT INTO daily_recovery (athlete_id, date, sleep_h, hrv_ms, rhr_bpm)
        VALUES {base_vals}
        ON CONFLICT (athlete_id, date) DO NOTHING;
        INSERT INTO daily_recovery (athlete_id, date, sleep_h, hrv_ms, rhr_bpm)
        VALUES ({aid}, '{today.isoformat()}', 7.5, 40, 46)
        ON CONFLICT (athlete_id, date) DO NOTHING;
    """)
    return True


@pytest.fixture(scope="session")
def seeded_tests(bearer) -> bool:
    """Seed one fitness_test_result + two activities (Bike, Run) for the bearer's
    athlete so the fitness-test read parity tests exercise real data: /results +
    the catalog's last_tested/due, and /prefill's activity→inputs mapping. Neither
    table is touched by the `seeded` profile PUT, but seeding after `bearer` keeps
    it simple + athlete-scoped. Returns False (no-op) outside the parity runner."""
    if not os.environ.get("PARITY_PG_ID"):
        return False
    aid = _psql("SELECT athlete_id FROM device_token ORDER BY id DESC LIMIT 1")
    assert aid.isdigit(), f"could not resolve bearer athlete id (got {aid!r})"
    # Fixed absolute date/created_at + explicit activity ids so BOTH backends read
    # the identical rows (byte-parity depends on same id/created_at/date).
    _psql(f"""
        INSERT INTO fitness_test_result
            (athlete_id, test_slug, sport, date, inputs_json, result_json, applied, created_at)
        SELECT {aid}, 'bike-ftp-20', 'Bike', '2026-06-01',
               '{{"avg_power_w": 250}}', '{{"ftp": 238}}', false, '2026-06-01T10:00:00+00:00'
        WHERE NOT EXISTS (
            SELECT 1 FROM fitness_test_result
            WHERE athlete_id = {aid} AND test_slug = 'bike-ftp-20' AND date = '2026-06-01');
        INSERT INTO activities
            (id, athlete_id, sport, start_date, name, moving_time, distance,
             avg_power, weighted_power, avg_hr, is_duplicate)
        VALUES
            (900001, {aid}, 'Bike', '2026-07-10T08:00:00', 'Morning Ride',
             3600, 30000, 210, 225, 145, 0),
            (900002, {aid}, 'Run', '2026-07-11T07:00:00', 'Tempo Run',
             1800, 6000, NULL, NULL, 160, 0)
        ON CONFLICT (id) DO NOTHING;
    """)
    return True


@pytest.fixture(scope="session")
def seeded_dupes(bearer) -> bool:
    """Seed a DUPLICATE Bike pair (same event, two devices) so the strava dedup
    parity test finds a cluster: an Apple Watch and a Garmin Edge recording of the
    same ride, five minutes apart. The Edge (power meter) is the primary; the
    watch is the duplicate. Fixed ids so both backends read identical rows."""
    if not os.environ.get("PARITY_PG_ID"):
        return False
    aid = _psql("SELECT athlete_id FROM device_token ORDER BY id DESC LIMIT 1")
    assert aid.isdigit(), f"could not resolve bearer athlete id (got {aid!r})"
    _psql(f"""
        INSERT INTO activities
            (id, athlete_id, sport, start_date, name, moving_time, distance,
             avg_power, weighted_power, avg_hr, device_name, has_power_meter, is_duplicate)
        VALUES
            (900101, {aid}, 'Bike', '2026-07-12T08:00:00', 'Ride (watch)',
             3600, 30000, NULL, NULL, 150, 'Apple Watch Series 9', 0, 0),
            (900102, {aid}, 'Bike', '2026-07-12T08:05:00', 'Ride (edge)',
             3500, 29500, 210, 225, 148, 'Garmin Edge 1040', 1, 0)
        ON CONFLICT (id) DO NOTHING;
    """)
    return True


@pytest.fixture(scope="session")
def v2(bearer) -> httpx.Client:
    c = httpx.Client(base_url=V2_BASE_URL, timeout=60,
                     headers={"Authorization": f"Bearer {bearer}"})
    yield c
    c.close()


@pytest.fixture(scope="session")
def v1(bearer, api) -> httpx.Client:
    c = httpx.Client(base_url=str(api.base_url), timeout=60,
                     headers={"Authorization": f"Bearer {bearer}"})
    yield c
    c.close()


def _bike_id(v1: httpx.Client) -> int:
    workouts = v1.get("/api/plan").json()["workouts"]
    return next(w["id"] for w in workouts if w["sport"] == "Bike")


def test_itw_parity(v1, v2):
    wid = _bike_id(v1)
    a = v1.get(f"/api/export/workout/{wid}.itw")
    b = v2.get(f"/api/export/workout/{wid}.itw")
    assert a.status_code == b.status_code == 200
    # Whitespace/key-order free comparison: parsed JSON must be IDENTICAL.
    assert json.loads(a.text) == json.loads(b.text)


def test_plan_itw_parity(v1, v2):
    a = json.loads(v1.get("/api/export/plan.itw").text)
    b = json.loads(v2.get("/api/export/plan.itw").text)
    assert a["schema_version"] == b["schema_version"] == 1
    assert a["plan"] == b["plan"]
    assert len(a["workouts"]) == len(b["workouts"])
    assert a["workouts"] == b["workouts"]


def test_zwo_parity(v1, v2):
    wid = _bike_id(v1)
    a = v1.get(f"/api/export/workout/{wid}.zwo")
    b = v2.get(f"/api/export/workout/{wid}.zwo")
    assert a.status_code == b.status_code
    if a.status_code == 200:
        # Segment-level equality, whitespace-insensitive.
        norm = lambda t: [l.strip() for l in t.strip().splitlines() if l.strip()]
        assert norm(a.text) == norm(b.text)


def test_fit_both_valid(v1, v2):
    """FIT bytes intentionally DIFFER (v2 encodes spec-correct ms durations —
    iron-trainer-sqib); assert both are valid FIT with the same step count."""
    wid = _bike_id(v1)
    a = v1.get(f"/api/export/workout/{wid}.fit").content
    b = v2.get(f"/api/export/workout/{wid}.fit").content
    assert a[8:12] == b[8:12] == b".FIT"
    assert len(b) > 40


def test_cross_tenant_404_parity(v1, v2):
    assert v1.get("/api/export/workout/999999.itw").status_code == 404
    assert v2.get("/api/export/workout/999999.itw").status_code == 404


def test_nutrition_daily_parity(v1, v2):
    """Deterministic daily-carb math (body weight seeded) must be byte-identical,
    incl. integer-vs-float JSON emission (round()→int, weekly_hours→float)."""
    a = v1.get("/api/nutrition/daily")
    b = v2.get("/api/nutrition/daily")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()


def test_nutrition_workout_parity(v1, v2):
    """Per-workout fueling on a real planned workout — carb/hydration/sodium/gel
    math + the estimate_sweat_rate path (no measured sweat) must match exactly."""
    wid = _bike_id(v1)
    a = v1.get(f"/api/nutrition/workout/{wid}")
    b = v2.get(f"/api/nutrition/workout/{wid}")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()


def test_nutrition_cross_tenant_404_parity(v1, v2):
    assert v1.get("/api/nutrition/workout/999999").status_code == 404
    assert v2.get("/api/nutrition/workout/999999").status_code == 404


def test_tests_catalog_parity(v1, v2, seeded_tests):
    """The fixed protocol catalog + last_tested/due (a seeded 2026-06-01 Bike
    result makes Bike's last_tested non-null) must be byte-identical."""
    a = v1.get("/api/tests")
    b = v2.get("/api/tests")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()


def test_tests_results_parity(v1, v2, seeded_tests):
    """GET /results: _test_result_dict shape (key order, parsed inputs/result
    JSON) on the identical seeded row must match."""
    a = v1.get("/api/tests/results")
    b = v2.get("/api/tests/results")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()


def test_tests_prefill_parity(v1, v2, seeded_tests):
    """Prefill maps a synced activity to candidate inputs (round()→int); bike
    (weighted_power), run (distance/time/hr), and swim (no prefill_sport → [])."""
    for slug in ("bike-ftp-20", "run-lthr-30", "swim-css-400-200"):
        a = v1.get(f"/api/tests/{slug}/prefill")
        b = v2.get(f"/api/tests/{slug}/prefill")
        assert a.status_code == b.status_code == 200, slug
        assert a.json() == b.json(), slug


def test_tests_prefill_unknown_slug_404_parity(v1, v2):
    assert v1.get("/api/tests/nope/prefill").status_code == 404
    assert v2.get("/api/tests/nope/prefill").status_code == 404


def test_zones_parity(v1, v2):
    """First domain-math vertical: identical zone tables from both backends."""
    a = v1.get("/api/athlete/zones")
    b = v2.get("/api/athlete/zones")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()


def test_pmc_parity(v1, v2, seeded_metrics):
    """PMC windowing: both backends read the same metrics_daily rows, so the
    windowed response must be byte-identical (shape + window params + rows).
    The 4 backend-v2 unit tests prove the windowing math on 400 seeded rows;
    this proves the two implementations agree on whatever the DB holds
    (seeded_metrics gives them a real 42-day series to agree on)."""
    a = v1.get("/api/metrics/pmc")
    b = v2.get("/api/metrics/pmc")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()
    a2 = v1.get("/api/metrics/pmc?days=30")
    b2 = v2.get("/api/metrics/pmc?days=30")
    assert a2.status_code == b2.status_code == 200
    a2j, b2j = a2.json(), b2.json()
    assert a2j == b2j
    assert a2j["window_days"] == 30



def test_readiness_parity(v1, v2, seeded_metrics):
    """Readiness call: identical status/call/level, numeric fields, AND reason
    strings (the hard part — Python vs Java float formatting must match). The
    runner seeds a near-steady load (with fractional derived numbers, incl. a
    352.5 banker's-rounding tie) + suppressed-HRV recovery so this exercises the
    ACWR path, the HALF_EVEN reason-string formatting, the recovery-flag path,
    and the green→easy merge. Requires the DB seed (parity runner only)."""
    if not seeded_metrics:
        pytest.skip("readiness parity needs the DB seed (PARITY_PG_ID / parity runner only)")
    a = v1.get("/api/metrics/readiness/today")
    b = v2.get("/api/metrics/readiness/today")
    assert a.status_code == b.status_code == 200
    aj, bj = a.json(), b.json()
    assert aj == bj
    assert aj["status"] == "ok"
    assert aj["call"] == "easy"  # near-steady load, downgraded by suppressed HRV
    assert any("HRV suppressed" in r for r in aj["reasons"])
    # The reason strings must carry the FRACTIONAL derived numbers verbatim —
    # if Java's HALF_EVEN diverged from Python's format, aj == bj above fails.
    assert any("ratio 1.02" in r for r in aj["reasons"])
    assert any("352/wk" in r for r in aj["reasons"])


# ── Fitness-test WRITES (record + schedule) ───────────────────────────────────
# Defined LAST so every read-parity test above runs on clean, un-mutated state:
# these POST to BOTH backends against the shared DB (record adds two
# fitness_test_result rows, schedule adds two planned_workouts). Compare the
# responses modulo volatile id/created_at. apply — the cascade write — is
# deferred to the metrics-write vertical (bean iron-trainer-adix).

def test_tests_record_parity(v1, v2):
    """POST /result: compute (round()→int) + the saved _test_result_dict shape
    must match, modulo the serial id + created_at timestamp."""
    body = {"test_slug": "bike-ftp-20", "date": "2026-07-15",
            "inputs": {"avg_power_w": 250}}
    a = v1.post("/api/tests/result", json=body)
    b = v2.post("/api/tests/result", json=body)
    assert a.status_code == b.status_code == 200
    aj, bj = a.json(), b.json()
    for k in ("id", "created_at"):  # serial id / wall-clock timestamp
        aj.pop(k, None)
        bj.pop(k, None)
    assert aj == bj
    assert aj["test_slug"] == "bike-ftp-20"
    assert aj["sport"] == "Bike"
    assert aj["date"] == "2026-07-15"
    assert aj["applied"] is False
    assert aj["inputs"] == {"avg_power_w": 250}
    assert aj["result"] == {"ftp": 238}  # round(250 * 0.95) = round(237.5) → 238


def test_tests_schedule_parity(v1, v2):
    """POST /{slug}/schedule: the materialized test workout (to_workout + date)
    has no volatile fields, so it must be byte-identical."""
    body = {"date": "2026-08-01"}
    a = v1.post("/api/tests/bike-ftp-20/schedule", json=body)
    b = v2.post("/api/tests/bike-ftp-20/schedule", json=body)
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()
    assert a.json()["intensity"] == "test"
    assert a.json()["date"] == "2026-08-01"


def test_tests_record_unknown_slug_404_parity(v1, v2):
    body = {"test_slug": "nope", "inputs": {}}
    assert v1.post("/api/tests/result", json=body).status_code == 404
    assert v2.post("/api/tests/result", json=body).status_code == 404


def test_tests_record_missing_inputs_422_parity(v1, v2):
    """inputs is a required field — FastAPI 422 before compute; v2 must match
    (not a 400/500 from coercing a missing inputs to an empty map)."""
    body = {"test_slug": "bike-ftp-20"}  # no inputs
    assert v1.post("/api/tests/result", json=body).status_code == 422
    assert v2.post("/api/tests/result", json=body).status_code == 422


def test_tests_schedule_missing_date_422_parity(v1, v2):
    """date is required — FastAPI 422 before any write; v2 must match (not a 500
    from persisting a null into the NOT NULL date column)."""
    assert v1.post("/api/tests/bike-ftp-20/schedule", json={}).status_code == 422
    assert v2.post("/api/tests/bike-ftp-20/schedule", json={}).status_code == 422


# ── Fitness-test APPLY (the metrics-write cascade) ────────────────────────────
# apply cascades save_profile → recompute_tss → rebuild_metrics. Defined LAST: it
# rebuilds metrics_daily from activities, REPLACING the seeded 42-day series the
# readiness/pmc read-parity tests above rely on.

def test_tests_apply_parity(v1, v2, seeded_tests):
    """Each backend records + applies its OWN result (same thresholds), so the
    applied response matches modulo id/created_at AND the rebuilt metrics_daily
    (via /pmc) is byte-identical — proving the TSS + PMC write math agrees."""
    body = {"test_slug": "bike-ftp-20", "date": "2026-07-15",
            "inputs": {"avg_power_w": 250}}
    ra = v1.post("/api/tests/result", json=body).json()
    rb = v2.post("/api/tests/result", json=body).json()
    aa = v1.post(f"/api/tests/result/{ra['id']}/apply")
    ab = v2.post(f"/api/tests/result/{rb['id']}/apply")
    assert aa.status_code == ab.status_code == 200
    aj, bj = aa.json(), ab.json()
    for k in ("id", "created_at"):
        aj.pop(k, None)
        bj.pop(k, None)
    assert aj == bj
    assert aj["applied"] is True
    assert aj["result"] == {"ftp": 238}
    # The cascade recomputed activity TSS + rebuilt metrics_daily identically.
    assert v1.get("/api/metrics/pmc").json() == v2.get("/api/metrics/pmc").json()


def test_tests_apply_not_found_404_parity(v1, v2):
    assert v1.post("/api/tests/result/999999/apply").status_code == 404
    assert v2.post("/api/tests/result/999999/apply").status_code == 404


# ── Strava dedup (deterministic core) ─────────────────────────────────────────
# Also a metrics_daily-rebuilding write → defined after the read-parity tests.

def test_strava_dedup_parity(v1, v2, seeded_dupes):
    """POST /api/strava/dedup?fetch=false clusters same-event activities, marks
    duplicates, and rebuilds the PMC. The stats response and the rebuilt /pmc
    must be byte-identical (seeded_dupes gives a watch+Edge pair to collapse)."""
    a = v1.post("/api/strava/dedup?fetch=false")
    b = v2.post("/api/strava/dedup?fetch=false")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()
    assert a.json()["duplicates"] >= 1
    assert v1.get("/api/metrics/pmc").json() == v2.get("/api/metrics/pmc").json()


def test_strava_dedup_not_connected_409_parity(v1, v2):
    """fetch=true needs a live Strava connection; an unconnected athlete gets 409
    on both backends (v2 checks the same refresh-token presence, no live call)."""
    assert v1.post("/api/strava/dedup?fetch=true").status_code == 409
    assert v2.post("/api/strava/dedup?fetch=true").status_code == 409


# ── Races (catalog + selection) ───────────────────────────────────────────────
# The race catalog is seeded on FastAPI startup (db._seed_races) into the shared
# Postgres, so both backends read the same rows. set_athlete_race is a write —
# its effective_race() response has no volatile fields, so it must be byte-equal.

def test_races_catalog_parity(v1, v2):
    """GET /api/races: the seeded catalog (Race.model_dump order) is identical."""
    a = v1.get("/api/races")
    b = v2.get("/api/races")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()
    assert len(a.json()["races"]) > 0  # startup seeds the catalog


def test_races_filters_parity(v1, v2):
    """distance / country / q filters agree, using values drawn from the catalog."""
    races = v1.get("/api/races").json()["races"]
    assert races, "catalog seeded"
    sample = races[0]
    cases = [{"distance": sample["distance"]},
             {"q": sample["name"].split()[0].lower()}]
    if sample.get("country"):
        cases.append({"country": sample["country"]})
    for params in cases:
        a = v1.get("/api/races", params=params)
        b = v2.get("/api/races", params=params)
        assert a.status_code == b.status_code == 200, params
        assert a.json() == b.json(), params


def test_races_month_filter_parity(v1, v2):
    races = v1.get("/api/races").json()["races"]
    if races:
        month = races[0]["date"][:7]  # YYYY-MM
        a = v1.get("/api/races", params={"month": month})
        b = v2.get("/api/races", params={"month": month})
        assert a.status_code == b.status_code == 200
        assert a.json() == b.json()


def test_set_race_by_id_parity(v1, v2):
    """PUT /api/athlete/race {race_id}: effective_race() is byte-identical."""
    rid = v1.get("/api/races").json()["races"][0]["id"]
    a = v1.put("/api/athlete/race", json={"race_id": rid})
    b = v2.put("/api/athlete/race", json={"race_id": rid})
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()


def test_set_race_custom_parity(v1, v2):
    """A custom race derives cutoffs from distance (cutoffs_for) on both."""
    body = {"name": "Backyard Half", "race_date": "2026-10-11", "distance": "70.3"}
    a = v1.put("/api/athlete/race", json=body)
    b = v2.put("/api/athlete/race", json=body)
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()
    assert a.json()["race"]["cutoff_swim_s"] == 4200  # cutoffs_for("70.3")[0]


def test_set_race_not_found_400_parity(v1, v2):
    assert v1.put("/api/athlete/race", json={"race_id": 999999}).status_code == 400
    assert v2.put("/api/athlete/race", json={"race_id": 999999}).status_code == 400


def test_set_race_custom_missing_400_parity(v1, v2):
    """Custom race without name+date → 400 on both (not a 500 from a null write)."""
    assert v1.put("/api/athlete/race", json={"distance": "70.3"}).status_code == 400
    assert v2.put("/api/athlete/race", json={"distance": "70.3"}).status_code == 400


def test_races_empty_filter_returns_all_parity(v1, v2):
    """Empty-string query params are falsy → NO filter (full catalog) on both,
    not a zero-match. Guards against the != null vs Python-truthiness divergence."""
    full = v1.get("/api/races").json()
    for params in ({"country": ""}, {"distance": ""}, {"month": ""}, {"q": ""}):
        a = v1.get("/api/races", params=params)
        b = v2.get("/api/races", params=params)
        assert a.status_code == b.status_code == 200, params
        assert a.json() == b.json() == full, params


# ── Analytics reads: weekly volume + activities feed ──────────────────────────
# Defined last (like apply/dedup): seeds extra activities, so run after the
# metrics-rebuild tests that depend on the exact seeded_metrics series.

@pytest.fixture(scope="session")
def seeded_history(bearer) -> bool:
    """A handful of non-duplicate activities across two ISO weeks (with tss), so
    weekly-volume bucketing + the activities feed exercise real data. Distinct
    ids/dates from the dedup pair to avoid clustering interference."""
    if not os.environ.get("PARITY_PG_ID"):
        return False
    aid = _psql("SELECT athlete_id FROM device_token ORDER BY id DESC LIMIT 1")
    assert aid.isdigit(), f"could not resolve bearer athlete id (got {aid!r})"
    _psql(f"""
        INSERT INTO activities
            (id, athlete_id, sport, start_date, name, moving_time, distance, tss, is_duplicate)
        VALUES
            (900201, {aid}, 'Bike', '2026-06-01T08:00:00', 'W1 Ride', 3600, 30000, 55.4, 0),
            (900202, {aid}, 'Run',  '2026-06-03T07:00:00', 'W1 Run',  2400,  8000, 40.2, 0),
            (900203, {aid}, 'Swim', '2026-06-05T06:30:00', 'W1 Swim', 1800,  2000, 25.1, 0),
            (900204, {aid}, 'Bike', '2026-06-09T08:00:00', 'W2 Ride', 5400, 45000, 88.7, 0),
            (900205, {aid}, 'Run',  '2026-06-11T07:00:00', 'W2 Run',  3000, 10000, 52.9, 0)
        ON CONFLICT (id) DO NOTHING;
    """)
    return True


def test_weekly_parity(v1, v2, seeded_history):
    """GET /api/metrics/weekly: ISO-week volume by sport (hours/km/tss), totals
    summing the rounded per-sport values, must be byte-identical."""
    a = v1.get("/api/metrics/weekly")
    b = v2.get("/api/metrics/weekly")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()
    assert len(a.json()["weeks"]) > 0


def test_weekly_window_parity(v1, v2, seeded_history):
    for weeks in (1, 4, 52):
        a = v1.get("/api/metrics/weekly", params={"weeks": weeks})
        b = v2.get("/api/metrics/weekly", params={"weeks": weeks})
        assert a.status_code == b.status_code == 200, weeks
        assert a.json() == b.json(), weeks


def test_activities_parity(v1, v2, seeded_history):
    """GET /api/activities: full Activity.model_dump feed, most-recent first,
    with total count + in-page duplicate count."""
    a = v1.get("/api/activities")
    b = v2.get("/api/activities")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()
    assert a.json()["count"] > 0


def test_activities_filters_parity(v1, v2, seeded_history):
    for params in ({"include_duplicates": "false"}, {"limit": 2},
                   {"include_duplicates": "false", "limit": 3}):
        a = v1.get("/api/activities", params=params)
        b = v2.get("/api/activities", params=params)
        assert a.status_code == b.status_code == 200, params
        assert a.json() == b.json(), params


def test_activities_bool_coercion_parity(v1, v2, seeded_history):
    """include_duplicates coercion matches pydantic lax bool: 1/0/yes/no all
    agree between backends (not just true/false)."""
    for val in ("1", "0", "yes", "no", "on", "off"):
        a = v1.get("/api/activities", params={"include_duplicates": val})
        b = v2.get("/api/activities", params={"include_duplicates": val})
        assert a.status_code == b.status_code == 200, val
        assert a.json() == b.json(), val


def test_activities_negative_limit_parity(v1, v2, seeded_history):
    """limit=-1 → Python reversed[:-1] (all but the last), not an empty page."""
    a = v1.get("/api/activities", params={"limit": -1})
    b = v2.get("/api/activities", params={"limit": -1})
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()


def test_weekly_negative_weeks_parity(v1, v2, seeded_history):
    """weeks=-1 → Python out[1:] (drops the earliest week)."""
    a = v1.get("/api/metrics/weekly", params={"weeks": -1})
    b = v2.get("/api/metrics/weekly", params={"weeks": -1})
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()


def test_activities_bad_param_422_parity(v1, v2):
    """Malformed query params → 422 on both (pydantic validation parity)."""
    assert v1.get("/api/activities", params={"include_duplicates": "maybe"}).status_code == 422
    assert v2.get("/api/activities", params={"include_duplicates": "maybe"}).status_code == 422
    assert v1.get("/api/activities", params={"limit": "abc"}).status_code == 422
    assert v2.get("/api/activities", params={"limit": "abc"}).status_code == 422
    assert v1.get("/api/metrics/weekly", params={"weeks": "x"}).status_code == 422
    assert v2.get("/api/metrics/weekly", params={"weeks": "x"}).status_code == 422
