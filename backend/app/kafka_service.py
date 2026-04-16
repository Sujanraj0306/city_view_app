import asyncio
import json
import logging
from datetime import datetime, timezone
from uuid import UUID

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from sqlalchemy import text

from .config import settings
from .database import SessionLocal


log = logging.getLogger(__name__)

_producer: AIOKafkaProducer | None = None
_consumer_task: asyncio.Task | None = None


async def start_producer() -> None:
    global _producer
    producer = AIOKafkaProducer(
        bootstrap_servers=settings.KAFKA_BOOTSTRAP_SERVERS,
        value_serializer=lambda v: json.dumps(v, default=str).encode("utf-8"),
    )
    await producer.start()
    _producer = producer
    log.info("aiokafka producer started (bootstrap=%s)", settings.KAFKA_BOOTSTRAP_SERVERS)


async def stop_producer() -> None:
    global _producer
    if _producer is not None:
        await _producer.stop()
        _producer = None


async def publish_report(
    case_id: UUID | str,
    case_type: str,
    lat: float,
    lng: float,
    ai_verified: bool,
) -> None:
    if _producer is None:
        log.warning("Kafka producer not initialized; dropping event for case %s", case_id)
        return
    payload = {
        "case_id": str(case_id),
        "type": case_type,
        "lat": lat,
        "lng": lng,
        "ai_verified": ai_verified,
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
    try:
        await _producer.send_and_wait(settings.KAFKA_CIVIC_REPORTS_TOPIC, payload)
    except Exception as e:
        log.warning("Kafka publish failed: %s", e)


_UPSERT_SQL = text(
    """
    INSERT INTO zone_stats (lat_zone, lng_zone, type, count)
    VALUES (:lat, :lng, :type, 1)
    ON CONFLICT (lat_zone, lng_zone, type)
    DO UPDATE SET count = zone_stats.count + 1
    """
)


def _upsert_zone_stat_sync(event: dict) -> None:
    case_type = event.get("type")
    if case_type not in ("helmet", "pothole"):
        return
    if "lat" not in event or "lng" not in event:
        return
    lat_zone = round(float(event["lat"]), 2)
    lng_zone = round(float(event["lng"]), 2)
    db = SessionLocal()
    try:
        db.execute(_UPSERT_SQL, {"lat": lat_zone, "lng": lng_zone, "type": case_type})
        db.commit()
    finally:
        db.close()


async def _consumer_loop() -> None:
    consumer = AIOKafkaConsumer(
        settings.KAFKA_CIVIC_REPORTS_TOPIC,
        bootstrap_servers=settings.KAFKA_BOOTSTRAP_SERVERS,
        group_id="civicshield-zone-stats",
        value_deserializer=lambda v: json.loads(v.decode("utf-8")),
        auto_offset_reset="earliest",
        enable_auto_commit=True,
    )
    await consumer.start()
    log.info("zone-stats consumer started")
    try:
        async for msg in consumer:
            try:
                await asyncio.to_thread(_upsert_zone_stat_sync, msg.value)
            except Exception as e:
                log.warning("zone_stats upsert failed for %s: %s", msg.value, e)
    except asyncio.CancelledError:
        log.info("zone-stats consumer cancelled")
    finally:
        await consumer.stop()


async def start_consumer() -> None:
    global _consumer_task
    _consumer_task = asyncio.create_task(_consumer_loop())


async def stop_consumer() -> None:
    global _consumer_task
    if _consumer_task is not None:
        _consumer_task.cancel()
        try:
            await _consumer_task
        except asyncio.CancelledError:
            pass
        _consumer_task = None
