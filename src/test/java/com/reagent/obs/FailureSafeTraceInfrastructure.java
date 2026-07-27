package com.reagent.obs;

import java.util.Objects;

final class FailureSafeTraceInfrastructure<Jaeger, Python> implements AutoCloseable {

    private final Jaeger jaeger;
    private final Python python;
    private final Cleanup<Python> pythonCleanup;
    private final Cleanup<Jaeger> jaegerCleanup;

    private FailureSafeTraceInfrastructure(
            Jaeger jaeger,
            Python python,
            Cleanup<Python> pythonCleanup,
            Cleanup<Jaeger> jaegerCleanup
    ) {
        this.jaeger = Objects.requireNonNull(jaeger, "jaeger");
        this.python = Objects.requireNonNull(python, "python");
        this.pythonCleanup = Objects.requireNonNull(pythonCleanup, "pythonCleanup");
        this.jaegerCleanup = Objects.requireNonNull(jaegerCleanup, "jaegerCleanup");
    }

    static <Handle> Handle startOwned(
            Starter<Handle> constructor,
            StartAction<Handle> startAction,
            Cleanup<Handle> cleanup
    ) {
        Objects.requireNonNull(constructor, "constructor");
        Objects.requireNonNull(startAction, "startAction");
        Objects.requireNonNull(cleanup, "cleanup");
        Handle handle = Objects.requireNonNull(constructor.start(), "handle");
        try {
            startAction.start(handle);
            return handle;
        } catch (Throwable startFailure) {
            closeSuppressing(handle, cleanup, startFailure);
            throwUnchecked(startFailure);
            throw new AssertionError("unreachable");
        }
    }

    static <Jaeger, Python> FailureSafeTraceInfrastructure<Jaeger, Python> start(
            Starter<Jaeger> jaegerStarter,
            Launcher<Jaeger, Python> pythonLauncher,
            Readiness<Jaeger, Python> readiness,
            Cleanup<Python> pythonCleanup,
            Cleanup<Jaeger> jaegerCleanup
    ) {
        Jaeger jaeger = null;
        Python python = null;
        try {
            jaeger = Objects.requireNonNull(jaegerStarter.start(), "jaeger");
            python = Objects.requireNonNull(
                    pythonLauncher.launch(jaeger), "python");
            readiness.await(jaeger, python);
            return new FailureSafeTraceInfrastructure<>(
                    jaeger, python, pythonCleanup, jaegerCleanup);
        } catch (Throwable startupFailure) {
            if (python != null) {
                closeSuppressing(python, pythonCleanup, startupFailure);
            }
            if (jaeger != null) {
                closeSuppressing(jaeger, jaegerCleanup, startupFailure);
            }
            throwUnchecked(startupFailure);
            throw new AssertionError("unreachable");
        }
    }

    Jaeger jaeger() {
        return jaeger;
    }

    Python python() {
        return python;
    }

    @Override
    public void close() {
        Throwable failure = null;
        try {
            pythonCleanup.close(python);
        } catch (Throwable pythonFailure) {
            failure = pythonFailure;
        }
        try {
            jaegerCleanup.close(jaeger);
        } catch (Throwable jaegerFailure) {
            if (failure == null) {
                failure = jaegerFailure;
            } else if (failure != jaegerFailure) {
                failure.addSuppressed(jaegerFailure);
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    private static <T> void closeSuppressing(
            T handle,
            Cleanup<T> cleanup,
            Throwable startupFailure
    ) {
        try {
            cleanup.close(handle);
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != startupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Trace infrastructure operation failed", failure);
    }

    @FunctionalInterface
    interface Starter<T> {
        T start();
    }

    @FunctionalInterface
    interface StartAction<T> {
        void start(T handle);
    }

    @FunctionalInterface
    interface Launcher<Jaeger, Python> {
        Python launch(Jaeger jaeger);
    }

    @FunctionalInterface
    interface Readiness<Jaeger, Python> {
        void await(Jaeger jaeger, Python python);
    }

    @FunctionalInterface
    interface Cleanup<T> {
        void close(T handle);
    }
}
