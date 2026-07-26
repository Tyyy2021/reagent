package com.reagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.FaultInjector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpToolConfiguration {

    private static final String SERVER_ID = "fake-ops";

    private final McpGateway gateway;
    private final McpProperties properties;
    private final ObjectMapper mapper;
    private final FaultInjector faultInjector;

    public McpToolConfiguration(
            McpGateway gateway, McpProperties properties, ObjectMapper mapper) {
        this(gateway, properties, mapper, FaultInjector.none());
    }

    @Autowired
    public McpToolConfiguration(
            McpGateway gateway,
            McpProperties properties,
            ObjectMapper mapper,
            FaultInjector faultInjector) {
        this.gateway = gateway;
        this.properties = properties;
        this.mapper = mapper;
        this.faultInjector = faultInjector;
    }

    @Bean
    public McpToolAdapter queryMetrics() {
        return adapter("query_metrics");
    }

    @Bean
    public McpToolAdapter searchLogs() {
        return adapter("search_logs");
    }

    @Bean
    public McpToolAdapter createTicket() {
        return adapter("create_ticket");
    }

    private McpToolAdapter adapter(String toolName) {
        return new McpToolAdapter(
                gateway, properties, mapper, SERVER_ID, toolName, faultInjector);
    }
}
