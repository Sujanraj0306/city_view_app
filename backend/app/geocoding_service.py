"""Reverse-geocoding via Nominatim (OpenStreetMap).

Usage policy: https://operations.osmfoundation.org/policies/nominatim/
- No bulk scraping
- Required User-Agent identifying the application
- 1 request/second hard cap (we enforce softer via caching)

This module returns display_name strings and caches them in-memory by
(rounded lat, rounded lng) so admin screens reopening the same case don't
re-hit Nominatim.
"""
import asyncio
import logging
import time
from typing import Tuple

import httpx


log = logging.getLogger(__name__)

_NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse"
_USER_AGENT = "CivicShield/0.6 (civic-reporting-demo)"
_TIMEOUT = httpx.Timeout(10.0, connect=5.0)

# (lat6, lng6) -> (display_name, inserted_at_epoch)
_cache: dict[Tuple[float, float], Tuple[str, float]] = {}
_TTL_SECONDS = 60 * 60 * 24  # 24h

# Serialize outbound requests so we respect the 1 req/s policy even under burst.
_req_lock = asyncio.Lock()
_last_request_at: float = 0.0
_MIN_INTERVAL_S = 1.05


class GeocodeUnavailable(Exception):
    """Raised when Nominatim can't be reached or returns unusable data."""


def _cache_key(lat: float, lng: float) -> Tuple[float, float]:
    # 6 decimals ~= 11 cm precision, plenty granular, avoids cache miss on FP noise
    return (round(lat, 6), round(lng, 6))


def cached_address(lat: float, lng: float) -> str | None:
    """Return the cached display_name for (lat, lng), or None if not cached
    or expired. Does NOT make a network call — use this from hot paths like
    the RSS feed where we don't want to block the request on Nominatim.
    """
    key = _cache_key(lat, lng)
    cached = _cache.get(key)
    if cached is None:
        return None
    display_name, inserted_at = cached
    if (time.time() - inserted_at) >= _TTL_SECONDS:
        return None
    return display_name


async def reverse_geocode(lat: float, lng: float) -> str:
    """Return a human-readable address for the given coordinates.

    Raises GeocodeUnavailable on transport / 4xx / 5xx / empty response.
    """
    key = _cache_key(lat, lng)
    now = time.time()
    cached = _cache.get(key)
    if cached and (now - cached[1]) < _TTL_SECONDS:
        return cached[0]

    global _last_request_at
    async with _req_lock:
        # Honor 1 req/s policy: wait out the remaining interval if needed.
        wait_s = _MIN_INTERVAL_S - (time.time() - _last_request_at)
        if wait_s > 0:
            await asyncio.sleep(wait_s)

        params = {
            "lat": f"{lat:.6f}",
            "lon": f"{lng:.6f}",
            "format": "json",
            "zoom": "18",
            "addressdetails": "0",
        }
        headers = {"User-Agent": _USER_AGENT, "Accept-Language": "en"}

        try:
            async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
                resp = await client.get(_NOMINATIM_URL, params=params, headers=headers)
        except Exception as e:
            log.warning("Nominatim request failed: %s", e)
            raise GeocodeUnavailable(str(e))
        finally:
            _last_request_at = time.time()

    if resp.status_code >= 400:
        raise GeocodeUnavailable(f"Nominatim HTTP {resp.status_code}")

    try:
        data = resp.json()
    except Exception as e:
        raise GeocodeUnavailable(f"Nominatim bad JSON: {e}")

    display_name = (data or {}).get("display_name")
    if not isinstance(display_name, str) or not display_name.strip():
        raise GeocodeUnavailable("Nominatim returned no display_name")

    display_name = display_name.strip()
    _cache[key] = (display_name, time.time())
    return display_name
