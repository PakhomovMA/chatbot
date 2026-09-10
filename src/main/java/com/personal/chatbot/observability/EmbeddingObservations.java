package com.personal.chatbot.observability;

import com.personal.chatbot.models.embedding.EmbeddingMode;
import io.micrometer.core.instrument.Counter;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What embedding measures (docs/observability/metric-catalog.json): the wait for a batch slot, the
 * batch itself, the texts that came out of a finished request and the batches that broke.
 *
 * <p>Waiting and working are two measurements on purpose. A backend that is busy and a backend that
 * is slow look the same in one timer, and the wait is the half that says whether the concurrency
 * limit is the bottleneck (docs/observability-plan.md §4.1). {@code chatbot.embedding} keeps exactly
 * the labels and the boundary it had before the catalog — no outcome, from the moment capacity is
 * claimed to the end of the {@code finally} that gives it back — so a failed batch is reported as an
 * event of its own instead.
 *
 * <p>One facade instance belongs to one backend: the provider and the model are what that backend
 * says they are, never a string from a request (§5.3).
 */
public final class EmbeddingObservations {

    private final Observations observations;
    private final String provider;
    private final String model;
    private final Counter texts;
    private final Map<EmbeddingMode, Counter> failures = new HashMap<>();

    public EmbeddingObservations(Observations observations, String provider, String model) {
        this.observations = observations;
        this.provider = provider;
        this.model = model;
        this.texts = observations.counter("chatbot.embedding.texts", "Texts embedded by a finished request",
                MeasuredOperation.Labels.PROVIDER, provider);
        for (EmbeddingMode mode : EmbeddingMode.values()) {
            failures.put(mode, observations.counter("chatbot.embedding.failures", "Embedding batches that failed",
                    MeasuredOperation.Labels.MODE, label(mode), MeasuredOperation.Labels.PROVIDER, provider));
        }
    }

    /** Waiting for a free batch slot; a zero wait is measured too, and says the limit was not binding. */
    public Measured startWait(EmbeddingMode mode) {
        return observations.start(MeasuredOperation.EMBEDDING_WAIT, MeasuredOperation.Labels.MODE, label(mode));
    }

    /** One batch, from the moment its capacity is claimed until the backend has finished with it. */
    public Measured startBatch(EmbeddingMode mode) {
        return observations.start(MeasuredOperation.EMBEDDING,
                MeasuredOperation.Labels.MODE, label(mode),
                MeasuredOperation.Labels.PROVIDER, provider,
                MeasuredOperation.Labels.MODEL, model);
    }

    /**
     * A batch that reached the backend and ended in a failure. A call refused before it got there —
     * the service was closing — is not one: nothing was attempted.
     */
    public void batchFailed(EmbeddingMode mode) {
        failures.get(mode).increment();
    }

    /**
     * Texts of a request that embedded all of its batches. A request that failed halfway adds none,
     * as before: its earlier batches produced vectors nobody received.
     */
    public void embedded(int count) {
        texts.increment(count);
    }

    /** The elapsed-time source of the layer, for the durations this backend writes to its log. */
    public MonotonicClock clock() {
        return observations.clock();
    }

    private static String label(EmbeddingMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }
}
