package com.reagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reagent.obs.Trace;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpTransportException;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class McpGatewayContractTest {

    @Test
    void springSelectsProductionConstructorWhenPackagePrivateTestSeamAlsoExists() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    McpProperties.class, McpGatewayContractTest::validProperties);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(McpReadiness.class, () -> new McpReadiness());
            context.register(OfficialMcpGateway.class);

            context.refresh();

            assertTrue(context.containsBean("officialMcpGateway"));
        }
    }

    @Test
    void trustedConfigurationRejectsUnknownServerAndTool() {
        McpProperties properties = validProperties();
        properties.validate();

        assertThrows(McpContractException.class, () -> properties.requireServer("missing"));
        assertThrows(
                McpContractException.class,
                () -> properties.requireServer("fake-ops").requireTool("missing"));
        assertEquals(
                IdempotencyClass.READ_ONLY,
                properties.requireServer("fake-ops")
                        .requireTool("query_metrics")
                        .getIdempotencyClass());
        assertEquals(
                ApprovalPolicy.NONE,
                properties.requireServer("fake-ops")
                        .requireTool("query_metrics")
                        .getApprovalPolicy());
    }

    @Test
    void trustedConfigurationRejectsUntrustedTransportValues() {
        for (String baseUrl : List.of(
                "file:///tmp/server",
                "/relative",
                "http://user:secret@localhost:8090",
                "http://localhost:8090/path?secret=yes",
                "http://localhost:8090/path#fragment")) {
            McpProperties properties = validProperties();
            properties.getServers().get("fake-ops").setBaseUrl(baseUrl);
            assertThrows(McpContractException.class, properties::validate, baseUrl);
        }

        McpProperties endpoint = validProperties();
        endpoint.getServers().get("fake-ops").setEndpoint("/mcp/");
        assertThrows(McpContractException.class, endpoint::validate);

        McpProperties timeout = validProperties();
        timeout.getServers().get("fake-ops").setRequestTimeout(Duration.ZERO);
        assertThrows(McpContractException.class, timeout::validate);

        McpProperties bytes = validProperties();
        bytes.getServers().get("fake-ops").setMaximumResponseBytes(0);
        assertThrows(McpContractException.class, bytes::validate);
    }

    @Test
    void discoveryRejectsDuplicateIllegalMissingAndUnconfiguredTools() {
        assertDiscoveryRejected(List.of(
                tool("query_metrics", objectSchema()),
                tool("query_metrics", objectSchema())));
        assertDiscoveryRejected(List.of(tool("illegal name", objectSchema())));
        assertDiscoveryRejected(List.of());
        assertDiscoveryRejected(List.of(
                tool("query_metrics", objectSchema()),
                tool("unexpected", objectSchema())));
    }

    @Test
    void discoveryRejectsNonObjectOversizedDeepExcessiveAndUnsupportedSchemas() {
        assertDiscoveryRejected(List.of(tool("query_metrics", "not-an-object")));

        Map<String, Object> oversized = objectSchema();
        oversized.put("description", "x".repeat(70_000));
        assertDiscoveryRejected(List.of(tool("query_metrics", oversized)));

        Map<String, Object> deep = objectSchema();
        Map<String, Object> cursor = deep;
        for (int index = 0; index < 34; index++) {
            Map<String, Object> nested = new LinkedHashMap<>();
            cursor.put("nested", nested);
            cursor = nested;
        }
        assertDiscoveryRejected(List.of(tool("query_metrics", deep)));

        Map<String, Object> excessiveProperties = objectSchema();
        Map<String, Object> propertyMap = new LinkedHashMap<>();
        for (int index = 0; index < 129; index++) {
            propertyMap.put("p" + index, Map.of("type", "string"));
        }
        excessiveProperties.put("properties", propertyMap);
        assertDiscoveryRejected(List.of(tool("query_metrics", excessiveProperties)));

        Map<String, Object> unsupported = objectSchema();
        unsupported.put("unsupported", new Object());
        assertDiscoveryRejected(List.of(tool("query_metrics", unsupported)));
    }

    @Test
    void discoveryRejectsPropertyValueThatIsNotSchemaObject() {
        assertDiscoveryRejected(List.of(tool(
                "query_metrics",
                Map.of(
                        "type", "object",
                        "properties", Map.of("name", "not-a-schema")))));
    }

    @Test
    void discoveryRejectsDuplicateRequiredNamesAtSameObjectLevel() {
        assertDiscoveryRejected(List.of(tool(
                "query_metrics",
                Map.of(
                        "type", "object",
                        "properties", Map.of("name", Map.of("type", "string")),
                        "required", List.of("name", "name")))));
    }

    @Test
    void discoveryRejectsInvalidAdditionalPropertiesValue() {
        assertDiscoveryRejected(List.of(tool(
                "query_metrics",
                Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "additionalProperties", "sometimes"))));
    }

    @Test
    void discoveryRecursivelyRejectsMalformedNestedPropertySchema() {
        assertDiscoveryRejected(List.of(tool(
                "query_metrics",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "child", Map.of(
                                        "type", "object",
                                        "properties", Map.of("leaf", "not-a-schema")))))));
    }

    @Test
    void discoveryRecursivelyRejectsNestedDuplicateRequiredNames() {
        assertDiscoveryRejected(List.of(tool(
                "query_metrics",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "child", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "leaf", Map.of("type", "string")),
                                        "required", List.of("leaf", "leaf")))))));
    }

    @Test
    void discoveryRecursivelyRejectsNestedInvalidAdditionalProperties() {
        assertDiscoveryRejected(List.of(tool(
                "query_metrics",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "child", Map.of(
                                        "type", "object",
                                        "properties", Map.of(),
                                        "additionalProperties", 1))))));
    }

    @Test
    void discoveryRecursivelyRejectsUnknownNestedTypeName() {
        assertDiscoveryRejected(List.of(tool(
                "query_metrics",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "child", Map.of("type", "mystery"))))));
    }

    @Test
    void discoveryRecursivelyRejectsNonStringNestedTypeValue() {
        assertDiscoveryRejected(List.of(tool(
                "query_metrics",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "child", Map.of("type", 7))))));
    }

    @Test
    void discoveryRecursivelyRejectsDuplicateNestedTypeArrayEntries() {
        assertDiscoveryRejected(List.of(tool(
                "query_metrics",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "child", Map.of(
                                        "type", List.of("string", "string")))))));
    }

    @Test
    void discoveryRejectsMalformedCompositionSchemaPosition() {
        assertDiscoveryRejected(List.of(tool(
                "query_metrics",
                Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "allOf", List.of("not-a-schema")))));
    }

    @Test
    void discoveryRejectsMalformedItemSchemaPosition() {
        assertDiscoveryRejected(List.of(tool(
                "query_metrics",
                Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "items", "not-a-schema"))));
    }

    @Test
    void discoveryAcceptsValidNestedSchemasAnnotationsAndCompositionPositions() {
        Map<String, Object> schema = Map.of(
                "title", "query metrics input",
                "type", "object",
                "properties", Map.of(
                        "child", Map.of(
                                "title", "child input",
                                "type", "object",
                                "properties", Map.of(
                                        "leaf", Map.of(
                                                "title", "leaf value",
                                                "type", "string"),
                                        "optional", Map.of(
                                                "type", List.of("string", "null"))),
                                "required", List.of("leaf"),
                                "additionalProperties", false)),
                "required", List.of("child"),
                "additionalProperties", Map.of("type", "string"),
                "allOf", List.of(Map.of("title", "composition annotation")),
                "items", Map.of("type", "string"),
                "prefixItems", List.of(Map.of("type", "number")),
                "$defs", Map.of("label", Map.of("type", "string")));
        ScriptedFactory factory = new ScriptedFactory();
        factory.addSession().discovery.add(List.of(tool("query_metrics", schema)));

        assertEquals(
                List.of("query_metrics"),
                gateway(factory).discover("fake-ops").stream()
                        .map(McpRemoteTool::name)
                        .toList());
    }

    @Test
    void callAcceptsExactlyOneBoundedTextAndPreservesRemoteErrorFlag() {
        ScriptedFactory factory = new ScriptedFactory();
        ScriptedSession session = factory.addSession();
        session.discovery.add(List.of(tool("query_metrics", objectSchema())));
        session.results.add(new OfficialMcpGateway.RawCallResult(
                List.of(new OfficialMcpGateway.RawContent(true, "ok")), false));
        session.results.add(new OfficialMcpGateway.RawCallResult(
                List.of(new OfficialMcpGateway.RawContent(true, "remote rejected")), true));
        OfficialMcpGateway gateway = gateway(factory);

        assertEquals(List.of("query_metrics"), gateway.discover("fake-ops").stream()
                .map(McpRemoteTool::name)
                .toList());
        assertEquals(
                new McpCallResult("ok", false),
                gateway.call("fake-ops", "query_metrics", Map.of()));
        assertEquals(
                new McpCallResult("remote rejected", true),
                gateway.call("fake-ops", "query_metrics", Map.of()));
    }

    @Test
    void callRejectsEmptyNonTextMultipleAndOversizedContent() {
        assertCallRejected(new OfficialMcpGateway.RawCallResult(List.of(), false));
        assertCallRejected(new OfficialMcpGateway.RawCallResult(
                List.of(new OfficialMcpGateway.RawContent(false, "image")), false));
        assertCallRejected(new OfficialMcpGateway.RawCallResult(
                List.of(
                        new OfficialMcpGateway.RawContent(true, "one"),
                        new OfficialMcpGateway.RawContent(true, "two")),
                false));
        assertCallRejected(new OfficialMcpGateway.RawCallResult(
                List.of(new OfficialMcpGateway.RawContent(true, "x".repeat(65_537))), false));
    }

    @Test
    void fixedByteCeilingAccepts65536AndRejects65537ForEveryBoundedPayload()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> acceptedSchema = schemaWithSerializedBytes(mapper, 65_536);
        Map<String, Object> rejectedSchema = schemaWithSerializedBytes(mapper, 65_537);
        assertEquals(65_536, mapper.writeValueAsBytes(acceptedSchema).length);
        assertEquals(65_537, mapper.writeValueAsBytes(rejectedSchema).length);

        ScriptedFactory acceptedSchemaFactory = new ScriptedFactory();
        acceptedSchemaFactory.addSession().discovery.add(
                List.of(tool("query_metrics", acceptedSchema)));
        assertEquals(
                List.of("query_metrics"),
                gateway(acceptedSchemaFactory).discover("fake-ops").stream()
                        .map(McpRemoteTool::name)
                        .toList());
        assertDiscoveryRejected(List.of(tool("query_metrics", rejectedSchema)));

        Map<String, Object> acceptedArguments =
                argumentsWithSerializedBytes(mapper, 65_536);
        Map<String, Object> rejectedArguments =
                argumentsWithSerializedBytes(mapper, 65_537);
        assertEquals(65_536, mapper.writeValueAsBytes(acceptedArguments).length);
        assertEquals(65_537, mapper.writeValueAsBytes(rejectedArguments).length);
        ScriptedFactory argumentFactory = new ScriptedFactory();
        ScriptedSession argumentSession = argumentFactory.addSession();
        argumentSession.discovery.add(List.of(tool("query_metrics", objectSchema())));
        argumentSession.results.add(new OfficialMcpGateway.RawCallResult(
                List.of(new OfficialMcpGateway.RawContent(true, "ok")), false));
        OfficialMcpGateway argumentGateway = gateway(argumentFactory);
        argumentGateway.discover("fake-ops");
        assertEquals(
                new McpCallResult("ok", false),
                argumentGateway.call(
                        "fake-ops", "query_metrics", acceptedArguments));
        assertThrows(
                McpContractException.class,
                () -> argumentGateway.call(
                        "fake-ops", "query_metrics", rejectedArguments));
        assertEquals(1, argumentSession.calls.get(),
                "65,537-byte arguments must fail before the session call");

        String acceptedResult = "x".repeat(65_536);
        String rejectedResult = "x".repeat(65_537);
        assertEquals(
                65_536, acceptedResult.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(
                65_537, rejectedResult.getBytes(StandardCharsets.UTF_8).length);
        ScriptedFactory resultFactory = new ScriptedFactory();
        ScriptedSession resultSession = resultFactory.addSession();
        resultSession.discovery.add(List.of(tool("query_metrics", objectSchema())));
        resultSession.results.add(new OfficialMcpGateway.RawCallResult(
                List.of(new OfficialMcpGateway.RawContent(true, acceptedResult)), false));
        resultSession.results.add(new OfficialMcpGateway.RawCallResult(
                List.of(new OfficialMcpGateway.RawContent(true, rejectedResult)), false));
        OfficialMcpGateway resultGateway = gateway(resultFactory);
        resultGateway.discover("fake-ops");
        assertEquals(
                acceptedResult,
                resultGateway.call("fake-ops", "query_metrics", Map.of()).text());
        assertThrows(
                McpContractException.class,
                () -> resultGateway.call("fake-ops", "query_metrics", Map.of()));

        McpProperties acceptedConfiguration = validProperties();
        acceptedConfiguration.getServers().get("fake-ops")
                .setMaximumResponseBytes(65_536);
        acceptedConfiguration.validate();
        McpProperties rejectedConfiguration = validProperties();
        rejectedConfiguration.getServers().get("fake-ops")
                .setMaximumResponseBytes(65_537);
        assertThrows(McpContractException.class, rejectedConfiguration::validate);
        assertEquals(65_536, McpProperties.MAX_RESPONSE_BYTES);
    }

    @Test
    void transportBreakReconnectsOnceAndRequiresUnchangedSchema() {
        ScriptedFactory factory = new ScriptedFactory();
        ScriptedSession first = factory.addSession();
        first.discovery.add(List.of(tool("query_metrics", objectSchema())));
        first.failCall = new McpTransportException(
                "connection reset", new ConnectException("connection reset"));
        ScriptedSession second = factory.addSession();
        second.discovery.add(List.of(tool("query_metrics", objectSchema())));
        second.results.add(new OfficialMcpGateway.RawCallResult(
                List.of(new OfficialMcpGateway.RawContent(true, "after reconnect")), false));
        OfficialMcpGateway gateway = gateway(factory);

        gateway.discover("fake-ops");
        assertEquals(
                new McpCallResult("after reconnect", false),
                gateway.call("fake-ops", "query_metrics", Map.of()));
        assertEquals(2, factory.opens.get());
        assertEquals(1, first.closes.get());
        assertEquals(1, second.calls.get());

        ScriptedFactory driftFactory = new ScriptedFactory();
        ScriptedSession driftFirst = driftFactory.addSession();
        driftFirst.discovery.add(List.of(tool("query_metrics", objectSchema())));
        driftFirst.failCall = new McpTransportException(
                "connection reset", new ConnectException("connection reset"));
        ScriptedSession driftSecond = driftFactory.addSession();
        driftSecond.discovery.add(List.of(tool(
                "query_metrics",
                Map.of("type", "object", "properties", Map.of("new", Map.of("type", "string"))))));
        OfficialMcpGateway driftGateway = gateway(driftFactory);
        driftGateway.discover("fake-ops");

        assertThrows(
                McpContractException.class,
                () -> driftGateway.call("fake-ops", "query_metrics", Map.of()));
        assertEquals(0, driftSecond.calls.get());
    }

    @Test
    void secondTransportFailureInvalidatesStateAndLaterCallRediscovers() {
        ScriptedFactory factory = new ScriptedFactory();
        ScriptedSession first = factory.addSession();
        first.discovery.add(List.of(tool("query_metrics", objectSchema())));
        first.failCall = transportFailure("first");
        ScriptedSession second = factory.addSession();
        second.discovery.add(List.of(tool("query_metrics", objectSchema())));
        second.failCall = transportFailure("second");
        ScriptedSession third = factory.addSession();
        third.discovery.add(List.of(tool("query_metrics", objectSchema())));
        third.results.add(textResult("recovered"));
        McpReadiness readiness = new McpReadiness();
        OfficialMcpGateway gateway = new OfficialMcpGateway(
                validProperties(), new ObjectMapper(), readiness, factory);

        gateway.discover("fake-ops");
        assertThrows(
                OfficialMcpGateway.TransportFailureException.class,
                () -> gateway.call("fake-ops", "query_metrics", Map.of()));
        assertEquals(1, first.closes.get());
        assertEquals(1, second.closes.get());
        assertFalse(readiness.state("fake-ops").ready());

        assertEquals(
                new McpCallResult("recovered", false),
                assertDoesNotThrow(
                        () -> gateway.call("fake-ops", "query_metrics", Map.of())));
        assertEquals(3, factory.opens.get());
        assertEquals(1, third.calls.get());
        assertTrue(readiness.state("fake-ops").ready());
    }

    @Test
    void rejectedReplacementDiscoveryLeavesNoCallableCachedState() {
        ScriptedFactory factory = new ScriptedFactory();
        ScriptedSession first = factory.addSession();
        first.discovery.add(List.of(tool("query_metrics", objectSchema())));
        first.failCall = transportFailure("first");
        ScriptedSession rejected = factory.addSession();
        rejected.discovery.add(List.of());
        ScriptedSession fresh = factory.addSession();
        fresh.discovery.add(List.of(tool("query_metrics", objectSchema())));
        fresh.results.add(textResult("fresh"));
        McpReadiness readiness = new McpReadiness();
        OfficialMcpGateway gateway = new OfficialMcpGateway(
                validProperties(), new ObjectMapper(), readiness, factory);

        gateway.discover("fake-ops");
        assertThrows(
                McpContractException.class,
                () -> gateway.call("fake-ops", "query_metrics", Map.of()));
        assertEquals(1, rejected.closes.get());
        assertFalse(readiness.state("fake-ops").ready());

        assertEquals(
                new McpCallResult("fresh", false),
                assertDoesNotThrow(
                        () -> gateway.call("fake-ops", "query_metrics", Map.of())));
        assertEquals(3, factory.opens.get());
        assertEquals(1, fresh.calls.get());
        assertTrue(readiness.state("fake-ops").ready());
    }

    @Test
    void failedPublicDiscoveryRefreshInvalidatesPreviouslyValidState() {
        ScriptedFactory factory = new ScriptedFactory();
        ScriptedSession stale = factory.addSession();
        stale.discovery.add(List.of(tool("query_metrics", objectSchema())));
        stale.discovery.add(List.of());
        ScriptedSession fresh = factory.addSession();
        fresh.discovery.add(List.of(tool("query_metrics", objectSchema())));
        fresh.results.add(textResult("fresh"));
        McpReadiness readiness = new McpReadiness();
        OfficialMcpGateway gateway = new OfficialMcpGateway(
                validProperties(), new ObjectMapper(), readiness, factory);

        gateway.discover("fake-ops");
        assertThrows(McpContractException.class, () -> gateway.discover("fake-ops"));
        assertEquals(1, stale.closes.get());
        assertFalse(readiness.state("fake-ops").ready());

        assertEquals(
                new McpCallResult("fresh", false),
                assertDoesNotThrow(
                        () -> gateway.call("fake-ops", "query_metrics", Map.of())));
        assertEquals(2, factory.opens.get());
        assertEquals(1, fresh.calls.get());
        assertTrue(readiness.state("fake-ops").ready());
    }

    @Test
    void sdkWrappedJsonFailureIsDefinitive() {
        ScriptedFactory jsonFactory = new ScriptedFactory();
        ScriptedSession jsonFirst = jsonFactory.addSession();
        jsonFirst.discovery.add(List.of(tool("query_metrics", objectSchema())));
        jsonFirst.failCall = new McpTransportException(
                "decode failed",
                new com.fasterxml.jackson.core.JsonParseException(
                        (com.fasterxml.jackson.core.JsonParser) null,
                        "malformed response JSON"));
        ScriptedSession jsonSecond = jsonFactory.addSession();
        jsonSecond.discovery.add(List.of(tool("query_metrics", objectSchema())));
        jsonSecond.results.add(new OfficialMcpGateway.RawCallResult(
                List.of(new OfficialMcpGateway.RawContent(true, "must not retry")), false));
        OfficialMcpGateway jsonGateway = gateway(jsonFactory);
        jsonGateway.discover("fake-ops");

        assertThrows(
                McpContractException.class,
                () -> jsonGateway.call("fake-ops", "query_metrics", Map.of()));
        assertEquals(1, jsonFactory.opens.get());
        assertEquals(0, jsonFirst.closes.get());
        assertEquals(0, jsonSecond.calls.get());
    }

    @Test
    void plainTransportExceptionIsDefinitive() {
        ScriptedFactory plainFactory = new ScriptedFactory();
        ScriptedSession plainFirst = plainFactory.addSession();
        plainFirst.discovery.add(List.of(tool("query_metrics", objectSchema())));
        plainFirst.failCall = new McpTransportException("HTTP/protocol failure");
        ScriptedSession plainSecond = plainFactory.addSession();
        plainSecond.discovery.add(List.of(tool("query_metrics", objectSchema())));
        plainSecond.results.add(new OfficialMcpGateway.RawCallResult(
                List.of(new OfficialMcpGateway.RawContent(true, "must not retry")), false));
        OfficialMcpGateway plainGateway = gateway(plainFactory);
        plainGateway.discover("fake-ops");

        assertThrows(
                McpContractException.class,
                () -> plainGateway.call("fake-ops", "query_metrics", Map.of()));
        assertEquals(1, plainFactory.opens.get());
        assertEquals(0, plainFirst.closes.get());
        assertEquals(0, plainSecond.calls.get());
    }

    @Test
    void definitiveContractFailureIsNeverRetried() {
        ScriptedFactory factory = new ScriptedFactory();
        ScriptedSession session = factory.addSession();
        session.discovery.add(List.of(tool("query_metrics", objectSchema())));
        session.failCall = new McpContractException("definitive protocol failure");
        OfficialMcpGateway gateway = gateway(factory);
        gateway.discover("fake-ops");

        assertThrows(
                McpContractException.class,
                () -> gateway.call("fake-ops", "query_metrics", Map.of()));
        assertEquals(1, factory.opens.get());
        assertEquals(0, session.closes.get());
    }

    @Test
    void traceCaptureContainsOnlyCurrentW3cTraceHeaders() {
        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        Tracer tracer = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build()
                .getTracer("mcp-contract-test");
        Span span = tracer.spanBuilder("caller").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            var context = OfficialMcpGateway.captureTraceContext();
            assertTrue(String.valueOf(context.get("traceparent"))
                    .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"));
            assertEquals(null, context.get("goal"));
            assertEquals(null, context.get("arguments"));
            assertEquals(null, context.get("baggage"));
            assertFalse(Trace.currentW3cHeaders().containsKey("authorization"));
        } finally {
            span.end();
            provider.close();
        }
    }

    @Test
    void officialHttpTransportCopiesOnlyW3cHeadersAndReconnectsAfterBreak() throws Exception {
        try (McpHttpFixture fixture = new McpHttpFixture()) {
            fixture.breakFirstToolCall = true;
            McpProperties properties = propertiesFor(fixture.baseUrl(), Duration.ofSeconds(1));
            McpReadiness readiness = new McpReadiness();
            OfficialMcpGateway gateway =
                    new OfficialMcpGateway(properties, fixture.mapper, readiness);
            SdkTracerProvider provider = SdkTracerProvider.builder().build();
            Tracer tracer = OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .build()
                    .getTracer("mcp-http-contract-test");
            Span span = tracer.spanBuilder("caller").startSpan();
            try (Scope ignored = span.makeCurrent();
                    Scope baggage = Baggage.current()
                            .toBuilder()
                            .put("secret", "must-not-cross")
                            .build()
                            .makeCurrent()) {
                gateway.discover("fake-ops");
                assertEquals(
                        new McpCallResult("metric-ok", false),
                        gateway.call("fake-ops", "query_metrics", Map.of("name", "cpu")));
            } finally {
                span.end();
                gateway.close();
                provider.close();
            }

            assertEquals(2, fixture.initializeRequests.get());
            assertEquals(2, fixture.listRequests.get());
            assertEquals(2, fixture.callRequests.get());
            assertTrue(fixture.observedHeaders.stream().allMatch(headers ->
                    String.valueOf(headers.get("traceparent"))
                            .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01")));
            assertTrue(fixture.observedHeaders.stream()
                    .allMatch(headers -> !headers.containsKey("baggage")));
            assertTrue(fixture.observedHeaders.stream()
                    .allMatch(headers -> !headers.containsKey("authorization")));
            assertTrue(readiness.state("fake-ops").ready());
            assertEquals(Set.of("query_metrics"), readiness.state("fake-ops").toolNames());
            fixture.assertNoUnexpectedFailures();
        }
    }

    @Test
    void officialHttpMalformedJsonIsDefinitiveWithoutReconnectOrRetry() throws Exception {
        try (McpHttpFixture fixture = new McpHttpFixture()) {
            fixture.malformedToolCallResponses = true;
            OfficialMcpGateway gateway = new OfficialMcpGateway(
                    propertiesFor(fixture.baseUrl(), Duration.ofSeconds(1)),
                    fixture.mapper,
                    new McpReadiness());
            try {
                assertThrows(
                        McpContractException.class,
                        () -> {
                            gateway.discover("fake-ops");
                            gateway.call(
                                    "fake-ops", "query_metrics", Map.of("name", "cpu"));
                        });
            } finally {
                gateway.close();
            }

            assertEquals(1, fixture.initializeRequests.get());
            assertEquals(1, fixture.listRequests.get());
            assertEquals(1, fixture.callRequests.get());
            fixture.assertNoUnexpectedFailures();
        }
    }

    @Test
    void officialHttpRequestTimeoutReconnectsOnceThenMarksNotReady() throws Exception {
        try (McpHttpFixture fixture = new McpHttpFixture()) {
            fixture.blockToolLists = true;
            McpReadiness readiness = new McpReadiness();
            OfficialMcpGateway gateway = new OfficialMcpGateway(
                    propertiesFor(fixture.baseUrl(), Duration.ofMillis(100)),
                    fixture.mapper,
                    readiness);
            try {
                assertThrows(
                        OfficialMcpGateway.TransportFailureException.class,
                        () -> gateway.discover("fake-ops"));
            } finally {
                fixture.releaseToolLists.countDown();
                gateway.close();
            }

            assertEquals(2, fixture.initializeRequests.get());
            assertEquals(2, fixture.listRequests.get());
            assertFalse(readiness.state("fake-ops").ready());
            assertTrue(readiness.state("fake-ops").reason().length() <= 160);
            assertFalse(readiness.state("fake-ops").reason().contains(fixture.baseUrl()));
            fixture.assertNoUnexpectedFailures();
        }
    }

    private static void assertDiscoveryRejected(List<OfficialMcpGateway.RawTool> tools) {
        ScriptedFactory factory = new ScriptedFactory();
        factory.addSession().discovery.add(tools);
        OfficialMcpGateway gateway = gateway(factory);
        assertThrows(McpContractException.class, () -> gateway.discover("fake-ops"));
    }

    private static void assertCallRejected(OfficialMcpGateway.RawCallResult result) {
        ScriptedFactory factory = new ScriptedFactory();
        ScriptedSession session = factory.addSession();
        session.discovery.add(List.of(tool("query_metrics", objectSchema())));
        session.results.add(result);
        OfficialMcpGateway gateway = gateway(factory);
        gateway.discover("fake-ops");
        assertThrows(
                McpContractException.class,
                () -> gateway.call("fake-ops", "query_metrics", Map.of()));
        assertEquals(1, factory.opens.get());
    }

    private static OfficialMcpGateway gateway(ScriptedFactory factory) {
        return new OfficialMcpGateway(
                validProperties(), new ObjectMapper(), new McpReadiness(), factory);
    }

    private static OfficialMcpGateway.RawTool tool(String name, Object schema) {
        return new OfficialMcpGateway.RawTool(name, "description", schema);
    }

    private static McpTransportException transportFailure(String attempt) {
        return new McpTransportException(
                "connection reset " + attempt,
                new ConnectException("connection reset"));
    }

    private static OfficialMcpGateway.RawCallResult textResult(String text) {
        return new OfficialMcpGateway.RawCallResult(
                List.of(new OfficialMcpGateway.RawContent(true, text)),
                false);
    }

    private static Map<String, Object> objectSchema() {
        return new LinkedHashMap<>(Map.of(
                "type", "object",
                "properties", Map.of()));
    }

    private static Map<String, Object> schemaWithSerializedBytes(
            ObjectMapper mapper, int targetBytes) throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("properties", Map.of());
        schema.put("title", "");
        schema.put("type", "object");
        int fixedBytes = mapper.writeValueAsBytes(schema).length;
        schema.put("title", "x".repeat(targetBytes - fixedBytes));
        return schema;
    }

    private static Map<String, Object> argumentsWithSerializedBytes(
            ObjectMapper mapper, int targetBytes) throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("payload", "");
        int fixedBytes = mapper.writeValueAsBytes(arguments).length;
        arguments.put("payload", "x".repeat(targetBytes - fixedBytes));
        return arguments;
    }

    private static McpProperties validProperties() {
        return propertiesFor("http://localhost:8090", Duration.ofSeconds(3));
    }

    private static McpProperties propertiesFor(String baseUrl, Duration requestTimeout) {
        McpProperties properties = new McpProperties();
        McpProperties.Server server = new McpProperties.Server();
        server.setBaseUrl(baseUrl);
        server.setEndpoint("/mcp");
        server.setConnectTimeout(Duration.ofMillis(500));
        server.setRequestTimeout(requestTimeout);
        server.setMaximumResponseBytes(65_536);

        McpProperties.ToolPolicy query = new McpProperties.ToolPolicy();
        query.setIdempotencyClass(IdempotencyClass.READ_ONLY);
        query.setApprovalPolicy(ApprovalPolicy.NONE);
        server.setTools(Map.of("query_metrics", query));

        Map<String, McpProperties.Server> servers = new LinkedHashMap<>();
        servers.put("fake-ops", server);
        properties.setServers(servers);
        return properties;
    }

    private static final class McpHttpFixture implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger initializeRequests = new AtomicInteger();
        private final AtomicInteger listRequests = new AtomicInteger();
        private final AtomicInteger callRequests = new AtomicInteger();
        private final Set<Map<String, String>> observedHeaders = ConcurrentHashMap.newKeySet();
        private final Queue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();
        private final CountDownLatch releaseToolLists = new CountDownLatch(1);
        private volatile boolean breakFirstToolCall;
        private volatile boolean malformedToolCallResponses;
        private volatile boolean blockToolLists;

        private McpHttpFixture() throws Exception {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/mcp", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private String baseUrl() {
            return "http://" + server.getAddress().getAddress().getHostAddress()
                    + ":" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) {
            String method = "";
            boolean clientTimeoutExpected = false;
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                Map<String, String> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((name, values) ->
                        headers.put(name.toLowerCase(), values.getFirst()));
                observedHeaders.add(Map.copyOf(headers));
                var request = mapper.readTree(exchange.getRequestBody());
                method = request.path("method").asText();
                if ("notifications/initialized".equals(method)) {
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                ObjectNode response = mapper.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", request.get("id"));
                switch (method) {
                    case "initialize" -> {
                        initializeRequests.incrementAndGet();
                        ObjectNode result = response.putObject("result");
                        result.put("protocolVersion", "2025-06-18");
                        result.putObject("capabilities").putObject("tools");
                        ObjectNode serverInfo = result.putObject("serverInfo");
                        serverInfo.put("name", "fixture");
                        serverInfo.put("version", "1");
                    }
                    case "tools/list" -> {
                        listRequests.incrementAndGet();
                        if (blockToolLists) {
                            clientTimeoutExpected = true;
                            releaseToolLists.await(2, TimeUnit.SECONDS);
                        }
                        ObjectNode result = response.putObject("result");
                        var tools = result.putArray("tools");
                        ObjectNode tool = tools.addObject();
                        tool.put("name", "query_metrics");
                        tool.put("description", "query fixture metrics");
                        ObjectNode schema = tool.putObject("inputSchema");
                        schema.put("type", "object");
                        schema.putObject("properties")
                                .putObject("name")
                                .put("type", "string");
                    }
                    case "tools/call" -> {
                        int requestNumber = callRequests.incrementAndGet();
                        if (breakFirstToolCall && requestNumber == 1) {
                            return;
                        }
                        if (malformedToolCallResponses) {
                            byte[] malformed = (
                                    "event: message\n"
                                            + "data: {malformed-json\n\n")
                                    .getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set(
                                    "Content-Type", "text/event-stream");
                            exchange.getResponseHeaders().set(
                                    "Mcp-Session-Id", "fixture-session");
                            exchange.sendResponseHeaders(200, malformed.length);
                            exchange.getResponseBody().write(malformed);
                            return;
                        }
                        ObjectNode result = response.putObject("result");
                        result.putArray("content")
                                .addObject()
                                .put("type", "text")
                                .put("text", "metric-ok");
                        result.put("isError", false);
                    }
                    default -> {
                        ObjectNode error = response.putObject("error");
                        error.put("code", -32601);
                        error.put("message", "method not found");
                    }
                }
                byte[] body = mapper.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Mcp-Session-Id", "fixture-session");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                unexpectedFailures.add(ex);
            } catch (IOException ex) {
                if (!(clientTimeoutExpected && "tools/list".equals(method))) {
                    unexpectedFailures.add(ex);
                }
            } catch (Exception ex) {
                unexpectedFailures.add(ex);
            }
        }

        private void assertNoUnexpectedFailures() {
            assertTrue(
                    unexpectedFailures.isEmpty(),
                    () -> "Unexpected MCP fixture failures: " + unexpectedFailures);
        }

        @Override
        public void close() {
            releaseToolLists.countDown();
            server.stop(0);
            executor.close();
        }
    }

    private static final class ScriptedFactory implements OfficialMcpGateway.SessionFactory {
        private final Queue<ScriptedSession> sessions = new ArrayDeque<>();
        private final AtomicInteger opens = new AtomicInteger();

        private ScriptedSession addSession() {
            ScriptedSession session = new ScriptedSession();
            sessions.add(session);
            return session;
        }

        @Override
        public OfficialMcpGateway.Session open(
                String serverId, McpProperties.Server server, ObjectMapper mapper) {
            opens.incrementAndGet();
            ScriptedSession session = sessions.poll();
            if (session == null) {
                throw new AssertionError("No scripted session");
            }
            return session;
        }
    }

    private static final class ScriptedSession implements OfficialMcpGateway.Session {
        private final Queue<List<OfficialMcpGateway.RawTool>> discovery = new ArrayDeque<>();
        private final Queue<OfficialMcpGateway.RawCallResult> results = new ArrayDeque<>();
        private final AtomicInteger initializes = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private RuntimeException failCall;

        @Override
        public void initialize() {
            initializes.incrementAndGet();
        }

        @Override
        public List<OfficialMcpGateway.RawTool> listTools() {
            List<OfficialMcpGateway.RawTool> result = discovery.poll();
            if (result == null) {
                throw new AssertionError("No scripted discovery");
            }
            return result;
        }

        @Override
        public OfficialMcpGateway.RawCallResult call(
                String toolName, Map<String, Object> arguments) {
            calls.incrementAndGet();
            if (failCall != null) {
                throw failCall;
            }
            OfficialMcpGateway.RawCallResult result = results.poll();
            if (result == null) {
                throw new AssertionError("No scripted result");
            }
            return result;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
