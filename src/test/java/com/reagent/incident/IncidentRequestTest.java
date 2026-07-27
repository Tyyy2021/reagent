package com.reagent.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncidentRequestTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void sharedIncidentFixtureDeserializesWithBoundedFields() throws Exception {
        IncidentRequest request = mapper.readValue(
                Path.of("contracts/incident-intake-v1.example.json").toFile(),
                IncidentRequest.class);

        assertEquals("fake-alertmanager", request.source());
        assertEquals("ALERT-CHECKOUT-001", request.externalAlertId());
        assertEquals("checkout", request.service());
    }

    @Test
    void labelsAreRequiredAndDefensivelyCopied() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("region", "cn-east");

        IncidentRequest request = request(labels);
        labels.put("environment", "demo");

        assertEquals(Map.of("region", "cn-east"), request.labels());
        assertThrows(NullPointerException.class, () -> request(null));
    }

    @Test
    void acceptedResponseCarriesStableIdentifiersAndDeduplicationState() {
        IncidentAccepted accepted = new IncidentAccepted("incident-1", "task-1", true);

        assertEquals("incident-1", accepted.incidentId());
        assertEquals("task-1", accepted.taskId());
        assertEquals(true, accepted.deduplicated());
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
