package com.reagent.core;

import java.util.Objects;

/** Test/demo-chaos signal that models process disappearance rather than task failure. */
public final class InjectedWorkerCrashException extends RuntimeException {

    private final FaultPoint point;
    private final FaultContext faultContext;

    public InjectedWorkerCrashException(FaultPoint point, FaultContext faultContext) {
        super("Injected worker crash at " + Objects.requireNonNull(point, "point")
                + " for task " + Objects.requireNonNull(faultContext, "faultContext").taskId());
        this.point = point;
        this.faultContext = faultContext;
    }

    public FaultPoint point() {
        return point;
    }

    public FaultContext faultContext() {
        return faultContext;
    }
}
