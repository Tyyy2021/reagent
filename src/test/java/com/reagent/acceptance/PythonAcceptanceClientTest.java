package com.reagent.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.persist.ToolCallEntity;
import com.reagent.persist.ToolCallRepository;
import com.reagent.rag.RagProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PythonAcceptanceClientTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/acceptance", exchange -> {
            requests.incrementAndGet();
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void persistedCreateTicketCallIdScopesTheStrictPythonResponse() {
        ToolCallRepository ledger = mock(ToolCallRepository.class);
        ToolCallEntity create = new ToolCallEntity(
                "call-create-ticket-a/b", "task-1", "create_ticket", "{}",
                Instant.EPOCH);
        when(ledger.findAll()).thenReturn(List.of(create));
        response.set("""
                {"contractVersion":1,"scope":"idempotency-key",
                 "toolAttempts":{"query_metrics":0,"search_logs":0,"create_ticket":2},
                 "createTicketAttempts":2,"uniqueTicketCount":1,
                 "ticketIds":["OPS-0123456789AB"],"faultGateState":"released"}
                """);

        PythonAcceptanceClient.PythonAcceptanceResponse actual =
                client(ledger).fetch("task-1");

        assertEquals(2, actual.createTicketAttempts());
        assertEquals(List.of("OPS-0123456789AB"), actual.ticketIds());
        assertEquals("idempotencyKey=call-create-ticket-a%2Fb", query.get());
        assertEquals(1, requests.get());
    }

    @Test
    void missingCreateTicketLedgerRowReturnsScopedZeroWithoutHttp() {
        ToolCallRepository ledger = mock(ToolCallRepository.class);
        when(ledger.findAll()).thenReturn(List.of(
                new ToolCallEntity("call-rag", "task-2", "search_knowledge", "{}")));

        PythonAcceptanceClient.PythonAcceptanceResponse actual =
                client(ledger).fetch("task-2");

        assertEquals(0, actual.createTicketAttempts());
        assertEquals(0, actual.uniqueTicketCount());
        assertEquals(List.of(), actual.ticketIds());
        assertEquals(0, requests.get());
    }

    @Test
    void unknownFieldsNegativeCountsAndInvalidTicketIdsAreRejected() {
        ToolCallRepository ledger = mock(ToolCallRepository.class);
        when(ledger.findAll()).thenReturn(List.of(
                new ToolCallEntity("call-ticket", "task-3", "create_ticket", "{}")));
        PythonAcceptanceClient client = client(ledger);

        response.set("""
                {"contractVersion":1,"scope":"idempotency-key",
                 "toolAttempts":{"query_metrics":0,"search_logs":0,"create_ticket":0},
                 "createTicketAttempts":0,"uniqueTicketCount":0,"ticketIds":[],
                 "faultGateState":"disabled","unknown":"forbidden"}
                """);
        assertThrows(IllegalStateException.class, () -> client.fetch("task-3"));

        response.set("""
                {"contractVersion":1,"scope":"idempotency-key",
                 "toolAttempts":{"query_metrics":0,"search_logs":0,"create_ticket":-1},
                 "createTicketAttempts":-1,"uniqueTicketCount":1,
                 "ticketIds":["not-a-ticket"],"faultGateState":"disabled"}
                """);
        assertThrows(IllegalStateException.class, () -> client.fetch("task-3"));
    }

    private PythonAcceptanceClient client(ToolCallRepository ledger) {
        RagProperties properties = new RagProperties();
        properties.setBaseUrl(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()));
        return new PythonAcceptanceClient(properties, new ObjectMapper(), ledger);
    }
}
