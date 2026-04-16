import base64
import logging
import smtplib
from email.mime.image import MIMEImage
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.utils import make_msgid
from uuid import UUID

from .config import settings


log = logging.getLogger(__name__)


def _send_mime(msg: MIMEMultipart, to: str) -> bool:
    if not (settings.GMAIL_USER and settings.GMAIL_PASSWORD and to):
        log.info("Email skipped (missing Gmail creds or recipient)")
        return False
    try:
        with smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=15) as smtp:
            smtp.login(settings.GMAIL_USER, settings.GMAIL_PASSWORD)
            smtp.send_message(msg)
        log.info("alert email sent to %s", to)
        return True
    except Exception as e:
        log.warning("Email send failed: %s", e)
        return False


def _decode_image(image_base64: str | None) -> bytes | None:
    if not image_base64:
        return None
    try:
        return base64.b64decode(image_base64)
    except Exception as e:
        log.warning("could not decode image for email: %s", e)
        return None


def _build_message(
    to: str,
    subject: str,
    case_id: UUID | str,
    case_type: str,
    lat: float,
    lng: float,
    confidence: float,
    ai_description: str,
    image_bytes: bytes | None,
) -> MIMEMultipart:
    image_link = f"{settings.BACKEND_PUBLIC_URL}/cases/{case_id}/image"
    maps_link = f"https://www.google.com/maps?q={lat},{lng}"

    root = MIMEMultipart("related")
    root["From"] = settings.GMAIL_USER
    root["To"] = to
    root["Subject"] = subject

    alt = MIMEMultipart("alternative")
    root.attach(alt)

    plain = (
        f"A new {case_type} violation has been reported on CivicShield.\n\n"
        f"Case ID: {case_id}\n"
        f"GPS: ({lat}, {lng})   {maps_link}\n"
        f"AI confidence: {confidence:.2f}\n\n"
        f"AI description:\n{ai_description or '(none)'}\n\n"
        f"Evidence image: {image_link}\n"
    )
    alt.attach(MIMEText(plain, "plain", "utf-8"))

    cid = make_msgid(domain="civicshield")
    cid_bare = cid[1:-1]

    if image_bytes:
        img_block = (
            f'<p><img src="cid:{cid_bare}" '
            f'alt="Evidence" style="max-width:600px;border:1px solid #ccc"/></p>'
        )
    else:
        img_block = (
            f'<p><a href="{image_link}">Evidence image (click to view)</a></p>'
        )

    html = f"""\
<html>
  <body style="font-family:Arial,sans-serif;color:#222">
    <h2 style="margin-bottom:4px">CivicShield — {case_type.capitalize()} violation</h2>
    <p style="color:#666;margin-top:0">Case <code>{case_id}</code></p>
    {img_block}
    <table style="border-collapse:collapse">
      <tr><td style="padding:4px 12px 4px 0"><b>GPS</b></td>
          <td><a href="{maps_link}">{lat}, {lng}</a></td></tr>
      <tr><td style="padding:4px 12px 4px 0"><b>AI confidence</b></td>
          <td>{confidence:.2f}</td></tr>
      <tr><td style="padding:4px 12px 4px 0;vertical-align:top"><b>AI description</b></td>
          <td>{(ai_description or '(none)').replace('<','&lt;').replace('>','&gt;')}</td></tr>
    </table>
    <p style="color:#666;font-size:12px;margin-top:24px">
      Original evidence is also hosted at
      <a href="{image_link}">{image_link}</a>.
    </p>
  </body>
</html>
"""
    alt.attach(MIMEText(html, "html", "utf-8"))

    if image_bytes:
        img = MIMEImage(image_bytes, _subtype="jpeg")
        img.add_header("Content-ID", cid)
        img.add_header("Content-Disposition", "inline", filename=f"{case_id}.jpg")
        root.attach(img)

    return root


def send_helmet_alert(
    case_id: UUID | str,
    lat: float,
    lng: float,
    confidence: float,
    ai_description: str = "",
    image_base64: str | None = None,
) -> bool:
    subject = f"[CivicShield] Helmet Violation — Case {case_id}"
    msg = _build_message(
        settings.POLICE_EMAIL,
        subject,
        case_id,
        "helmet",
        lat,
        lng,
        confidence,
        ai_description,
        _decode_image(image_base64),
    )
    return _send_mime(msg, settings.POLICE_EMAIL)


def send_pothole_alert(
    case_id: UUID | str,
    lat: float,
    lng: float,
    confidence: float,
    ai_description: str = "",
    image_base64: str | None = None,
) -> bool:
    subject = f"[CivicShield] Pothole Report — Case {case_id}"
    msg = _build_message(
        settings.CORPORATION_EMAIL,
        subject,
        case_id,
        "pothole",
        lat,
        lng,
        confidence,
        ai_description,
        _decode_image(image_base64),
    )
    return _send_mime(msg, settings.CORPORATION_EMAIL)
