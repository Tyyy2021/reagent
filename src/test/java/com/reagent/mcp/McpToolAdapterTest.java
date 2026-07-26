package com.reagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.FaultContext;
import com.reagent.core.FaultInjector;
import com.reagent.core.FaultPoint;
import com.reagent.core.InjectedWorkerCrashException;
import com.reagent.core.TaskRunToken;
import com.reagent.profile.AgentProfileDefinition;
import com.reagent.profile.SchemaHasher;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.ToolCatalogResolver;
import com.reagent.profile.ToolSchemaDriftException;
import com.reagent.profile.ToolSnapshot;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolProperties;
import com.reagent.tool.ToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpToolAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ticketSchemaIsFreshDeeplyImmutableAndHidesReservedKey() {
        FakeGateway gateway = new FakeGateway();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", nested);
        properties.put("idempotency_key", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(List.of("title", "idempotency_key")));
        gateway.discoveries.add(List.of(remote("create_ticket", schema)));
        McpToolAdapter adapter = adapter(gateway, "create_ticket");

        Map<String, Object> visible = adapter.parameterSchema();

        @SuppressWarnings("unchecked")
        Map<String, Object> visibleProperties =
                (Map<String, Object>) visible.get("properties");
        assertEquals(Map.of("type", "string"), visibleProperties.get("title"));
        assertFalse(visibleProperties.containsKey("idempotency_key"));
        assertEquals(List.of("title"), visible.get("required"));
        assertEquals(false, visible.get("additionalProperties"));
        nested.put("description", "mutated remotely");
        assertEquals(Map.of("type", "string"), visibleProperties.get("title"));
        assertThrows(UnsupportedOperationException.class, () -> visible.put("x", "y"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> visibleProperties.put("x", Map.of()));
        assertEquals(1, gateway.discoverCalls.get());
    }

    @Test
    void ticketRejectsForgedOrMissingReservedKeyBeforeGatewayAndInjectsExactCallId()
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        McpToolAdapter adapter = adapter(gateway, "create_ticket");
        ToolContext template = new ToolContext("task-1", Path.of("."));

        JsonNode forged = MAPPER.readTree(
                "{\"title\":\"incident\",\"idempotency_key\":\"forged\"}");
        assertThrows(McpContractException.class, () -> adapter.execute(forged, template.forCall("call-1")));
        assertEquals(0, gateway.callCount.get());

        JsonNode valid = MAPPER.readTree("{\"title\":\"incident\"}");
        assertThrows(McpContractException.class, () -> adapter.execute(valid, template));
        assertEquals(0, gateway.callCount.get());

        gateway.results.add(new McpCallResult("{\"ticket_id\":\"T-1\"}", false));
        assertEquals(
                "{\"ticket_id\":\"T-1\"}",
                adapter.execute(valid, template.forCall("persisted-tool-call-id")));
        assertEquals(
                Map.of("title", "incident", "idempotency_key", "persisted-tool-call-id"),
                gateway.lastArguments);
    }

    @Test
    void readToolsNeverReceiveReservedKeyAndDefinitiveFailuresStayText() throws Exception {
        FakeGateway gateway = new FakeGateway();
        McpToolAdapter adapter = adapter(gateway, "query_metrics");
        gateway.results.add(new McpCallResult("remote validation failed", true));

        String result = adapter.execute(
                MAPPER.readTree("{\"name\":\"cpu\"}"),
                new ToolContext("task-1", Path.of(".")).forCall("call-read"));

        assertTrue(result.contains("remote validation failed"));
        assertEquals(Map.of("name", "cpu"), gateway.lastArguments);
        assertFalse(gateway.lastArguments.containsKey("idempotency_key"));

        gateway.failure = new OfficialMcpGateway.TransportFailureException(
                "transport unavailable", new java.io.IOException("secret-url"));
        String transportResult = adapter.execute(
                MAPPER.readTree("{\"name\":\"cpu\"}"),
                new ToolContext("task-1", Path.of(".")).forCall("call-read-2"));
        assertTrue(transportResult.contains("unavailable"));
        assertFalse(transportResult.contains("secret-url"));
    }

    @Test
    void idempotentTransportUncertaintyEscapesAsBoundedUnknownOutcome() throws Exception {
        FakeGateway gateway = new FakeGateway();
        McpToolAdapter adapter = adapter(gateway, "create_ticket");
        gateway.failure = new OfficialMcpGateway.TransportFailureException(
                "transport unavailable", new java.io.IOException("http://secret/path"));

        RemoteOutcomeUnknownException failure = assertThrows(
                RemoteOutcomeUnknownException.class,
                () -> adapter.execute(
                        MAPPER.readTree("{\"title\":\"incident\"}"),
                        new ToolContext("task-1", Path.of(".")).forCall("call-ticket")));

        assertTrue(failure.getMessage().contains("outcome is unknown"));
        assertFalse(failure.getMessage().contains("secret"));
        assertTrue(failure.getMessage().length() <= 200);
    }

    @Test
    void successfulTicketHitsRemoteSideEffectBoundaryOnlyWithRunIdentity() throws Exception {
        FakeGateway gateway = new FakeGateway();
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<FaultPoint> observedPoint = new AtomicReference<>();
        AtomicReference<FaultContext> observedContext = new AtomicReference<>();
        FaultInjector injector = (point, context) -> {
            hits.incrementAndGet();
            observedPoint.set(point);
            observedContext.set(context);
            throw new InjectedWorkerCrashException(point, context);
        };
        McpToolAdapter ticket = adapter(gateway, "create_ticket", injector);
        TaskRunToken token = new TaskRunToken("task-1", "worker-a", 7);
        ToolContext running =
                new ToolContext(token, Path.of(".")).forCall("persisted-ticket-call");
        gateway.results.add(new McpCallResult("{\"ticket_id\":\"T-1\"}", false));

        InjectedWorkerCrashException crash = assertThrows(
                InjectedWorkerCrashException.class,
                () -> ticket.execute(MAPPER.readTree("{\"title\":\"incident\"}"), running));

        FaultContext expected = new FaultContext(
                "task-1",
                "worker-a",
                7,
                java.util.Optional.of("persisted-ticket-call"),
                java.util.Optional.empty());
        assertEquals(FaultPoint.AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT, crash.point());
        assertEquals(expected, crash.faultContext());
        assertEquals(FaultPoint.AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT, observedPoint.get());
        assertEquals(expected, observedContext.get());
        assertEquals(1, hits.get());
        assertEquals(1, gateway.callCount.get());

        gateway.results.add(new McpCallResult("{\"ticket_id\":\"T-2\"}", false));
        assertEquals(
                "{\"ticket_id\":\"T-2\"}",
                ticket.execute(
                        MAPPER.readTree("{\"title\":\"without-run-token\"}"),
                        new ToolContext("task-2", Path.of(".")).forCall("call-no-token")));

        McpToolAdapter metrics = adapter(gateway, "query_metrics", injector);
        gateway.results.add(new McpCallResult("{\"value\":14.2}", false));
        assertEquals(
                "{\"value\":14.2}",
                metrics.execute(MAPPER.readTree("{}"), running.forCall("metrics-call")));

        gateway.results.add(new McpCallResult("validation failed", true));
        assertTrue(ticket.execute(
                        MAPPER.readTree("{\"title\":\"bad\"}"),
                        running.forCall("ticket-error-call"))
                .contains("validation failed"));
        assertEquals(1, hits.get());
    }

    @Test
    void resolverUsesTrustedMcpPolicyTimeoutProviderAndSanitizedSchema() {
        FakeGateway gateway = new FakeGateway();
        gateway.discoveries.add(List.of(remote("create_ticket", ticketSchema())));
        gateway.discoveries.add(List.of(remote("create_ticket", ticketSchema())));
        McpProperties properties = properties();
        McpToolAdapter adapter = new McpToolAdapter(
                gateway, properties, MAPPER, "fake-ops", "create_ticket");
        Tool local = localTool();
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(List.of(local, adapter)),
                new SchemaHasher(MAPPER),
                new ToolProperties(),
                properties);
        AgentProfileDefinition definition = new AgentProfileDefinition(
                "focused",
                "v1",
                "system",
                null,
                null,
                List.of("fake-ops"),
                List.of("local_read", "create_ticket"));

        TaskProfileSnapshot snapshot = resolver.snapshot(definition);
        ToolSnapshot localSnapshot = snapshot.tools().get(0);
        ToolSnapshot mcpSnapshot = snapshot.tools().get(1);

        assertEquals("local", localSnapshot.provider());
        assertEquals("mcp:fake-ops", mcpSnapshot.provider());
        assertEquals(3_000, mcpSnapshot.timeoutMs());
        assertEquals(IdempotencyClass.IDEMPOTENT, mcpSnapshot.idempotencyClass());
        assertEquals(ApprovalPolicy.REQUIRE_APPROVAL, mcpSnapshot.approvalPolicy());
        @SuppressWarnings("unchecked")
        Map<String, Object> visibleProperties =
                (Map<String, Object>) mcpSnapshot.parameterSchema().get("properties");
        assertFalse(visibleProperties.containsKey("idempotency_key"));
        resolver.resolve(snapshot);
    }

    @Test
    void resolverFailsClosedForMissingServerPolicyOrCurrentSchemaDrift() {
        McpProperties properties = properties();
        FakeGateway gateway = new FakeGateway();
        gateway.discoveries.add(List.of(remote("create_ticket", ticketSchema())));
        McpToolAdapter adapter = new McpToolAdapter(
                gateway, properties, MAPPER, "fake-ops", "create_ticket");
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(List.of(adapter)),
                new SchemaHasher(MAPPER),
                new ToolProperties(),
                properties);

        assertThrows(
                McpContractException.class,
                () -> resolver.snapshot(new AgentProfileDefinition(
                        "missing-server",
                        "v1",
                        "system",
                        null,
                        null,
                        List.of(),
                        List.of("create_ticket"))));
        assertThrows(
                McpContractException.class,
                () -> resolver.snapshot(new AgentProfileDefinition(
                        "unknown-server",
                        "v1",
                        "system",
                        null,
                        null,
                        List.of("unknown"),
                        List.of())));

        gateway.discoveries.add(List.of(remote("create_ticket", ticketSchema())));
        Map<String, Object> drifted = ticketSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> driftedProperties =
                (Map<String, Object>) drifted.get("properties");
        driftedProperties.put("new_field", Map.of("type", "string"));
        gateway.discoveries.add(List.of(remote("create_ticket", drifted)));
        TaskProfileSnapshot snapshot = resolver.snapshot(new AgentProfileDefinition(
                "drift",
                "v1",
                "system",
                null,
                null,
                List.of("fake-ops"),
                List.of("create_ticket")));

        assertThrows(ToolSchemaDriftException.class, () -> resolver.resolve(snapshot));
    }

    @Test
    void configurationCreatesExactlyThreeOfflineAdaptersAndMissingPolicyFailsClosed() {
        FakeGateway gateway = new FakeGateway();
        McpToolConfiguration configuration =
                new McpToolConfiguration(gateway, properties(), MAPPER);

        List<McpToolAdapter> adapters = List.of(
                configuration.queryMetrics(),
                configuration.searchLogs(),
                configuration.createTicket());

        assertEquals(
                List.of("query_metrics", "search_logs", "create_ticket"),
                adapters.stream().map(McpToolAdapter::name).toList());
        assertEquals(0, gateway.discoverCalls.get());
        assertEquals(0, gateway.callCount.get());

        McpProperties missing = properties();
        missing.getServers().get("fake-ops").setTools(Map.of(
                "query_metrics", policy(IdempotencyClass.READ_ONLY, ApprovalPolicy.NONE),
                "search_logs", policy(IdempotencyClass.READ_ONLY, ApprovalPolicy.NONE)));
        assertThrows(
                McpContractException.class,
                () -> new McpToolConfiguration(gateway, missing, MAPPER).createTicket());
        assertEquals(0, gateway.discoverCalls.get());
    }

    private static McpToolAdapter adapter(FakeGateway gateway, String toolName) {
        return new McpToolAdapter(gateway, properties(), MAPPER, "fake-ops", toolName);
    }

    private static McpToolAdapter adapter(
            FakeGateway gateway, String toolName, FaultInjector faultInjector) {
        return new McpToolAdapter(
                gateway,
                properties(),
                MAPPER,
                "fake-ops",
                toolName,
                faultInjector);
    }

    private static McpRemoteTool remote(String name, Map<String, Object> schema) {
        return new McpRemoteTool("fake-ops", name, "remote " + name, schema);
    }

    private static Map<String, Object> ticketSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", Map.of("type", "string"));
        properties.put("idempotency_key", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(List.of("title", "idempotency_key")));
        return schema;
    }

    private static McpProperties properties() {
        McpProperties properties = new McpProperties();
        McpProperties.Server server = new McpProperties.Server();
        server.setBaseUrl("http://localhost:8090");
        server.setEndpoint("/mcp");
        server.setConnectTimeout(Duration.ofMillis(500));
        server.setRequestTimeout(Duration.ofSeconds(3));
        server.setMaximumResponseBytes(65_536);
        server.setTools(Map.of(
                "query_metrics", policy(IdempotencyClass.READ_ONLY, ApprovalPolicy.NONE),
                "search_logs", policy(IdempotencyClass.READ_ONLY, ApprovalPolicy.NONE),
                "create_ticket", policy(
                        IdempotencyClass.IDEMPOTENT, ApprovalPolicy.REQUIRE_APPROVAL)));
        properties.setServers(Map.of("fake-ops", server));
        properties.validate();
        return properties;
    }

    private static McpProperties.ToolPolicy policy(
            IdempotencyClass idempotency, ApprovalPolicy approval) {
        McpProperties.ToolPolicy policy = new McpProperties.ToolPolicy();
        policy.setIdempotencyClass(idempotency);
        policy.setApprovalPolicy(approval);
        return policy;
    }

    private static Tool localTool() {
        return new Tool() {
            @Override
            public String name() {
                return "local_read";
            }

            @Override
            public String description() {
                return "local";
            }

            @Override
            public Map<String, Object> parameterSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public IdempotencyClass idempotency() {
                return IdempotencyClass.READ_ONLY;
            }

            @Override
            public String execute(JsonNode args, ToolContext ctx) {
                return "local";
            }
        };
    }

    private static final class FakeGateway implements McpGateway {
        private final Queue<List<McpRemoteTool>> discoveries = new ArrayDeque<>();
        private final Queue<McpCallResult> results = new ArrayDeque<>();
        private final AtomicInteger discoverCalls = new AtomicInteger();
        private final AtomicInteger callCount = new AtomicInteger();
        private Map<String, Object> lastArguments;
        private RuntimeException failure;

        @Override
        public List<McpRemoteTool> discover(String serverId) {
            discoverCalls.incrementAndGet();
            List<McpRemoteTool> result = discoveries.poll();
            if (result == null) {
                throw new AssertionError("No scripted discovery");
            }
            return result;
        }

        @Override
        public McpCallResult call(
                String serverId, String toolName, Map<String, Object> arguments) {
            callCount.incrementAndGet();
            lastArguments = Map.copyOf(arguments);
            if (failure != null) {
                RuntimeException current = failure;
                failure = null;
                throw current;
            }
            McpCallResult result = results.poll();
            if (result == null) {
                throw new AssertionError("No scripted result");
            }
            return result;
        }

        @Override
        public void close() {}
    }
}
