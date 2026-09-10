package com.personal.chatbot.models.agent;

import com.personal.chatbot.exceptions.ChatCancelledException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one completion signal of a chat request. A client that disconnects, the stream timeout, an
 * overflowing send buffer and application shutdown all end up here, and only the first of them
 * counts — the reason is recorded once and later attempts are ignored.
 *
 * <p>Two ways to observe it, because polling alone is not enough: agent actions call
 * {@link #isCancelled()} before work that is pointless for a caller who has left, while a reactive
 * pipeline registers an {@link #onCancel} listener, so a model that has gone quiet is dropped
 * without waiting for a next token. Listeners run on the cancelling thread and must be cheap.
 *
 * <p>The listener list has a monitor of its own, held only while the list is taken over or added to
 * (docs/concurrency-plan.md C09): the invariant is that every listener runs exactly once — by
 * {@link #cancel} if it was registered in time, by {@link #onCancel} itself if it was not.
 */
public final class ChatCancellation {

    private static final Logger log = LoggerFactory.getLogger(ChatCancellation.class);

    /** For callers that cannot be abandoned; cancelling it still works, nobody just ever does. */
    public static ChatCancellation none() {
        return new ChatCancellation();
    }

    public enum Cause { TIMEOUT, SHUTDOWN, OVERFLOW, CLIENT_DISCONNECT, REJECTED, UNKNOWN }

    private record Cancelled(Cause cause, String detail) { }
    private final AtomicReference<Cancelled> reason = new AtomicReference<>();
    private final List<Runnable> listeners = new ArrayList<>();

    /** Ends the request. The first reason wins; repeated calls do nothing. */
    public void cancel(String why) {
        cancel(Cause.UNKNOWN, why);
    }

    public void cancel(Cause cause, String why) {
        if (!reason.compareAndSet(null, new Cancelled(cause, why))) {
            return;
        }
        List<Runnable> waiting;
        synchronized (listeners) {
            waiting = List.copyOf(listeners);
            listeners.clear();
        }
        waiting.forEach(this::notifyQuietly);
    }

    public boolean isCancelled() {
        return reason.get() != null;
    }

    /** Why the request ended, or null while it is still wanted. */
    public @Nullable String reason() {
        Cancelled state = reason.get();
        return state == null ? null : state.detail();
    }

    /** Bounded cancellation state for measurements; never parse a human explanation into labels. */
    public @Nullable String telemetryReason() {
        Cancelled state = reason.get();
        return state == null ? null : state.cause().name();
    }

    /** Runs {@code action} once the request is cancelled — immediately if it already was. */
    public void onCancel(Runnable action) {
        boolean cancelledAlready;
        synchronized (listeners) {
            cancelledAlready = isCancelled();
            if (!cancelledAlready) {
                listeners.add(action);
            }
        }
        if (cancelledAlready) {
            notifyQuietly(action);
        }
    }

    /** Stops the current work when the caller is gone; a no-op otherwise. */
    public void abortIfCancelled(String messageId) {
        if (isCancelled()) {
            throw new ChatCancelledException(messageId, reason());
        }
    }

    private void notifyQuietly(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException e) {
            log.warn("Cancellation listener failed: {}", e.toString());
        }
    }
}
