package com.reagent.acceptance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.reagent.persist.ToolCallEntity;
import com.reagent.persist.ToolCallRepository;
import com.reagent.rag.RagProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class PythonAcceptanceClient {

    private static final Pattern TICKET_ID = Pattern.compile("^OPS-[A-Z0-9]{12}$");
    private static final Set<String> TOOLS =
            Set.of("query_metrics", "search_logs", "create_ticket");

    private final RagProperties properties;
    private final ToolCallRepository toolCalls;
    private final HttpClient http;
    private final ObjectReader reader;

    public PythonAcceptanceClient(
            RagProperties properties,
            ObjectMapper mapper,
            ToolCallRepository toolCalls
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.toolCalls = Objects.requireNonNull(toolCalls, "toolCalls");
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.reader = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readerFor(PythonAcceptanceResponse.class);
    }

    public PythonAcceptanceResponse fetch(String taskId) {
        ToolCallEntity createTicket = toolCalls.findAll().stream()
                .filter(call -> taskId.equals(call.getTaskId()))
                .filter(call -> "create_ticket".equals(call.getToolName()))
                .findFirst()
                .orElse(null);
        if (createTicket == null) {
            return zero();
        }
        String encoded = URLEncoder.encode(
                        createTicket.getId(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        HttpRequest request = HttpRequest.newBuilder(
                        endpoint("/internal/acceptance?idempotencyKey=" + encoded))
                .timeout(properties.getRequestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response =
                    http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200
                    || response.body().length > properties.getMaximumResponseBytes()) {
                throw invalid();
            }
            PythonAcceptanceResponse parsed = reader.readValue(response.body());
            validate(parsed);
            return parsed;
        } catch (IOException failure) {
            throw invalid();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw invalid();
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalStateException) {
                throw failure;
            }
            throw invalid();
        }
    }

    private URI endpoint(String suffix) {
        String base = properties.getBaseUrl().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + suffix);
    }

    private static void validate(PythonAcceptanceResponse response) {
        if (response.contractVersion() != 1
                || !"idempotency-key".equals(response.scope())
                || response.toolAttempts() == null
                || !response.toolAttempts().keySet().equals(TOOLS)
                || response.toolAttempts().values().stream().anyMatch(value -> value < 0)
                || response.createTicketAttempts() < 0
                || response.uniqueTicketCount() < 0
                || response.uniqueTicketCount() > 16
                || response.ticketIds() == null
                || response.ticketIds().size() > 16
                || response.uniqueTicketCount() != response.ticketIds().size()
                || response.ticketIds().stream()
                .anyMatch(ticket -> ticket == null || !TICKET_ID.matcher(ticket).matches())
                || response.createTicketAttempts()
                != response.toolAttempts().get("create_ticket")
                || response.faultGateState() == null
                || response.faultGateState().isBlank()
                || response.faultGateState().length() > 64) {
            throw invalid();
        }
    }

    private static PythonAcceptanceResponse zero() {
        return new PythonAcceptanceResponse(
                1,
                "idempotency-key",
                Map.of(
                        "query_metrics", 0,
                        "search_logs", 0,
                        "create_ticket", 0),
                0,
                0,
                List.of(),
                "not-invoked");
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Python acceptance response is invalid");
    }

    record PythonAcceptanceResponse(
            int contractVersion,
            String scope,
            Map<String, Integer> toolAttempts,
            int createTicketAttempts,
            int uniqueTicketCount,
            List<String> ticketIds,
            String faultGateState
    ) {
        PythonAcceptanceResponse {
            toolAttempts = toolAttempts == null ? null : Map.copyOf(toolAttempts);
            ticketIds = ticketIds == null ? null : List.copyOf(ticketIds);
        }
    }
}
