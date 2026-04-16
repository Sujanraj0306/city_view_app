"""initial schema: users and cases

Revision ID: 0001
Revises:
Create Date: 2026-04-16
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql


revision: str = "0001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Drop any legacy tables from the pre-alembic schema (test data only).
    op.execute("DROP TABLE IF EXISTS reports CASCADE")
    op.execute("DROP TABLE IF EXISTS users CASCADE")

    op.create_table(
        "users",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("username", sa.String(64), nullable=False),
        sa.Column("password_hash", sa.String(255), nullable=False),
        sa.Column("role", sa.String(16), nullable=False, server_default="user"),
        sa.Column("fcm_token", sa.String(512), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint("role IN ('user', 'admin')", name="users_role_check"),
        sa.UniqueConstraint("username", name="uq_users_username"),
    )
    op.create_index("ix_users_username", "users", ["username"])

    op.create_table(
        "cases",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("type", sa.String(16), nullable=False),
        sa.Column("description", sa.String(2000), nullable=False),
        sa.Column("latitude", sa.Float, nullable=False),
        sa.Column("longitude", sa.Float, nullable=False),
        sa.Column("image_hdfs_path", sa.String(512), nullable=True),
        sa.Column(
            "ai_verified",
            sa.Boolean,
            nullable=False,
            server_default=sa.text("false"),
        ),
        sa.Column("ai_confidence", sa.Float, nullable=True),
        sa.Column("status", sa.String(16), nullable=False, server_default="pending"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            sa.Integer,
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "type IN ('helmet', 'pothole')", name="cases_type_check"
        ),
        sa.CheckConstraint(
            "status IN ('pending', 'verified', 'in_progress', 'completed')",
            name="cases_status_check",
        ),
    )
    op.create_index("ix_cases_user_id", "cases", ["user_id"])


def downgrade() -> None:
    op.drop_index("ix_cases_user_id", table_name="cases")
    op.drop_table("cases")
    op.drop_index("ix_users_username", table_name="users")
    op.drop_table("users")
