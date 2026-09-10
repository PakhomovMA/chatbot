package com.personal.chatbot.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;

/**
 * What a capability facade needs to publish measurements, assembled once
 * (docs/observability-plan.md §3.1): the Observation registry that turns a boundary into a timer and
 * a span, the meter registry for the counters and distributions that are events rather than
 * boundaries, the monotonic clock and the adapter for the legacy names.
 *
 * <p>Services depend on a facade, never on this: no {@code MeterRegistry}, {@code Timer.builder} or
 * {@code Tracer} in {@code service}, {@code agents} or {@code models}.
 */
public record Observations(ObservationRegistry observationRegistry, MeterRegistry meterRegistry,
                           MonotonicClock clock, LegacyMetrics legacyMetrics) {

    /**
     * Everything wired around a meter registry of its own, with the standard meter handler, the same
     * label schema as the application and no tracing — what a test or a standalone tool needs to read
     * the meters the application publishes.
     */
    public static Observations standalone(MeterRegistry meters, MonotonicClock clock) {
        meters.config().meterFilter(MeterSchema.labels());
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return new Observations(observations, meters, clock, new LegacyMetrics(meters));
    }

    public static Observations standalone(MeterRegistry meters) {
        return standalone(meters, MonotonicClock.SYSTEM);
    }

    /**
     * Opens a canonical measurement; the caller closes it exactly once.
     *
     * @param labels key/value pairs of the schema that are known already, so that the meter of what is
     *               running right now carries them too
     */
    Measured start(MeasuredOperation operation, String... labels) {
        return new Measured(observationRegistry, clock, legacyMetrics, operation, labels);
    }

    /** A counter of the canonical schema, registered here rather than on a hot path. */
    Counter counter(String name, String description, String... tags) {
        return Counter.builder(name).description(description).tags(tags).register(meterRegistry);
    }
}
