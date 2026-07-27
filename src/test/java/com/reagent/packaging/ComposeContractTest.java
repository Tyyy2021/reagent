package com.reagent.packaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> EXPECTED_SERVICES = Set.of(
            "mysql",
            "redis",
            "jaeger",
            "agent-capabilities",
            "reagent-worker-a",
            "reagent-worker-b");

    @Test
    void rendersTheCompleteStackWithPinnedMajorServicesAndSafeDemoDefaults()
            throws Exception {
        JsonNode full = renderCompose("failover");
        JsonNode services = full.path("services");

        assertEquals(EXPECTED_SERVICES, fieldNames(services));
        assertEquals("mysql:8.0", services.path("mysql").path("image").asText());
        assertEquals("redis:8", services.path("redis").path("image").asText());
        assertEquals(
                "jaegertracing/all-in-one:1.62.0",
                services.path("jaeger").path("image").asText());
        assertEquals(
                List.of("failover"),
                strings(services.path("reagent-worker-b").path("profiles")));

        for (String service : EXPECTED_SERVICES) {
            assertFalse(
                    services.path(service).has("container_name"),
                    () -> service + " must not set container_name");
        }

        JsonNode pythonEnvironment =
                services.path("agent-capabilities").path("environment");
        assertEquals(
                "false",
                pythonEnvironment.path(
                        "AGENT_CAPABILITIES_ACCEPTANCE_ENABLED").asText());
        assertEquals(
                "false",
                pythonEnvironment.path(
                        "AGENT_CAPABILITIES_CHAOS_ENABLED").asText());
        assertEquals(
                "",
                services.path("reagent-worker-a").path("environment")
                        .path("SPRING_PROFILES_ACTIVE").asText());
        assertFalse(
                full.toString().contains("sk-"),
                "rendered Compose must not contain a real-looking API key");
    }

    @Test
    void keepsDependenciesInternalAndWaitsForRequiredHealth() throws Exception {
        JsonNode services = renderCompose("failover").path("services");
        Map<String, Set<Integer>> published = new HashMap<>();

        for (String service : EXPECTED_SERVICES) {
            JsonNode definition = services.path(service);
            assertTrue(
                    definition.path("networks").has("reagent-backend"),
                    () -> service + " must join reagent-backend");
            Set<Integer> ports = publishedPorts(definition.path("ports"));
            if (!ports.isEmpty()) {
                published.put(service, ports);
            }
        }

        assertEquals(
                Map.of(
                        "reagent-worker-a", Set.of(8080),
                        "jaeger", Set.of(16686)),
                published);
        assertHealthyDependencies(
                services.path("agent-capabilities"),
                Set.of("mysql", "redis"));
        assertHealthyDependencies(
                services.path("reagent-worker-a"),
                Set.of("mysql", "redis", "agent-capabilities"));
        assertHealthyDependencies(
                services.path("reagent-worker-b"),
                Set.of("mysql", "redis", "agent-capabilities"));

        for (String service : List.of(
                "mysql", "redis", "agent-capabilities",
                "reagent-worker-a", "reagent-worker-b")) {
            assertTrue(
                    services.path(service).path("healthcheck").has("test"),
                    () -> service + " must define a healthcheck");
        }
    }

    @Test
    void usesNamedPersistentStoresAndSharedWorkerWorkspace() throws Exception {
        JsonNode compose = renderCompose("failover");
        assertEquals(
                Set.of(
                        "mysql-data",
                        "redis-data",
                        "model-cache",
                        "worker-workspaces"),
                fieldNames(compose.path("volumes")));

        assertEquals(
                Set.of("mysql-data"),
                volumeSources(compose.path("services").path("mysql")));
        assertEquals(
                Set.of("redis-data"),
                volumeSources(compose.path("services").path("redis")));
        assertEquals(
                Set.of("model-cache"),
                volumeSources(compose.path("services").path("agent-capabilities")));
        assertEquals(
                Set.of("worker-workspaces"),
                volumeSources(compose.path("services").path("reagent-worker-a")));
        assertEquals(
                Set.of("worker-workspaces"),
                volumeSources(compose.path("services").path("reagent-worker-b")));
    }

    @Test
    void wiresSchemaScopedAccountsWithoutCrossSchemaGrants() throws Exception {
        JsonNode services = renderCompose("failover").path("services");
        JsonNode javaEnvironment =
                services.path("reagent-worker-a").path("environment");
        JsonNode pythonEnvironment =
                services.path("agent-capabilities").path("environment");

        assertEquals("reagent_app", javaEnvironment.path("MYSQL_USER").asText());
        assertEquals(
                "reagent-local-only",
                javaEnvironment.path("MYSQL_PASSWORD").asText());
        assertTrue(
                javaEnvironment.path("SPRING_DATASOURCE_URL").asText()
                        .contains("mysql:3306/reagent"));
        assertTrue(
                pythonEnvironment.path("AGENT_CAPABILITIES_MYSQL_URL").asText()
                        .contains(
                                "fake_ops_app:fake-ops-local-only@mysql:3306/fake_ops"));

        String sql = readRequired(
                "deploy/mysql/init/001-create-schemas.sql");
        assertTrue(sql.contains("CREATE DATABASE IF NOT EXISTS reagent"));
        assertTrue(sql.contains("CREATE DATABASE IF NOT EXISTS fake_ops"));
        assertTrue(sql.contains(
                "GRANT ALL PRIVILEGES ON reagent.* TO 'reagent_app'@'%'"));
        assertTrue(sql.contains(
                "GRANT ALL PRIVILEGES ON fake_ops.* TO 'fake_ops_app'@'%'"));
        assertFalse(sql.contains(
                "GRANT ALL PRIVILEGES ON fake_ops.* TO 'reagent_app'@'%'"));
        assertFalse(sql.contains(
                "GRANT ALL PRIVILEGES ON reagent.* TO 'fake_ops_app'@'%'"));
    }

    @Test
    void buildsNonRootHealthcheckedImagesFromLockedInputs() throws IOException {
        String javaDockerfile = readRequired("Dockerfile");
        assertTrue(javaDockerfile.contains("maven:3.9.9-eclipse-temurin-21"));
        assertTrue(javaDockerfile.contains("eclipse-temurin:21-jre"));
        assertTrue(javaDockerfile.contains("USER reagent"));
        assertTrue(javaDockerfile.contains("/var/reagent/workspaces"));
        assertTrue(javaDockerfile.contains("HEALTHCHECK"));
        assertFalse(javaDockerfile.contains("COPY . ."));

        String pythonDockerfile =
                readRequired("services/agent-capabilities/Dockerfile");
        assertTrue(pythonDockerfile.contains("uv sync --locked"));
        assertTrue(pythonDockerfile.contains("knowledge ./knowledge"));
        assertTrue(pythonDockerfile.contains("USER app"));
        assertTrue(pythonDockerfile.contains("HEALTHCHECK"));
        assertFalse(pythonDockerfile.contains("--no-sync"));
        assertFalse(
                pythonDockerfile.contains("chown -R app:app /app"),
                "the multi-gigabyte locked environment must not be recursively "
                        + "copied into a new ownership layer");
    }

    private static JsonNode renderCompose(String... profiles) throws Exception {
        Path env = ROOT.resolve(".env.example");
        assertTrue(Files.isRegularFile(env), ".env.example must exist");

        List<String> command = new ArrayList<>(List.of(
                "docker",
                "compose",
                "--project-name",
                "reagent-demo",
                "--env-file",
                env.toString(),
                "--file",
                ROOT.resolve("docker-compose.yml").toString()));
        for (String profile : profiles) {
            command.add("--profile");
            command.add(profile);
        }
        command.add("config");
        command.add("--format");
        command.add("json");

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(ROOT.toFile())
                .redirectErrorStream(true);
        builder.environment().put("DEEPSEEK_API_KEY", "");
        builder.environment().put("REAGENT_DEMO_PROFILES", "");
        builder.environment().put(
                "AGENT_CAPABILITIES_ACCEPTANCE_ENABLED", "false");
        builder.environment().put("AGENT_CAPABILITIES_CHAOS_ENABLED", "false");
        Process process = builder.start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "docker compose config exceeded 15 seconds");
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(
                0,
                process.exitValue(),
                () -> "docker compose config failed:\n" + output);
        return MAPPER.readTree(output);
    }

    private static void assertHealthyDependencies(
            JsonNode service,
            Set<String> expected
    ) {
        JsonNode dependencies = service.path("depends_on");
        assertEquals(expected, fieldNames(dependencies));
        for (String dependency : expected) {
            assertEquals(
                    "service_healthy",
                    dependencies.path(dependency).path("condition").asText(),
                    () -> dependency + " must be health-gated");
        }
    }

    private static Set<Integer> publishedPorts(JsonNode ports) {
        Set<Integer> values = new HashSet<>();
        if (!ports.isArray()) {
            return values;
        }
        for (JsonNode port : ports) {
            JsonNode published = port.get("published");
            assertNotNull(published, "published port must be explicit");
            values.add(Integer.parseInt(published.asText()));
        }
        return values;
    }

    private static Set<String> volumeSources(JsonNode service) {
        Set<String> values = new HashSet<>();
        for (JsonNode volume : service.path("volumes")) {
            if ("volume".equals(volume.path("type").asText())) {
                values.add(volume.path("source").asText());
            }
        }
        return values;
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new HashSet<>();
        Iterator<String> iterator = object.fieldNames();
        iterator.forEachRemaining(names::add);
        return names;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static String readRequired(String relativePath) throws IOException {
        Path path = ROOT.resolve(relativePath);
        assertTrue(Files.isRegularFile(path), () -> relativePath + " must exist");
        return Files.readString(path);
    }
}
