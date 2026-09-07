package com.personal.chatbot.models.retrieval;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of a search; also the retrieval trace kept for diagnostics (docs/system-plan.md §4, D14).
 *
 * @param traceId            id under which this result is retained by the trace store
 * @param evidenceSufficient whether the best vector match clears the calibrated sufficiency floor
 * @param maxVectorScore     best cosine among the hits, or -1 when the vector facet did not run
 * @param candidates         how many candidates each facet was asked for before fusion
 * @param expansion          what the second retrieval pass did, or null when there was only one pass
 */
public record RetrievalResult(
        String traceId,
        String query,
        RetrievalMode mode,
        int topK,
        int candidates,
        List<RetrievedChunk> hits,
        boolean evidenceSufficient,
        double maxVectorScore,
        RetrievalTimings timings,
        Instant at,
        @Nullable SearchExpansion expansion
) {

    public RetrievalResult(String traceId, String query, RetrievalMode mode, int topK, int candidates,
                           List<RetrievedChunk> hits, boolean evidenceSufficient, double maxVectorScore,
                           RetrievalTimings timings, Instant at) {
        this(traceId, query, mode, topK, candidates, hits, evidenceSufficient, maxVectorScore, timings, at, null);
    }

    /** Whether this result already merges a widened second pass, so widening it again would only repeat work. */
    public boolean expanded() {
        return expansion != null;
    }
}
