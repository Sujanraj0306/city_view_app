from datetime import datetime
from typing import Literal
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
