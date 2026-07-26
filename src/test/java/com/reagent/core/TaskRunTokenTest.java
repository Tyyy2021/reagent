package com.reagent.core;

import com.reagent.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskRunTokenTest {

    @Test
    void rejectsBlankTaskId() {
        assertThrows(IllegalArgumentException.class, () -> new TaskRunToken(" ", "worker-a", 0));
    }

    @Test
    void rejectsBlankWorkerId() {
        assertThrows(IllegalArgumentException.class, () -> new TaskRunToken("task-1", "\t", 0));
    }

    @Test
    void rejectsNegativeLeaseEpoch() {
        assertThrows(IllegalArgumentException.class, () -> new TaskRunToken("task-1", "worker-a", -1));
    }

    @Test
    void rejectsZeroLeaseEpoch() {
        assertThrows(IllegalArgumentException.class, () -> new TaskRunToken("task-1", "worker-a", 0));
    }

    @Test
    void mutableClockAdvancesInUtcWithoutSleeping() {
        Instant initial = Instant.parse("2026-07-18T00:00:00Z");
        MutableClock clock = new MutableClock(initial);

        clock.advance(Duration.ofSeconds(30));

        assertEquals(ZoneOffset.UTC, clock.getZone());
        assertEquals(initial.plusSeconds(30), clock.instant());
    }
}
