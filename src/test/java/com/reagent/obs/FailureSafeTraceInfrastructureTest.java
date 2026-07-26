package com.reagent.obs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailureSafeTraceInfrastructureTest {

    @Test
    void ownedStarterClosesAHandleWhenStartThrowsAndSuppressesCleanupFailure() {
        List<String> events = new ArrayList<>();
        AssertionError startup = new AssertionError("jaeger start failed");
        IllegalStateException cleanup =
                new IllegalStateException("jaeger cleanup failed");

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> FailureSafeTraceInfrastructure.startOwned(
                        () -> {
                            events.add("jaeger.construct");
                            return "jaeger";
                        },
                        jaeger -> {
                            events.add("jaeger.start:" + jaeger);
                            throw startup;
                        },
                        jaeger -> {
                            events.add("jaeger.close:" + jaeger);
                            throw cleanup;
                        }));

        assertSame(startup, actual);
        assertEquals(
                List.of(
                        "jaeger.construct",
                        "jaeger.start:jaeger",
                        "jaeger.close:jaeger"),
                events);
        assertArrayEquals(new Throwable[]{cleanup}, actual.getSuppressed());
    }

    @Test
    void startupThrowableClosesLocalPythonAndJaegerAndKeepsCleanupFailuresSuppressed() {
        List<String> events = new ArrayList<>();
        AssertionError startup = new AssertionError("readiness failed");
        IllegalStateException pythonCleanup =
                new IllegalStateException("python cleanup failed");
        IllegalStateException jaegerCleanup =
                new IllegalStateException("jaeger cleanup failed");

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> FailureSafeTraceInfrastructure.start(
                        () -> {
                            events.add("jaeger.start");
                            return "jaeger";
                        },
                        jaeger -> {
                            events.add("python.launch:" + jaeger);
                            return "python";
                        },
                        (jaeger, python) -> {
                            events.add("readiness:" + jaeger + ":" + python);
                            throw startup;
                        },
                        python -> {
                            events.add("python.close:" + python);
                            throw pythonCleanup;
                        },
                        jaeger -> {
                            events.add("jaeger.close:" + jaeger);
                            throw jaegerCleanup;
                        }));

        assertSame(startup, actual);
        assertEquals(
                List.of(
                        "jaeger.start",
                        "python.launch:jaeger",
                        "readiness:jaeger:python",
                        "python.close:python",
                        "jaeger.close:jaeger"),
                events);
        assertArrayEquals(
                new Throwable[]{pythonCleanup, jaegerCleanup},
                actual.getSuppressed());
    }
}
