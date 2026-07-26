package com.reagent.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.mcp.McpReadiness;
import com.reagent.profile.AgentProfileRegistry;
import com.reagent.rag.KnowledgeVersionProvider;
import com.reagent.rag.RagProperties;
import com.reagent.stream.RedisStreamTransport;
import com.reagent.stream.StreamTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@Component("reAgentReadiness")
public final class ReAgentReadinessHealthIndicator implements HealthIndicator {

    private static final Set<String> REQUIRED_MCP_TOOLS =
            Set.of("query_metrics", "search_logs", "create_ticket");

    private final Clock clock;
    private final List<Probe> probes;

    @Autowired
    public ReAgentReadinessHealthIndicator(
            Clock clock,
            DataSource dataSource,
            StreamTransport streamTransport,
            ObjectProvider<StringRedisTemplate> redisProvider,
            RagProperties ragProperties,
            KnowledgeVersionProvider knowledgeVersionProvider,
            McpReadiness mcpReadiness,
            AgentProfileRegistry profileRegistry,
            ObjectMapper mapper
    ) {
        this(clock, List.of(
                probe("database", "database-unavailable", () -> databaseVersion(dataSource)),
                probe("redis", "redis-unavailable",
                        () -> redisVersion(streamTransport, redisProvider)),
                probe("python", "python-unavailable",
                        () -> pythonVersion(ragProperties, mapper)),
                probe("rag-index", "rag-index-not-ready",
                        () -> knowledgeVersionProvider.requireActiveVersion("incident-ops")),
                probe("mcp", "mcp-discovery-failed", () -> mcpVersion(mcpReadiness)),
                probe("incident-profile", "incident-profile-unavailable",
                        () -> profileRegistry.snapshot("incident-ops").profileVersion())
        ));
    }

    private ReAgentReadinessHealthIndicator(Clock clock, List<Probe> probes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.probes = List.copyOf(Objects.requireNonNull(probes, "probes"));
    }

    static ReAgentReadinessHealthIndicator forTesting(Clock clock, List<Probe> probes) {
        return new ReAgentReadinessHealthIndicator(clock, probes);
    }

    static Probe probe(String name, String failureReason, Supplier<String> version) {
        return new Probe(name, failureReason, version);
    }

    public ReAgentReadiness readiness() {
        List<ComponentReadiness> components = new ArrayList<>(probes.size());
        boolean ready = true;
        for (Probe probe : probes) {
            try {
                String version = probe.version().get();
                if (version == null || version.isBlank()) {
                    throw new IllegalStateException("component version unavailable");
                }
                components.add(new ComponentReadiness(probe.name(), true, version, "ready"));
            } catch (RuntimeException failure) {
                ready = false;
                components.add(new ComponentReadiness(
                        probe.name(), false, "", probe.failureReason()));
            }
        }
        return new ReAgentReadiness(ready, clock.instant(), components);
    }

    @Override
    public Health health() {
        ReAgentReadiness snapshot = readiness();
        Health.Builder builder = snapshot.ready() ? Health.up() : Health.down();
        return builder
                .withDetail("ready", snapshot.ready())
                .withDetail("checkedAt", snapshot.checkedAt())
                .withDetail("components", snapshot.components())
                .build();
    }

    private static String databaseVersion(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(2)) {
                throw new IllegalStateException("database unavailable");
            }
            String product = connection.getMetaData().getDatabaseProductName();
            return product == null || product.isBlank() ? "database" : bounded(product);
        } catch (Exception failure) {
            throw new IllegalStateException("database unavailable");
        }
    }

    private static String redisVersion(
            StreamTransport transport,
            ObjectProvider<StringRedisTemplate> redisProvider
    ) {
        if (!(transport instanceof RedisStreamTransport)) {
            return "in-process";
        }
        StringRedisTemplate template = redisProvider.getIfAvailable();
        if (template == null || template.getConnectionFactory() == null) {
            throw new IllegalStateException("redis unavailable");
        }
        try (RedisConnection connection = template.getConnectionFactory().getConnection()) {
            String pong = connection.ping();
            if (pong == null || pong.isBlank()) {
                throw new IllegalStateException("redis unavailable");
            }
            return "redis";
        }
    }

    private static String pythonVersion(RagProperties properties, ObjectMapper mapper) {
        try {
            URI base = properties.getBaseUrl();
            String root = base.toString().endsWith("/")
                    ? base.toString().substring(0, base.toString().length() - 1)
                    : base.toString();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(root + "/internal/readiness"))
                    .timeout(properties.getRequestTimeout())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HttpClient.newBuilder()
                    .connectTimeout(properties.getConnectTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200
                    || response.body().length > properties.getMaximumResponseBytes()) {
                throw new IllegalStateException("python unavailable");
            }
            JsonNode body = mapper.readTree(response.body());
            if (!body.path("ready").asBoolean(false)
                    || !"agent-capabilities".equals(body.path("service").asText())) {
                throw new IllegalStateException("python unavailable");
            }
            String version = body.path("version").asText("0.1.0");
            return bounded(version);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("python unavailable");
        }
    }

    private static String mcpVersion(McpReadiness readiness) {
        McpReadiness.State state = readiness.state("fake-ops");
        if (!state.ready() || !state.toolNames().equals(REQUIRED_MCP_TOOLS)) {
            throw new IllegalStateException("MCP discovery unavailable");
        }
        return state.toolCount() + "-tools";
    }

    private static String bounded(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    record Probe(String name, String failureReason, Supplier<String> version) {
        Probe {
            name = Objects.requireNonNull(name, "name");
            failureReason = Objects.requireNonNull(failureReason, "failureReason");
            version = Objects.requireNonNull(version, "version");
        }
    }
}
