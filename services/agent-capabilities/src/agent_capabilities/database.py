from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine

_SERVICE_ROOT = Path(__file__).resolve().parents[2]


def run_migrations(mysql_url: str) -> None:
    configuration = Config(_SERVICE_ROOT / "alembic.ini")
    engine = create_engine(mysql_url, pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            configuration.attributes["connection"] = connection
            command.upgrade(configuration, "head")
    finally:
        engine.dispose()
