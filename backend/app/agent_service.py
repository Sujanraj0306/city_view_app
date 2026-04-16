"""CivicShield conversational agent.

Thin wrapper around google-generativeai's multi-turn chat. Each session_id
maps to a persistent ChatSession so history accumulates across turns. The
model is steered toward a structured JSON reply so the Android app can act
on the response (open the camera, show past cases, submit a report).

In-memory sessions — demo-grade. History is lost on process restart.
"""

from __future__ import annotations

import base64
import json
import logging
import os
import re
from dataclasses import dataclass
from typing import Any, Optional

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
    log.warning("GEMINI_API_KEY not set — agent chat disabled")


_SYSTEM_PROMPT = (
    "You are CivicShield Assistant, a helpful civic reporting agent. "
    "You help citizens check their case status and file new violation reports. "
    "You have access to the user's case history. "
    "When the user wants to file a report, ask for: image (tell them to attach it), "
    "description, and confirm their GPS is shared. "
    "When they want case status, look up their cases and report clearly. "
    "Always be concise and friendly.\n\n"
    "RESPONSE FORMAT: Reply with a single JSON object and nothing else:\n"
    '  {"reply": "<conversational text, <= 3 sentences>", '
    '"action": null | "open_camera" | "show_cases" | "submit_report", '
    '"data": {}}\n'
    "Action guidance:\n"
    "  - \"open_camera\"    — user wants to file a report and has not attached an image yet.\n"
    "  - \"show_cases\"     — user is asking about their past reports/status.\n"
    "  - \"submit_report\"  — user attached an image and confirmed they want to submit it.\n"
    "  - null             — general chat, clarification, or anything else.\n"
    "Put structured fields you want the app to use inside \"data\" (e.g. case IDs "
    "to highlight). Keep it small."
)


class AgentUnavailable(Exception):
    """Raised when the agent cannot produce a reply (no API key, transport, parse)."""


@dataclass
class _Session:
    chat: Any  # genai.ChatSession
    user_id: str


_sessions: dict[str, _Session] = {}

_FENCE_RE = re.compile(r"^```(?:json)?\s*(.*?)\s*```$", re.DOTALL)


def _strip_code_fences(text: str) -> str:
    m = _FENCE_RE.match(text.strip())
    return m.group(1) if m else text


def _new_chat() -> Any:
    model = genai.GenerativeModel(
        model_name=_MODEL_NAME,
        system_instruction=_SYSTEM_PROMPT,
        generation_config={"response_mime_type": "application/json"},
    )
    return model.start_chat(history=[])


async def chat_turn(
    user_id: str,
    session_id: str,
    message: str,
    image_base64: Optional[str],
    case_context: str,
) -> dict:
    """Run one agent turn.

    `case_context` is a short human-readable snapshot of the user's cases;
    it is prepended to the user's message on every turn so the model always
    sees current state, even when the chat session is long-running.
    """
    if not _configured:
        raise AgentUnavailable("GEMINI_API_KEY not configured")

    sess = _sessions.get(session_id)
    if sess is None or sess.user_id != user_id:
        # New session (or session_id reused across users — start fresh).
        sess = _Session(chat=_new_chat(), user_id=user_id)
        _sessions[session_id] = sess

    preamble = f"[Case snapshot]\n{case_context}\n\n[User]\n{message or ''}"

    parts: list[Any] = []
    if image_base64:
        try:
            image_bytes = base64.b64decode(image_base64)
        except Exception as e:
            raise AgentUnavailable(f"invalid base64 image: {e}")
        parts.append({"mime_type": "image/jpeg", "data": image_bytes})
    parts.append(preamble)

    try:
        resp = await sess.chat.send_message_async(parts)
    except Exception as e:
        log.warning("Gemini agent call failed: %s", e)
        raise AgentUnavailable(str(e))

    text = (getattr(resp, "text", "") or "").strip()
    if not text:
        raise AgentUnavailable("empty response from Gemini")

    try:
        data = json.loads(_strip_code_fences(text))
    except json.JSONDecodeError:
        log.info("Agent returned non-JSON, wrapping as plain reply: %r", text[:200])
        return {"reply": text, "action": None, "data": {}}

    reply = str(data.get("reply") or "").strip()
    action = data.get("action")
    if action not in (None, "open_camera", "show_cases", "submit_report"):
        action = None
    extras = data.get("data")
    if not isinstance(extras, dict):
        extras = {}

    return {"reply": reply, "action": action, "data": extras}


def reset_session(session_id: str) -> None:
    """Drop a session's history. Used when the client wants a clean slate."""
    _sessions.pop(session_id, None)
