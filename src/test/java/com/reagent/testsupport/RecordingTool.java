package com.reagent.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic Tool with configurable safety metadata, result, call recording, and an optional bounded gate. */
public final class RecordingTool implements Tool {

    private final String name;
    private final IdempotencyClass idempotency;
    private final ApprovalPolicy approvalPolicy;
    private final String result;
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicReference<ToolContext> lastContext = new AtomicReference<>();
    private volatile Gate gate;

    public RecordingTool(
            String name,
            IdempotencyClass idempotency,
            ApprovalPolicy approvalPolicy,
            String result
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        this.name = name;
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.result = Objects.requireNonNull(result, "result");
    }

    public RecordingTool withGate(
            CountDownLatch entered,
            CountDownLatch release,
            Duration timeout
    ) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("gate timeout must be positive");
        }
        this.gate = new Gate(
                Objects.requireNonNull(entered, "entered"),
                Objects.requireNonNull(release, "release"),
                timeout);
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "Deterministic recording tool " + name;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of("type", "object");
    }

    @Override
    public String execute(JsonNode args, ToolContext context) {
        callCount.incrementAndGet();
        lastContext.set(Objects.requireNonNull(context, "context"));
        Gate currentGate = gate;
        if (currentGate != null) {
            currentGate.entered().countDown();
            try {
                if (!currentGate.release().await(currentGate.timeout().toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new IllegalStateException(
                            "RecordingTool gate timed out after " + currentGate.timeout());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("RecordingTool gate was interrupted", exception);
            }
        }
        return result;
    }

    @Override
    public IdempotencyClass idempotency() {
        return idempotency;
    }

    @Override
    public ApprovalPolicy approvalPolicy() {
        return approvalPolicy;
    }

    public int callCount() {
        return callCount.get();
    }

    public Optional<ToolContext> lastContext() {
        return Optional.ofNullable(lastContext.get());
    }

    private record Gate(CountDownLatch entered, CountDownLatch release, Duration timeout) {
    }
}
