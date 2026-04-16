import logging

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session

from . import schemas
from .auth import create_access_token, get_current_user, verify_password
from .database import SessionLocal, get_db
from .models import User
from .seed import seed_users


logging.basicConfig(level=logging.INFO)
log = logging.getLogger("civicshield.backend")

app = FastAPI(title="CivicShield Backend", version="0.3.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
def on_startup() -> None:
    db = SessionLocal()
    try:
        seed_users(db)
    finally:
        db.close()


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "backend"}


@app.post("/auth/login", response_model=schemas.Token)
def login(payload: schemas.LoginRequest, db: Session = Depends(get_db)):
    user = db.query(User).filter(User.username == payload.username).first()
    if not user or not verify_password(payload.password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
        )
    return schemas.Token(
        access_token=create_access_token(user.id, user.role),
        user_id=user.id,
        role=user.role,
    )


@app.post("/auth/fcm-token", response_model=schemas.OkResponse)
def save_fcm_token(
    payload: schemas.FCMTokenRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    current_user.fcm_token = payload.fcm_token
    db.commit()
    return schemas.OkResponse()
