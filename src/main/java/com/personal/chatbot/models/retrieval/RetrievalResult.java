package com.personal.chatbot.models.retrieval;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of a search; also the retrieval trace kept for diagnostics (docs/system-plan.md §4, D14).
 *
 * @param traceId            id under which this result is retained by the trace store
 * @param evidenceSufficient whether the best vector match clears the calibrated sufficiency floor
 * @param maxVectorScore     best cosine among the hits, or -1 when the vector facet did not run
 * @param candidates         how many candidates each facet was asked for before fusion
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
        Instant at
) {
}
