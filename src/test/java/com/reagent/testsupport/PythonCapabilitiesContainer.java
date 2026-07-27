package com.reagent.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared real Python RAG/MCP capability stack used by Task 11 incident scenarios. */
public final class PythonCapabilitiesContainer {

    private static final DockerImageName MYSQL_IMAGE =
            DockerImageName.parse("mysql:8.0");
    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:8.0-alpine");
    private static final DockerImageName PYTHON_IMAGE =
            DockerImageName.parse("reagent-agent-capabilities:task9");
    private static final int PYTHON_PORT = 8090;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);
    private static final String STABLE_PROXY_SCRIPT = """
            import select
            import socket
            import socketserver

            class ProxyHandler(socketserver.BaseRequestHandler):
                def handle(self):
                    try:
                        with socket.create_connection(
                                ("agent-capabilities", 8090), timeout=10
                        ) as upstream:
                            upstream.settimeout(None)
                            peers = {
                                self.request: upstream,
                                upstream: self.request,
                            }
                            while True:
                                readable, _, _ = select.select(tuple(peers), (), ())
                                for source in readable:
                                    data = source.recv(65536)
                                    if not data:
                                        return
                                    peers[source].sendall(data)
                    except OSError:
                        return

            class ProxyServer(socketserver.ThreadingTCPServer):
                allow_reuse_address = True
                daemon_threads = True

            with ProxyServer(("0.0.0.0", 8090), ProxyHandler) as server:
                server.serve_forever()
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final Network network = Network.newNetwork();
    private final MySQLContainer<?> mysql = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("fake_ops")
            .withUsername("fake_ops_app")
            .withPassword("fake-ops-test")
            .withNetwork(network)
            .withNetworkAliases("mysql")
            .withStartupTimeout(Duration.ofMinutes(3));
    private final GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("redis")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));
    private final GenericContainer<?> python;
    private final GenericContainer<?> stableProxy;

    private PythonCapabilitiesContainer() {
        Path repositoryRoot = Path.of("").toAbsolutePath().normalize();
        Path knowledgeRoot = repositoryRoot.resolve("knowledge");
        Path modelCache = repositoryRoot.resolve(".superpowers/sdd/hf-cache-task9");
        requireDirectory(knowledgeRoot.resolve("incident-ops"), "incident corpus");
        requireDirectory(
                modelCache.resolve("models--sentence-transformers--all-MiniLM-L6-v2"),
                "MiniLM cache");

        mysql.start();
        redis.start();
        try {
            assertTrue(
                    redis.execInContainer("redis-server", "--version")
                            .getStdout()
                            .contains("v=8."),
                    "Task 11 requires Redis 8");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot verify Python Redis version", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cannot verify Python Redis version", exception);
        }
        python = new GenericContainer<>(PYTHON_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("agent-capabilities")
                .withExposedPorts(PYTHON_PORT)
                .withEnv("AGENT_CAPABILITIES_ENV", "container")
                .withEnv(
                        "AGENT_CAPABILITIES_MYSQL_URL",
                        "mysql+pymysql://fake_ops_app:fake-ops-test@mysql:3306/fake_ops")
                .withEnv(
                        "AGENT_CAPABILITIES_REDIS_URL",
                        "redis://redis:6379/0")
                .withEnv(
                        "AGENT_CAPABILITIES_KNOWLEDGE_ROOT",
                        "/app/knowledge")
                .withEnv("AGENT_CAPABILITIES_ACCEPTANCE_ENABLED", "true")
                .withEnv("AGENT_CAPABILITIES_CHAOS_ENABLED", "true")
                .withEnv("HF_HUB_CACHE", "/app/hf-cache")
                .withEnv("HF_HUB_OFFLINE", "1")
                .withEnv("TRANSFORMERS_OFFLINE", "1")
                .withFileSystemBind(
                        knowledgeRoot.toString(), "/app/knowledge", BindMode.READ_ONLY)
                .withFileSystemBind(
                        modelCache.toString(), "/app/hf-cache", BindMode.READ_ONLY)
                .waitingFor(Wait.forHttp("/internal/readiness")
                        .forPort(PYTHON_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(8)))
                .withStartupTimeout(Duration.ofMinutes(8));
        python.start();

        stableProxy = new GenericContainer<>(PYTHON_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("agent-capabilities-proxy")
                .withExposedPorts(PYTHON_PORT)
                .withCommand("python", "-u", "-c", STABLE_PROXY_SCRIPT)
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(2));
        stableProxy.start();
        awaitReady(Duration.ofMinutes(8));
    }

    public static PythonCapabilitiesContainer shared() {
        return Holder.INSTANCE;
    }

    public URI baseUri() {
        return URI.create(
                "http://" + stableProxy.getHost() + ":"
                        + stableProxy.getMappedPort(PYTHON_PORT));
    }

    public URI backendUri() {
        return URI.create(
                "http://" + python.getHost() + ":" + python.getMappedPort(PYTHON_PORT));
    }

    public String mappedMysqlUrl() {
        return "mysql+pymysql://" + mysql.getUsername() + ":" + mysql.getPassword()
                + "@" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/" + mysql.getDatabaseName();
    }

    public String mappedRedisUrl() {
        return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379) + "/0";
    }

    public AcceptanceSnapshot acceptance(String idempotencyKey) {
        JsonNode body = get(
                "/internal/acceptance?idempotencyKey=" + idempotencyKey,
                200);
        return new AcceptanceSnapshot(
                body.path("createTicketAttempts").asInt(),
                body.path("uniqueTicketCount").asInt(),
                body.path("ticketIds").isArray() && !body.path("ticketIds").isEmpty()
                        ? body.path("ticketIds").get(0).asText()
                        : null,
                body.path("faultGateState").asText());
    }

    public AcceptanceSnapshot awaitCreateTicketAttempts(
            String idempotencyKey,
            int minimumAttempts,
            Duration timeout
    ) {
        Instant deadline = Instant.now().plus(timeout);
        AcceptanceSnapshot last = null;
        while (Instant.now().isBefore(deadline)) {
            last = acceptance(idempotencyKey);
            if (last.createTicketAttempts() >= minimumAttempts) {
                return last;
            }
            pausePolling();
        }
        throw new AssertionError(
                "Python create_ticket attempts did not reach "
                        + minimumAttempts + " within " + timeout + "; last=" + last);
    }

    public void armTicketAfterCommit(String idempotencyKey) {
        JsonNode response = post(
                "/internal/chaos/ticket-after-commit/arm",
                "{\"idempotencyKey\":\"" + idempotencyKey + "\"}",
                200);
        assertTrue(response.path("armed").asBoolean());
    }

    public void awaitTicketAfterCommitBlocked(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            JsonNode status = get(
                    "/internal/chaos/ticket-after-commit/status",
                    200);
            if (status.path("blocked").asBoolean()) {
                return;
            }
            pausePolling();
        }
        throw new AssertionError("Python ticket fault gate did not block within " + timeout);
    }

    public void releaseTicketAfterCommit() {
        JsonNode response = post(
                "/internal/chaos/ticket-after-commit/release",
                "{}",
                200);
        assertTrue(response.path("released").asBoolean());
    }

    public synchronized void restartPython() {
        python.getDockerClient()
                .restartContainerCmd(python.getContainerId())
                .withTimeout(20)
                .exec();
        awaitReady(Duration.ofMinutes(8));
    }

    public synchronized void pausePython() {
        python.getDockerClient()
                .pauseContainerCmd(python.getContainerId())
                .exec();
    }

    public synchronized void unpausePython() {
        python.getDockerClient()
                .unpauseContainerCmd(python.getContainerId())
                .exec();
        awaitReady(Duration.ofMinutes(8));
    }

    private void awaitReady(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                JsonNode readiness = get("/internal/readiness", 200);
                if (readiness.path("rag").path("ready").asBoolean()
                        && readiness.path("fakeOps").path("ready").asBoolean()) {
                    return;
                }
            } catch (AssertionError ignored) {
                // Uvicorn or its lifespan may still be starting.
            }
            pausePolling();
        }
        throw new AssertionError("Python capability service did not become ready within " + timeout);
    }

    private JsonNode get(String path, int expectedStatus) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(baseUri().resolve(path))
                            .timeout(HTTP_TIMEOUT)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(
                    expectedStatus,
                    response.statusCode(),
                    () -> path + " returned " + response.statusCode());
            return mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new AssertionError("Python capability GET failed for " + path, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Python capability GET was interrupted for " + path, exception);
        }
    }

    private JsonNode post(String path, String body, int expectedStatus) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(baseUri().resolve(path))
                            .timeout(HTTP_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(
                    expectedStatus,
                    response.statusCode(),
                    () -> path + " returned " + response.statusCode());
            return mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new AssertionError("Python capability POST failed for " + path, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Python capability POST was interrupted for " + path, exception);
        }
    }

    private static void pausePolling() {
        try {
            Thread.sleep(Duration.ofMillis(100));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while polling Python capability state", exception);
        }
    }

    private static void requireDirectory(Path path, String label) {
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(label + " is missing: " + path);
        }
    }

    public record AcceptanceSnapshot(
            int createTicketAttempts,
            int uniqueTicketCount,
            String ticketId,
            String faultGateState
    ) {
    }

    private static final class Holder {
        private static final PythonCapabilitiesContainer INSTANCE =
                new PythonCapabilitiesContainer();
    }

}
