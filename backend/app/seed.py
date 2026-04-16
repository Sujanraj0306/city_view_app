import logging

from sqlalchemy.orm import Session

from .auth import hash_password
from .models import User


log = logging.getLogger(__name__)


TEST_USERS = [
    ("user1", "pass123", "user"),
    ("user2", "pass123", "user"),
    ("admin", "admin123", "admin"),
]


def seed_users(db: Session) -> None:
    """Insert the 3 baseline test users if they don't already exist. Idempotent."""
    created = 0
    for username, password, role in TEST_USERS:
        if db.query(User).filter(User.username == username).first():
            continue
        db.add(
            User(
                username=username,
                password_hash=hash_password(password),
                role=role,
            )
        )
        created += 1
    if created:
        db.commit()
        log.info("Seeded %d test users", created)
    else:
        log.info("Test users already present; skipping seed")
