package com.reagent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.profile.AgentProfileDefinition;
import com.reagent.profile.SchemaHasher;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.ToolCatalogResolver;
import com.reagent.profile.ToolSchemaDriftException;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolProperties;
import com.reagent.tool.ToolRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

class McpProtocolIT {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0");
    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:8.0-alpine");
    private static final DockerImageName PYTHON_IMAGE =
            DockerImageName.parse("reagent-agent-capabilities:task9");
    private static final int PYTHON_PORT = 8090;
    private static final String PYTHON_LAUNCHER = """
            from pathlib import Path
            import uvicorn
            from agent_capabilities.app import create_app
            from agent_capabilities.config import Settings
            from agent_capabilities.fake_ops.tickets import TicketService

            mysql_url = "mysql+pymysql://fake_ops_app:fake-ops-test@mysql:3306/fake_ops"
            settings = Settings(
                env="test",
                mysql_url=mysql_url,
                redis_url="redis://redis:6379/0",
                knowledge_root=Path("/unused"),
                acceptance_enabled=True,
                chaos_enabled=False,
            )
            app = create_app(
                settings,
                ticket_service_factory=lambda: TicketService.from_url(
                    mysql_url, pool_size=8
                ),
            )
            uvicorn.run(
                app,
                host="0.0.0.0",
                port=8090,
                log_level="warning",
                lifespan="on",
            )
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void officialJavaClientCallsRealTask8PythonStreamableHttp() throws Exception {
        try (Network network = Network.newNetwork();
                MySQLContainer<?> mysql = mysql(network);
                GenericContainer<?> redis = redis(network);
                GenericContainer<?> python = python(network)) {
            mysql.start();
            redis.start();
            assertTrue(redis.execInContainer("redis-server", "--version")
                    .getStdout()
                    .contains("v=8."));
            python.start();

            URI baseUri = URI.create(
                    "http://" + python.getHost() + ":" + python.getMappedPort(PYTHON_PORT));
            HttpResponse<String> rawInitialize = rawInitialize(baseUri);
            assertEquals(
                    200,
                    rawInitialize.statusCode(),
                    () -> "initialize status=" + rawInitialize.statusCode()
                            + ", content-type="
                            + rawInitialize.headers().firstValue("content-type").orElse("")
                            + ", location="
                            + rawInitialize.headers().firstValue("location").orElse("")
                            + ", body-bytes="
                            + rawInitialize.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                    .length);
            McpProperties properties = properties(baseUri.toString());
            McpReadiness readiness = new McpReadiness();
            try (OfficialMcpGateway gateway =
                    new OfficialMcpGateway(properties, mapper, readiness)) {
                List<McpRemoteTool> tools = gateway.discover("fake-ops");
                assertEquals(
                        List.of("create_ticket", "query_metrics", "search_logs"),
                        tools.stream().map(McpRemoteTool::name).toList());
                assertTrue(readiness.state("fake-ops").ready());

                HttpResponse<String> wrongPath = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(500))
                        .build()
                        .send(
                                HttpRequest.newBuilder(baseUri.resolve("/mcp/mcp"))
                                        .timeout(Duration.ofSeconds(2))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertEquals(404, wrongPath.statusCode());

                McpToolAdapter metrics = new McpToolAdapter(
                        gateway, properties, mapper, "fake-ops", "query_metrics");
                McpToolAdapter logs = new McpToolAdapter(
                        gateway, properties, mapper, "fake-ops", "search_logs");
                McpToolAdapter ticket = new McpToolAdapter(
                        gateway, properties, mapper, "fake-ops", "create_ticket");
                @SuppressWarnings("unchecked")
                Map<String, Object> ticketProperties =
                        (Map<String, Object>) ticket.parameterSchema().get("properties");
                assertFalse(ticketProperties.containsKey("idempotency_key"));

                ToolContext context = new ToolContext("task-mcp-it", Path.of("."));
                String metricsResult = metrics.execute(
                        mapper.readTree("""
                                {
                                  "service": "checkout",
                                  "start": "2026-07-19T10:00:00Z",
                                  "end": "2026-07-19T10:15:00Z"
                                }
                                """),
                        context.forCall("metrics-call"));
                String logsResult = logs.execute(
                        mapper.readTree("""
                                {
                                  "service": "checkout",
                                  "start": "2026-07-19T10:00:00Z",
                                  "end": "2026-07-19T10:15:00Z",
                                  "query": "SQLTransientConnectionException",
                                  "limit": 2
                                }
                                """),
                        context.forCall("logs-call"));
                assertEquals(14.2, mapper.readTree(metricsResult)
                        .path("errorRatePercent")
                        .asDouble());
                assertEquals(2, mapper.readTree(logsResult).path("entries").size());

                String ticketArguments = """
                        {
                          "title": "Checkout connection pool exhausted",
                          "severity": "critical",
                          "evidence": "metrics and timeout logs corroborate saturation"
                        }
                        """;
                String toolCallId = "java-mcp-stable-tool-call";
                Map<String, Object> first = readObject(ticket.execute(
                        mapper.readTree(ticketArguments), context.forCall(toolCallId)));
                Map<String, Object> replay = readObject(ticket.execute(
                        mapper.readTree(ticketArguments), context.forCall(toolCallId)));
                assertEquals(first.get("ticketId"), replay.get("ticketId"));
                assertEquals(List.of(1, 2), List.of(
                        ((Number) first.get("attemptCount")).intValue(),
                        ((Number) replay.get("attemptCount")).intValue()));

                HttpResponse<String> acceptance = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(baseUri.resolve(
                                        "/internal/acceptance?idempotencyKey=" + toolCallId))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, acceptance.statusCode());
                var acceptanceJson = mapper.readTree(acceptance.body());
                assertEquals(2, acceptanceJson.path("createTicketAttempts").asInt());
                assertEquals(1, acceptanceJson.path("uniqueTicketCount").asInt());
                assertEquals(
                        first.get("ticketId"),
                        acceptanceJson.path("ticketIds").get(0).asText());

                DriftGateway driftGateway = new DriftGateway(gateway);
                McpToolAdapter driftTicket = new McpToolAdapter(
                        driftGateway,
                        properties,
                        mapper,
                        "fake-ops",
                        "create_ticket");
                ToolCatalogResolver resolver = new ToolCatalogResolver(
                        new ToolRegistry(List.of(driftTicket)),
                        new SchemaHasher(mapper),
                        new ToolProperties(),
                        properties);
                TaskProfileSnapshot snapshot = resolver.snapshot(new AgentProfileDefinition(
                        "mcp-drift",
                        "v1",
                        "system",
                        null,
                        null,
                        List.of("fake-ops"),
                        List.of("create_ticket")));
                driftGateway.drift.set(true);

                assertThrows(
                        ToolSchemaDriftException.class,
                        () -> resolver.resolve(snapshot));
                assertEquals(0, driftGateway.calls.get());
            }
        }
    }

    private Map<String, Object> readObject(String json) throws Exception {
        return mapper.readValue(json, new TypeReference<>() {});
    }

    private static HttpResponse<String> rawInitialize(URI baseUri) throws Exception {
        String body = """
                {
                  "jsonrpc": "2.0",
                  "id": "path-probe",
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": {"name": "java-path-probe", "version": "1"}
                  }
                }
                """;
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(
                        HttpRequest.newBuilder(baseUri.resolve("/mcp"))
                                .timeout(Duration.ofSeconds(3))
                                .header("Accept", "application/json, text/event-stream")
                                .header("Content-Type", "application/json; charset=utf-8")
                                .header("MCP-Protocol-Version", "2025-06-18")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private static MySQLContainer<?> mysql(Network network) {
        return new MySQLContainer<>(MYSQL_IMAGE)
                .withDatabaseName("fake_ops")
                .withUsername("fake_ops_app")
                .withPassword("fake-ops-test")
                .withNetwork(network)
                .withNetworkAliases("mysql")
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    private static GenericContainer<?> redis(Network network) {
        return new GenericContainer<>(REDIS_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("redis")
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    private static GenericContainer<?> python(Network network) {
        return new GenericContainer<>(PYTHON_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("agent-capabilities")
                .withExposedPorts(PYTHON_PORT)
                .withCommand("python", "-c", PYTHON_LAUNCHER)
                .waitingFor(Wait.forHttp("/internal/readiness")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)))
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    private static McpProperties properties(String baseUrl) {
        McpProperties.Server server = new McpProperties.Server();
        server.setBaseUrl(baseUrl);
        server.setEndpoint("/mcp");
        server.setConnectTimeout(Duration.ofMillis(500));
        server.setRequestTimeout(Duration.ofSeconds(3));
        server.setMaximumResponseBytes(65_536);
        server.setTools(Map.of(
                "query_metrics", policy(IdempotencyClass.READ_ONLY, ApprovalPolicy.NONE),
                "search_logs", policy(IdempotencyClass.READ_ONLY, ApprovalPolicy.NONE),
                "create_ticket", policy(
                        IdempotencyClass.IDEMPOTENT, ApprovalPolicy.REQUIRE_APPROVAL)));
        McpProperties properties = new McpProperties();
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

    private static final class DriftGateway implements McpGateway {
        private final McpGateway delegate;
        private final AtomicBoolean drift = new AtomicBoolean();
        private final AtomicInteger calls = new AtomicInteger();

        private DriftGateway(McpGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<McpRemoteTool> discover(String serverId) {
            List<McpRemoteTool> genuine = delegate.discover(serverId);
            if (!drift.get()) {
                return genuine;
            }
            List<McpRemoteTool> modified = new ArrayList<>(genuine.size());
            for (McpRemoteTool tool : genuine) {
                if (!"create_ticket".equals(tool.name())) {
                    modified.add(tool);
                    continue;
                }
                Map<String, Object> schema = new LinkedHashMap<>(tool.inputSchema());
                @SuppressWarnings("unchecked")
                Map<String, Object> originalProperties =
                        (Map<String, Object>) schema.get("properties");
                Map<String, Object> changedProperties =
                        new LinkedHashMap<>(originalProperties);
                changedProperties.put(
                        "controller_drift", Map.of("type", "string"));
                schema.put("properties", changedProperties);
                modified.add(new McpRemoteTool(
                        tool.serverId(),
                        tool.name(),
                        tool.description(),
                        schema));
            }
            return List.copyOf(modified);
        }

        @Override
        public McpCallResult call(
                String serverId, String toolName, Map<String, Object> arguments) {
            calls.incrementAndGet();
            return delegate.call(serverId, toolName, arguments);
        }

        @Override
        public void close() {}
    }
}
