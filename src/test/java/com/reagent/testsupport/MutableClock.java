package com.reagent.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {

    private final AtomicReference<Instant> current;

    public MutableClock(Instant initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        current.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) {
            throw new IllegalArgumentException("MutableClock only supports UTC");
        }
        return this;
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
