package com.reagent.testsupport;

import com.reagent.core.FaultContext;
import com.reagent.core.FaultInjector;
import com.reagent.core.FaultPoint;
import com.reagent.core.InjectedWorkerCrashException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Deterministic one-shot fault gate for Task 11 committed-boundary assertions. */
public final class LatchingFaultInjector implements FaultInjector {

    private final AtomicReference<Arm> active = new AtomicReference<>();

    public Arm arm(FaultPoint point) {
        return arm(point, context -> true);
    }

    public Arm arm(FaultPoint point, Predicate<FaultContext> selector) {
        Arm arm = new Arm(point, selector);
        if (!active.compareAndSet(null, arm)) {
            throw new IllegalStateException("A Task 11 fault gate is already armed");
        }
        return arm;
    }

    public void clear() {
        Arm arm = active.getAndSet(null);
        if (arm != null) {
            arm.release(false);
        }
    }

    @Override
    public void hit(FaultPoint point, FaultContext context) {
        Arm arm = active.get();
        if (arm == null || arm.point != point || !arm.selector.test(context)) {
            return;
        }
        if (!active.compareAndSet(arm, null)) {
            return;
        }
        arm.reachedContext.set(context);
        arm.reached.countDown();
        arm.awaitRelease();
        if (arm.crash.get()) {
            throw new InjectedWorkerCrashException(point, context);
        }
    }

    public static final class Arm {
        private final FaultPoint point;
        private final Predicate<FaultContext> selector;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicReference<FaultContext> reachedContext =
                new AtomicReference<>();
        private final AtomicReference<Boolean> crash =
                new AtomicReference<>(true);

        private Arm(FaultPoint point, Predicate<FaultContext> selector) {
            this.point = Objects.requireNonNull(point, "point");
            this.selector = Objects.requireNonNull(selector, "selector");
        }

        public FaultContext awaitReached(Duration timeout) {
            try {
                if (!reached.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError(
                            "Fault point " + point + " was not reached within " + timeout);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "Interrupted while awaiting fault point " + point,
                        exception);
            }
            return reachedContext.get();
        }

        public void releaseCrash() {
            release(true);
        }

        public void releaseNormally() {
            release(false);
        }

        private void release(boolean shouldCrash) {
            crash.set(shouldCrash);
            released.countDown();
        }

        private void awaitRelease() {
            try {
                if (!released.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError(
                            "Fault point " + point + " was not released within PT30S");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "Interrupted while fault point " + point + " was blocked",
                        exception);
            }
        }
    }
}
