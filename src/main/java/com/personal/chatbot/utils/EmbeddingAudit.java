package com.personal.chatbot.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Counts vectors produced and failures raised by the embedding service within a scope. Embabel's
 * batch embedder swallows exceptions and silently indexes chunks without vectors; the ingestion
 * path runs inside {@link #record(Supplier)} and compares the audit with the number of chunks
 * written (docs/system-plan.md §5.4, INV-09).
 */
public final class EmbeddingAudit {

    private static final ScopedValue<EmbeddingAudit> CURRENT = ScopedValue.newInstance();

    private final AtomicInteger vectors = new AtomicInteger();
    private final List<Throwable> failures = new ArrayList<>();

    private EmbeddingAudit() {
    }

    /** Runs {@code action} with a fresh audit and returns both. */
    public static <T> Result<T> record(Supplier<T> action) {
        EmbeddingAudit audit = new EmbeddingAudit();
        T value = ScopedValue.where(CURRENT, audit).call(action::get);
        return new Result<>(value, audit);
    }

    public static void vectorsProduced(int count) {
        if (CURRENT.isBound()) {
            CURRENT.get().vectors.addAndGet(count);
        }
    }

    public static void failed(Throwable failure) {
        if (CURRENT.isBound()) {
            synchronized (CURRENT.get().failures) {
                CURRENT.get().failures.add(failure);
            }
        }
    }

    public int vectors() {
        return vectors.get();
    }

    public List<Throwable> failures() {
        synchronized (failures) {
            return List.copyOf(failures);
        }
    }

    public record Result<T>(T value, EmbeddingAudit audit) {
    }
}
