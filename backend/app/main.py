import logging
import uuid
from typing import Awaitable, Callable

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from sqlalchemy.orm import Session

from . import ai_service, hdfs_service, schemas
from .auth import create_access_token, get_current_user, verify_password
from .config import settings
from .database import SessionLocal, get_db
from .events import emit
from .models import Case, User
from .notifier import send_alert_email
from .seed import seed_users


logging.basicConfig(level=logging.INFO)
log = logging.getLogger("civicshield.backend")

app = FastAPI(title="CivicShield Backend", version="0.4.0")

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


# Helmet violations go to police; potholes to the municipal corporation
_EMAIL_ROUTE = {"helmet": "police", "pothole": "corporation"}


async def _create_report(
    case_type: str,
    predict_fn: Callable[[str], Awaitable[dict]],
    payload: schemas.ReportRequest,
    db: Session,
    current_user: User,
) -> schemas.ReportResponse:
    case_id = uuid.uuid4()

    hdfs_path = await run_in_threadpool(
        hdfs_service.upload_image, case_id, payload.image_base64
    )

    try:
        prediction = await predict_fn(payload.image_base64)
    except Exception as e:
        log.warning("AI service call failed for case %s: %s", case_id, e)
        prediction = {"detected": False, "confidence": 0.0, "label": None}

    detected = bool(prediction.get("detected", False))
    confidence = float(prediction.get("confidence", 0.0) or 0.0)
    label = prediction.get("label")

    case = Case(
        id=case_id,
        type=case_type,
        description=payload.description or "",
        latitude=payload.latitude,
        longitude=payload.longitude,
        image_hdfs_path=hdfs_path,
        ai_verified=detected,
        ai_confidence=confidence,
        status="verified" if detected else "pending",
        user_id=current_user.id,
    )
    db.add(case)
    db.commit()
    db.refresh(case)

    emit(
        settings.KAFKA_CIVIC_REPORTS_TOPIC,
        {
            "case_id": str(case.id),
            "type": case.type,
            "user_id": case.user_id,
            "latitude": case.latitude,
            "longitude": case.longitude,
            "ai_verified": case.ai_verified,
            "ai_confidence": case.ai_confidence,
            "label": label,
            "status": case.status,
            "image_hdfs_path": case.image_hdfs_path,
            "created_at": case.created_at.isoformat() if case.created_at else None,
        },
    )

    if detected:
        subject = f"Verified {case.type} report ({case.id})"
        body = (
            f"Case ID: {case.id}\n"
            f"Type: {case.type}\n"
            f"Label: {label}\n"
            f"Confidence: {confidence:.2f}\n"
            f"Location: ({case.latitude}, {case.longitude})\n"
            f"Reporter: {current_user.username}\n"
            f"Description: {case.description}\n"
        )
        await run_in_threadpool(
            send_alert_email, _EMAIL_ROUTE[case_type], subject, body
        )

    return schemas.ReportResponse(
        case_id=case.id,
        status=case.status,
        ai_verified=case.ai_verified,
        ai_confidence=case.ai_confidence,
        label=label,
        image_hdfs_path=case.image_hdfs_path,
    )


@app.post("/report/helmet", response_model=schemas.ReportResponse)
async def report_helmet(
    payload: schemas.ReportRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    return await _create_report(
        "helmet", ai_service.predict_helmet, payload, db, current_user
    )


@app.post("/report/pothole", response_model=schemas.ReportResponse)
async def report_pothole(
    payload: schemas.ReportRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    return await _create_report(
        "pothole", ai_service.predict_pothole, payload, db, current_user
    )


@app.get("/cases/{case_id}/image")
async def get_case_image(case_id: uuid.UUID, db: Session = Depends(get_db)):
    case = db.get(Case, case_id)
    if case is None or not case.image_hdfs_path:
        raise HTTPException(status_code=404, detail="Image not found")
    data = await run_in_threadpool(hdfs_service.read_image, case.image_hdfs_path)
    return Response(content=data, media_type="image/jpeg")
