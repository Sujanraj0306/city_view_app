import base64
import json
import logging
import os
import re
from typing import Literal

import google.generativeai as genai
from dotenv import load_dotenv


load_dotenv()

log = logging.getLogger(__name__)

_MODEL_NAME = "gemini-1.5-flash"
_API_KEY = os.getenv("GEMINI_API_KEY", "")
_configured = False

if _API_KEY:
    genai.configure(api_key=_API_KEY)
    _configured = True
else:
    log.warning("GEMINI_API_KEY not set — Gemini validation disabled")


_SYSTEM_PROMPT = (
    "You are a civic violation analyst. Analyze this image and return a JSON "
    "object with fields: is_valid_violation (bool), confidence_score (0.0-1.0), "
    "description (2-3 sentence factual description of what is visible), "
    "rejection_reason (string if not valid, else null). "
    "For report_type='helmet', check if a person is visibly riding a vehicle "
    "without a helmet. "
    "For report_type='pothole', check if there is a clearly visible road "
    "pothole or damage. "
    "If the image does not show sufficient evidence — blurry, wrong subject, "
    "no violation visible — set is_valid_violation=false and explain why."
)


class GeminiUnavailable(Exception):
    """Raised when the Gemini API is unreachable, misconfigured, or returns garbage."""


_FENCE_RE = re.compile(r"^```(?:json)?\s*(.*?)\s*```$", re.DOTALL)


def _strip_code_fences(text: str) -> str:
    m = _FENCE_RE.match(text.strip())
    return m.group(1) if m else text


async def analyze_report_image(
    image_base64: str, report_type: Literal["helmet", "pothole"]
) -> dict:
    """Send an image to Gemini Vision and return the parsed analysis.

    Returns a dict with keys:
      - is_valid_violation (bool)
      - confidence_score (float, 0.0-1.0)
      - description (str, 2-3 sentences)
      - rejection_reason (str | None)

    Raises GeminiUnavailable if the API key is missing, the call fails, or the
    response cannot be parsed. Callers should treat this as a transient backend
    failure (503), not as a rejection.
    """
    if not _configured:
        raise GeminiUnavailable("GEMINI_API_KEY not configured")

    try:
        image_bytes = base64.b64decode(image_base64)
    except Exception as e:
        raise GeminiUnavailable(f"invalid base64 image: {e}")

    model = genai.GenerativeModel(
        model_name=_MODEL_NAME,
        system_instruction=_SYSTEM_PROMPT,
        generation_config={"response_mime_type": "application/json"},
    )
    user_prompt = (
        f"report_type='{report_type}'. "
        "Respond with a single JSON object following the schema described."
    )
    parts = [
        {"mime_type": "image/jpeg", "data": image_bytes},
        user_prompt,
    ]

    try:
        resp = await model.generate_content_async(parts)
    except Exception as e:
        log.warning("Gemini call failed: %s", e)
        raise GeminiUnavailable(str(e))

    text = (getattr(resp, "text", "") or "").strip()
    if not text:
        raise GeminiUnavailable("empty response from Gemini")

    try:
        data = json.loads(_strip_code_fences(text))
    except json.JSONDecodeError as e:
        log.warning("Gemini returned non-JSON: %r", text[:200])
        raise GeminiUnavailable(f"non-JSON response: {e}")

    is_valid = bool(data.get("is_valid_violation", False))
    confidence = float(data.get("confidence_score") or 0.0)
    confidence = max(0.0, min(1.0, confidence))
    description = str(data.get("description") or "").strip()
    rejection_reason = data.get("rejection_reason")
    if rejection_reason is not None:
        rejection_reason = str(rejection_reason).strip() or None

    return {
        "is_valid_violation": is_valid,
        "confidence_score": confidence,
        "description": description,
        "rejection_reason": rejection_reason,
    }
