package com.personal.chatbot.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Sends what is already recorded, once, on the way out (docs/observability-plan.md §8.3).
 *
 * <p>The phase is the point. It is below the ordered stop of the application's own work and below
 * Boot's graceful drain, so the spans of the requests that were still running are recorded before
 * this runs; and it is above bean destruction, which is where the SDK is closed. Between the two
 * there is exactly one flush, rather than one after every request.
 *
 * <p>The wait is bounded. A collector that is not there must not turn a shutdown into a hang: the
 * flush is given its timeout, and what did not leave in it is reported and dropped. Telemetry is
 * never a reason for the process to stay up (§3.1 rule 6).
 */
final class TelemetryFlush implements SmartLifecycle {

    /** Below Boot's graceful web shutdown, which sits at {@code Integer.MAX_VALUE - 1024}. */
    private static final int PHASE = Integer.MAX_VALUE - 2048;

    private static final Logger log = LoggerFactory.getLogger(TelemetryFlush.class);

    private final Supplier<@Nullable SdkTracerProvider> tracing;
    private final Duration timeout;
    private volatile boolean running;

    TelemetryFlush(Supplier<@Nullable SdkTracerProvider> tracing, Duration timeout) {
        this.tracing = tracing;
        this.timeout = timeout;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        SdkTracerProvider provider = tracing.get();
        if (provider == null) {
            return;
        }
        try {
            CompletableResultCode flushed = provider.forceFlush().join(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (flushed.isSuccess()) {
                log.info("Telemetry flushed within {}", timeout);
            } else {
                log.warn("Telemetry was not flushed within {}; the spans still in the queue are dropped", timeout);
            }
        } catch (RuntimeException e) {
            log.warn("Telemetry flush failed and is skipped: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
