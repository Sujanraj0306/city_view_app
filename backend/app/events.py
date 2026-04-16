import json
import logging

from kafka import KafkaProducer
from kafka.errors import KafkaError

from .config import settings


log = logging.getLogger(__name__)
_producer: KafkaProducer | None = None


def _get_producer() -> KafkaProducer | None:
    global _producer
    if _producer is not None:
        return _producer
    try:
        _producer = KafkaProducer(
            bootstrap_servers=settings.KAFKA_BOOTSTRAP_SERVERS.split(","),
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            request_timeout_ms=5000,
            api_version_auto_timeout_ms=5000,
        )
    except KafkaError as e:
        log.warning("Kafka producer unavailable: %s", e)
        _producer = None
    return _producer


def emit(topic: str, payload: dict) -> None:
    producer = _get_producer()
    if producer is None:
        return
    try:
        producer.send(topic, payload)
        producer.flush(timeout=2)
    except KafkaError as e:
        log.warning("Kafka emit failed: %s", e)
