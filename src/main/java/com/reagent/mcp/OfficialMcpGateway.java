package com.reagent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.obs.Trace;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The sole owner of official MCP SDK types. Public callers see only the local
 * {@link McpGateway} contract.
 */
@Component
public final class OfficialMcpGateway implements McpGateway {

    private static final int MAX_TOOLS = 64;
    private static final int MAX_DESCRIPTION_CODE_POINTS = 4_096;
    private static final int MAX_SCHEMA_DEPTH = 32;
    private static final int MAX_PROPERTIES_PER_OBJECT = 128;
    private static final Set<String> SCHEMA_MAP_POSITIONS = Set.of(
            "$defs", "definitions", "dependentSchemas", "patternProperties");
    private static final Set<String> SCHEMA_LIST_POSITIONS = Set.of(
            "allOf", "anyOf", "oneOf", "prefixItems");
    private static final Set<String> SCHEMA_OBJECT_POSITIONS = Set.of(
            "contains", "contentSchema", "else", "if", "items", "not",
            "propertyNames", "then");
    private static final Set<String> BOOLEAN_OR_SCHEMA_POSITIONS = Set.of(
            "additionalItems", "additionalProperties",
            "unevaluatedItems", "unevaluatedProperties");
    private static final Set<String> JSON_SCHEMA_TYPES = Set.of(
            "array", "boolean", "integer", "null", "number", "object", "string");

    private final McpProperties properties;
    private final ObjectMapper mapper;
    private final McpReadiness readiness;
    private final SessionFactory sessionFactory;
    private Tracer tracer;
    private final Map<String, ServerState> states = new LinkedHashMap<>();
    private boolean closed;

    @Autowired
    public OfficialMcpGateway(
            McpProperties properties, ObjectMapper mapper, McpReadiness readiness) {
        this(
                properties,
                mapper,
                readiness,
                OfficialMcpGateway::openSdkSession,
                OpenTelemetry.noop().getTracer(Trace.INSTRUMENTATION_NAME));
    }

    @Autowired(required = false)
    void setTracer(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    OfficialMcpGateway(
            McpProperties properties,
            ObjectMapper mapper,
            McpReadiness readiness,
            SessionFactory sessionFactory) {
        this(
                properties,
                mapper,
                readiness,
                sessionFactory,
                OpenTelemetry.noop().getTracer(Trace.INSTRUMENTATION_NAME));
    }

    OfficialMcpGateway(
            McpProperties properties,
            ObjectMapper mapper,
            McpReadiness readiness,
            SessionFactory sessionFactory,
            Tracer tracer) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        properties.validate();
    }

    static McpTransportContext captureTraceContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        Trace.currentW3cHeaders().forEach((name, value) -> {
            if (("traceparent".equals(name) || "tracestate".equals(name))
                    && value != null
                    && !value.isBlank()) {
                context.put(name, value);
            }
        });
        return context.isEmpty() ? McpTransportContext.EMPTY : McpTransportContext.create(context);
    }

    @Override
    public synchronized List<McpRemoteTool> discover(String serverId) {
        ensureOpen();
        McpProperties.Server configured = properties.requireServer(serverId);
        ServerState state = states.computeIfAbsent(serverId, ignored -> new ServerState());
        return discoverOrInvalidate(serverId, configured, state);
    }

    @Override
    public synchronized McpCallResult call(
            String serverId, String toolName, Map<String, Object> arguments) {
        Span span = tracer.spanBuilder("mcp.call_tool").startSpan();
        setSafeServer(span, serverId);
        setSafeTool(span, toolName);
        try (Scope ignored = span.makeCurrent()) {
            return callTraced(serverId, toolName, arguments);
        } catch (RuntimeException failure) {
            markFailure(span, failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private McpCallResult callTraced(
            String serverId, String toolName, Map<String, Object> arguments) {
        ensureOpen();
        McpProperties.Server configured = properties.requireServer(serverId);
        configured.requireTool(toolName);
        requireLegalToolName(toolName);
        Map<String, Object> safeArguments = immutableJsonMap(
                Objects.requireNonNull(arguments, "arguments"), 1, false);
        requireBoundedJson(
                safeArguments, configured.getMaximumResponseBytes(), "MCP arguments exceed limit");

        ServerState state = states.computeIfAbsent(serverId, ignored -> new ServerState());
        if (state.session == null || state.discovery.isEmpty()) {
            discoverOrInvalidate(serverId, configured, state);
        }
        Map<String, Object> previousSchema = schemaFor(state.discovery, toolName);
        if (previousSchema == null) {
            throw new McpContractException("Configured MCP tool is unavailable: " + toolName);
        }

        try {
            return validateCallResult(
                    state.session.call(toolName, safeArguments),
                    configured.getMaximumResponseBytes());
        } catch (RuntimeException firstFailure) {
            if (!isTransportFailure(firstFailure)) {
                throw normalize("MCP call failed", firstFailure);
            }
            invalidateState(state);
            Session replacement;
            List<McpRemoteTool> rediscovered;
            try {
                replacement = openInitialized(serverId, configured);
                state.session = replacement;
                rediscovered = validatedDiscovery(serverId, configured, replacement);
            } catch (RuntimeException reconnectFailure) {
                invalidateState(state);
                readiness.recordUnavailable(
                        serverId, unavailableReason(reconnectFailure));
                if (isTransportFailure(reconnectFailure)) {
                    throw new TransportFailureException(
                            "MCP transport unavailable", reconnectFailure);
                }
                throw normalize("MCP reconnect failed", reconnectFailure);
            }

            Map<String, Object> currentSchema =
                    schemaFor(rediscovered, toolName);
            if (!previousSchema.equals(currentSchema)) {
                invalidateState(state);
                readiness.recordUnavailable(serverId, "schema drift");
                throw new McpContractException(
                        "MCP Schema drift detected for " + serverId + "/" + toolName);
            }
            state.discovery = rediscovered;
            readiness.recordReady(serverId, names(rediscovered));

            try {
                return validateCallResult(
                        replacement.call(toolName, safeArguments),
                        configured.getMaximumResponseBytes());
            } catch (RuntimeException secondFailure) {
                if (isTransportFailure(secondFailure)) {
                    invalidateState(state);
                    readiness.recordUnavailable(
                            serverId, "transport unavailable");
                    throw new TransportFailureException(
                            "MCP transport unavailable", secondFailure);
                }
                throw normalize("MCP call failed", secondFailure);
            }
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        states.values().forEach(OfficialMcpGateway::invalidateState);
        states.clear();
    }

    private List<McpRemoteTool> discoverOrInvalidate(
            String serverId,
            McpProperties.Server configured,
            ServerState state) {
        try {
            return discoverWithReconnect(serverId, configured, state);
        } catch (RuntimeException exception) {
            invalidateState(state);
            readiness.recordUnavailable(
                    serverId, unavailableReason(exception));
            throw normalize("MCP discovery failed", exception);
        }
    }

    private List<McpRemoteTool> discoverWithReconnect(
            String serverId, McpProperties.Server configured, ServerState state) {
        try {
            Session session = requireSession(serverId, configured, state);
            List<McpRemoteTool> discovered = validatedDiscovery(serverId, configured, session);
            state.discovery = discovered;
            readiness.recordReady(serverId, names(discovered));
            return discovered;
        } catch (RuntimeException firstFailure) {
            if (!isTransportFailure(firstFailure)) {
                invalidateState(state);
                throw normalize("MCP discovery failed", firstFailure);
            }
            invalidateState(state);
            try {
                Session replacement = openInitialized(serverId, configured);
                state.session = replacement;
                List<McpRemoteTool> discovered =
                        validatedDiscovery(serverId, configured, replacement);
                state.discovery = discovered;
                readiness.recordReady(serverId, names(discovered));
                return discovered;
            } catch (RuntimeException secondFailure) {
                invalidateState(state);
                if (isTransportFailure(secondFailure)) {
                    throw new TransportFailureException(
                            "MCP transport unavailable", secondFailure);
                }
                throw normalize("MCP discovery failed", secondFailure);
            }
        }
    }

    private Session requireSession(
            String serverId, McpProperties.Server configured, ServerState state) {
        if (state.session == null) {
            state.session = openInitialized(serverId, configured);
        }
        return state.session;
    }

    private Session openInitialized(String serverId, McpProperties.Server configured) {
        Session session = sessionFactory.open(serverId, configured, mapper);
        boolean initialized = false;
        Span span = tracer.spanBuilder("mcp.initialize").startSpan();
        setSafeServer(span, serverId);
        try {
            try (Scope ignored = span.makeCurrent()) {
                session.initialize();
                initialized = true;
                return session;
            }
        } catch (RuntimeException failure) {
            markFailure(span, failure);
            throw failure;
        } finally {
            span.end();
            if (!initialized) {
                session.close();
            }
        }
    }

    private List<McpRemoteTool> validatedDiscovery(
            String serverId, McpProperties.Server configured, Session session) {
        List<RawTool> rawTools = listToolsTraced(serverId, session);
        if (rawTools == null || rawTools.size() > MAX_TOOLS) {
            throw new McpContractException("MCP discovery tool count exceeds limit");
        }

        Set<String> configuredNames = configured.getTools() == null
                ? Set.of()
                : Set.copyOf(configured.getTools().keySet());
        Set<String> discoveredNames = new LinkedHashSet<>();
        List<McpRemoteTool> validated = new ArrayList<>();
        for (RawTool raw : rawTools) {
            if (raw == null) {
                throw new McpContractException("MCP discovery contains an invalid tool");
            }
            requireLegalToolName(raw.name());
            if (!discoveredNames.add(raw.name())) {
                throw new McpContractException("Duplicate MCP tool name: " + raw.name());
            }
            if (!configuredNames.contains(raw.name())) {
                throw new McpContractException("Unconfigured MCP tool: " + raw.name());
            }
            String description = raw.description() == null ? "" : raw.description();
            if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION_CODE_POINTS) {
                throw new McpContractException("MCP tool description exceeds limit");
            }
            if (!(raw.inputSchema() instanceof Map<?, ?> schemaObject)) {
                throw new McpContractException("MCP tool Schema must be an object");
            }
            Map<String, Object> schema = immutableJsonMap(schemaObject, 1, false);
            validateRootSchema(schema);
            requireBoundedJson(
                    schema, configured.getMaximumResponseBytes(), "MCP Schema exceeds limit");
            validated.add(new McpRemoteTool(serverId, raw.name(), description, schema));
        }
        if (!discoveredNames.equals(configuredNames)) {
            throw new McpContractException("Configured MCP tools are missing from discovery");
        }
        validated.sort((left, right) -> left.name().compareTo(right.name()));
        return List.copyOf(validated);
    }

    private List<RawTool> listToolsTraced(String serverId, Session session) {
        Span span = tracer.spanBuilder("mcp.list_tools").startSpan();
        setSafeServer(span, serverId);
        try (Scope ignored = span.makeCurrent()) {
            return session.listTools();
        } catch (RuntimeException failure) {
            markFailure(span, failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private Map<String, Object> immutableJsonMap(
            Map<?, ?> source, int depth, boolean propertiesObject) {
        if (depth > MAX_SCHEMA_DEPTH) {
            throw new McpContractException("MCP JSON nesting exceeds limit");
        }
        if (propertiesObject && source.size() > MAX_PROPERTIES_PER_OBJECT) {
            throw new McpContractException("MCP Schema properties exceed limit");
        }
        Map<String, Object> sorted = new TreeMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String textKey)) {
                throw new McpContractException("MCP JSON object key must be text");
            }
            sorted.put(textKey, immutableJsonValue(
                    value, depth + 1, "properties".equals(textKey)));
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private Object immutableJsonValue(Object value, int depth, boolean propertiesObject) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                    || number instanceof Float floatValue && !Float.isFinite(floatValue)) {
                throw new McpContractException("MCP JSON number must be finite");
            }
            return number;
        }
        if (value instanceof Map<?, ?> map) {
            return immutableJsonMap(map, depth, propertiesObject);
        }
        if (value instanceof List<?> list) {
            if (depth > MAX_SCHEMA_DEPTH) {
                throw new McpContractException("MCP JSON nesting exceeds limit");
            }
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(immutableJsonValue(item, depth + 1, false));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new McpContractException(
                "Unsupported MCP JSON value: " + value.getClass().getSimpleName());
    }

    private void validateRootSchema(Map<String, Object> schema) {
        if (!"object".equals(schema.get("type"))) {
            throw new McpContractException("MCP tool Schema root type must be object");
        }
        validateSchemaObject(schema);
    }

    private void validateSchemaObject(Map<?, ?> schema) {
        if (schema.containsKey("type")) {
            validateSchemaType(schema.get("type"));
        }

        Map<?, ?> properties = Map.of();
        if (schema.containsKey("properties")) {
            Object propertiesValue = schema.get("properties");
            if (!(propertiesValue instanceof Map<?, ?> propertyMap)) {
                throw new McpContractException("MCP Schema properties must be an object");
            }
            properties = propertyMap;
            propertyMap.forEach((name, child) -> {
                if (!(child instanceof Map<?, ?> childSchema)) {
                    throw new McpContractException(
                            "MCP Schema property value must be a Schema object");
                }
                validateSchemaObject(childSchema);
            });
        }

        if (schema.containsKey("required")) {
            Object requiredValue = schema.get("required");
            if (!(requiredValue instanceof List<?> required)) {
                throw new McpContractException("MCP Schema required must be an array");
            }
            Set<String> seen = new HashSet<>();
            for (Object name : required) {
                if (!(name instanceof String text)
                        || !seen.add(text)
                        || !properties.containsKey(text)) {
                    throw new McpContractException(
                            "MCP Schema required property is malformed");
                }
            }
        }

        for (String position : BOOLEAN_OR_SCHEMA_POSITIONS) {
            if (!schema.containsKey(position)) {
                continue;
            }
            Object value = schema.get(position);
            if (value instanceof Boolean) {
                continue;
            }
            if (!(value instanceof Map<?, ?> childSchema)) {
                throw new McpContractException(
                        "MCP Schema " + position + " must be boolean or a Schema object");
            }
            validateSchemaObject(childSchema);
        }

        for (String position : SCHEMA_OBJECT_POSITIONS) {
            if (!schema.containsKey(position)) {
                continue;
            }
            Object value = schema.get(position);
            if (!(value instanceof Map<?, ?> childSchema)) {
                throw new McpContractException(
                        "MCP Schema " + position + " must be a Schema object");
            }
            validateSchemaObject(childSchema);
        }

        for (String position : SCHEMA_LIST_POSITIONS) {
            if (!schema.containsKey(position)) {
                continue;
            }
            Object value = schema.get(position);
            if (!(value instanceof List<?> children)) {
                throw new McpContractException(
                        "MCP Schema " + position + " must be an array");
            }
            for (Object child : children) {
                if (!(child instanceof Map<?, ?> childSchema)) {
                    throw new McpContractException(
                            "MCP Schema " + position + " entries must be Schema objects");
                }
                validateSchemaObject(childSchema);
            }
        }

        for (String position : SCHEMA_MAP_POSITIONS) {
            if (!schema.containsKey(position)) {
                continue;
            }
            Object value = schema.get(position);
            if (!(value instanceof Map<?, ?> children)) {
                throw new McpContractException(
                        "MCP Schema " + position + " must be an object");
            }
            children.forEach((name, child) -> {
                if (!(child instanceof Map<?, ?> childSchema)) {
                    throw new McpContractException(
                            "MCP Schema " + position + " values must be Schema objects");
                }
                validateSchemaObject(childSchema);
            });
        }
    }

    private static void validateSchemaType(Object value) {
        if (value instanceof String type) {
            if (JSON_SCHEMA_TYPES.contains(type)) {
                return;
            }
            throw new McpContractException("MCP Schema type name is invalid");
        }
        if (value instanceof List<?> types && !types.isEmpty()) {
            Set<String> seen = new HashSet<>();
            for (Object type : types) {
                if (!(type instanceof String text)
                        || !JSON_SCHEMA_TYPES.contains(text)
                        || !seen.add(text)) {
                    throw new McpContractException("MCP Schema type array is malformed");
                }
            }
            return;
        }
        throw new McpContractException(
                "MCP Schema type must be a string or non-empty string array");
    }

    private void requireBoundedJson(Object value, int maximumBytes, String message) {
        try {
            if (mapper.writeValueAsBytes(value).length > maximumBytes) {
                throw new McpContractException(message);
            }
        } catch (JsonProcessingException ex) {
            throw new McpContractException("MCP JSON cannot be serialized", ex);
        }
    }

    private McpCallResult validateCallResult(RawCallResult raw, int maximumBytes) {
        if (raw == null || raw.content() == null || raw.content().size() != 1) {
            throw new McpContractException("MCP result must contain exactly one text item");
        }
        RawContent content = raw.content().getFirst();
        if (content == null
                || !content.textContent()
                || content.text() == null
                || content.text().isEmpty()) {
            throw new McpContractException("MCP result must contain exactly one text item");
        }
        int bytes = content.text().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maximumBytes) {
            throw new McpContractException("MCP text result exceeds limit");
        }
        return new McpCallResult(content.text(), raw.error());
    }

    private static Map<String, Object> schemaFor(
            List<McpRemoteTool> discovery, String toolName) {
        return discovery.stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst()
                .map(McpRemoteTool::inputSchema)
                .orElse(null);
    }

    private static Set<String> names(List<McpRemoteTool> tools) {
        Set<String> names = new LinkedHashSet<>();
        tools.forEach(tool -> names.add(tool.name()));
        return names;
    }

    private static void requireLegalToolName(String toolName) {
        if (toolName == null || !McpProperties.LEGAL_ID.matcher(toolName).matches()) {
            throw new McpContractException("Illegal MCP tool name");
        }
    }

    private static RuntimeException normalize(String message, RuntimeException failure) {
        if (failure instanceof McpContractException
                || failure instanceof TransportFailureException) {
            return failure;
        }
        if (failure instanceof McpError) {
            return new McpContractException(message + ": protocol error", failure);
        }
        return new McpContractException(message, failure);
    }

    private static boolean isTransportFailure(Throwable failure) {
        if (hasDefinitiveProtocolCause(failure)) {
            return false;
        }
        Throwable current = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            if (current instanceof McpTransportSessionClosedException
                    || current instanceof McpTransportSessionNotFoundException
                    || current instanceof HttpTimeoutException
                    || current instanceof ConnectException
                    || current instanceof InterruptedIOException
                    || current instanceof TimeoutException
                    || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void setSafeServer(Span span, String serverId) {
        if (serverId != null && McpProperties.LEGAL_ID.matcher(serverId).matches()) {
            span.setAttribute(Trace.MCP_SERVER, serverId);
        }
    }

    private static void setSafeTool(Span span, String toolName) {
        if (toolName != null && McpProperties.LEGAL_ID.matcher(toolName).matches()) {
            span.setAttribute(Trace.TOOL_NAME, toolName);
        }
    }

    private static void markFailure(Span span, RuntimeException failure) {
        String errorType;
        if (failure instanceof McpContractException
                || hasDefinitiveProtocolCause(failure)) {
            errorType = "contract";
        } else if (isTransportFailure(failure)) {
            errorType = "transport";
        } else {
            errorType = "runtime";
        }
        span.setAttribute(Trace.MCP_ERROR_TYPE, errorType);
        span.setStatus(StatusCode.ERROR);
    }

    private static boolean hasDefinitiveProtocolCause(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            if (current instanceof McpContractException
                    || current instanceof McpError
                    || current instanceof JsonProcessingException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String unavailableReason(RuntimeException failure) {
        if (hasDefinitiveProtocolCause(failure)
                || failure instanceof McpTransportException
                        && failure.getCause() == null) {
            return "contract unavailable";
        }
        return "transport unavailable";
    }

    private void ensureOpen() {
        if (closed) {
            throw new McpContractException("MCP gateway is closed");
        }
    }

    private static void invalidateState(ServerState state) {
        Session session = state.session;
        state.session = null;
        state.discovery = List.of();
        if (session != null) {
            session.close();
        }
    }

    private static Session openSdkSession(
            String serverId, McpProperties.Server server, ObjectMapper mapper) {
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper.copy());
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder(server.getBaseUrl())
                        .jsonMapper(jsonMapper)
                        .endpoint(server.getEndpoint())
                        .connectTimeout(server.getConnectTimeout())
                        .httpRequestCustomizer((request, method, endpoint, body, context) -> {
                            copyHeader(context, request, "traceparent");
                            copyHeader(context, request, "tracestate");
                        })
                        .build();
        McpSyncClient client = McpClient.sync(transport)
                .transportContextProvider(OfficialMcpGateway::captureTraceContext)
                .requestTimeout(server.getRequestTimeout())
                .initializationTimeout(server.getRequestTimeout())
                .build();
        return new SdkSession(client);
    }

    private static void copyHeader(
            McpTransportContext context, HttpRequest.Builder request, String name) {
        Object value = context.get(name);
        if (value instanceof String text && !text.isBlank()) {
            request.header(name, text);
        }
    }

    interface SessionFactory {
        Session open(String serverId, McpProperties.Server server, ObjectMapper mapper);
    }

    interface Session extends AutoCloseable {
        void initialize();

        List<RawTool> listTools();

        RawCallResult call(String toolName, Map<String, Object> arguments);

        @Override
        void close();
    }

    record RawTool(String name, String description, Object inputSchema) {}

    record RawContent(boolean textContent, String text) {}

    record RawCallResult(List<RawContent> content, boolean error) {}

    static final class TransportFailureException extends RuntimeException {
        TransportFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ServerState {
        private Session session;
        private List<McpRemoteTool> discovery = List.of();
    }

    private static final class SdkSession implements Session {
        private final McpSyncClient client;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SdkSession(McpSyncClient client) {
            this.client = client;
        }

        @Override
        public void initialize() {
            client.initialize();
        }

        @Override
        public List<RawTool> listTools() {
            return client.listTools().tools().stream()
                    .map(tool -> new RawTool(tool.name(), tool.description(), tool.inputSchema()))
                    .toList();
        }

        @Override
        public RawCallResult call(String toolName, Map<String, Object> arguments) {
            var result = client.callTool(CallToolRequest.builder(toolName)
                    .arguments(Map.copyOf(arguments))
                    .build());
            return new RawCallResult(
                    result.content().stream()
                            .map(content -> content instanceof TextContent text
                                    ? new RawContent(true, text.text())
                                    : new RawContent(false, content.type()))
                            .toList(),
                    Boolean.TRUE.equals(result.isError()));
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                client.close();
            }
        }
    }
}
