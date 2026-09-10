package com.personal.chatbot.observability;

import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.exceptions.ServiceStoppingException;
import com.personal.chatbot.utils.Throwables;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * How a measured operation ended (docs/observability/metric-catalog.json, {@code outcomes}). The
 * vocabulary is closed on purpose: an outcome is a label, and a free-form reason or exception message
 * must never become one (docs/observability-plan.md §5.3).
 *
 * <p>{@link #FALLBACK} belongs to {@code chatbot.ai.operation} alone: a best-effort branch whose model
 * call failed and whose caller carried on without it did not fail the request, and the chat run above
 * it is still a success (§5.2). The provider attempt underneath keeps its own error.
 */
public enum Outcome {

    SUCCESS,
    ERROR,
    CANCELLED,
    TIMEOUT,
    REJECTED,
    SKIPPED,
    /** A recovered failure of a best-effort AI operation; the caller answered without it. */
    FALLBACK;

    private final String label = name().toLowerCase(Locale.ROOT);

    public String label() {
        return label;
    }

    /**
     * The outcome an exception ends in. A cancelled request is not an error, and neither is a refusal
     * to accept new work while the application is stopping; both would otherwise turn a shutdown into
     * a wall of failures.
     *
     * @param cancellationReason why the request was abandoned, when it is known; a request stopped by
     *                           its own deadline is a {@link #TIMEOUT} rather than a plain cancel
     */
    public static Outcome of(Throwable error, @Nullable String cancellationReason) {
        if (abandoned(error, cancellationReason)) {
            return Cancellations.outcomeOf(cancellationReason);
        }
        return Throwables.anyCauseIs(error, ServiceStoppingException.class) ? REJECTED : ERROR;
    }

    /**
     * Whether the request behind {@code error} was given up on rather than broken. A caller that
     * already knows the request was abandoned says so with a reason: the failure then reported by a
     * model call is a consequence of that, not the cause of it.
     */
    static boolean abandoned(Throwable error, @Nullable String cancellationReason) {
        return cancellationReason != null
                || ChatCancelledException.isCancellation(error)
                || Throwables.anyCauseIs(error, InterruptedException.class);
    }
}
