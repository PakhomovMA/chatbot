package com.personal.chatbot.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One measured execution boundary (docs/observability-plan.md §3.1): it starts a Micrometer
 * {@link Observation}, carries the labels of the canonical schema and ends exactly once with an
 * {@link Outcome}.
 *
 * <p>The Observation is what publishes the measurement: the standard handlers turn it into a timer
 * and, where tracing is on, into a span. Nothing here records a second timer for the same boundary
 * (§3.1 rule 2), and nothing here decides anything about the request — telemetry that failed must not
 * change what the application answers (rule 6).
 *
 * <p>Use it as a resource:
 *
 * <pre>{@code
 * try (Measured pass = retrieval.startSearch(mode)) {
 *     ...
 *     pass.succeeded();
 * }
 * }</pre>
 *
 * <p>Closing is what publishes the record, so every path through the block ends in exactly one. A
 * block left through an exception nobody reported ends as {@link Outcome#ERROR}: an unaccounted exit
 * is not a success. The scope is opened and closed on the calling thread; work handed to another
 * thread is measured either by a boundary of its own or by one that opens no scope at all
 * ({@link MeasuredOperation.Boundary#HANDED_OVER}).
 */
public final class Measured implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Measured.class);
    private static final Convention CONVENTION = new Convention();

    private final MeasuredOperation operation;
    private final Context context;
    private final Observation observation;
    private final Observation.@Nullable Scope scope;
    private final MonotonicClock clock;
    private final long startedNanos;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long legacyUntilNanos = Long.MIN_VALUE;

    /**
     * @param labels key/value pairs known before the boundary opens. They are set first on purpose:
     *               the standard handler tags the "active" long-task timer with what the context
     *               carries at start, and a label written later would leave it reading {@code none}.
     */
    Measured(ObservationRegistry registry, MonotonicClock clock, MeasuredOperation operation,
             String... labels) {
        this.operation = operation;
        this.clock = clock;
        this.context = new Context(operation);
        for (int i = 0; i + 1 < labels.length; i += 2) {
            context.label(labels[i], labels[i + 1]);
        }
        this.observation = Observation.createNotStarted(operation.metricName(), () -> context, registry)
                .observationConvention(CONVENTION);
        this.startedNanos = clock.nanoTime();
        this.observation.start();
        // A boundary whose two ends are on different threads opens no scope: a scope belongs to the
        // thread that opened it, and closing somebody else's would corrupt theirs (§7.2).
        this.scope = operation.boundary() == MeasuredOperation.Boundary.HANDED_OVER ? null : observation.openScope();
    }

    /** Sets a label of this operation's schema; a key the schema does not declare never reaches a meter. */
    Measured label(String key, String value) {
        context.label(key, value);
        return this;
    }

    /** The operation ended the way it was supposed to. */
    public void succeeded() {
        finished(Outcome.SUCCESS);
    }

    /** Records the first terminal outcome; later ones are ignored, as a boundary ends once. */
    public void finished(Outcome ended) {
        outcome.compareAndSet(null, ended);
    }

    /**
     * The operation was ended by {@code error}. The exception is attached to the observation, so a
     * span carries it, and classified into an outcome: a cancelled or interrupted run is not counted
     * as a failure of the application.
     */
    public void failed(Throwable error) {
        failed(error, null);
    }

    /**
     * @param cancellationReason why the request was abandoned, when the caller knows it; it decides
     *                           between {@code timeout} and {@code cancelled} and is never a label
     *                           itself ({@link Cancellations})
     */
    public void failed(Throwable error, @Nullable String cancellationReason) {
        observation.error(error);
        finished(Outcome.of(error, cancellationReason));
    }

    /**
     * The operation failed and its caller carried on without it: the attempt keeps its error on the
     * span, and the operation is counted as {@link Outcome#FALLBACK} rather than as a failed request
     * (docs/observability-plan.md §5.2).
     */
    public void recovered(Throwable error) {
        recovered(error, null);
    }

    /**
     * The same, for a best-effort branch of a request that may have been abandoned underneath it: a
     * call that failed because nobody is waiting any more ended in that cancellation, not in a
     * fallback the caller chose.
     */
    public void recovered(Throwable error, @Nullable String cancellationReason) {
        observation.error(error);
        finished(Outcome.abandoned(error, cancellationReason)
                ? Outcome.of(error, cancellationReason) : Outcome.FALLBACK);
    }

    /**
     * Restates which AI operation this is, where the branch is only known once it has been taken —
     * a draft the platform turned out to be able to stream is a different operation from one it
     * could not.
     */
    public Measured operation(AiOperation operation) {
        return label(MeasuredOperation.Labels.OPERATION, operation.label());
    }

    /** How long the boundary has been open, by the injected clock. */
    public Duration elapsed() {
        return clock.since(startedNanos);
    }

    /**
     * Closes the narrower window the v1 API diagnostics report for this boundary, where they stop
     * counting before the boundary does (docs/observability-plan.md §4.3). Without it the window
     * ends where the canonical one does.
     */
    public void legacyEnds() {
        legacyUntilNanos = clock.nanoTime();
    }

    /** The v1 diagnostics window so far, for the API projection that still reports it (§4.3). */
    public Duration legacyElapsed() {
        long until = legacyUntilNanos != Long.MIN_VALUE ? legacyUntilNanos : clock.nanoTime();
        return Duration.ofNanos(Math.max(0, until - startedNanos));
    }

    public boolean isFinished() {
        return outcome.get() != null;
    }

    /** Publishes the record. Idempotent: the second call does nothing. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Outcome ended = outcome.get();
        if (ended == null) {
            ended = Outcome.ERROR;
            log.debug("{} was closed without an outcome and is recorded as an error", operation.metricName());
        }
        if (legacyUntilNanos == Long.MIN_VALUE) {
            legacyUntilNanos = clock.nanoTime();
        }
        if (operation.labelsOutcome()) {
            context.label(MeasuredOperation.Labels.OUTCOME, ended.label());
        }
        try {
            if (scope != null) {
                scope.close();
            }
        } finally {
            observation.stop();
        }
    }

    /** What the convention reads: the operation and the labels gathered while it ran. */
    static final class Context extends Observation.Context {

        private final MeasuredOperation operation;
        private final Map<String, String> labels = new ConcurrentHashMap<>();

        Context(MeasuredOperation operation) {
            this.operation = operation;
        }

        void label(String key, String value) {
            labels.put(key, value);
        }

        Map<String, String> labels() {
            return labels;
        }
    }

    /**
     * The one convention of the application's own measurements: it writes every declared key, in the
     * declared order, and refuses anything the schema does not know. Micrometer asks it again when the
     * observation stops, which is how a label the run only learns at the end — an outcome, a grounding
     * — reaches the timer.
     */
    private static final class Convention implements ObservationConvention<Context> {

        @Override
        public boolean supportsContext(Observation.@NonNull Context context) {
            return context instanceof Context;
        }

        @Override
        public @NonNull KeyValues getLowCardinalityKeyValues(Context context) {
            List<KeyValue> values = new ArrayList<>();
            for (String key : context.operation.labelKeys()) {
                values.add(KeyValue.of(key, context.labels.getOrDefault(key, MeasuredOperation.Labels.NONE)));
            }
            if (context.operation.labelsOutcome()) {
                values.add(KeyValue.of(MeasuredOperation.Labels.OUTCOME,
                        context.labels.getOrDefault(MeasuredOperation.Labels.OUTCOME, MeasuredOperation.Labels.NONE)));
            }
            return KeyValues.of(values);
        }
    }
}
