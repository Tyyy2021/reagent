from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

from agent_capabilities.config import Settings
from agent_capabilities.fake_ops.models import metadata

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

config.set_main_option("sqlalchemy.url", Settings().mysql_url)
target_metadata = metadata


def run_migrations_offline() -> None:
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        include_schemas=True,
        version_table_schema="fake_ops",
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    existing_connection = config.attributes.get("connection")
    if existing_connection is not None:
        context.configure(
            connection=existing_connection,
            target_metadata=target_metadata,
            include_schemas=True,
            version_table_schema="fake_ops",
        )
        with context.begin_transaction():
            context.run_migrations()
        return

    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            include_schemas=True,
            version_table_schema="fake_ops",
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
