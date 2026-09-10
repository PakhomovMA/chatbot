package com.personal.chatbot.observability;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Normalises why a chat request was abandoned into a bounded category
 * (docs/observability-plan.md §5.2). The reason itself is written for a human — "stream timed out
 * after PT10M", "client closed the stream" — and must not reach a metric label, where every variant
 * would be a series of its own.
 *
 * <p>The categories are read from the reason text because the reason is a plain string at its source
 * today; giving {@code ChatCancellation} a typed reason belongs to the terminal-lifecycle work of
 * O06. What this class guarantees meanwhile is the bound: an unrecognised reason is {@code unknown},
 * never the text itself.
 */
public final class Cancellations {

    /** The categories, in the order they are recognised. */
    public static final String TIMEOUT = "timeout";
    public static final String SHUTDOWN = "shutdown";
    public static final String OVERFLOW = "overflow";
    public static final String CLIENT_DISCONNECT = "client_disconnect";
    public static final String UNKNOWN = "unknown";

    private Cancellations() {
    }

    /** The bounded category of {@code reason}, or {@link #UNKNOWN} when it names none of them. */
    public static String categoryOf(@Nullable String reason) {
        if (reason == null || reason.isBlank()) {
            return UNKNOWN;
        }
        String text = reason.toLowerCase(Locale.ROOT);
        if (text.contains("timed out") || text.contains("timeout")) {
            return TIMEOUT;
        }
        if (text.contains("shutting down") || text.contains("shutdown")) {
            return SHUTDOWN;
        }
        if (text.contains("buffer full")) {
            return OVERFLOW;
        }
        return text.contains("client") || text.contains("stream") ? CLIENT_DISCONNECT : UNKNOWN;
    }

    /**
     * The outcome a cancellation is recorded with: a request stopped by its own deadline is counted
     * as a timeout, everything else as a cancellation. Both stay out of the error ratio.
     */
    public static Outcome outcomeOf(@Nullable String reason) {
        return TIMEOUT.equals(categoryOf(reason)) ? Outcome.TIMEOUT : Outcome.CANCELLED;
    }
}
