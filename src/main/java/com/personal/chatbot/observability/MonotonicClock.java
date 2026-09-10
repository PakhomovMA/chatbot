package com.personal.chatbot.observability;

import java.time.Duration;

/**
 * The elapsed-time source of the observability layer (docs/observability-plan.md §4.1). Durations
 * are measured against a monotonic reading and never against wall-clock time, which a clock
 * adjustment can move backwards; {@link java.time.Clock} stays what it is for — the time an event
 * happened, not how long it took.
 *
 * <p>Injected rather than called statically, so a test can advance time by a known amount instead of
 * sleeping for it. Only differences between readings mean anything.
 */
@FunctionalInterface
public interface MonotonicClock {

    /** The system's monotonic reading; the production source. */
    MonotonicClock SYSTEM = System::nanoTime;

    long nanoTime();

    /** How long ago {@code reading} was taken, never negative. */
    default Duration since(long reading) {
        return Duration.ofNanos(Math.max(0, nanoTime() - reading));
    }
}
