package com.reagent.core;

/** Controlled deterministic hook; production uses a no-op implementation. */
@FunctionalInterface
public interface FaultInjector {

    void hit(FaultPoint point, FaultContext context);

    static FaultInjector none() {
        return (point, context) -> { };
    }
}
