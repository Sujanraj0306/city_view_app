from datetime import datetime

from pydantic import BaseModel, EmailStr


class UserCreate(BaseModel):
    email: EmailStr
    password: str
    full_name: str | None = None


class UserOut(BaseModel):
    id: int
    email: EmailStr
    full_name: str | None = None
    created_at: datetime

    class Config:
        from_attributes = True


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


class ReportCreate(BaseModel):
    title: str
    description: str
    location: str | None = None


class ReportOut(BaseModel):
    id: int
    title: str
    description: str
    location: str | None
    category: str | None
    routed_to: str | None
    status: str
    created_at: datetime

    class Config:
        from_attributes = True
