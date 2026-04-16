import uuid
from datetime import datetime

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    PrimaryKeyConstraint,
    String,
    func,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base


class User(Base):
    __tablename__ = "users"
    __table_args__ = (
        CheckConstraint("role IN ('user', 'admin')", name="users_role_check"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    role: Mapped[str] = mapped_column(String(16), default="user")
    fcm_token: Mapped[str | None] = mapped_column(String(512), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    cases: Mapped[list["Case"]] = relationship(back_populates="user")


class Case(Base):
    __tablename__ = "cases"
    __table_args__ = (
        CheckConstraint("type IN ('helmet', 'pothole')", name="cases_type_check"),
        CheckConstraint(
            "status IN ('pending', 'verified', 'in_progress', 'completed')",
            name="cases_status_check",
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    type: Mapped[str] = mapped_column(String(16))
    user_description: Mapped[str] = mapped_column(String(2000), default="")
    ai_description: Mapped[str | None] = mapped_column(String(2000), nullable=True)
    latitude: Mapped[float] = mapped_column(Float)
    longitude: Mapped[float] = mapped_column(Float)
    image_hdfs_path: Mapped[str | None] = mapped_column(String(512), nullable=True)
    ai_verified: Mapped[bool] = mapped_column(Boolean, default=False)
    ai_confidence: Mapped[float | None] = mapped_column(Float, nullable=True)
    status: Mapped[str] = mapped_column(String(16), default="pending")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)

    user: Mapped[User] = relationship(back_populates="cases")


class ZoneStat(Base):
    __tablename__ = "zone_stats"
    __table_args__ = (
        PrimaryKeyConstraint("lat_zone", "lng_zone", "type", name="zone_stats_pk"),
        CheckConstraint(
            "type IN ('helmet', 'pothole')", name="zone_stats_type_check"
        ),
    )

    lat_zone: Mapped[float] = mapped_column(Float)
    lng_zone: Mapped[float] = mapped_column(Float)
    type: Mapped[str] = mapped_column(String(16))
    count: Mapped[int] = mapped_column(Integer, default=0)
