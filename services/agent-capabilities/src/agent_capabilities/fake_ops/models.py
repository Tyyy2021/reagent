from sqlalchemy import (
    BigInteger,
    CHAR,
    Column,
    Integer,
    MetaData,
    String,
    Table,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.mysql import DATETIME, MEDIUMTEXT

metadata = MetaData(schema="fake_ops")

demo_ticket = Table(
    "demo_ticket",
    metadata,
    Column("id", BigInteger, primary_key=True, autoincrement=True),
    Column("idempotency_key", String(255), nullable=False),
    Column("request_hash", CHAR(64), nullable=False),
    Column("ticket_id", String(32), nullable=False),
    Column("title", String(255), nullable=False),
    Column("severity", String(16), nullable=False),
    Column("evidence", MEDIUMTEXT, nullable=False),
    Column("attempt_count", Integer, nullable=False, server_default=text("1")),
    Column("conflict_count", Integer, nullable=False, server_default=text("0")),
    Column("created_at", DATETIME(fsp=6), nullable=False),
    Column("updated_at", DATETIME(fsp=6), nullable=False),
    UniqueConstraint("idempotency_key", name="uk_demo_ticket_key"),
    UniqueConstraint("ticket_id", name="uk_demo_ticket_id"),
    mysql_engine="InnoDB",
    mysql_charset="utf8mb4",
    mysql_collate="utf8mb4_unicode_ci",
)
