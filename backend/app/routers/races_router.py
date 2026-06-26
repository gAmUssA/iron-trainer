"""Race catalog + per-athlete race selection."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from .. import repo

router = APIRouter(prefix="/api", tags=["races"])


@router.get("/races")
def list_races(
    distance: str | None = None,
    country: str | None = None,
    month: str | None = None,
    q: str | None = None,
) -> dict:
    """The IRONMAN catalog, filterable by distance / country / month (YYYY-MM) / text."""
    return {"races": repo.list_races(distance=distance, country=country, month=month, q=q)}


class RaceSelect(BaseModel):
    # Pick a catalog race by id, OR provide a custom race (name + date [+ distance]).
    race_id: int | None = None
    name: str | None = None
    race_date: str | None = None  # YYYY-MM-DD
    distance: str | None = None  # "70.3" | "140.6"


@router.put("/athlete/race")
def set_race(sel: RaceSelect) -> dict:
    try:
        race = repo.set_athlete_race(
            race_id=sel.race_id, name=sel.name, race_date=sel.race_date, distance=sel.distance
        )
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    return {"race": race}
