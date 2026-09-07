package com.personal.chatbot.support;

import com.embabel.agent.rag.model.NavigableDocument;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.service.parsing.DocumentParser;

import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the ingestion worker inside the parse stage until the test lets it go, so concurrency tests
 * can put a document reliably "in flight" without sleeping. The parse stage is the pause point on
 * purpose: it runs before the index write lock is taken, so the test thread stays free to upload,
 * replace, delete or rebuild meanwhile.
 */
public final class PausingDocumentParser extends DocumentParser {

    private static final long TIMEOUT_SECONDS = 20;

    private final Semaphore entered = new Semaphore(0);
    private final Semaphore released = new Semaphore(0);
    private final AtomicInteger pausesLeft = new AtomicInteger();
    private final AtomicInteger parses = new AtomicInteger();

    /** Arms the next {@code count} parses to block until {@link #resume()} is called for each. */
    public void pauseNext(int count) {
        pausesLeft.set(count);
    }

    /** Blocks until an armed parse has actually started. */
    public void awaitParsing() {
        acquire(entered);
    }

    /** Lets one paused parse continue. */
    public void resume() {
        released.release();
    }

    /** How many parses have run to completion of the pause handshake. */
    public int parses() {
        return parses.get();
    }

    @Override
    public NavigableDocument parse(Document document, Path original) {
        parses.incrementAndGet();
        if (pausesLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
            entered.release();
            acquire(released);
        }
        return super.parse(document, original);
    }

    private static void acquire(Semaphore semaphore) {
        try {
            if (!semaphore.tryAcquire(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the paused parse stage");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the paused parse stage", e);
        }
    }
}
