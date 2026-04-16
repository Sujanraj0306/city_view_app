"""Split Case.description into user_description + ai_description

Revision ID: 0003
Revises: 0002
Create Date: 2026-04-16
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


revision: str = "0003"
down_revision: Union[str, None] = "0002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.alter_column(
        "cases",
        "description",
        new_column_name="user_description",
        existing_type=sa.String(length=2000),
        existing_nullable=False,
    )
    op.add_column(
        "cases",
        sa.Column("ai_description", sa.String(length=2000), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("cases", "ai_description")
    op.alter_column(
        "cases",
        "user_description",
        new_column_name="description",
        existing_type=sa.String(length=2000),
        existing_nullable=False,
    )
