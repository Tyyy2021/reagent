package com.reagent.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class InfrastructureIT {

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("reagent")
            .withUsername("reagent")
            .withPassword("reagent");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.0-alpine")
            .withExposedPorts(6379);

    private static final Path WORKSPACE_ROOT = createWorkspaceRoot();

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("reagent.test.workspace-root", WORKSPACE_ROOT::toString);
    }

    private static Path createWorkspaceRoot() {
        try {
            return Files.createTempDirectory("reagent-it-workspaces-");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create integration-test workspace", exception);
        }
    }
}
