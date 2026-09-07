package com.personal.chatbot.service.knowledge;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The single-writer work queue in front of the index (docs/system-plan.md §5, INV-11): one worker
 * thread, at most one document in flight, no duplicate entries. Knows nothing about documents beyond
 * their id — what happens to a claimed id is the processor's business.
 */
public class IngestionQueue implements AutoCloseable {

    /** Queue depth and the document currently being processed, if any. */
    public record Status(int pending, @Nullable String activeDocumentId) {
    }

    private final Consumer<String> processor;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("ingestion-worker").daemon(true).factory());
    private final Set<String> queued = new LinkedHashSet<>();
    private final AtomicReference<@Nullable String> active = new AtomicReference<>();

    /** @param processor invoked on the worker thread once an id has been claimed */
    public IngestionQueue(Consumer<String> processor) {
        this.processor = processor;
    }

    /** @return false if the id is already queued or in flight */
    public boolean enqueue(String documentId) {
        synchronized (queued) {
            if (documentId.equals(active.get()) || !queued.add(documentId)) {
                return false;
            }
        }
        worker.submit(() -> claimAndRun(documentId));
        return true;
    }

    /** Withdraws an id that has not been claimed yet; a document already in flight is unaffected. */
    public void dequeue(String documentId) {
        synchronized (queued) {
            queued.remove(documentId);
        }
    }

    /** Drops everything still waiting; used before a full index rebuild. */
    public void clear() {
        synchronized (queued) {
            queued.clear();
        }
    }

    public Status status() {
        synchronized (queued) {
            return new Status(queued.size(), active.get());
        }
    }

    private void claimAndRun(String documentId) {
        synchronized (queued) {
            if (!queued.remove(documentId)) {
                return; // withdrawn between submission and execution
            }
            active.set(documentId);
        }
        try {
            processor.accept(documentId);
        } finally {
            active.set(null);
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
