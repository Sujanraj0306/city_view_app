from datetime import datetime
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, Field


class LoginRequest(BaseModel):
    username: str
    password: str


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user_id: int
    role: str


class FCMTokenRequest(BaseModel):
    fcm_token: str = Field(..., min_length=1, max_length=512)


class OkResponse(BaseModel):
    ok: bool = True


class ReportRequest(BaseModel):
    image_base64: str = Field(..., min_length=1)
    latitude: float
    longitude: float
    description: str | None = None


class ReportResponse(BaseModel):
    case_id: UUID
    status: str
    ai_verified: bool
    ai_confidence: float | None
    label: str | None
    image_hdfs_path: str


class UserMini(BaseModel):
    id: int
    username: str
    role: str
    email: str | None = None


class CaseAdminOut(BaseModel):
    id: UUID
    type: str
    user_description: str
    ai_description: str | None
    latitude: float
    longitude: float
    image_hdfs_path: str | None
    ai_verified: bool
    ai_confidence: float | None
    status: str
    created_at: datetime
    user: UserMini


class PagedCases(BaseModel):
    items: list[CaseAdminOut]
    total: int
    page: int
    limit: int
    pages: int


class CaseStatusUpdate(BaseModel):
    status: Literal["pending", "verified", "in_progress", "completed"]


class CaseStatusUpdateResponse(BaseModel):
    case_id: UUID
    status: str
    push_sent: bool


class BoundingBox(BaseModel):
    x: float
    y: float
    width: float
    height: float


class CaseAnalysisResponse(BaseModel):
    case_id: UUID
    scene_description: str
    violation_confirmed: bool
    vehicle_number: str | None
    violation_zone: str
    severity: Literal["low", "medium", "high"]
    bounding_box: BoundingBox | None
    cached: bool


class CaseAddressResponse(BaseModel):
    case_id: UUID
    display_name: str


# ---------------------------------------------------------------------------
# Analytics dashboard
# ---------------------------------------------------------------------------


class AnalyticsSummary(BaseModel):
    total: int
    pending: int
    verified: int
    in_progress: int
    completed: int


class DailyCountPoint(BaseModel):
    date: str  # YYYY-MM-DD
    count: int


class DailyCountsResponse(BaseModel):
    days: int
    points: list[DailyCountPoint]


class TopZonePoint(BaseModel):
    lat: float
    lng: float
    total: int
    helmet_count: int
    pothole_count: int


class TopZonesResponse(BaseModel):
    limit: int
    zones: list[TopZonePoint]


class ResolutionTrendPoint(BaseModel):
    week_start: str  # YYYY-MM-DD (Monday)
    avg_days: float
    resolved_count: int


class ResolutionTrendResponse(BaseModel):
    weeks: int
    points: list[ResolutionTrendPoint]


class RecentActivityItem(BaseModel):
    case_id: UUID
    case_type: str
    old_status: str | None
    new_status: str
    changed_at: datetime
    username: str | None


class RecentActivityResponse(BaseModel):
    items: list[RecentActivityItem]


# ---------------------------------------------------------------------------
# Conversational agent
# ---------------------------------------------------------------------------


class AgentChatRequest(BaseModel):
    user_id: str
    session_id: str
    message: str = Field(default="", max_length=2000)
    image_base64: str | None = None


class AgentChatResponse(BaseModel):
    reply: str
    action: Literal["open_camera", "show_cases", "submit_report"] | None = None
    data: dict[str, Any] = Field(default_factory=dict)
