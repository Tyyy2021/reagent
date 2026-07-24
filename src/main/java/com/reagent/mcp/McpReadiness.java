package com.reagent.mcp;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public final class McpReadiness {

    private static final State NOT_DISCOVERED =
            new State(false, 0, Set.of(), "not discovered");
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public State state(String serverId) {
        return states.getOrDefault(serverId, NOT_DISCOVERED);
    }

    void recordReady(String serverId, Set<String> toolNames) {
        Set<String> names = Set.copyOf(new LinkedHashSet<>(toolNames));
        states.put(serverId, new State(true, names.size(), names, ""));
    }

    void recordUnavailable(String serverId, String reason) {
        states.put(serverId, new State(false, 0, Set.of(), bounded(reason)));
    }

    private static String bounded(String reason) {
        String safe = reason == null || reason.isBlank() ? "unavailable" : reason;
        return safe.length() <= 160 ? safe : safe.substring(0, 160);
    }

    public record State(boolean ready, int toolCount, Set<String> toolNames, String reason) {
        public State {
            toolNames = Set.copyOf(toolNames);
        }
    }
}
