package com.personal.chatbot.models.retrieval;

import org.jspecify.annotations.Nullable;

/**
 * One retrieval hit.
 *
 * @param text        the original chunk text ({@code urtext}), suitable for quoting
 * @param vectorScore cosine similarity in [-1, 1] when the vector facet matched, else null
 * @param textScore   BM25 score normalised to [0, 1) when the text facet matched, else null
 * @param fusedScore  score the ranking is based on (RRF in hybrid mode, the facet score otherwise)
 * @param rank        1-based position in the result
 */
public record RetrievedChunk(
        String chunkId,
        String text,
        Provenance provenance,
        @Nullable Double vectorScore,
        @Nullable Double textScore,
        double fusedScore,
        int rank
) {
}
