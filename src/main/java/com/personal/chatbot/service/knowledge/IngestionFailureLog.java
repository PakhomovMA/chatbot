package com.personal.chatbot.service.knowledge;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Bounded ring of recent ingestion failures for the knowledge-base status (docs/system-plan.md D14).
 *
 * <p>The ingestion worker writes, HTTP threads read; the deque's own monitor keeps the append and
 * the trim that follows it one step, and hands readers a copy (docs/concurrency-plan.md C09).
 */
public class IngestionFailureLog {

    public record Failure(String documentId, String stage, String message, Instant at) {
    }

    public static final int DEFAULT_CAPACITY = 50;

    private final int capacity;
    private final Deque<Failure> failures = new ArrayDeque<>();

    public IngestionFailureLog() {
        this(DEFAULT_CAPACITY);
    }

    public IngestionFailureLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    public void record(Failure failure) {
        synchronized (failures) {
            failures.addFirst(failure);
            while (failures.size() > capacity) {
                failures.removeLast();
            }
        }
    }

    /** Newest first. */
    public List<Failure> recent() {
        synchronized (failures) {
            return List.copyOf(failures);
        }
    }
}
