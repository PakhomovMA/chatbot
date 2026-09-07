package com.personal.chatbot.service.embedding;

import com.personal.chatbot.models.embedding.EmbeddingFingerprint;
import com.personal.chatbot.models.embedding.EmbeddingMode;
import com.personal.chatbot.utils.EmbeddingModeScope;
import com.personal.chatbot.utils.EmbeddingPrompts;
import com.personal.chatbot.utils.VectorMath;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Backend-agnostic {@link KnowledgeEmbeddingService}: applies EmbeddingGemma prefixes for the ambient
 * {@link EmbeddingMode}, batches inputs (longest first, to minimise padding), bounds backend
 * concurrency, L2-normalises the result and records Micrometer timings.
 */
public final class PromptedEmbeddingService implements KnowledgeEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(PromptedEmbeddingService.class);

    private final TextEmbedder backend;
    private final int batchSize;
    private final boolean normalize;
    private final Semaphore concurrency;
    private final Map<EmbeddingMode, Timer> timers = new EnumMap<>(EmbeddingMode.class);
    private final Counter textsCounter;
    private volatile Duration warmupDuration;

    public PromptedEmbeddingService(
            TextEmbedder backend,
            int batchSize,
            int maxConcurrentBatches,
            boolean normalize,
            MeterRegistry meterRegistry
    ) {
        if (batchSize < 1 || maxConcurrentBatches < 1) {
            throw new IllegalArgumentException("batchSize and maxConcurrentBatches must be >= 1");
        }
        this.backend = backend;
        this.batchSize = batchSize;
        this.normalize = normalize;
        this.concurrency = new Semaphore(maxConcurrentBatches, true);
        for (EmbeddingMode mode : EmbeddingMode.values()) {
            timers.put(mode, Timer.builder("chatbot.embedding")
                    .description("Time spent embedding one batch")
                    .tag("provider", backend.provider())
                    .tag("model", backend.modelName())
                    .tag("mode", mode.name().toLowerCase())
                    .register(meterRegistry));
        }
        this.textsCounter = Counter.builder("chatbot.embedding.texts")
                .description("Number of texts embedded")
                .tag("provider", backend.provider())
                .register(meterRegistry);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        EmbeddingMode mode = EmbeddingModeScope.current();
        int n = texts.size();
        String[] prefixed = new String[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            prefixed[i] = EmbeddingPrompts.forMode(mode, texts.get(i));
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingInt((Integer i) -> prefixed[i].length()).reversed());

        float[][] result = new float[n][];
        for (int start = 0; start < n; start += batchSize) {
            int end = Math.min(n, start + batchSize);
            List<String> batch = new ArrayList<>(end - start);
            for (int k = start; k < end; k++) {
                batch.add(prefixed[order[k]]);
            }
            List<float[]> vectors = embedBatch(batch, mode);
            for (int k = start; k < end; k++) {
                result[order[k]] = vectors.get(k - start);
            }
        }
        textsCounter.increment(n);
        return Arrays.asList(result);
    }

    private List<float[]> embedBatch(List<String> batch, EmbeddingMode mode) {
        acquire();
        long started = System.nanoTime();
        try {
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
            return out;
        } finally {
            concurrency.release();
            timers.get(mode).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void acquire() {
        try {
            concurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for embedding capacity", e);
        }
    }

    /** Runs one embedding in each mode so the first real request does not pay model warm-up costs. */
    public void warmUp() {
        long started = System.nanoTime();
        EmbeddingModeScope.inMode(EmbeddingMode.DOCUMENT, () -> embed("warm-up document"));
        EmbeddingModeScope.inMode(EmbeddingMode.QUERY, () -> embed("warm-up query"));
        warmupDuration = Duration.ofNanos(System.nanoTime() - started);
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
        try {
            backend.close();
        } catch (Exception e) {
            log.warn("Error closing embedding backend {}: {}", backend.modelName(), e.toString());
        }
    }
}
