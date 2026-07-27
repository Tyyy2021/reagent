import os
import subprocess
from collections.abc import Generator
from pathlib import Path

import pytest
from sqlalchemy import create_engine, text
from testcontainers.mysql import MySqlContainer  # pyright: ignore[reportMissingTypeStubs]

pytestmark = pytest.mark.integration

_SERVICE_ROOT = Path(__file__).resolve().parents[1]
_EXPECTED_CREATE_TABLE = """CREATE TABLE `demo_ticket` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `idempotency_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `request_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `ticket_id` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `severity` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `evidence` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempt_count` int NOT NULL DEFAULT '1',
  `conflict_count` int NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_demo_ticket_key` (`idempotency_key`),
  UNIQUE KEY `uk_demo_ticket_id` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"""


@pytest.fixture(scope="module")
def mysql_url() -> Generator[str]:
    with MySqlContainer(
        "mysql:8.0",
        dialect="pymysql",
        username="fake_ops_app",
        password="fake-ops-test",
        root_password="fake-ops-root",
        dbname="fake_ops",
    ) as mysql:
        yield mysql.get_connection_url()


def test_upgrade_head_is_repeatable_and_creates_exact_fake_ops_contract(
    mysql_url: str,
) -> None:
    environment = {
        **os.environ,
        "AGENT_CAPABILITIES_MYSQL_URL": mysql_url,
        "PYTHONDONTWRITEBYTECODE": "1",
    }

    for _ in range(2):
        subprocess.run(
            ["alembic", "upgrade", "head"],
            cwd=_SERVICE_ROOT,
            env=environment,
            check=True,
            capture_output=True,
            text=True,
        )

    engine = create_engine(mysql_url)
    try:
        with engine.connect() as connection:
            revisions = connection.execute(
                text("SELECT version_num FROM fake_ops.alembic_version")
            ).scalars()
            create_table = connection.execute(
                text("SHOW CREATE TABLE fake_ops.demo_ticket")
            ).one()[1]
    finally:
        engine.dispose()

    assert list(revisions) == ["0001_demo_ticket"]
    assert create_table == _EXPECTED_CREATE_TABLE
