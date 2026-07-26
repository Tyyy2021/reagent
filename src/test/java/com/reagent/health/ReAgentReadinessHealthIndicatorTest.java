package com.reagent.health;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.reagent.api.ReadinessController;
import com.reagent.mcp.McpReadiness;
import com.reagent.profile.AgentProfileRegistry;
import com.reagent.rag.KnowledgeVersionProvider;
import com.reagent.rag.RagProperties;
import com.reagent.stream.StreamTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ReAgentReadinessHealthIndicatorTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-07-26T08:00:00Z");
    private static final List<String> COMPONENTS = List.of(
            "database", "redis", "python", "rag-index", "mcp", "incident-profile");

    @Test
    void springSelectsTheProductionConstructorWhenTheTestingSeamAlsoExists() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(DataSource.class, () -> mock(DataSource.class));
            context.registerBean(StreamTransport.class, () -> mock(StreamTransport.class));
            context.registerBean(RagProperties.class, RagProperties::new);
            context.registerBean(
                    KnowledgeVersionProvider.class,
                    () -> mock(KnowledgeVersionProvider.class));
            context.registerBean(McpReadiness.class, McpReadiness::new);
            context.registerBean(
                    AgentProfileRegistry.class,
                    () -> mock(AgentProfileRegistry.class));
            context.registerBean(
                    com.fasterxml.jackson.databind.ObjectMapper.class,
                    () -> new com.fasterxml.jackson.databind.ObjectMapper());
            context.register(ReAgentReadinessHealthIndicator.class);

            context.refresh();

            assertTrue(context.containsBean("reAgentReadiness"));
        }
    }

    @Test
    void productionReadinessRequiresTheRedisStreamTransport() {
        RagProperties rag = new RagProperties();
        rag.setBaseUrl(java.net.URI.create("http://127.0.0.1:1"));
        rag.setConnectTimeout(Duration.ofMillis(10));
        rag.setRequestTimeout(Duration.ofMillis(10));
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        ReAgentReadinessHealthIndicator indicator = new ReAgentReadinessHealthIndicator(
                Clock.fixed(CHECKED_AT, ZoneOffset.UTC),
                mock(DataSource.class),
                mock(StreamTransport.class),
                redisProvider,
                rag,
                mock(KnowledgeVersionProvider.class),
                new McpReadiness(),
                mock(AgentProfileRegistry.class),
                JsonMapper.builder().build());

        ComponentReadiness redis = indicator.readiness().components().stream()
                .filter(component -> component.name().equals("redis"))
                .findFirst()
                .orElseThrow();

        assertFalse(redis.ready());
        assertEquals("", redis.version());
        assertEquals("redis-unavailable", redis.reason());
    }

    @Test
    void allRequiredComponentsProduceOneReadySnapshotForActuatorAndApi() {
        ReAgentReadinessHealthIndicator indicator = indicator(Map.of());

        ReAgentReadiness snapshot = indicator.readiness();

        assertTrue(snapshot.ready());
        assertEquals(CHECKED_AT, snapshot.checkedAt());
        assertEquals(COMPONENTS, snapshot.components().stream()
                .map(ComponentReadiness::name)
                .toList());
        assertTrue(snapshot.components().stream().allMatch(ComponentReadiness::ready));
        assertEquals(Status.UP, indicator.health().getStatus());
        assertEquals(snapshot, new ReadinessController(indicator).readiness());
    }

    @ParameterizedTest
    @MethodSource("unsafeVersions")
    void everyComponentRejectsUnsafeVersionMetadata(String unsafeVersion) throws Exception {
        for (String component : COMPONENTS) {
            ReAgentReadinessHealthIndicator indicator =
                    indicator(Map.of(component, () -> unsafeVersion));

            ReAgentReadiness snapshot = indicator.readiness();
            ComponentReadiness unsafe = snapshot.components().stream()
                    .filter(item -> item.name().equals(component))
                    .findFirst()
                    .orElseThrow();
            String json = JsonMapper.builder().findAndAddModules().build()
                    .writeValueAsString(snapshot);

            assertFalse(unsafe.ready(), component);
            assertEquals("", unsafe.version(), component);
            assertFalse(json.contains("SECRET_SENTINEL"), component);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidPythonReadinessBodies")
    void pythonReadinessRequiresAPresentTextualSafeVersion(String responseBody) throws Exception {
        ComponentReadiness python = pythonReadiness(responseBody);

        assertFalse(python.ready());
        assertEquals("", python.version());
        assertEquals("python-unavailable", python.reason());
    }

    @Test
    void pythonReadinessAcceptsABoundedResponseWithoutContentLength() throws Exception {
        ComponentReadiness python = pythonReadiness(
                """
                {"ready":true,"service":"agent-capabilities","version":"0.1.0"}
                """,
                128,
                true);

        assertTrue(python.ready());
        assertEquals("0.1.0", python.version());
    }

    @Test
    void pythonReadinessUsesUvicornCompatibleHttp11WithoutH2cUpgrade() throws Exception {
        AtomicReference<String> upgrade = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/readiness", exchange -> {
            String observedUpgrade = exchange.getRequestHeaders().getFirst("Upgrade");
            upgrade.set(observedUpgrade);
            byte[] body = (observedUpgrade == null
                    ? """
                      {"ready":true,"service":"agent-capabilities","version":"0.1.0"}
                      """
                    : "Invalid HTTP request received.")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(
                    "Content-Type",
                    observedUpgrade == null ? "application/json" : "text/plain");
            exchange.sendResponseHeaders(observedUpgrade == null ? 200 : 400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RagProperties rag = new RagProperties();
            rag.setBaseUrl(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort()));

            ComponentReadiness python = pythonReadiness(rag);

            assertTrue(python.ready());
            assertEquals("0.1.0", python.version());
            assertNull(upgrade.get());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pythonReadinessRejectsOversizedDeclaredAndStreamedBodies(boolean chunked)
            throws Exception {
        ComponentReadiness python = pythonReadiness("x".repeat(129), 128, chunked);

        assertFalse(python.ready());
        assertEquals("python-unavailable", python.reason());
    }

    @Test
    void completeResponseDeadlineCancelsAStalledReadinessBody() throws Exception {
        CountDownLatch bodyClosed = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/readiness", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                while (true) {
                    Thread.sleep(25);
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                }
            } catch (IOException closedByClient) {
                bodyClosed.countDown();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            RagProperties rag = new RagProperties();
            rag.setBaseUrl(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort()));
            rag.setRequestTimeout(Duration.ofMillis(100));

            ComponentReadiness python = assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> pythonReadiness(rag));

            assertFalse(python.ready());
            assertEquals("python-unavailable", python.reason());
            assertTrue(bodyClosed.await(1, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "database", "redis", "python", "rag-index", "mcp", "incident-profile"
    })
    void oneFailedRequiredComponentMakesTheAggregateDownWithOnlyStableSafeData(
            String failedComponent
    ) throws Exception {
        Map<String, Supplier<String>> failures = Map.of(
                failedComponent,
                () -> {
                    throw new IllegalStateException(
                            "jdbc:mysql://db/private?password=SECRET_SENTINEL apiKey=ARGUMENT_SENTINEL");
                });
        ReAgentReadinessHealthIndicator indicator = indicator(failures);

        ReAgentReadiness snapshot = indicator.readiness();
        String json = JsonMapper.builder().findAndAddModules().build()
                .writeValueAsString(snapshot);

        assertFalse(snapshot.ready());
        assertEquals(Status.DOWN, indicator.health().getStatus());
        ComponentReadiness failed = snapshot.components().stream()
                .filter(component -> component.name().equals(failedComponent))
                .findFirst()
                .orElseThrow();
        assertFalse(failed.ready());
        assertEquals(failedComponent + "-unavailable", failed.reason());
        assertEquals("", failed.version());
        assertFalse(json.contains("jdbc:"));
        assertFalse(json.contains("url"));
        assertFalse(json.contains("password"));
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains("SECRET_SENTINEL"));
        assertFalse(json.contains("ARGUMENT_SENTINEL"));
        assertFalse(json.contains("schema"));
        assertFalse(json.contains("document"));
    }

    private static ReAgentReadinessHealthIndicator indicator(
            Map<String, Supplier<String>> overrides
    ) {
        Map<String, AtomicReference<Supplier<String>>> probes = new LinkedHashMap<>();
        for (String component : COMPONENTS) {
            probes.put(component, new AtomicReference<>(
                    () -> component.equals("rag-index") ? "incident-ops-v1" : "ready"));
        }
        overrides.forEach((name, probe) -> probes.get(name).set(probe));
        return ReAgentReadinessHealthIndicator.forTesting(
                Clock.fixed(CHECKED_AT, ZoneOffset.UTC),
                probes.entrySet().stream()
                        .map(entry -> ReAgentReadinessHealthIndicator.probe(
                                entry.getKey(),
                                entry.getKey() + "-unavailable",
                                entry.getValue().get()))
                        .toList());
    }

    private static Stream<String> unsafeVersions() {
        return Stream.of(
                "https://versions.invalid/SECRET_SENTINEL",
                "token=SECRET_SENTINEL",
                "release notes SECRET_SENTINEL",
                "v".repeat(65));
    }

    private static Stream<String> invalidPythonReadinessBodies() {
        return Stream.of(
                """
                {"ready":true,"service":"agent-capabilities"}
                """,
                """
                {"ready":true,"service":"agent-capabilities","version":7}
                """,
                """
                {"ready":true,"service":"agent-capabilities","version":""}
                """,
                """
                {"ready":true,"service":"agent-capabilities",
                 "version":"https://versions.invalid/SECRET_SENTINEL"}
                """,
                """
                {"ready":true,"service":"agent-capabilities","version":"%s"}
                """.formatted("v".repeat(65)));
    }

    private static ComponentReadiness pythonReadiness(String responseBody) throws Exception {
        return pythonReadiness(responseBody, 65_536, false);
    }

    private static ComponentReadiness pythonReadiness(
            String responseBody,
            int maximumBytes,
            boolean chunked
    ) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/readiness", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RagProperties rag = new RagProperties();
            rag.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            rag.setMaximumResponseBytes(maximumBytes);
            return pythonReadiness(rag);
        } finally {
            server.stop(0);
        }
    }

    private static ComponentReadiness pythonReadiness(RagProperties rag) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        ReAgentReadinessHealthIndicator indicator = new ReAgentReadinessHealthIndicator(
                Clock.fixed(CHECKED_AT, ZoneOffset.UTC),
                mock(DataSource.class),
                mock(StreamTransport.class),
                redisProvider,
                rag,
                mock(KnowledgeVersionProvider.class),
                new McpReadiness(),
                mock(AgentProfileRegistry.class),
                JsonMapper.builder().build());
        return indicator.readiness().components().stream()
                .filter(component -> component.name().equals("python"))
                .findFirst()
                .orElseThrow();
    }
}
