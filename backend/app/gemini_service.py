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


_DETAIL_SYSTEM_PROMPT = (
    "You are a civic violation analyst producing detailed case analysis. "
    "Given an image, return a JSON object with fields: "
    "scene_description (what is happening in plain English, 2-3 sentences), "
    "violation_confirmed (bool), "
    "vehicle_number (string or null — read any visible number plate, exact characters as shown), "
    "violation_zone (describe the exact spot visible — road name, landmark, any text in the background, even if only partially visible), "
    "severity ('low', 'medium', or 'high'), "
    "bounding_box (object with x, y, width, height in 0-1 normalized image coordinates, or null if the violation is not a discrete object). "
    "Respond with JSON only, no prose."
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


async def analyze_case_detail(image_base64: str) -> dict:
    """Detailed analysis used by the admin case-detail screen.

    Returned keys (all always present, missing fields coerced to sensible defaults):
      - scene_description (str)
      - violation_confirmed (bool)
      - vehicle_number (str | None) — visible number-plate text, exact characters
      - violation_zone (str) — road/landmark/visible text description
      - severity (str) — "low" | "medium" | "high"
      - bounding_box (dict | None) — {x, y, width, height} in 0..1 normalized coords

    Raises GeminiUnavailable on transport / parse / config failure.
    """
    if not _configured:
        raise GeminiUnavailable("GEMINI_API_KEY not configured")

    try:
        image_bytes = base64.b64decode(image_base64)
    except Exception as e:
        raise GeminiUnavailable(f"invalid base64 image: {e}")

    model = genai.GenerativeModel(
        model_name=_MODEL_NAME,
        system_instruction=_DETAIL_SYSTEM_PROMPT,
        generation_config={"response_mime_type": "application/json"},
    )
    parts = [
        {"mime_type": "image/jpeg", "data": image_bytes},
        "Produce the detailed analysis JSON now.",
    ]

    try:
        resp = await model.generate_content_async(parts)
    except Exception as e:
        log.warning("Gemini detail call failed: %s", e)
        raise GeminiUnavailable(str(e))

    text = (getattr(resp, "text", "") or "").strip()
    if not text:
        raise GeminiUnavailable("empty response from Gemini")

    try:
        data = json.loads(_strip_code_fences(text))
    except json.JSONDecodeError as e:
        log.warning("Gemini detail returned non-JSON: %r", text[:200])
        raise GeminiUnavailable(f"non-JSON response: {e}")

    severity = str(data.get("severity") or "").lower()
    if severity not in ("low", "medium", "high"):
        severity = "medium"

    vehicle_number = data.get("vehicle_number")
    if vehicle_number is not None:
        vehicle_number = str(vehicle_number).strip() or None

    bbox_raw = data.get("bounding_box")
    bounding_box = None
    if isinstance(bbox_raw, dict):
        try:
            bounding_box = {
                "x": max(0.0, min(1.0, float(bbox_raw.get("x", 0.0)))),
                "y": max(0.0, min(1.0, float(bbox_raw.get("y", 0.0)))),
                "width": max(0.0, min(1.0, float(bbox_raw.get("width", 0.0)))),
                "height": max(0.0, min(1.0, float(bbox_raw.get("height", 0.0)))),
            }
            # Sanity: drop degenerate bboxes
            if bounding_box["width"] <= 0.0 or bounding_box["height"] <= 0.0:
                bounding_box = None
        except (TypeError, ValueError):
            bounding_box = None

    return {
        "scene_description": str(data.get("scene_description") or "").strip(),
        "violation_confirmed": bool(data.get("violation_confirmed", False)),
        "vehicle_number": vehicle_number,
        "violation_zone": str(data.get("violation_zone") or "").strip(),
        "severity": severity,
        "bounding_box": bounding_box,
    }
