package com.reagent.incident;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentGoalFactoryTest {

    @Test
    void createsBoundedDeterministicGoalWithSortedLabels() {
        IncidentGoalFactory factory = new IncidentGoalFactory();
        LinkedHashMap<String, String> reverseLabels = new LinkedHashMap<>();
        reverseLabels.put("region", "cn-east");
        reverseLabels.put("environment", "demo");

        String first = factory.create(request(reverseLabels));
        String second = factory.create(request(Map.of(
                "environment", "demo",
                "region", "cn-east")));

        assertEquals(first, second);
        assertTrue(first.contains("source: fake-alertmanager"));
        assertTrue(first.contains("externalAlertId: ALERT-CHECKOUT-001"));
        assertTrue(first.contains("service: checkout"));
        assertTrue(first.contains("severity: critical"));
        assertTrue(first.contains("title: Checkout error rate is above threshold"));
        assertTrue(first.contains("summary: 5xx error rate exceeded 10% for five minutes"));
        assertTrue(first.contains("startedAt: 2026-07-19T10:00:00Z"));
        assertTrue(first.indexOf("environment=demo") < first.indexOf("region=cn-east"));
        assertTrue(first.length() <= IncidentGoalFactory.MAX_GOAL_CHARS);
    }

    private static IncidentRequest request(Map<String, String> labels) {
        return new IncidentRequest(
                "fake-alertmanager",
                "ALERT-CHECKOUT-001",
                "checkout",
                "critical",
                "Checkout error rate is above threshold",
                "5xx error rate exceeded 10% for five minutes",
                Instant.parse("2026-07-19T10:00:00Z"),
                labels);
    }
}
