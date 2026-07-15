"""checkin table — persisted weekly check-ins (subjective inputs + story)

Revision ID: f7c9e1a3b5d7
Revises: e6b8d0f2a4c6
Create Date: 2026-07-15 10:00:00.000000
"""
from typing import Sequence, Union

import sqlalchemy as sa
import sqlmodel
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "f7c9e1a3b5d7"
down_revision: Union[str, None] = "e6b8d0f2a4c6"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "checkin",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("athlete_id", sa.Integer(),
                  sa.ForeignKey("athlete.id", ondelete="CASCADE"), nullable=False),
        sa.Column("date", sqlmodel.sql.sqltypes.AutoString(), nullable=False),
        sa.Column("created_at", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("inputs_json", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("story_json", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("readiness_json", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
    )
    op.create_index("ix_checkin_athlete_id", "checkin", ["athlete_id"])


def downgrade() -> None:
    op.drop_index("ix_checkin_athlete_id", table_name="checkin")
    op.drop_table("checkin")
