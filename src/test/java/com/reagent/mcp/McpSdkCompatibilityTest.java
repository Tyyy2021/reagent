package com.reagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class McpSdkCompatibilityTest {

    @Test
    void pinnedSdkSupportsTheRequiredStreamableHttpClientShape() {
        ObjectMapper objectMapper = new ObjectMapper();
        String baseUrl = "http://127.0.0.1:65535";

        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper.copy());
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder(baseUrl)
                        .jsonMapper(jsonMapper)
                        .endpoint("/mcp")
                        .connectTimeout(Duration.ofMillis(500))
                        .httpRequestCustomizer((request, method, endpoint, body, context) -> {
                            copyHeader(context, request, "traceparent");
                            copyHeader(context, request, "tracestate");
                        })
                        .build();
        McpSyncClient client = McpClient.sync(transport)
                .transportContextProvider(OfficialMcpGateway::captureTraceContext)
                .requestTimeout(Duration.ofSeconds(3))
                .initializationTimeout(Duration.ofSeconds(3))
                .build();

        client.close();
    }

    private static void copyHeader(
            McpTransportContext context, HttpRequest.Builder request, String name) {
        Object value = context.get(name);
        if (value instanceof String text && !text.isBlank()) {
            request.header(name, text);
        }
    }
}
