package com.reagent.persist;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaMigrationIT {

    private static final String FRESH_DATABASE = uniqueDatabase("fresh");
    private static final String LEGACY_DATABASE = uniqueDatabase("legacy");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("bootstrap")
            .withUsername("reagent")
            .withPassword("reagent");

    @BeforeAll
    static void createDatabases() throws SQLException {
        try (Connection connection = rootConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + FRESH_DATABASE
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            statement.execute("CREATE DATABASE `" + LEGACY_DATABASE
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    @Test
    @Order(1)
    void createsFreshRuntimeSchemaAtVersionTwo() throws SQLException {
        Flyway flyway = flyway(FRESH_DATABASE, false);

        MigrateResult result = flyway.migrate();

        assertTrue(result.success, "Flyway migration must succeed");
        assertEquals(2, result.migrationsExecuted, "Fresh schema must execute V1 and V2");
        assertHistoryRow(FRESH_DATABASE, "1", "SQL");
        assertHistoryRow(FRESH_DATABASE, "2", "SQL");
        assertTables(FRESH_DATABASE, Set.of(
                "task", "message", "tool_call", "event", "flyway_schema_history"));
        assertV2Metadata(FRESH_DATABASE);
    }

    @Test
    @Order(2)
    void upgradesLegacyRuntimeSchemaAndConvergesAtVersionTwo() throws SQLException {
        createLegacySchema();
        execute(LEGACY_DATABASE, """
                INSERT INTO task (id, goal, status, recovery_count, lease_epoch)
                VALUES ('legacy-task', 'keep me', 'RUNNING', 0, 0)
                """);

        MigrateResult result = flyway(LEGACY_DATABASE, true).migrate();

        assertTrue(result.success, "Flyway baseline migration must succeed");
        assertEquals(1, result.migrationsExecuted, "Existing V1 schema must baseline then execute V2");
        assertHistoryRow(LEGACY_DATABASE, "1", "BASELINE");
        assertHistoryRow(LEGACY_DATABASE, "2", "SQL");
        assertTables(LEGACY_DATABASE, Set.of(
                "task", "message", "tool_call", "event", "flyway_schema_history"));
        try (Connection connection = databaseConnection(LEGACY_DATABASE);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT goal, profile_id, profile_snapshot FROM task WHERE id = 'legacy-task'");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next(), "Legacy row must survive baselining");
            assertEquals("keep me", rows.getString("goal"));
            assertEquals("coding", rows.getString("profile_id"));
            assertEquals(null, rows.getString("profile_snapshot"),
                    "Legacy snapshot remains null until StateStore materializes it under lock");
        }
        assertV2Metadata(LEGACY_DATABASE);
        assertEquals(columnMetadata(FRESH_DATABASE), columnMetadata(LEGACY_DATABASE),
                "Fresh and upgraded V2 task/tool_call metadata must converge exactly");
    }

    private static Flyway flyway(String database, boolean baselineOnMigrate) {
        return Flyway.configure()
                .dataSource(jdbcUrl(database), "root", MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("1")
                .load();
    }

    private static void createLegacySchema() throws SQLException {
        execute(LEGACY_DATABASE, """
                CREATE TABLE task (
                    id VARCHAR(255) NOT NULL,
                    goal MEDIUMTEXT NULL,
                    status VARCHAR(32) NOT NULL,
                    result MEDIUMTEXT NULL,
                    created_at DATETIME(6) NULL,
                    updated_at DATETIME(6) NULL,
                    recovery_count INT NOT NULL DEFAULT 0,
                    owner_id VARCHAR(64) NULL,
                    lease_expires_at DATETIME(6) NULL,
                    lease_epoch BIGINT NOT NULL DEFAULT 0,
                    control_signal VARCHAR(16) NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        execute(LEGACY_DATABASE, """
                CREATE TABLE message (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(255) NULL,
                    seq INT NOT NULL,
                    role VARCHAR(255) NULL,
                    content MEDIUMTEXT NULL,
                    tool_calls_json MEDIUMTEXT NULL,
                    tool_call_id VARCHAR(255) NULL,
                    created_at DATETIME(6) NULL,
                    PRIMARY KEY (id),
                    CONSTRAINT uk_msg_task_seq UNIQUE (task_id, seq)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        execute(LEGACY_DATABASE, """
                CREATE TABLE tool_call (
                    id VARCHAR(255) NOT NULL,
                    task_id VARCHAR(255) NULL,
                    tool_name VARCHAR(255) NULL,
                    arguments MEDIUMTEXT NULL,
                    result MEDIUMTEXT NULL,
                    status VARCHAR(32) NULL,
                    created_at DATETIME(6) NULL,
                    completed_at DATETIME(6) NULL,
                    attempt_count INT NOT NULL DEFAULT 0,
                    started_at DATETIME(6) NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        execute(LEGACY_DATABASE, """
                CREATE TABLE event (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(255) NULL,
                    type VARCHAR(32) NULL,
                    data MEDIUMTEXT NULL,
                    created_at DATETIME(6) NULL,
                    PRIMARY KEY (id),
                    INDEX idx_event_task_id (task_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private static void assertHistoryRow(String database, String version, String expectedType) throws SQLException {
        try (Connection connection = databaseConnection(database);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT version, type, success
                     FROM flyway_schema_history
                     WHERE version = ?
                     """);
             ) {
            statement.setString(1, version);
            try (ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next(), "Flyway history must contain version " + version);
            assertEquals(version, rows.getString("version"));
            assertEquals(expectedType, rows.getString("type"));
            assertTrue(rows.getBoolean("success"));
            assertTrue(!rows.next(), "Version " + version + " must have exactly one history row");
            }
        }
    }

    private static void assertV2Metadata(String database) throws SQLException {
        assertColumn(database, "task", "profile_id", "varchar(64)", "YES");
        assertColumn(database, "task", "profile_snapshot", "mediumtext", "YES");
        assertColumn(database, "task", "status", "varchar(32)", "NO");
        assertColumn(database, "tool_call", "assistant_message_seq", "int", "YES");
        assertColumn(database, "tool_call", "status", "varchar(32)", "YES");

        List<String> columns = new java.util.ArrayList<>();
        try (Connection connection = databaseConnection(database);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT column_name
                     FROM information_schema.statistics
                     WHERE table_schema = ? AND table_name = 'tool_call'
                       AND index_name = 'idx_tool_call_task_batch'
                     ORDER BY seq_in_index
                     """)) {
            statement.setString(1, database);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString("column_name"));
                }
            }
        }
        assertEquals(List.of("task_id", "assistant_message_seq"), columns);
    }

    private static void assertColumn(String database, String table, String column,
                                     String expectedType, String expectedNullable) throws SQLException {
        try (Connection connection = databaseConnection(database);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT column_type, is_nullable
                     FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = ? AND column_name = ?
                     """)) {
            statement.setString(1, database);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), () -> "Missing column " + table + "." + column);
                assertEquals(expectedType, rows.getString("column_type"));
                assertEquals(expectedNullable, rows.getString("is_nullable"));
                assertTrue(!rows.next(), "Column metadata must be unique");
            }
        }
    }

    private static Map<String, ColumnMetadata> columnMetadata(String database) throws SQLException {
        Map<String, ColumnMetadata> metadata = new TreeMap<>();
        try (Connection connection = databaseConnection(database);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT table_name, column_name, ordinal_position, column_type, is_nullable,
                            column_default, extra, character_set_name, collation_name
                     FROM information_schema.columns
                     WHERE table_schema = ? AND table_name IN ('task', 'tool_call')
                     ORDER BY table_name, ordinal_position
                     """)) {
            statement.setString(1, database);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String key = rows.getString("table_name") + "." + rows.getString("column_name");
                    metadata.put(key, new ColumnMetadata(
                            rows.getInt("ordinal_position"),
                            rows.getString("column_type"),
                            rows.getString("is_nullable"),
                            rows.getString("column_default"),
                            rows.getString("extra"),
                            rows.getString("character_set_name"),
                            rows.getString("collation_name")));
                }
            }
        }
        return metadata;
    }

    private record ColumnMetadata(
            int ordinalPosition,
            String columnType,
            String nullable,
            String defaultValue,
            String extra,
            String characterSet,
            String collation
    ) {
    }

    private static void assertTables(String database, Set<String> expected) throws SQLException {
        Set<String> actual = new HashSet<>();
        try (Connection connection = databaseConnection(database);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = ?
                     """)) {
            statement.setString(1, database);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.add(rows.getString("table_name"));
                }
            }
        }
        assertTrue(actual.containsAll(expected), () -> "Missing runtime tables; actual=" + actual);
    }

    private static void execute(String database, String sql) throws SQLException {
        try (Connection connection = databaseConnection(database); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection rootConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl("mysql"), "root", MYSQL.getPassword());
    }

    private static Connection databaseConnection(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), "root", MYSQL.getPassword());
    }

    private static String jdbcUrl(String database) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    private static String uniqueDatabase(String kind) {
        return "reagent_" + kind + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
