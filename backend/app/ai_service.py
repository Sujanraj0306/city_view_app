import logging

import httpx

from .config import settings


log = logging.getLogger(__name__)

_TIMEOUT = httpx.Timeout(30.0, connect=5.0)


async def _call(endpoint: str, image_base64: str) -> dict:
    url = f"{settings.AI_SERVICE_URL}{endpoint}"
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.post(url, json={"image_base64": image_base64})
        resp.raise_for_status()
        return resp.json()


async def predict_helmet(image_base64: str) -> dict:
    return await _call("/predict/helmet", image_base64)


async def predict_pothole(image_base64: str) -> dict:
    return await _call("/predict/pothole", image_base64)
