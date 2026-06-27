"""fitness_test_result table

Revision ID: b2d4e6f8a0c1
Revises: a1c2d3e4f5a6
Create Date: 2026-06-27 03:00:00.000000
"""
from typing import Sequence, Union

import sqlalchemy as sa
import sqlmodel
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "b2d4e6f8a0c1"
down_revision: Union[str, None] = "a1c2d3e4f5a6"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "fitness_test_result",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("athlete_id", sa.Integer(), nullable=False),
        sa.Column("test_slug", sqlmodel.sql.sqltypes.AutoString(), nullable=False),
        sa.Column("sport", sqlmodel.sql.sqltypes.AutoString(), nullable=False),
        sa.Column("date", sqlmodel.sql.sqltypes.AutoString(), nullable=False),
        sa.Column("inputs_json", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("result_json", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("applied", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.ForeignKeyConstraint(
            ["athlete_id"], ["athlete.id"],
            name="fk_fitness_test_result_athlete_id_athlete", ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    with op.batch_alter_table("fitness_test_result", schema=None) as b:
        b.create_index(b.f("ix_fitness_test_result_athlete_id"), ["athlete_id"], unique=False)
        b.create_index(b.f("ix_fitness_test_result_test_slug"), ["test_slug"], unique=False)


def downgrade() -> None:
    with op.batch_alter_table("fitness_test_result", schema=None) as b:
        b.drop_index(b.f("ix_fitness_test_result_test_slug"))
        b.drop_index(b.f("ix_fitness_test_result_athlete_id"))
    op.drop_table("fitness_test_result")
