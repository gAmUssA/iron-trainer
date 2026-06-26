from datetime import date, timedelta

from app import repo
from app.config import get_settings


def test_history_cutoff_defaults_to_five_years():
    s = get_settings()
    assert s.history_years == 5
    assert s.history_cutoff_date is not None
    assert s.history_cutoff_epoch and s.history_cutoff_epoch > 0
    # ~5 years back from today.
    assert (date.today() - s.history_cutoff_date).days >= 5 * 365 - 2


def test_delete_activities_before_prunes_old():
    old = "2018-01-01T08:00:00Z"
    recent = (date.today() - timedelta(days=30)).isoformat() + "T08:00:00Z"
    repo.upsert_activities([
        {"id": 1, "type": "Run", "sport_type": "Run", "start_date_local": old,
         "moving_time": 1800, "distance": 5000},
        {"id": 2, "type": "Run", "sport_type": "Run", "start_date_local": recent,
         "moving_time": 1800, "distance": 5000},
    ])
    cutoff = (date.today() - timedelta(days=round(365.25 * 5))).isoformat()
    removed = repo.delete_activities_before(cutoff)
    assert removed == 1
    assert {a["id"] for a in repo.list_activities(include_duplicates=True)} == {2}
