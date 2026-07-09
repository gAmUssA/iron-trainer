"""plan.base_weekly_hours — the weekly-hours target the plan was generated for

Revision ID: d5a7b9c1e3f5
Revises: c3e5f7a9b1d2
Create Date: 2026-07-08 23:00:00.000000
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "d5a7b9c1e3f5"
down_revision: Union[str, None] = "c3e5f7a9b1d2"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    with op.batch_alter_table("plan", schema=None) as b:
        b.add_column(sa.Column("base_weekly_hours", sa.Float(), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table("plan", schema=None) as b:
        b.drop_column("base_weekly_hours")
