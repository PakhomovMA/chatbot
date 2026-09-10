package com.personal.chatbot.service.embedding;

import com.personal.chatbot.models.embedding.EmbeddingFingerprint;
import com.personal.chatbot.models.embedding.EmbeddingMode;
import com.personal.chatbot.observability.EmbeddingObservations;
import com.personal.chatbot.observability.Measured;
import com.personal.chatbot.utils.EmbeddingModeScope;
import com.personal.chatbot.utils.EmbeddingPrompts;
import com.personal.chatbot.utils.VectorMath;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Gatherers;
import java.util.stream.IntStream;

/**
 * Backend-agnostic {@link KnowledgeEmbeddingService}: applies EmbeddingGemma prefixes for the ambient
 * {@link EmbeddingMode}, batches inputs (longest first, to minimise padding), bounds backend
 * concurrency and L2-normalises the result. What it measures — the wait for capacity, the batch, the
 * texts and the failures — it declares to {@link EmbeddingObservations}; no meter is built here.
 *
 * <p>It also guards the backend's lifetime (docs/concurrency-plan.md C08): every backend call is
 * registered, {@link #close()} waits for the calls in flight, and no call may start once closing has
 * begun. An ONNX session closed under a running inference does not throw — it takes the JVM down —
 * and inference ignores interruption, so waiting is the only way to know it is safe. The deadline of
 * that wait is a monotonic reading of its own: it controls execution rather than reporting it, which
 * is why it is not a measurement (docs/observability-plan.md §4.1).
 */
public final class PromptedEmbeddingService implements KnowledgeEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(PromptedEmbeddingService.class);

    /** How long {@link #close()} waits for embeddings in flight before it gives up on the backend. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(10);

    private final TextEmbedder backend;
    private final int batchSize;
    private final boolean normalize;
    private final Semaphore concurrency;
    private final EmbeddingObservations observations;
    private volatile Duration warmupDuration;

    /** Guards {@link #inFlight} and {@link #closing}; held only around bookkeeping and the close itself. */
    private final Object activity = new Object();
    private int inFlight;
    private boolean closing;
    private boolean closed;

    public PromptedEmbeddingService(
            TextEmbedder backend,
            int batchSize,
            int maxConcurrentBatches,
            boolean normalize,
            EmbeddingObservations observations
    ) {
        if (batchSize < 1 || maxConcurrentBatches < 1) {
            throw new IllegalArgumentException("batchSize and maxConcurrentBatches must be >= 1");
        }
        this.backend = backend;
        this.batchSize = batchSize;
        this.normalize = normalize;
        this.concurrency = new Semaphore(maxConcurrentBatches, true);
        this.observations = observations;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        EmbeddingMode mode = EmbeddingModeScope.current();
        List<String> prefixed = texts.stream().map(text -> EmbeddingPrompts.forMode(mode, text)).toList();
        // Longest first so each fixed-size window pads as little as possible; results go back to input order.
        List<List<Integer>> windows = IntStream.range(0, prefixed.size()).boxed()
                .sorted(Comparator.comparingInt((Integer i) -> prefixed.get(i).length()).reversed())
                .gather(Gatherers.windowFixed(batchSize))
                .toList();
        float[][] result = new float[prefixed.size()][];
        for (List<Integer> window : windows) {
            List<float[]> vectors = embedBatch(window.stream().map(prefixed::get).toList(), mode);
            for (int k = 0; k < window.size(); k++) {
                result[window.get(k)] = vectors.get(k);
            }
        }
        // Only a request that embedded every one of its batches; a later failure leaves the vectors of
        // the earlier ones with nobody to receive them, so they were never delivered.
        observations.embedded(prefixed.size());
        return Arrays.asList(result);
    }

    private List<float[]> embedBatch(List<String> batch, EmbeddingMode mode) {
        acquire(mode);
        // The batch is measured from here, as it always was: the wait above is its own measurement,
        // so a busy backend and a slow one no longer look the same.
        try (Measured measured = observations.startBatch(mode)) {
            boolean registered = false;
            try {
                begin();
                registered = true;
                List<float[]> vectors = backend.embed(batch);
                if (vectors.size() != batch.size()) {
                    throw new IllegalStateException("Backend returned " + vectors.size()
                            + " vectors for " + batch.size() + " texts");
                }
                int expected = backend.dimensions();
                List<float[]> out = new ArrayList<>(vectors.size());
                for (float[] vector : vectors) {
                    if (vector.length != expected) {
                        throw new IllegalStateException("Backend returned " + vector.length
                                + " dimensions, expected " + expected);
                    }
                    out.add(normalize ? VectorMath.normalized(vector) : vector.clone());
                }
                measured.succeeded();
                return out;
            } catch (RuntimeException e) {
                // A batch refused before it reached the backend was not attempted, so it did not fail.
                if (registered) {
                    observations.batchFailed(mode);
                }
                measured.failed(e);
                throw e;
            } finally {
                // begin() refuses a call that arrives while closing, so the capacity it claimed has to be
                // given back here rather than by end(). Both happen before the batch stops being measured.
                if (registered) {
                    end();
                }
                concurrency.release();
            }
        }
    }

    /**
     * Registers a call about to reach the backend. Claiming capacity first and registering here means
     * a call that was waiting for capacity while {@link #close()} started never touches the backend.
     */
    private void begin() {
        synchronized (activity) {
            if (closing) {
                throw new IllegalStateException("Embedding service is closing; " + backend.modelName()
                        + " is no longer available");
            }
            inFlight++;
        }
    }

    private void end() {
        synchronized (activity) {
            inFlight--;
            activity.notifyAll();
        }
    }

    /** Waits for a batch slot. A wait of no time at all is measured too: it says the limit was not binding. */
    private void acquire(EmbeddingMode mode) {
        try (Measured wait = observations.startWait(mode)) {
            try {
                concurrency.acquire();
                wait.succeeded();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                IllegalStateException failure =
                        new IllegalStateException("Interrupted while waiting for embedding capacity", e);
                wait.failed(failure);
                throw failure;
            }
        }
    }

    /** Runs one embedding in each mode so the first real request does not pay model warm-up costs. */
    public void warmUp() {
        long started = observations.clock().nanoTime();
        EmbeddingModeScope.inMode(EmbeddingMode.DOCUMENT, () -> embed("warm-up document"));
        EmbeddingModeScope.inMode(EmbeddingMode.QUERY, () -> embed("warm-up query"));
        warmupDuration = observations.clock().since(started);
        log.info("Embedding service warmed up in {} ms: {}", warmupDuration.toMillis(), fingerprint().value());
    }

    @Override
    public int dimensions() {
        return backend.dimensions();
    }

    @Override
    public String provider() {
        return backend.provider();
    }

    @Override
    public String modelName() {
        return backend.modelName();
    }

    @Override
    public EmbeddingFingerprint fingerprint() {
        return new EmbeddingFingerprint(backend.provider(), backend.modelName(), backend.artifactHash(),
                backend.dimensions(), EmbeddingPrompts.PREFIX_VERSION, normalize);
    }

    @Override
    public Optional<Duration> warmupDuration() {
        return Optional.ofNullable(warmupDuration);
    }

    @Override
    public void close() {
        closeWithin(CLOSE_WAIT);
    }

    /**
     * Closes the backend once nothing is embedding any more. Refuses new calls immediately, then
     * waits up to {@code timeout} for the ones in flight; if one is still running the backend is
     * left open — an incomplete stop is a diagnosable state, a native session closed under an active
     * call is not. Idempotent, and safe to retry once the call has finished.
     *
     * @return true if the backend is closed
     */
    public boolean closeWithin(Duration timeout) {
        synchronized (activity) {
            if (closed) {
                return true;
            }
            closing = true;
            long deadline = System.nanoTime() + timeout.toNanos();
            while (inFlight > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(activity, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (inFlight > 0) {
                log.error("{} embedding call(s) still running after {}; leaving {} open rather than closing it"
                        + " while it is in use", inFlight, timeout, backend.modelName());
                return false;
            }
            closed = true;
            try {
                backend.close();
            } catch (Exception e) {
                log.warn("Error closing embedding backend {}: {}", backend.modelName(), e.toString());
            }
            return true;
        }
    }
}
