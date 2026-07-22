package com.reagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.obs.Trace;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRagGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void propertiesHaveTrustedBoundedDefaults() {
        RagProperties properties = new RagProperties();

        assertEquals("http", properties.getBaseUrl().getScheme());
        assertEquals("localhost", properties.getBaseUrl().getHost());
        assertEquals(Duration.ofMillis(500), properties.getConnectTimeout());
        assertEquals(Duration.ofSeconds(2), properties.getRequestTimeout());
        assertEquals(65_536, properties.getMaximumResponseBytes());
        assertEquals(1, properties.getRetries());
        assertEquals(Duration.ofMillis(100), properties.getRetryBackoff());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://localhost:8090",
            "//localhost:8090",
            "http://user@localhost:8090",
            "http://localhost:8090?query=forbidden",
            "http://localhost:8090#fragment",
            "http:///missing-host"
    })
    void rejectsUntrustedBaseUriShapes(String value) {
        RagProperties properties = properties(URI.create(value));

        assertThrows(IllegalArgumentException.class,
                () -> new HttpRagGateway(properties, mapper));
    }

    @Test
    void searchReturnsBoundedHitsAndEmptyHitsWithoutChangingAuthority() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> upgrade = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            respond(exchange, 200, response("v1", """
                    {"chunkId":"chunk-1","title":"Runbook","section":"Pool",
                     "source":"knowledge/incident-ops/runbooks/checkout.md","score":0.9,
                     "excerpt":"Inspect pending acquisitions."}
                    """));
        })) {
            HttpRagGateway gateway = gateway(server);
            RagSearchResponse result = gateway.search(new RagSearchRequest(
                    1, "incident-ops", "v1", "http://attacker.invalid/override", 3));

            assertEquals(1, result.hits().size());
            assertEquals("/internal/rag/search", requestPath.get());
            assertNull(upgrade.get(), "trusted Uvicorn transport must not attempt an h2c upgrade");
            assertEquals(
                    new RagSearchRequest(
                            1, "incident-ops", "v1", "http://attacker.invalid/override", 3),
                    RagSearchRequest.reader(mapper).readValue(requestBody.get()));
        }

        try (StubServer server = new StubServer(exchange ->
                respond(exchange, 200, response("v1", "")))) {
            RagSearchResponse empty = gateway(server).search(request("v1", 3));
            assertTrue(empty.hits().isEmpty());
        }
    }

    @Test
    void retriesOneReadTimeoutThenSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                Thread.sleep(300);
            }
            respond(exchange, 200, response("v1", ""));
        })) {
            RagProperties properties = properties(server.baseUri());
            properties.setRequestTimeout(Duration.ofMillis(120));
            properties.setRetryBackoff(Duration.ofMillis(5));

            RagSearchResponse response = new HttpRagGateway(properties, mapper).search(request("v1", 3));

            assertTrue(response.hits().isEmpty());
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void retriesWhenResponseBodyStallsAfterHeadersAndPrefix() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            String body = response("v1", "");
            if (attempts.incrementAndGet() == 1) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                int prefixLength = bytes.length - 2;
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream responseBody = exchange.getResponseBody()) {
                    responseBody.write(bytes, 0, prefixLength);
                    responseBody.flush();
                    Thread.sleep(300);
                    responseBody.write(bytes, prefixLength, bytes.length - prefixLength);
                }
            } else {
                respond(exchange, 200, body);
            }
        })) {
            RagProperties properties = properties(server.baseUri());
            properties.setRequestTimeout(Duration.ofMillis(120));
            properties.setRetryBackoff(Duration.ofMillis(5));

            RagSearchResponse response = new HttpRagGateway(properties, mapper).search(request("v1", 3));

            assertTrue(response.hits().isEmpty());
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void doesNotRetry4xxOr5xxErrorsOrExposeTheirBodies() throws Exception {
        for (int status : new int[]{400, 503}) {
            AtomicInteger attempts = new AtomicInteger();
            try (StubServer server = new StubServer(exchange -> {
                attempts.incrementAndGet();
                respond(exchange, status, "secret-response-body-" + status);
            })) {
                RagContractException error = assertThrows(RagContractException.class,
                        () -> gateway(server).search(request("v1", 3)));

                assertEquals("RAG_HTTP_STATUS", error.code());
                assertEquals(1, attempts.get());
                assertFalse(error.getMessage().contains("secret-response-body"));
            }
        }
    }

    @Test
    void rejectsOversizedMalformedAndUnknownFieldBodiesWithoutRetry() throws Exception {
        for (String body : new String[]{
                "x".repeat(257),
                "not-json secret-response-body",
                "{\"contractVersion\":1,\"indexVersion\":\"v1\",\"hits\":[],\"unknown\":true}"
        }) {
            AtomicInteger attempts = new AtomicInteger();
            try (StubServer server = new StubServer(exchange -> {
                attempts.incrementAndGet();
                respond(exchange, 200, body);
            })) {
                RagProperties properties = properties(server.baseUri());
                properties.setMaximumResponseBytes(256);

                RagContractException error = assertThrows(RagContractException.class,
                        () -> new HttpRagGateway(properties, mapper).search(request("v1", 3)));

                assertEquals(1, attempts.get());
                assertFalse(error.getMessage().contains("secret-response-body"));
            }
        }
    }

    @Test
    void rejectsMismatchedVersionTooManyHitsAndInvalidSourceWithoutRetry() throws Exception {
        Map<String, String> bodies = Map.of(
                "version", response("v2", ""),
                "hits", response("v1", String.join(",",
                        hitJson("chunk-1", "knowledge/incident-ops/runbooks/a.md"),
                        hitJson("chunk-2", "knowledge/incident-ops/runbooks/b.md"))),
                "source", response("v1", hitJson("chunk-1", "knowledge/incident-ops/../secret.md")));

        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            AtomicInteger attempts = new AtomicInteger();
            try (StubServer server = new StubServer(exchange -> {
                attempts.incrementAndGet();
                respond(exchange, 200, entry.getValue());
            })) {
                RagContractException error = assertThrows(RagContractException.class,
                        () -> gateway(server).search(request("v1", 1)), entry.getKey());

                assertEquals(1, attempts.get(), entry.getKey());
                assertTrue(error.getMessage().length() <= 256);
            }
        }
    }

    @Test
    void activeVersionRequiresMatchingKnowledgeBaseAndReadyResponse() throws Exception {
        try (StubServer server = new StubServer(exchange -> respond(exchange, 200, """
                {"contractVersion":1,"knowledgeBaseId":"incident-ops","indexVersion":"v1-active","ready":true}
                """))) {
            assertEquals("v1-active", gateway(server).requireActiveVersion("incident-ops"));
        }

        for (String body : new String[]{
                "{\"contractVersion\":1,\"knowledgeBaseId\":\"incident-ops\",\"indexVersion\":\"v1\",\"ready\":false}",
                "{\"contractVersion\":1,\"knowledgeBaseId\":\"other\",\"indexVersion\":\"v1\",\"ready\":true}"
        }) {
            try (StubServer server = new StubServer(exchange -> respond(exchange, 200, body))) {
                assertThrows(RagContractException.class,
                        () -> gateway(server).requireActiveVersion("incident-ops"));
            }
        }
    }

    @Test
    void propagatesCurrentW3cTraceContextOnSearchAndActiveCalls() throws Exception {
        AtomicReference<String> searchTraceparent = new AtomicReference<>();
        AtomicReference<String> activeTraceparent = new AtomicReference<>();
        AtomicReference<String> tracestate = new AtomicReference<>();
        try (StubServer server = new StubServer(exchange -> {
            String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
            if (exchange.getRequestURI().getPath().endsWith("/active")) {
                activeTraceparent.set(traceparent);
                tracestate.set(exchange.getRequestHeaders().getFirst("tracestate"));
                respond(exchange, 200, """
                        {"contractVersion":1,"knowledgeBaseId":"incident-ops","indexVersion":"v1","ready":true}
                        """);
            } else {
                searchTraceparent.set(traceparent);
                respond(exchange, 200, response("v1", ""));
            }
        })) {
            SpanContext spanContext = SpanContext.create(
                    "0123456789abcdef0123456789abcdef",
                    "0123456789abcdef",
                    TraceFlags.getSampled(),
                    TraceState.builder().put("vendor", "state").build());
            try (Scope ignored = Span.wrap(spanContext).makeCurrent()) {
                gateway(server).search(request("v1", 3));
                gateway(server).requireActiveVersion("incident-ops");
            }
        }

        String expected = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        assertEquals(expected, searchTraceparent.get());
        assertEquals(expected, activeTraceparent.get());
        assertEquals("vendor=state", tracestate.get());
        assertEquals(expected, Trace.currentW3cHeaders().getOrDefault("unused", expected),
                "Trace helper remains safe when no valid span is current");
    }

    @Test
    void interruptedRequestRestoresInterruptAndDoesNotRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch received = new CountDownLatch(1);
        try (StubServer server = new StubServer(exchange -> {
            attempts.incrementAndGet();
            received.countDown();
            Thread.sleep(2_000);
            respond(exchange, 200, response("v1", ""));
        })) {
            RagProperties properties = properties(server.baseUri());
            properties.setRequestTimeout(Duration.ofSeconds(5));
            ExecutorService worker = Executors.newSingleThreadExecutor();
            AtomicReference<Thread> requestThread = new AtomicReference<>();
            try {
                Future<Boolean> interrupted = worker.submit(() -> {
                    requestThread.set(Thread.currentThread());
                    try {
                        new HttpRagGateway(properties, mapper).search(request("v1", 3));
                        return false;
                    } catch (RagContractException expected) {
                        return Thread.currentThread().isInterrupted();
                    }
                });
                assertTrue(received.await(1, TimeUnit.SECONDS));
                requestThread.get().interrupt();

                assertTrue(interrupted.get(2, TimeUnit.SECONDS));
                assertEquals(1, attempts.get());
            } finally {
                worker.shutdownNow();
            }
        }
    }

    private HttpRagGateway gateway(StubServer server) {
        return new HttpRagGateway(properties(server.baseUri()), mapper);
    }

    private static RagSearchRequest request(String version, int topK) {
        return new RagSearchRequest(1, "incident-ops", version, "checkout pool exhaustion", topK);
    }

    private static RagProperties properties(URI baseUri) {
        RagProperties properties = new RagProperties();
        properties.setBaseUrl(baseUri);
        return properties;
    }

    private static String response(String version, String hits) {
        return "{\"contractVersion\":1,\"indexVersion\":\"" + version + "\",\"hits\":[" + hits + "]}";
    }

    private static String hitJson(String chunkId, String source) {
        return "{\"chunkId\":\"" + chunkId + "\",\"title\":\"Title\",\"section\":\"Section\","
                + "\"source\":\"" + source + "\",\"score\":0.8,\"excerpt\":\"Excerpt\"}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws Exception;
    }

    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();

        private StubServer(Responder responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    responder.respond(exchange);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Expected when the client times out or is interrupted mid-response.
                } catch (Exception exception) {
                    throw new IOException(exception);
                }
            });
            server.start();
        }

        private URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
