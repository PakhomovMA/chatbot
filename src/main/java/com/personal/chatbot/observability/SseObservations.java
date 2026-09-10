package com.personal.chatbot.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the server-sent-events transport measures (docs/observability/metric-catalog.json): how long a
 * write to an emitter takes, how often a connection fell so far behind that it was dropped, and how
 * many are open.
 *
 * <p>A send is timed rather than observed. It measures the write itself and nothing else — it does
 * not say the client received anything — and a token-by-token answer performs one per fragment, so a
 * span each would bury the trace of the request that produced them under hundreds of empty ones. This
 * is the one boundary of the application that is deliberately a plain timer
 * (docs/observability-plan.md §3.1 rule 2 covers the opposite mistake: measuring the same boundary
 * twice).
 *
 * <p>Meters are per stream and created on first use, which is what bounds them: a stream name comes
 * from the application, never from a request.
 */
public final class SseObservations {

    /** One write to an emitter, closed by the caller however the write ended. */
    public final class Send implements AutoCloseable {

        private final Stream stream;
        private final long startedNanos;

        private Send(Stream stream) {
            this.stream = stream;
            this.startedNanos = observations.clock().nanoTime();
        }

        @Override
        public void close() {
            stream.sends.record(observations.clock().since(startedNanos));
        }
    }

    private record Stream(Timer sends, Counter overflows, AtomicInteger open) {
    }

    private final Observations observations;
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();

    public SseObservations(Observations observations) {
        this.observations = observations;
    }

    /** Times one write to the emitter; the write itself belongs to the caller. */
    public Send startSend(String stream) {
        return new Send(stream(stream));
    }

    /**
     * An event that did not fit into a connection's buffer. Counted per event, as before: the check
     * that a connection has finished is not atomic with the producers writing to it, so a connection
     * dropped by one overflowing producer may be counted again by another. It is a count of events,
     * not of connections lost.
     */
    public void overflowed(String stream) {
        stream(stream).overflows().increment();
    }

    /**
     * A connection that has just been opened.
     *
     * @return what its single terminal close calls; running it twice would take the gauge below zero,
     * so the caller has to guarantee there is exactly one
     */
    public Runnable opened(String stream) {
        AtomicInteger open = stream(stream).open();
        open.incrementAndGet();
        return open::decrementAndGet;
    }

    private Stream stream(String name) {
        return streams.computeIfAbsent(name, stream -> {
            AtomicInteger open = new AtomicInteger();
            Gauge.builder("chatbot.sse.connections", open, AtomicInteger::doubleValue)
                    .tag("stream", stream).description("Open server-sent-events connections")
                    .register(observations.meterRegistry());
            return new Stream(
                    observations.timer("chatbot.sse.send", "Writing one event to an emitter", "stream", stream),
                    observations.counter("chatbot.sse.overflows", "Events that did not fit into a send buffer",
                            "stream", stream),
                    open);
        });
    }
}
