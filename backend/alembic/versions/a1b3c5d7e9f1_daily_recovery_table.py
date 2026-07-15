"""daily_recovery table — recovery data ingested from Health Auto Export

Revision ID: a1b3c5d7e9f1
Revises: f7c9e1a3b5d7
Create Date: 2026-07-15 12:00:00.000000
"""
from typing import Sequence, Union

import sqlalchemy as sa
import sqlmodel
from alembic import op

revision: str = "a1b3c5d7e9f1"
down_revision: Union[str, None] = "f7c9e1a3b5d7"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "daily_recovery",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("athlete_id", sa.Integer(),
                  sa.ForeignKey("athlete.id", ondelete="CASCADE"), nullable=False),
        sa.Column("date", sqlmodel.sql.sqltypes.AutoString(), nullable=False),
        sa.Column("updated_at", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("sleep_h", sa.Float(), nullable=True),
        sa.Column("deep_h", sa.Float(), nullable=True),
        sa.Column("rem_h", sa.Float(), nullable=True),
        sa.Column("awake_h", sa.Float(), nullable=True),
        sa.Column("sleep_start", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("sleep_end", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("hrv_ms", sa.Float(), nullable=True),
        sa.Column("rhr_bpm", sa.Float(), nullable=True),
        sa.Column("weight_kg", sa.Float(), nullable=True),
        sa.Column("vo2max", sa.Float(), nullable=True),
        sa.Column("respiratory_rate", sa.Float(), nullable=True),
        sa.Column("wrist_temp_c", sa.Float(), nullable=True),
    )
    op.create_index("ix_daily_recovery_athlete_id", "daily_recovery", ["athlete_id"])
    op.create_index("ix_daily_recovery_date", "daily_recovery", ["date"])


def downgrade() -> None:
    op.drop_index("ix_daily_recovery_date", table_name="daily_recovery")
    op.drop_index("ix_daily_recovery_athlete_id", table_name="daily_recovery")
    op.drop_table("daily_recovery")
