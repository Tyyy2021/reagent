package com.reagent.mcp;

import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reagent.mcp")
public class McpProperties implements InitializingBean {

    static final int MAX_RESPONSE_BYTES = 1_048_576;
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    static final Pattern LEGAL_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private Map<String, Server> servers = new LinkedHashMap<>();

    public Map<String, Server> getServers() {
        return servers;
    }

    public void setServers(Map<String, Server> servers) {
        this.servers = servers == null ? null : new LinkedHashMap<>(servers);
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        if (servers == null || servers.isEmpty()) {
            throw new McpContractException("MCP servers are required");
        }
        servers.forEach((serverId, server) -> {
            requireLegalId(serverId, "server ID");
            if (server == null) {
                throw new McpContractException("MCP server configuration is required: " + serverId);
            }
            server.validate(serverId);
        });
    }

    public Server requireServer(String serverId) {
        requireLegalId(serverId, "server ID");
        Server server = servers == null ? null : servers.get(serverId);
        if (server == null) {
            throw new McpContractException("Unknown MCP server: " + serverId);
        }
        return server;
    }

    private static void requireLegalId(String value, String field) {
        if (value == null || !LEGAL_ID.matcher(value).matches()) {
            throw new McpContractException("Illegal " + field);
        }
    }

    private static void requirePositiveBounded(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new McpContractException(field + " must be positive and at most " + MAX_TIMEOUT);
        }
    }

    public static class Server {
        private String baseUrl;
        private String endpoint;
        private Duration connectTimeout;
        private Duration requestTimeout;
        private int maximumResponseBytes;
        private Map<String, ToolPolicy> tools = new LinkedHashMap<>();

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public int getMaximumResponseBytes() {
            return maximumResponseBytes;
        }

        public void setMaximumResponseBytes(int maximumResponseBytes) {
            this.maximumResponseBytes = maximumResponseBytes;
        }

        public Map<String, ToolPolicy> getTools() {
            return tools;
        }

        public void setTools(Map<String, ToolPolicy> tools) {
            this.tools = tools;
        }

        public ToolPolicy requireTool(String toolName) {
            requireLegalId(toolName, "tool name");
            ToolPolicy policy = tools == null ? null : tools.get(toolName);
            if (policy == null) {
                throw new McpContractException("Unknown MCP tool policy: " + toolName);
            }
            return policy;
        }

        private void validate(String serverId) {
            URI uri;
            try {
                uri = new URI(Objects.requireNonNull(baseUrl, "baseUrl"));
            } catch (NullPointerException | URISyntaxException ex) {
                throw new McpContractException("Invalid MCP base URL for " + serverId, ex);
            }
            String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getPath() != null
                            && !uri.getPath().isEmpty()
                            && !"/".equals(uri.getPath()))) {
                throw new McpContractException("Invalid MCP base URL for " + serverId);
            }
            if (!"/mcp".equals(endpoint)) {
                throw new McpContractException("MCP endpoint must be exactly /mcp");
            }
            requirePositiveBounded(connectTimeout, "connect timeout");
            requirePositiveBounded(requestTimeout, "request timeout");
            if (maximumResponseBytes <= 0 || maximumResponseBytes > MAX_RESPONSE_BYTES) {
                throw new McpContractException(
                        "maximum response bytes must be between 1 and " + MAX_RESPONSE_BYTES);
            }
            if (tools == null || tools.isEmpty()) {
                throw new McpContractException("MCP tool policies are required for " + serverId);
            }
            tools.forEach((toolName, policy) -> {
                requireLegalId(toolName, "tool name");
                if (policy == null
                        || policy.idempotencyClass == null
                        || policy.approvalPolicy == null) {
                    throw new McpContractException(
                            "Complete MCP tool policy is required: " + toolName);
                }
            });
        }
    }

    public static class ToolPolicy {
        private IdempotencyClass idempotencyClass;
        private ApprovalPolicy approvalPolicy;

        public IdempotencyClass getIdempotencyClass() {
            return idempotencyClass;
        }

        public void setIdempotencyClass(IdempotencyClass idempotencyClass) {
            this.idempotencyClass = idempotencyClass;
        }

        public ApprovalPolicy getApprovalPolicy() {
            return approvalPolicy;
        }

        public void setApprovalPolicy(ApprovalPolicy approvalPolicy) {
            this.approvalPolicy = approvalPolicy;
        }
    }
}
