package com.personal.chatbot.service.lifecycle;

import java.time.Duration;

/**
 * Work the application runs on threads of its own — the ingestion worker, the threads behind
 * streaming chat, the senders of open SSE connections — which has to be quiet before the resources
 * it uses are closed (docs/concurrency-plan.md C08). A Lucene store or a native embedding session
 * closed under an active call does not throw; it takes the process with it.
 *
 * <p>Stopping is three idempotent steps, driven by {@link ShutdownSequence}: refuse new work and ask
 * what is running to stop, wait for it, and interrupt only what did not stop by itself.
 */
public interface ActiveWork {

    /** Name of this work in the shutdown log. */
    String name();

    /** Refuses new work and asks what is already running to stop. Called once, but must be idempotent. */
    void stopAccepting();

    /**
     * Waits for the work in flight, at most {@code timeout}; a non-positive timeout only checks.
     *
     * @return true when nothing of this work is running any more
     */
    boolean awaitQuiet(Duration timeout);

    /** Interrupts what is still running; called only after {@link #awaitQuiet} ran out of time. */
    void interruptActive();
}
