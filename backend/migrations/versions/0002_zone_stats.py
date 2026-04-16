"""zone_stats table for rounded-coordinate aggregates

Revision ID: 0002
Revises: 0001
Create Date: 2026-04-16
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


revision: str = "0002"
down_revision: Union[str, None] = "0001"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "zone_stats",
        sa.Column("lat_zone", sa.Float, nullable=False),
        sa.Column("lng_zone", sa.Float, nullable=False),
        sa.Column("type", sa.String(16), nullable=False),
        sa.Column("count", sa.Integer, nullable=False, server_default="0"),
        sa.PrimaryKeyConstraint(
            "lat_zone", "lng_zone", "type", name="zone_stats_pk"
        ),
        sa.CheckConstraint(
            "type IN ('helmet', 'pothole')", name="zone_stats_type_check"
        ),
    )


def downgrade() -> None:
    op.drop_table("zone_stats")
