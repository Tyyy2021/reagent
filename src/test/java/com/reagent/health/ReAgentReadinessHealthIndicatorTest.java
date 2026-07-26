package com.reagent.health;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.reagent.api.ReadinessController;
import com.reagent.mcp.McpReadiness;
import com.reagent.profile.AgentProfileRegistry;
import com.reagent.rag.KnowledgeVersionProvider;
import com.reagent.rag.RagProperties;
import com.reagent.stream.StreamTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
