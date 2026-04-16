import logging
import uuid
import xml.etree.ElementTree as ET
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from typing import Literal

from fastapi import Depends, FastAPI, HTTPException, Query, status
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from sqlalchemy import text
from sqlalchemy.orm import Session, joinedload

from . import (
    agent_service,
    ai_service,
    email_service,
    fcm_service,
    gemini_service,
    geocoding_service,
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
from .models import Case, CaseStatusEvent, User
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
                id=c.user.id,
                username=c.user.username,
                role=c.user.role,
                email=c.user.email,
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


# Case-level detail (used by the admin case-detail screen). In-memory caches
# keep repeat-opens cheap. Cleared on backend restart — demo-grade.
_analysis_cache: dict[uuid.UUID, dict] = {}


@app.post("/cases/{case_id}/analyze", response_model=schemas.CaseAnalysisResponse)
async def analyze_case(
    case_id: uuid.UUID,
    db: Session = Depends(get_db),
    _admin: User = Depends(require_admin),
):
    case = db.get(Case, case_id)
    if case is None:
        raise HTTPException(status_code=404, detail="Case not found")
    if not case.image_hdfs_path:
        raise HTTPException(status_code=422, detail="Case has no stored image")

    cached = _analysis_cache.get(case_id)
    if cached is not None:
        return schemas.CaseAnalysisResponse(case_id=case_id, cached=True, **cached)

    image_bytes = await run_in_threadpool(hdfs_service.read_image, case.image_hdfs_path)
    import base64 as _b64
    image_b64 = _b64.b64encode(image_bytes).decode("ascii")

    try:
        analysis = await gemini_service.analyze_case_detail(image_b64)
    except gemini_service.GeminiUnavailable as e:
        log.warning("Gemini analyze failed for %s: %s", case_id, e)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Gemini unavailable: {e}",
        )

    _analysis_cache[case_id] = analysis
    return schemas.CaseAnalysisResponse(case_id=case_id, cached=False, **analysis)


@app.get("/cases/{case_id}/address", response_model=schemas.CaseAddressResponse)
async def get_case_address(
    case_id: uuid.UUID,
    db: Session = Depends(get_db),
    _user: User = Depends(get_current_user),
):
    case = db.get(Case, case_id)
    if case is None:
        raise HTTPException(status_code=404, detail="Case not found")
    try:
        display_name = await geocoding_service.reverse_geocode(
            case.latitude, case.longitude
        )
    except geocoding_service.GeocodeUnavailable as e:
        log.info("Nominatim unavailable for %s: %s", case_id, e)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Geocoder unavailable: {e}",
        )
    return schemas.CaseAddressResponse(case_id=case_id, display_name=display_name)


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
                id=c.user.id,
                username=c.user.username,
                role=c.user.role,
                email=c.user.email,
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

    previous_status = case.status
    if payload.status != previous_status:
        db.add(
            CaseStatusEvent(
                case_id=case.id,
                old_status=previous_status,
                new_status=payload.status,
            )
        )
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


@app.get("/analytics/summary", response_model=schemas.AnalyticsSummary)
def analytics_summary(db: Session = Depends(get_db)):
    rows = dict(
        db.execute(
            text("SELECT status, COUNT(*) AS c FROM cases GROUP BY status")
        ).all()
    )
    pending = int(rows.get("pending", 0))
    verified = int(rows.get("verified", 0))
    in_progress = int(rows.get("in_progress", 0))
    completed = int(rows.get("completed", 0))
    total = pending + verified + in_progress + completed
    return schemas.AnalyticsSummary(
        total=total,
        pending=pending,
        verified=verified,
        in_progress=in_progress,
        completed=completed,
    )


@app.get("/analytics/daily", response_model=schemas.DailyCountsResponse)
def analytics_daily(
    days: int = Query(7, ge=1, le=90),
    db: Session = Depends(get_db),
):
    """Count of cases per day for the last N days, ending today. Days with
    zero cases are included with count=0 so the client can render a
    contiguous bar chart without gaps."""
    sql = text(
        """
        SELECT
          (created_at AT TIME ZONE 'UTC')::date AS day,
          COUNT(*) AS c
        FROM cases
        WHERE created_at >= NOW() - (:days || ' days')::interval
        GROUP BY day
        """
    )
    rows = {r.day.isoformat(): int(r.c) for r in db.execute(sql, {"days": days})}

    today = datetime.now(timezone.utc).date()
    points: list[schemas.DailyCountPoint] = []
    for offset in range(days - 1, -1, -1):
        d = today - timedelta(days=offset)
        points.append(
            schemas.DailyCountPoint(date=d.isoformat(), count=rows.get(d.isoformat(), 0))
        )
    return schemas.DailyCountsResponse(days=days, points=points)


@app.get("/analytics/top-zones", response_model=schemas.TopZonesResponse)
def analytics_top_zones(
    limit: int = Query(5, ge=1, le=50),
    db: Session = Depends(get_db),
):
    sql = text(
        """
        SELECT
          lat_zone,
          lng_zone,
          COALESCE(SUM(CASE WHEN type = 'helmet' THEN count END), 0)  AS helmet_count,
          COALESCE(SUM(CASE WHEN type = 'pothole' THEN count END), 0) AS pothole_count,
          COALESCE(SUM(count), 0) AS total
        FROM zone_stats
        GROUP BY lat_zone, lng_zone
        ORDER BY total DESC
        LIMIT :limit
        """
    )
    rows = db.execute(sql, {"limit": limit}).all()
    zones = [
        schemas.TopZonePoint(
            lat=float(r.lat_zone),
            lng=float(r.lng_zone),
            total=int(r.total),
            helmet_count=int(r.helmet_count),
            pothole_count=int(r.pothole_count),
        )
        for r in rows
    ]
    return schemas.TopZonesResponse(limit=limit, zones=zones)


@app.get(
    "/analytics/resolution-trend",
    response_model=schemas.ResolutionTrendResponse,
)
def analytics_resolution_trend(
    weeks: int = Query(8, ge=1, le=52),
    db: Session = Depends(get_db),
):
    """For each of the last N ISO weeks, average days from case.created_at
    to the first 'completed' status event landed in that week.

    Weeks with no resolutions are omitted (client draws a polyline only
    across the weeks that have data)."""
    sql = text(
        """
        WITH first_completed AS (
          SELECT DISTINCT ON (e.case_id)
            e.case_id,
            e.changed_at
          FROM case_status_events e
          WHERE e.new_status = 'completed'
          ORDER BY e.case_id, e.changed_at ASC
        )
        SELECT
          date_trunc('week', (fc.changed_at AT TIME ZONE 'UTC'))::date AS week_start,
          AVG(EXTRACT(EPOCH FROM (fc.changed_at - c.created_at)) / 86400.0) AS avg_days,
          COUNT(*) AS resolved_count
        FROM first_completed fc
        JOIN cases c ON c.id = fc.case_id
        WHERE fc.changed_at >= NOW() - (:weeks || ' weeks')::interval
        GROUP BY week_start
        ORDER BY week_start ASC
        """
    )
    rows = db.execute(sql, {"weeks": weeks}).all()
    points = [
        schemas.ResolutionTrendPoint(
            week_start=r.week_start.isoformat(),
            avg_days=round(float(r.avg_days or 0.0), 2),
            resolved_count=int(r.resolved_count),
        )
        for r in rows
    ]
    return schemas.ResolutionTrendResponse(weeks=weeks, points=points)


@app.get(
    "/analytics/recent-activity",
    response_model=schemas.RecentActivityResponse,
)
def analytics_recent_activity(
    limit: int = Query(10, ge=1, le=50),
    db: Session = Depends(get_db),
):
    sql = text(
        """
        SELECT
          e.case_id,
          c.type AS case_type,
          e.old_status,
          e.new_status,
          e.changed_at,
          u.username
        FROM case_status_events e
        JOIN cases c ON c.id = e.case_id
        LEFT JOIN users u ON u.id = c.user_id
        ORDER BY e.changed_at DESC
        LIMIT :limit
        """
    )
    rows = db.execute(sql, {"limit": limit}).all()
    items = [
        schemas.RecentActivityItem(
            case_id=r.case_id,
            case_type=r.case_type,
            old_status=r.old_status,
            new_status=r.new_status,
            changed_at=r.changed_at,
            username=r.username,
        )
        for r in rows
    ]
    return schemas.RecentActivityResponse(items=items)


# ---------------------------------------------------------------------------
# Conversational agent
# ---------------------------------------------------------------------------


@app.post("/agent/chat", response_model=schemas.AgentChatResponse)
async def agent_chat(
    payload: schemas.AgentChatRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """Multi-turn chat with the CivicShield assistant.

    Session history is keyed by `session_id` and scoped to the authenticated
    user. A compact snapshot of the user's recent cases is passed to the
    model every turn so it can answer status questions without a tool call.
    """
    cases = (
        db.query(Case)
        .filter(Case.user_id == current_user.id)
        .order_by(Case.created_at.desc())
        .limit(10)
        .all()
    )
    if cases:
        lines = [
            f"- {c.type} case {str(c.id)[:8]} — status {c.status}, "
            f"submitted {c.created_at.date().isoformat()}"
            for c in cases
        ]
        case_context = (
            f"username={current_user.username}, total_recent_cases={len(cases)}\n"
            + "\n".join(lines)
        )
    else:
        case_context = (
            f"username={current_user.username} — no cases filed yet."
        )

    try:
        result = await agent_service.chat_turn(
            user_id=str(current_user.id),
            session_id=payload.session_id,
            message=payload.message,
            image_base64=payload.image_base64,
            case_context=case_context,
        )
    except agent_service.AgentUnavailable as e:
        log.warning("Agent chat failed for user %s: %s", current_user.id, e)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Agent unavailable: {e}",
        )
    return schemas.AgentChatResponse(**result)


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
        image_url = f"{settings.BACKEND_PUBLIC_URL}/cases/{c.id}/image"

        ET.SubElement(item, "title").text = _rss_title(c)
        ET.SubElement(item, "description").text = _rss_description(c)
        ET.SubElement(item, "pubDate").text = format_datetime(c.created_at)

        guid = ET.SubElement(item, "guid", isPermaLink="false")
        guid.text = str(c.id)

        ET.SubElement(item, "link").text = image_url
        ET.SubElement(item, "category").text = c.type
        # RSS 2.0 <enclosure> — thumbnail URL for Android parser.
        # length="0" is a harmless placeholder (we don't know byte size server-side
        # without hitting HDFS); most readers accept 0.
        ET.SubElement(
            item,
            "enclosure",
            url=image_url,
            type="image/jpeg",
            length="0",
        )

        # Custom namespaced-ish fields. Readers that don't know them ignore them,
        # the Android app reads them explicitly.
        ET.SubElement(item, "caseId").text = str(c.id)
        ET.SubElement(item, "status").text = c.status
        ET.SubElement(item, "imageUrl").text = image_url

        # Reverse-geocoded address if we already have it cached; otherwise
        # we skip so this endpoint stays fast. The admin detail screen is
        # where Nominatim actually gets hit (and cached). Raw lat/lng is
        # still in <description> as a fallback.
        cached_addr = geocoding_service.cached_address(c.latitude, c.longitude)
        if cached_addr is not None:
            ET.SubElement(item, "address").text = cached_addr

    xml_bytes = ET.tostring(rss, encoding="utf-8", xml_declaration=True)
    return Response(content=xml_bytes, media_type="application/rss+xml; charset=utf-8")


def _rss_title(c: Case) -> str:
    """Format per spec: 'Helmet Violation Confirmed — Case #A1B2'.

    Action verb comes from status so completions read naturally:
      pending       -> "Reported"
      verified      -> "Violation Confirmed"
      in_progress   -> "Under Review"
      completed     -> "Resolved"
    """
    action = {
        "pending": "Reported",
        "verified": "Violation Confirmed",
        "in_progress": "Under Review",
        "completed": "Resolved",
    }.get(c.status, "Reported")
    short_id = str(c.id).replace("-", "")[:4].upper()
    return f"{c.type.capitalize()} {action} \u2014 Case #{short_id}"


def _rss_description(c: Case) -> str:
    parts: list[str] = [
        f"Location: ({c.latitude:.4f}, {c.longitude:.4f})",
        f"Status: {c.status}",
    ]
    if c.ai_description:
        parts.append(c.ai_description)
    elif c.user_description:
        parts.append(c.user_description)
    return " \u00b7 ".join(parts)
