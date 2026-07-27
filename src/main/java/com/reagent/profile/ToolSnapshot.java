package com.reagent.profile;

import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persisted, immutable tool metadata used by one task for its entire lifetime. */
public record ToolSnapshot(
        String name,
        String displayName,
        String description,
        Map<String, Object> parameterSchema,
        String schemaHash,
        IdempotencyClass idempotencyClass,
        ApprovalPolicy approvalPolicy,
        long timeoutMs,
        String provider
) {
    public ToolSnapshot {
        name = Objects.requireNonNull(name, "name");
        displayName = Objects.requireNonNull(displayName, "displayName");
        description = Objects.requireNonNull(description, "description");
        parameterSchema = immutableJsonMap(parameterSchema);
        schemaHash = Objects.requireNonNull(schemaHash, "schemaHash");
        idempotencyClass = Objects.requireNonNull(idempotencyClass, "idempotencyClass");
        approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must be non-negative");
        }
        provider = Objects.requireNonNull(provider, "provider");
    }

    private static Map<String, Object> immutableJsonMap(Map<?, ?> source) {
        Objects.requireNonNull(source, "parameterSchema");
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException("JSON object key must be a string: " + key);
            }
            copy.put(stringKey, immutableJsonValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return immutableJsonMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableJsonValue(item)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Unsupported JSON Schema value: " + value.getClass().getName());
    }
}
