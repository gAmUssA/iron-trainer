"""athlete nutrition fields

Revision ID: c3e5f7a9b1d2
Revises: b2d4e6f8a0c1
Create Date: 2026-07-06 16:45:00.000000
"""
from typing import Sequence, Union

import sqlalchemy as sa
import sqlmodel
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "c3e5f7a9b1d2"
down_revision: Union[str, None] = "b2d4e6f8a0c1"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    with op.batch_alter_table("athlete", schema=None) as b:
        b.add_column(sa.Column("body_weight_kg", sa.Float(), nullable=True))
        b.add_column(sa.Column("gel_carb_g", sa.Float(), nullable=True))
        b.add_column(sa.Column("sweat_rate_l_h", sa.Float(), nullable=True))
        b.add_column(sa.Column("gi_tolerance", sqlmodel.sql.sqltypes.AutoString(), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table("athlete", schema=None) as b:
        b.drop_column("gi_tolerance")
        b.drop_column("sweat_rate_l_h")
        b.drop_column("gel_carb_g")
        b.drop_column("body_weight_kg")
