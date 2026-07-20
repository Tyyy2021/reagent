package com.reagent.core;

import com.reagent.config.FaultConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FaultInjectionTest {

    @Test
    void exposesExactlyTheFiveStableRoadmapFaultPoints() {
        assertEquals(List.of(
                        "AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL",
                        "AFTER_APPROVAL_DECIDED_BEFORE_RESUME",
                        "AFTER_TOOL_MARKED_IN_PROGRESS",
                        "AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT",
                        "AFTER_TOOL_RESULT_PERSISTED_BEFORE_FINAL_ANSWER"),
                Arrays.stream(FaultPoint.values()).map(Enum::name).toList());
    }

    @Test
    void faultContextIsImmutableAndRejectsInvalidRunIdentityOrOptionalValues() {
        FaultContext context = new FaultContext(
                "task-1", "worker-a", 7,
                Optional.of("call-1"), Optional.of(2));

        assertEquals("task-1", context.taskId());
        assertEquals("worker-a", context.workerId());
        assertEquals(7, context.leaseEpoch());
        assertEquals(Optional.of("call-1"), context.toolCallId());
        assertEquals(Optional.of(2), context.batchSequence());
        assertThrows(IllegalArgumentException.class,
                () -> new FaultContext(" ", "worker-a", 0, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new FaultContext("task", "\t", 0, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new FaultContext("task", "worker", -1, Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new FaultContext("task", "worker", 0, null, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new FaultContext("task", "worker", 0, Optional.empty(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new FaultContext("task", "worker", 0, Optional.of(" "), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new FaultContext("task", "worker", 0, Optional.empty(), Optional.of(-1)));
    }

    @Test
    void productionConfigurationProvidesNoOpOnlyWhenNoInjectorWasSupplied() {
        new ApplicationContextRunner()
                .withUserConfiguration(FaultConfiguration.class)
                .run(context -> {
                    FaultInjector injector = context.getBean(FaultInjector.class);
                    assertDoesNotThrow(() -> injector.hit(
                            FaultPoint.AFTER_TOOL_MARKED_IN_PROGRESS,
                            new FaultContext("task", "worker", 0, Optional.empty(), Optional.empty())));
                });

        FaultInjector supplied = (point, context) -> { };
        new ApplicationContextRunner()
                .withBean(FaultInjector.class, () -> supplied)
                .withUserConfiguration(FaultConfiguration.class)
                .run(context -> assertSame(supplied, context.getBean(FaultInjector.class)));
    }

    @Test
    void injectedCrashCarriesTheExactBoundaryAndContext() {
        FaultContext context = new FaultContext(
                "task", "worker", 1, Optional.of("call"), Optional.empty());

        InjectedWorkerCrashException crash = new InjectedWorkerCrashException(
                FaultPoint.AFTER_TOOL_MARKED_IN_PROGRESS, context);

        assertSame(FaultPoint.AFTER_TOOL_MARKED_IN_PROGRESS, crash.point());
        assertSame(context, crash.faultContext());
    }
}
