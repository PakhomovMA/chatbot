package com.personal.chatbot.support;

import com.personal.chatbot.service.embedding.TextEmbedder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A backend that stays inside {@code embed()} until the test lets it out and ignores interruption
 * while it is there, the way a native inference call does. It records whether {@code close()} was
 * ever called while a call was running — the thing that must never happen at shutdown
 * (docs/concurrency-plan.md C08).
 */
public final class BlockingTextEmbedder implements TextEmbedder {

    private static final long TIMEOUT_SECONDS = 20;

    private final int dimensions;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final List<List<String>> batches = new CopyOnWriteArrayList<>();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicBoolean closedWhileRunning = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean interrupted = new AtomicBoolean();

    public BlockingTextEmbedder(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        batches.add(List.copyOf(texts));
        running.incrementAndGet();
        entered.countDown();
        try {
            awaitRelease();
            List<float[]> out = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                float[] vector = new float[dimensions];
                vector[0] = 1.0f;
                out.add(vector);
            }
            return out;
        } finally {
            running.decrementAndGet();
        }
    }

    /** Blocks until an embedding call has actually started. */
    public void awaitEntered() {
        await(entered);
    }

    /** Lets the blocked call finish. */
    public void release() {
        released.countDown();
    }

    public List<List<String>> batches() {
        return batches;
    }

    public boolean closed() {
        return closed.get();
    }

    /** True if the backend was closed while a call was inside it — the failure this guards against. */
    public boolean closedWhileRunning() {
        return closedWhileRunning.get();
    }

    /** True if the blocked call was interrupted; it keeps running regardless, like native inference. */
    public boolean wasInterrupted() {
        return interrupted.get();
    }

    @Override
    public void close() {
        if (running.get() > 0) {
            closedWhileRunning.set(true);
        }
        closed.set(true);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String provider() {
        return "blocking";
    }

    @Override
    public String modelName() {
        return "blocking-embedder";
    }

    @Override
    public String artifactHash() {
        return "000000000000";
    }

    /** Swallows interruption the way a call inside a native session does, restoring the flag at the end. */
    private void awaitRelease() {
        boolean wasInterrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new IllegalStateException("Timed out waiting for the blocked embedding call to be released");
            }
            try {
                if (released.await(left, TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                wasInterrupted = true;
                interrupted.set(true);
            }
        }
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the embedding call to start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the embedding call to start", e);
        }
    }
}
