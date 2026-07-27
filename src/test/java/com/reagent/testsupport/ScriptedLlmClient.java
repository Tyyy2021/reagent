package com.reagent.testsupport;

import com.reagent.core.Context;
import com.reagent.core.Decision;
import com.reagent.llm.LlmClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Strict deterministic LLM used by runtime scenarios; every expected turn must be consumed exactly once. */
public final class ScriptedLlmClient implements LlmClient {

    private final Deque<Turn> turns;
    private final AtomicInteger callCount = new AtomicInteger();

    public ScriptedLlmClient(List<Turn> turns) {
        Objects.requireNonNull(turns, "turns");
        this.turns = new ArrayDeque<>(turns);
    }

    public static Turn turn(Set<String> allowedToolNames, Decision decision) {
        return new Turn(allowedToolNames, Optional.empty(), decision);
    }

    public static Turn turn(
            Set<String> allowedToolNames,
            Predicate<List<Map<String, Object>>> persistedContextPredicate,
            Decision decision
    ) {
        return new Turn(allowedToolNames, Optional.of(persistedContextPredicate), decision);
    }

    @Override
    public Decision chat(Context context, List<Map<String, Object>> toolSpecs) {
        return consume(context, toolSpecs);
    }

    @Override
    public Decision chatStream(
            Context context,
            List<Map<String, Object>> toolSpecs,
            Consumer<String> onToken
    ) {
        Objects.requireNonNull(onToken, "onToken");
        return consume(context, toolSpecs);
    }

    public int callCount() {
        return callCount.get();
    }

    public synchronized void assertExhausted() {
        if (!turns.isEmpty()) {
            throw new AssertionError("Missing " + turns.size() + " scripted LLM turn(s)");
        }
    }

    private synchronized Decision consume(Context context, List<Map<String, Object>> toolSpecs) {
        Objects.requireNonNull(context, "context");
        int invocation = callCount.incrementAndGet();
        Turn turn = turns.pollFirst();
        if (turn == null) {
            throw new AssertionError("Unexpected extra LLM turn " + invocation);
        }
        Set<String> actualTools = exactToolNames(toolSpecs);
        if (!turn.allowedToolNames().equals(actualTools)) {
            throw new AssertionError(
                    "Unexpected allowed tool set on turn " + invocation
                            + ": expected " + turn.allowedToolNames() + ", actual " + actualTools);
        }
        List<Map<String, Object>> messages = context.messageSnapshot();
        if (turn.persistedContextPredicate().isPresent()
                && !turn.persistedContextPredicate().orElseThrow().test(messages)) {
            throw new AssertionError("Persisted context predicate rejected LLM turn " + invocation);
        }
        return turn.decision();
    }

    private static Set<String> exactToolNames(List<Map<String, Object>> toolSpecs) {
        Objects.requireNonNull(toolSpecs, "toolSpecs");
        Set<String> names = new HashSet<>();
        for (Map<String, Object> spec : toolSpecs) {
            if (spec == null || !"function".equals(spec.get("type"))) {
                throw new AssertionError("Unexpected tool schema: type must be function");
            }
            Object functionValue = spec.get("function");
            if (!(functionValue instanceof Map<?, ?> function)) {
                throw new AssertionError("Unexpected tool schema: function object is required");
            }
            Object nameValue = function.get("name");
            if (!(nameValue instanceof String name) || name.isBlank()) {
                throw new AssertionError("Unexpected tool schema: non-blank function name is required");
            }
            if (!(function.get("parameters") instanceof Map<?, ?>)) {
                throw new AssertionError("Unexpected tool schema for " + name + ": parameters object is required");
            }
            if (!names.add(name)) {
                throw new AssertionError("Unexpected tool schema: duplicate function name " + name);
            }
        }
        return Set.copyOf(names);
    }

    public record Turn(
            Set<String> allowedToolNames,
            Optional<Predicate<List<Map<String, Object>>>> persistedContextPredicate,
            Decision decision
    ) {
        public Turn {
            allowedToolNames = Set.copyOf(Objects.requireNonNull(allowedToolNames, "allowedToolNames"));
            persistedContextPredicate = Objects.requireNonNull(
                    persistedContextPredicate, "persistedContextPredicate");
            decision = Objects.requireNonNull(decision, "decision");
        }
    }
}
