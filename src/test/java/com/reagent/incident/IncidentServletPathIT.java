package com.reagent.incident;

import com.reagent.core.AgentRunner;
import com.reagent.testsupport.InfrastructureIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.mvc.servlet.path=/gateway"
)
class IncidentServletPathIT extends InfrastructureIT {

    private static final int MAX_PAYLOAD_BYTES = 32 * 1024;

    @Autowired private JdbcTemplate jdbc;
    @MockBean private AgentRunner runner;
    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearDurableState() {
        jdbc.update("DELETE FROM incident_intake");
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM task");
    }

    @Test
    void payloadLimitAppliesThroughConfiguredServletPath() throws Exception {
        byte[] oversized = ("{" + "x".repeat(MAX_PAYLOAD_BYTES))
                .getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/gateway/api/incidents"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(oversized))
                .build();

        HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(MAX_PAYLOAD_BYTES + 1, oversized.length);
        assertEquals(413, response.statusCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from task", Integer.class));
    }
}
