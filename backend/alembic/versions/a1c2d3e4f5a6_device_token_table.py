"""device_token table (native-app bearer auth)

Revision ID: a1c2d3e4f5a6
Revises: 9b56baa5ad9e
Create Date: 2026-06-27 01:10:00.000000
"""
from typing import Sequence, Union

import sqlalchemy as sa
import sqlmodel
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "a1c2d3e4f5a6"
down_revision: Union[str, None] = "9b56baa5ad9e"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "device_token",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("athlete_id", sa.Integer(), nullable=False),
        sa.Column("name", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("pairing_code", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("pairing_expires_at", sa.BigInteger(), nullable=True),
        sa.Column("token_hash", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("created_at", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.Column("last_used_at", sqlmodel.sql.sqltypes.AutoString(), nullable=True),
        sa.ForeignKeyConstraint(
            ["athlete_id"], ["athlete.id"], name="fk_device_token_athlete_id_athlete",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    with op.batch_alter_table("device_token", schema=None) as b:
        b.create_index(b.f("ix_device_token_athlete_id"), ["athlete_id"], unique=False)
        b.create_index(b.f("ix_device_token_pairing_code"), ["pairing_code"], unique=False)
        b.create_index(b.f("ix_device_token_token_hash"), ["token_hash"], unique=True)


def downgrade() -> None:
    with op.batch_alter_table("device_token", schema=None) as b:
        b.drop_index(b.f("ix_device_token_token_hash"))
        b.drop_index(b.f("ix_device_token_pairing_code"))
        b.drop_index(b.f("ix_device_token_athlete_id"))
    op.drop_table("device_token")
