import base64
import logging
from uuid import UUID

from hdfs import InsecureClient

from .config import settings


log = logging.getLogger(__name__)

HDFS_IMAGE_DIR = "/civic-shield/images"

_client: InsecureClient | None = None


def _get_client() -> InsecureClient:
    global _client
    if _client is None:
        _client = InsecureClient(settings.HDFS_WEBHDFS_URL, user=settings.HDFS_USER)
        _client.makedirs(HDFS_IMAGE_DIR)
    return _client


def upload_image(case_id: UUID | str, base64_bytes: str | bytes) -> str:
    """Decode base64 payload and write it to /civic-shield/images/{case_id}.jpg. Returns HDFS path."""
    raw = base64.b64decode(base64_bytes, validate=False)
    path = f"{HDFS_IMAGE_DIR}/{case_id}.jpg"
    client = _get_client()
    with client.write(path, overwrite=True) as writer:
        writer.write(raw)
    log.info("uploaded case image to %s (%d bytes)", path, len(raw))
    return path


def read_image(path: str) -> bytes:
    """Read raw image bytes from HDFS."""
    client = _get_client()
    with client.read(path) as reader:
        return reader.read()
