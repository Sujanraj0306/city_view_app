import logging
from pathlib import Path

from .config import settings


log = logging.getLogger(__name__)

_initialized = False


def init() -> bool:
    """Initialize the Firebase Admin SDK from a service-account JSON.

    Returns True if initialization succeeded. If creds are missing or invalid,
    returns False and FCM pushes become no-ops (the rest of the app keeps working).
    """
    global _initialized
    if _initialized:
        return True
    path = settings.FCM_CREDENTIALS_PATH
    if not path or not Path(path).is_file():
        log.warning("Firebase credentials not found at %r — FCM push disabled", path)
        return False
    try:
        import firebase_admin
        from firebase_admin import credentials

        cred = credentials.Certificate(path)
        firebase_admin.initialize_app(cred)
        _initialized = True
        log.info("Firebase Admin SDK initialized")
        return True
    except Exception as e:
        log.warning("Firebase init failed: %s", e)
        return False


def send_push(token: str, title: str, body: str, data: dict | None = None) -> bool:
    """Send an FCM push to a single device token. Returns True on success."""
    if not _initialized:
        return False
    if not token:
        return False
    try:
        from firebase_admin import messaging

        message = messaging.Message(
            token=token,
            notification=messaging.Notification(title=title, body=body),
            data={k: str(v) for k, v in (data or {}).items()},
        )
        messaging.send(message)
        return True
    except Exception as e:
        log.warning("FCM send failed for token %s...: %s", token[:12], e)
        return False
