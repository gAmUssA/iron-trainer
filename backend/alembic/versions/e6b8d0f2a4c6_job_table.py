"""job table — background-operation status tracking

Revision ID: e6b8d0f2a4c6
Revises: d5a7b9c1e3f5
Create Date: 2026-07-12 10:00:00.000000
"""
from typing import Sequence, Union

import sqlalchemy as sa
import sqlmodel
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "e6b8d0f2a4c6"
down_revision: Union[str, None] = "d5a7b9c1e3f5"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "job",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("athlete_id", sa.Integer(),
                  sa.ForeignKey("athlete.id", ondelete="CASCADE"), nullable=False),
        sa.Column("kind", sqlmodel.sql.sqltypes.AutoString(), nullable=False),
        sa.Column("status", sqlmodel.sql.sqltypes.AutoString(), nullable=False),
        sa.Column("created_at", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("started_at", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("finished_at", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("result_json", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("error", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
    )
    op.create_index("ix_job_athlete_id", "job", ["athlete_id"])
    op.create_index("ix_job_athlete_kind_status", "job", ["athlete_id", "kind", "status"])


def downgrade() -> None:
    op.drop_index("ix_job_athlete_kind_status", table_name="job")
    op.drop_index("ix_job_athlete_id", table_name="job")
    op.drop_table("job")
