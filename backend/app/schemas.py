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
