package com.personal.chatbot.observability;

import java.util.Locale;

/**
 * The parts of a retrieval pass that are worth timing apart
 * (docs/observability/metric-catalog.json, {@code chatbot.retrieval.stage}).
 *
 * <p>A facet that does not run for a mode publishes nothing: a text-only search has no vector stage,
 * and a zero is not the same statement as an absence.
 */
public enum RetrievalStage {

    /** k-NN over the index, including the wait for the read lock and the embedding of the query. */
    VECTOR,

    /** BM25 over the index, including the wait for the read lock. */
    TEXT,

    /** Fusing the facets and widening the surviving hits with their neighbours. */
    POSTPROCESS;

    private final String label = name().toLowerCase(Locale.ROOT);

    public String label() {
        return label;
    }
}
