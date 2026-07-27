package com.reagent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.FaultContext;
import com.reagent.core.FaultInjector;
import com.reagent.core.FaultPoint;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class McpToolAdapter implements Tool {

    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {};

    private final McpGateway gateway;
    private final McpProperties properties;
    private final ObjectMapper mapper;
    private final FaultInjector faultInjector;
    private final String serverId;
    private final String remoteToolName;

    public McpToolAdapter(
            McpGateway gateway,
            McpProperties properties,
            ObjectMapper mapper,
            String serverId,
            String remoteToolName) {
        this(gateway, properties, mapper, serverId, remoteToolName, FaultInjector.none());
    }

    public McpToolAdapter(
            McpGateway gateway,
            McpProperties properties,
            ObjectMapper mapper,
            String serverId,
            String remoteToolName,
            FaultInjector faultInjector) {
        this.gateway = gateway;
        this.properties = properties;
        this.mapper = mapper;
        this.faultInjector = Objects.requireNonNull(faultInjector, "faultInjector");
        this.serverId = serverId;
        this.remoteToolName = remoteToolName;
        properties.requireServer(serverId).requireTool(remoteToolName);
    }

    @Override
    public String name() {
        return remoteToolName;
    }

    @Override
    public String description() {
        return "Remote MCP tool " + remoteToolName;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        McpRemoteTool remote = gateway.discover(serverId).stream()
                .filter(tool -> remoteToolName.equals(tool.name()))
                .findFirst()
                .orElseThrow(() -> new McpContractException(
                        "Configured MCP tool is unavailable: " + remoteToolName));
        Map<String, Object> mutable = mutableJsonMap(remote.inputSchema());
        if ("create_ticket".equals(remoteToolName)) {
            Object propertiesValue = mutable.get("properties");
            if (!(propertiesValue instanceof Map<?, ?> propertyMap)) {
                throw new McpContractException("Ticket Schema properties are required");
            }
            Map<String, Object> sanitizedProperties = mutableJsonMap(propertyMap);
            sanitizedProperties.remove("idempotency_key");
            mutable.put("properties", sanitizedProperties);

            Object requiredValue = mutable.get("required");
            if (requiredValue instanceof List<?> required) {
                List<Object> sanitizedRequired = new ArrayList<>();
                required.stream()
                        .filter(item -> !"idempotency_key".equals(item))
                        .forEach(sanitizedRequired::add);
                mutable.put("required", sanitizedRequired);
            }
            mutable.put("additionalProperties", false);
        }
        return immutableJsonMap(mutable);
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) {
        if (args == null || !args.isObject()) {
            throw new McpContractException("MCP tool arguments must be an object");
        }
        if (args.has("idempotency_key")) {
            throw new McpContractException("Reserved MCP argument is not model-settable");
        }
        Map<String, Object> converted = mapper.convertValue(args, ARGUMENTS);
        Map<String, Object> arguments = new LinkedHashMap<>(converted);
        if (idempotency() == IdempotencyClass.IDEMPOTENT) {
            String key = ctx.idempotencyKey();
            if (key == null || key.isBlank()) {
                throw new McpContractException("Persisted tool call ID is required");
            }
            arguments.put("idempotency_key", key);
        }
        try {
            McpCallResult result = gateway.call(
                    serverId, remoteToolName, Collections.unmodifiableMap(arguments));
            if (result.error()) {
                return boundedObservation("Remote MCP error: " + result.text());
            }
            if ("create_ticket".equals(remoteToolName)) {
                ctx.runToken().ifPresent(runToken -> faultInjector.hit(
                        FaultPoint.AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT,
                        new FaultContext(
                                runToken.taskId(),
                                runToken.workerId(),
                                runToken.leaseEpoch(),
                                java.util.Optional.of(ctx.idempotencyKey()),
                                java.util.Optional.empty())));
            }
            return result.text();
        } catch (OfficialMcpGateway.TransportFailureException ex) {
            if (idempotency() == IdempotencyClass.IDEMPOTENT) {
                throw new RemoteOutcomeUnknownException(remoteToolName, ex);
            }
            return "Remote MCP tool is unavailable.";
        } catch (McpContractException ex) {
            return "Remote MCP tool returned a definitive contract error.";
        }
    }

    @Override
    public IdempotencyClass idempotency() {
        return properties.requireServer(serverId)
                .requireTool(remoteToolName)
                .getIdempotencyClass();
    }

    @Override
    public ApprovalPolicy approvalPolicy() {
        return properties.requireServer(serverId)
                .requireTool(remoteToolName)
                .getApprovalPolicy();
    }

    public String serverId() {
        return serverId;
    }

    public long requestTimeoutMs() {
        return properties.requireServer(serverId).getRequestTimeout().toMillis();
    }

    private static String boundedObservation(String value) {
        String safe = Objects.requireNonNullElse(value, "Remote MCP error.");
        return safe.length() <= 4_096 ? safe : safe.substring(0, 4_096);
    }

    private static Map<String, Object> mutableJsonMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String textKey)) {
                throw new McpContractException("MCP Schema key must be text");
            }
            copy.put(textKey, mutableJsonValue(value));
        });
        return copy;
    }

    private static Object mutableJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return mutableJsonMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(mutableJsonValue(item)));
            return copy;
        }
        throw new McpContractException("Unsupported MCP Schema value");
    }

    private static Map<String, Object> immutableJsonMap(Map<?, ?> source) {
        Map<String, Object> copy = mutableJsonMap(source);
        Map<String, Object> frozen = new LinkedHashMap<>();
        copy.forEach((key, value) -> frozen.put(key, immutableJsonValue(value)));
        return Collections.unmodifiableMap(frozen);
    }

    private static Object immutableJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableJsonMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableJsonValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
