package com.personal.chatbot.models.agent;

import java.util.ArrayList;
import java.util.List;

/** Request-owned cache writes. An unsuccessful run simply drops these with its blackboard input. */
public final class PendingDerivations {
    private final List<Runnable> writes = new ArrayList<>();

    public synchronized void add(Runnable write) {
        writes.add(write);
    }

    /** Called only after answer verification and the final cancellation check. */
    public synchronized void commit() {
        writes.forEach(Runnable::run);
        writes.clear();
    }
}
