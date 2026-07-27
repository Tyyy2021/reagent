package com.reagent.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.TaskRunToken;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingToolTest {

    @Test
    void exposesConfiguredSafetyMetadataResultAndCallRecording(@TempDir Path workspace) throws Exception {
        RecordingTool tool = new RecordingTool(
                "recording", IdempotencyClass.IDEMPOTENT,
                ApprovalPolicy.REQUIRE_APPROVAL, "configured-result");
        ToolContext context = new ToolContext(
                new TaskRunToken("task", "worker", 3), workspace).forCall("call-1");

        String result = tool.execute(new ObjectMapper().readTree("{\"value\":1}"), context);

        assertEquals("configured-result", result);
        assertEquals(1, tool.callCount());
        assertSame(context, tool.lastContext().orElseThrow());
        assertEquals(IdempotencyClass.IDEMPOTENT, tool.idempotency());
        assertEquals(ApprovalPolicy.REQUIRE_APPROVAL, tool.approvalPolicy());
    }

    @Test
    void optionalGateSignalsEntryAndReleasesWithoutFixedSleep(@TempDir Path workspace) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingTool tool = new RecordingTool(
                "gated", IdempotencyClass.READ_ONLY, ApprovalPolicy.NONE, "released")
                .withGate(entered, release, Duration.ofSeconds(2));
        ToolContext context = new ToolContext("task", workspace).forCall("call-gated");

        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> execute(tool, context));

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertFalse(result.isDone());
        assertEquals(1, tool.callCount());
        release.countDown();
        assertEquals("released", result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void gateWaitIsBoundedAndFailsWhenReleaseNeverArrives(@TempDir Path workspace) {
        RecordingTool tool = new RecordingTool(
                "bounded", IdempotencyClass.READ_ONLY, ApprovalPolicy.NONE, "never")
                .withGate(new CountDownLatch(1), new CountDownLatch(1), Duration.ofMillis(25));
        ToolContext context = new ToolContext("task", workspace).forCall("call-bounded");

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> execute(tool, context));

        assertTrue(error.getMessage().contains("timed out"));
        assertEquals(1, tool.callCount());
    }

    private static String execute(RecordingTool tool, ToolContext context) {
        try {
            return tool.execute(new ObjectMapper().createObjectNode(), context);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
