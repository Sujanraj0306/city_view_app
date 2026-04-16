import logging
import smtplib
from email.message import EmailMessage
from uuid import UUID

from .config import settings


log = logging.getLogger(__name__)


def _send(to: str, subject: str, body: str) -> bool:
    if not (settings.GMAIL_USER and settings.GMAIL_PASSWORD and to):
        log.info("Email skipped (missing Gmail creds or recipient)")
        return False

    msg = EmailMessage()
    msg["From"] = settings.GMAIL_USER
    msg["To"] = to
    msg["Subject"] = subject
    msg.set_content(body)

    try:
        with smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=10) as smtp:
            smtp.login(settings.GMAIL_USER, settings.GMAIL_PASSWORD)
            smtp.send_message(msg)
        log.info("alert email sent to %s", to)
        return True
    except Exception as e:
        log.warning("Email send failed: %s", e)
        return False


def _body(case_id: UUID | str, lat: float, lng: float, confidence: float) -> str:
    image_link = f"{settings.BACKEND_PUBLIC_URL}/cases/{case_id}/image"
    return (
        "A new civic violation has been reported on CivicShield.\n\n"
        f"Case ID: {case_id}\n"
        f"GPS coordinates: ({lat}, {lng})\n"
        f"AI confidence: {confidence:.2f}\n\n"
        f"Evidence image: {image_link}\n"
    )


def send_helmet_alert(
    case_id: UUID | str, lat: float, lng: float, confidence: float
) -> bool:
    subject = f"[CivicShield] Helmet Violation — Case {case_id}"
    return _send(settings.POLICE_EMAIL, subject, _body(case_id, lat, lng, confidence))


def send_pothole_alert(
    case_id: UUID | str, lat: float, lng: float, confidence: float
) -> bool:
    subject = f"[CivicShield] Pothole Report — Case {case_id}"
    return _send(
        settings.CORPORATION_EMAIL, subject, _body(case_id, lat, lng, confidence)
    )
