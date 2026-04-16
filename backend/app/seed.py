import logging

from sqlalchemy.orm import Session

from .auth import hash_password
from .models import User


log = logging.getLogger(__name__)


TEST_USERS = [
    ("user1", "pass123", "user", "user1@civicshield.demo"),
    ("user2", "pass123", "user", "user2@civicshield.demo"),
    ("admin", "admin123", "admin", "admin@civicshield.demo"),
]


def seed_users(db: Session) -> None:
    """Insert the 3 baseline test users if they don't already exist. Idempotent.

    If a user already exists without an email, backfill it in place so the
    admin detail screen always has an address to show.
    """
    created = 0
    updated = 0
    for username, password, role, email in TEST_USERS:
        existing = db.query(User).filter(User.username == username).first()
        if existing is None:
            db.add(
                User(
                    username=username,
                    email=email,
                    password_hash=hash_password(password),
                    role=role,
                )
            )
            created += 1
        elif not existing.email:
            existing.email = email
            updated += 1
    if created or updated:
        db.commit()
        log.info("Seed: created=%d, backfilled_email=%d", created, updated)
    else:
        log.info("Test users already present with emails; skipping seed")
