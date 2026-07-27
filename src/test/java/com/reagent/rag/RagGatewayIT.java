package com.reagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagGatewayIT {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:8.0-alpine");
    private static final DockerImageName PYTHON_IMAGE =
            DockerImageName.parse("reagent-agent-capabilities:task7");
    private static final int PYTHON_PORT = 8090;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient readinessClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .build();

    @Test
    void activeVersionAndChunkIdsSurviveTwoPythonLifecyclesOnOneRedis8() throws Exception {
        Path repositoryRoot = Path.of("").toAbsolutePath().normalize();
        Path knowledgeRoot = repositoryRoot.resolve("knowledge");
        Path fixedCorpus = knowledgeRoot.resolve("incident-ops").normalize();
        ModelCache modelCache = modelCache();
        assertTrue(Files.isDirectory(fixedCorpus));
        if (modelCache.prepopulated()) {
            requireFilteredModelCache(modelCache.path());
        }

        try (Network network = Network.newNetwork();
             GenericContainer<?> redis = redis(network)) {
            redis.start();
            assertTrue(redis.execInContainer("redis-server", "--version").getStdout().contains("v=8."));

            Snapshot first;
            try (GenericContainer<?> python = python(
                    network, knowledgeRoot, modelCache.path(), modelCache.prepopulated())) {
                python.start();
                first = searchReadyGateway(python, repositoryRoot, fixedCorpus);
            }
            requireFilteredModelCache(modelCache.path());

            Snapshot second;
            try (GenericContainer<?> python = python(network, knowledgeRoot, modelCache.path(), true)) {
                python.start();
                second = searchReadyGateway(python, repositoryRoot, fixedCorpus);
            }

            assertEquals(first.activeVersion(), second.activeVersion());
            assertFalse(first.chunkIds().isEmpty());
            assertEquals(first.chunkIds(), second.chunkIds());
        }
    }

    private Snapshot searchReadyGateway(GenericContainer<?> python,
                                        Path repositoryRoot,
                                        Path fixedCorpus) throws Exception {
        URI baseUri = URI.create("http://" + python.getHost() + ":" + python.getMappedPort(PYTHON_PORT));
        waitForRagReadiness(python, baseUri);

        RagProperties properties = new RagProperties();
        properties.setBaseUrl(baseUri);
        HttpRagGateway gateway = new HttpRagGateway(properties, mapper);
        String version = gateway.requireActiveVersion("incident-ops");
        RagSearchResponse response = gateway.search(new RagSearchRequest(
                1,
                "incident-ops",
                version,
                "How should checkout connection pool exhaustion be investigated?",
                3));

        assertEquals(version, response.indexVersion());
        assertFalse(response.hits().isEmpty());
        for (RagHit hit : response.hits()) {
            Path source = repositoryRoot.resolve(hit.source()).normalize();
            assertTrue(source.startsWith(fixedCorpus), () -> "source escaped fixed corpus: " + hit.source());
            assertTrue(Files.isRegularFile(source), () -> "source does not exist: " + hit.source());
        }
        return new Snapshot(version, response.hits().stream().map(RagHit::chunkId).toList());
    }

    private void waitForRagReadiness(GenericContainer<?> python, URI baseUri) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(8));
        URI readinessUri = baseUri.resolve("/internal/readiness");
        while (Instant.now().isBefore(deadline)) {
            if (!python.isRunning()) {
                Long exitCode = python.getCurrentContainerInfo().getState().getExitCodeLong();
                throw new AssertionError(
                        "Python RAG container exited before readiness (exit code " + exitCode + ")");
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(readinessUri)
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> response = readinessClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode readiness = mapper.readTree(response.body());
                    if (readiness.path("rag").path("ready").asBoolean(false)) {
                        return;
                    }
                }
            } catch (java.io.IOException ignored) {
                // Startup may not have bound its socket yet; poll until the fixed deadline.
            }
            Thread.sleep(Duration.ofMillis(250));
        }
        throw new AssertionError("Python RAG did not become ready within 8 minutes");
    }

    private static void requireFilteredModelCache(Path modelCache) throws Exception {
        Path model = modelCache.resolve("models--sentence-transformers--all-MiniLM-L6-v2");
        Path revisionFile = model.resolve("refs/main");
        assertTrue(Files.isRegularFile(revisionFile),
                () -> "filtered MiniLM cache precondition is missing: " + revisionFile);
        String revision = Files.readString(revisionFile).trim();
        Path snapshot = model.resolve("snapshots").resolve(revision);
        assertTrue(Files.isDirectory(snapshot),
                () -> "filtered MiniLM snapshot precondition is missing: " + snapshot);
        try (var files = Files.walk(snapshot)) {
            assertEquals(10L, files.filter(Files::isRegularFile).count(),
                    "filtered MiniLM cache must expose the exact 10-file snapshot");
        }
    }

    private static ModelCache modelCache() throws Exception {
        String configured = System.getProperty("reagent.test.hf-cache");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("REAGENT_TEST_HF_CACHE");
        }
        if (configured != null && !configured.isBlank()) {
            return new ModelCache(Path.of(configured).toAbsolutePath().normalize(), true);
        }

        Path writable = Files.createTempDirectory("reagent-rag-hf-cache-")
                .toAbsolutePath().normalize();
        try {
            Files.setPosixFilePermissions(writable, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (UnsupportedOperationException ignored) {
            writable.toFile().setReadable(true, false);
            writable.toFile().setWritable(true, false);
            writable.toFile().setExecutable(true, false);
        }
        return new ModelCache(writable, false);
    }

    private static GenericContainer<?> redis(Network network) {
        return new GenericContainer<>(REDIS_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("redis")
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    private static GenericContainer<?> python(Network network, Path knowledgeRoot,
                                               Path modelCache, boolean offline) {
        GenericContainer<?> container = new GenericContainer<>(PYTHON_IMAGE)
                .withNetwork(network)
                .withExposedPorts(PYTHON_PORT)
                .withEnv("AGENT_CAPABILITIES_ENV", "container")
                .withEnv("AGENT_CAPABILITIES_REDIS_URL", "redis://redis:6379/0")
                .withEnv("AGENT_CAPABILITIES_KNOWLEDGE_ROOT", "/app/knowledge")
                .withEnv("HF_HUB_CACHE", "/app/hf-cache")
                .withFileSystemBind(knowledgeRoot.toString(), "/app/knowledge", BindMode.READ_ONLY)
                .withFileSystemBind(modelCache.toString(), "/app/hf-cache",
                        offline ? BindMode.READ_ONLY : BindMode.READ_WRITE)
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(8));
        if (offline) {
            container.withEnv("HF_HUB_OFFLINE", "1")
                    .withEnv("TRANSFORMERS_OFFLINE", "1");
        }
        return container;
    }

    private record Snapshot(String activeVersion, List<String> chunkIds) {
    }

    private record ModelCache(Path path, boolean prepopulated) {
    }
}
