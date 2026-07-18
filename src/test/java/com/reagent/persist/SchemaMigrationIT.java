package com.reagent.persist;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
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
    void createsFreshRuntimeSchemaAtVersionOne() throws SQLException {
        Flyway flyway = flyway(FRESH_DATABASE, false);

        MigrateResult result = flyway.migrate();

        assertTrue(result.success, "Flyway migration must succeed");
        assertEquals(1, result.migrationsExecuted, "Fresh schema must execute V1");
        assertHistoryRow(FRESH_DATABASE, "SQL");
        assertTables(FRESH_DATABASE, Set.of(
                "task", "message", "tool_call", "event", "flyway_schema_history"));
    }

    @Test
    void baselinesExistingRuntimeSchemaAtVersionOne() throws SQLException {
        createLegacySchema();
        execute(LEGACY_DATABASE, """
                INSERT INTO task (id, goal, status, recovery_count, lease_epoch)
                VALUES ('legacy-task', 'keep me', 'RUNNING', 0, 0)
                """);

        MigrateResult result = flyway(LEGACY_DATABASE, true).migrate();

        assertTrue(result.success, "Flyway baseline migration must succeed");
        assertEquals(0, result.migrationsExecuted, "Existing V1 schema is baselined, not recreated");
        assertHistoryRow(LEGACY_DATABASE, "BASELINE");
        assertTables(LEGACY_DATABASE, Set.of(
                "task", "message", "tool_call", "event", "flyway_schema_history"));
        try (Connection connection = databaseConnection(LEGACY_DATABASE);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT goal FROM task WHERE id = 'legacy-task'");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next(), "Legacy row must survive baselining");
            assertEquals("keep me", rows.getString("goal"));
        }
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

    private static void assertHistoryRow(String database, String expectedType) throws SQLException {
        try (Connection connection = databaseConnection(database);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT version, type, success
                     FROM flyway_schema_history
                     WHERE version = '1'
                     """);
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next(), "Flyway history must contain version 1");
            assertEquals("1", rows.getString("version"));
            assertEquals(expectedType, rows.getString("type"));
            assertTrue(rows.getBoolean("success"));
            assertTrue(!rows.next(), "Version 1 must have exactly one history row");
        }
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
