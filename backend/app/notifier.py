import smtplib
from email.message import EmailMessage

from .config import settings


def send_alert_email(category: str, report_title: str, report_body: str) -> bool:
    """Send the report to the appropriate authority. No-op if Gmail creds are missing."""
    if not (settings.GMAIL_USER and settings.GMAIL_PASSWORD):
        return False

    recipient = settings.POLICE_EMAIL if category == "police" else settings.CORPORATION_EMAIL
    if not recipient:
        return False

    msg = EmailMessage()
    msg["From"] = settings.GMAIL_USER
    msg["To"] = recipient
    msg["Subject"] = f"[CivicShield] {category.upper()}: {report_title}"
    msg.set_content(report_body)

    try:
        with smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=10) as smtp:
            smtp.login(settings.GMAIL_USER, settings.GMAIL_PASSWORD)
            smtp.send_message(msg)
        return True
    except Exception:
        return False
