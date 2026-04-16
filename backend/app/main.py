import logging
import uuid
import xml.etree.ElementTree as ET
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from email.utils import format_datetime
from typing import Literal

from fastapi import Depends, FastAPI, HTTPException, Query, status
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from sqlalchemy import text
from sqlalchemy.orm import Session, joinedload

from . import (
    ai_service,
    email_service,
    fcm_service,
    gemini_service,
    hdfs_service,
    kafka_service,
    schemas,
)
from .auth import (
    create_access_token,
    get_current_user,
    require_admin,
    verify_password,
)
from .config import settings
from .database import SessionLocal, get_db
from .models import Case, User
from .seed import seed_users


logging.basicConfig(level=logging.INFO)
log = logging.getLogger("civicshield.backend")


@asynccontextmanager
async def lifespan(app: FastAPI):
    db = SessionLocal()
    try:
        seed_users(db)
    finally:
        db.close()

    fcm_service.init()
    await kafka_service.start_producer()
    await kafka_service.start_consumer()
    try:
        yield
    finally:
        await kafka_service.stop_consumer()
        await kafka_service.stop_producer()


app = FastAPI(title="CivicShield Backend", version="0.6.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


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


async def _create_report(
    case_type: Literal["helmet", "pothole"],
    payload: schemas.ReportRequest,
    db: Session,
    current_user: User,
):
    # (1) Gemini evidence gate — no side effects until this passes.
    try:
        analysis = await gemini_service.analyze_report_image(
            payload.image_base64, case_type
        )
    except gemini_service.GeminiUnavailable as e:
        log.warning("Gemini validation unavailable for %s report: %s", case_type, e)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Image validation service is unavailable, please retry.",
        )

    if not analysis["is_valid_violation"]:
        reason = (
            analysis["rejection_reason"]
            or "Image does not show sufficient evidence of a violation."
        )
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={"accepted": False, "reason": reason},
        )

    # (2) Valid — Gemini's description is authoritative.
    ai_description = analysis["description"]

    # (3) Store evidence image.
    case_id = uuid.uuid4()
    hdfs_path = await run_in_threadpool(
        hdfs_service.upload_image, case_id, payload.image_base64
    )

    # (4) YOLO confidence. Fall back to Gemini's score if the model is offline.
    predict_fn = (
        ai_service.predict_helmet
        if case_type == "helmet"
        else ai_service.predict_pothole
    )
    try:
        yolo = await predict_fn(payload.image_base64)
        confidence = float(yolo.get("confidence") or 0.0)
    except Exception as e:
        log.warning("YOLO call failed for %s case %s: %s", case_type, case_id, e)
        confidence = float(analysis["confidence_score"])

    # (5) Persist.
    case = Case(
        id=case_id,
        type=case_type,
        user_description=payload.description or "",
        ai_description=ai_description,
        latitude=payload.latitude,
        longitude=payload.longitude,
        image_hdfs_path=hdfs_path,
        ai_verified=True,
        ai_confidence=confidence,
        status="verified",
        user_id=current_user.id,
    )
    db.add(case)
    db.commit()
    db.refresh(case)

    # (6) Kafka event.
    await kafka_service.publish_report(
        case_id=case.id,
        case_type=case.type,
        lat=case.latitude,
        lng=case.longitude,
        ai_verified=case.ai_verified,
    )

    # (7) Alert email with image embedded inline (cid:).
    alert_fn = (
        email_service.send_helmet_alert
        if case_type == "helmet"
        else email_service.send_pothole_alert
    )
    await run_in_threadpool(
        alert_fn,
        case.id,
        case.latitude,
        case.longitude,
        confidence,
        ai_description,
        payload.image_base64,
    )

    return schemas.ReportResponse(
        case_id=case.id,
        status=case.status,
        ai_verified=case.ai_verified,
        ai_confidence=case.ai_confidence,
        label=case_type,
        image_hdfs_path=case.image_hdfs_path,
    )


@app.post("/report/helmet")
async def report_helmet(
    payload: schemas.ReportRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    return await _create_report("helmet", payload, db, current_user)


@app.post("/report/pothole")
async def report_pothole(
    payload: schemas.ReportRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    return await _create_report("pothole", payload, db, current_user)


@app.get("/cases", response_model=list[schemas.CaseAdminOut])
def list_my_cases(
    user_id: str | None = Query(None, description="Must be 'me' or omitted."),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    if user_id is not None and user_id != "me":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="user_id must be 'me'",
        )
    rows = (
        db.query(Case)
        .options(joinedload(Case.user))
        .filter(Case.user_id == current_user.id)
        .order_by(Case.created_at.desc())
        .limit(100)
        .all()
    )
    return [
        schemas.CaseAdminOut(
            id=c.id,
            type=c.type,
            user_description=c.user_description,
            ai_description=c.ai_description,
            latitude=c.latitude,
            longitude=c.longitude,
            image_hdfs_path=c.image_hdfs_path,
            ai_verified=c.ai_verified,
            ai_confidence=c.ai_confidence,
            status=c.status,
            created_at=c.created_at,
            user=schemas.UserMini(
                id=c.user.id, username=c.user.username, role=c.user.role
            ),
        )
        for c in rows
    ]


@app.get("/cases/{case_id}/image")
async def get_case_image(case_id: uuid.UUID, db: Session = Depends(get_db)):
    case = db.get(Case, case_id)
    if case is None or not case.image_hdfs_path:
        raise HTTPException(status_code=404, detail="Image not found")
    data = await run_in_threadpool(hdfs_service.read_image, case.image_hdfs_path)
    return Response(content=data, media_type="image/jpeg")


# ---------------------------------------------------------------------------
# Admin endpoints
# ---------------------------------------------------------------------------


@app.get("/admin/cases", response_model=schemas.PagedCases)
def list_cases_admin(
    page: int = Query(1, ge=1),
    limit: int = Query(20, ge=1, le=100),
    type: str | None = Query(None, pattern="^(helmet|pothole)$"),
    status_: str | None = Query(
        None,
        alias="status",
        pattern="^(pending|verified|in_progress|completed)$",
    ),
    db: Session = Depends(get_db),
    _admin: User = Depends(require_admin),
):
    q = db.query(Case).options(joinedload(Case.user))
    if type:
        q = q.filter(Case.type == type)
    if status_:
        q = q.filter(Case.status == status_)

    total = q.count()
    rows = (
        q.order_by(Case.created_at.desc())
        .offset((page - 1) * limit)
        .limit(limit)
        .all()
    )

    items = [
        schemas.CaseAdminOut(
            id=c.id,
            type=c.type,
            user_description=c.user_description,
            ai_description=c.ai_description,
            latitude=c.latitude,
            longitude=c.longitude,
            image_hdfs_path=c.image_hdfs_path,
            ai_verified=c.ai_verified,
            ai_confidence=c.ai_confidence,
            status=c.status,
            created_at=c.created_at,
            user=schemas.UserMini(
                id=c.user.id, username=c.user.username, role=c.user.role
            ),
        )
        for c in rows
    ]
    pages = (total + limit - 1) // limit if total else 0
    return schemas.PagedCases(
        items=items, total=total, page=page, limit=limit, pages=pages
    )


@app.patch(
    "/admin/cases/{case_id}/status", response_model=schemas.CaseStatusUpdateResponse
)
async def update_case_status(
    case_id: uuid.UUID,
    payload: schemas.CaseStatusUpdate,
    db: Session = Depends(get_db),
    _admin: User = Depends(require_admin),
):
    case = db.get(Case, case_id)
    if case is None:
        raise HTTPException(status_code=404, detail="Case not found")

    case.status = payload.status
    db.commit()
    db.refresh(case)

    push_sent = False
    reporter = db.get(User, case.user_id)
    if reporter and reporter.fcm_token:
        title = f"Case {case.id} updated"
        body = f"Your {case.type} report is now {case.status}."
        push_sent = await run_in_threadpool(
            fcm_service.send_push,
            reporter.fcm_token,
            title,
            body,
            {"case_id": str(case.id), "status": case.status, "type": case.type},
        )

    return schemas.CaseStatusUpdateResponse(
        case_id=case.id, status=case.status, push_sent=push_sent
    )


# ---------------------------------------------------------------------------
# Analytics
# ---------------------------------------------------------------------------


_ZONE_AGG_SQL = text(
    """
    SELECT
      lat_zone,
      lng_zone,
      COALESCE(SUM(CASE WHEN type = 'helmet' THEN count END), 0)  AS helmet_count,
      COALESCE(SUM(CASE WHEN type = 'pothole' THEN count END), 0) AS pothole_count,
      COALESCE(SUM(count), 0) AS total
    FROM zone_stats
    GROUP BY lat_zone, lng_zone
    ORDER BY lat_zone, lng_zone
    """
)


@app.get("/analytics/zones")
def analytics_zones(db: Session = Depends(get_db)):
    rows = db.execute(_ZONE_AGG_SQL).all()
    features = [
        {
            "type": "Feature",
            "geometry": {
                "type": "Point",
                "coordinates": [float(r.lng_zone), float(r.lat_zone)],
            },
            "properties": {
                "helmet_count": int(r.helmet_count),
                "pothole_count": int(r.pothole_count),
                "total": int(r.total),
            },
        }
        for r in rows
    ]
    return {"type": "FeatureCollection", "features": features}


# ---------------------------------------------------------------------------
# RSS 2.0 feed
# ---------------------------------------------------------------------------


@app.get("/rss")
def rss_feed(db: Session = Depends(get_db)):
    cases = (
        db.query(Case).order_by(Case.created_at.desc()).limit(50).all()
    )

    rss = ET.Element("rss", version="2.0")
    channel = ET.SubElement(rss, "channel")
    ET.SubElement(channel, "title").text = "CivicShield Reports"
    ET.SubElement(channel, "link").text = settings.BACKEND_PUBLIC_URL
    ET.SubElement(channel, "description").text = (
        "Latest 50 civic violation reports from CivicShield"
    )
    ET.SubElement(channel, "lastBuildDate").text = format_datetime(
        datetime.now(timezone.utc)
    )
    ET.SubElement(channel, "language").text = "en-us"

    for c in cases:
        item = ET.SubElement(channel, "item")
        ET.SubElement(item, "title").text = (
            f"{c.type.capitalize()} report at ({c.latitude:.4f}, {c.longitude:.4f})"
        )
        desc_parts = [
            f"Type: {c.type}",
            f"Location: ({c.latitude}, {c.longitude})",
            f"AI verified: {c.ai_verified}",
            f"AI confidence: {(c.ai_confidence or 0.0):.2f}",
            f"Status: {c.status}",
        ]
        if c.ai_description:
            desc_parts.append(f"AI: {c.ai_description}")
        if c.user_description:
            desc_parts.append(f"Reporter note: {c.user_description}")
        ET.SubElement(item, "description").text = " | ".join(desc_parts)
        ET.SubElement(item, "pubDate").text = format_datetime(c.created_at)
        guid = ET.SubElement(item, "guid", isPermaLink="false")
        guid.text = str(c.id)
        ET.SubElement(item, "link").text = (
            f"{settings.BACKEND_PUBLIC_URL}/cases/{c.id}/image"
        )

    xml_bytes = ET.tostring(rss, encoding="utf-8", xml_declaration=True)
    return Response(content=xml_bytes, media_type="application/rss+xml; charset=utf-8")
