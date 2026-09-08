package com.personal.chatbot.service.lifecycle;

import org.springframework.context.SmartLifecycle;

/**
 * Runs the {@link ShutdownSequence} as the first thing that stops when the context closes
 * (docs/concurrency-plan.md C08).
 *
 * <p>The phase matters: stopping happens from the highest phase down, and Spring Boot's graceful web
 * shutdown sits at {@code Integer.MAX_VALUE - 1024}. Running above it means the chat requests are
 * cancelled and the open event streams are ended before the server starts draining, so a browser
 * left on the page does not hold the shutdown for the whole grace period. Bean destruction, which
 * closes the index and the embedding backend, follows once this returns.
 */
public class ApplicationShutdown implements SmartLifecycle {

    private final ShutdownSequence sequence;
    private volatile boolean running;

    public ApplicationShutdown(ShutdownSequence sequence) {
        this.sequence = sequence;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        sequence.stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return DEFAULT_PHASE;
    }
}
