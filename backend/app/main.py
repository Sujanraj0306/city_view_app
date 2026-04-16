import logging

import httpx
from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session

from . import models, schemas
from .auth import (
    create_access_token,
    get_current_user,
    hash_password,
    verify_password,
)
from .config import settings
from .database import Base, engine, get_db
from .events import emit
from .notifier import send_alert_email


logging.basicConfig(level=logging.INFO)
log = logging.getLogger("civicshield.backend")

app = FastAPI(title="CivicShield Backend", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
def on_startup() -> None:
    Base.metadata.create_all(bind=engine)
    log.info("Database tables ensured")


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "backend"}


@app.post("/auth/register", response_model=schemas.UserOut, status_code=201)
def register(payload: schemas.UserCreate, db: Session = Depends(get_db)):
    if db.query(models.User).filter(models.User.email == payload.email).first():
        raise HTTPException(status_code=400, detail="Email already registered")
    user = models.User(
        email=payload.email,
        hashed_password=hash_password(payload.password),
        full_name=payload.full_name,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


@app.post("/auth/login", response_model=schemas.Token)
def login(payload: schemas.LoginRequest, db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.email == payload.email).first()
    if not user or not verify_password(payload.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
        )
    return schemas.Token(access_token=create_access_token(user.email))


@app.get("/auth/me", response_model=schemas.UserOut)
def me(current_user: models.User = Depends(get_current_user)):
    return current_user


def _classify(description: str) -> tuple[str, str]:
    """Call AI service to classify the report. Falls back to 'corporation' on failure."""
    try:
        with httpx.Client(timeout=5.0) as client:
            resp = client.post(
                f"{settings.AI_SERVICE_URL}/classify",
                json={"text": description},
            )
            resp.raise_for_status()
            data = resp.json()
            return data.get("category", "corporation"), data.get("route", "corporation")
    except Exception as e:
        log.warning("AI service call failed: %s", e)
        return "unknown", "corporation"


@app.post("/reports", response_model=schemas.ReportOut, status_code=201)
def create_report(
    payload: schemas.ReportCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    category, route = _classify(payload.description)

    report = models.Report(
        user_id=current_user.id,
        title=payload.title,
        description=payload.description,
        location=payload.location,
        category=category,
        routed_to=route,
        status="submitted",
    )
    db.add(report)
    db.commit()
    db.refresh(report)

    emit(
        "civicshield.reports",
        {
            "report_id": report.id,
            "user_id": current_user.id,
            "category": category,
            "route": route,
            "location": payload.location,
        },
    )

    sent = send_alert_email(route, payload.title, payload.description)
    if sent:
        report.status = "dispatched"
        db.commit()
        db.refresh(report)

    return report


@app.get("/reports", response_model=list[schemas.ReportOut])
def list_reports(
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    return (
        db.query(models.Report)
        .filter(models.Report.user_id == current_user.id)
        .order_by(models.Report.created_at.desc())
        .all()
    )
