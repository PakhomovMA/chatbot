package com.personal.chatbot.models.retrieval;

import org.jspecify.annotations.Nullable;

/**
 * One retrieval hit.
 *
 * @param text        the original chunk text ({@code urtext}), suitable for quoting
 * @param vectorScore cosine similarity in [-1, 1] when the vector facet matched, else null
 * @param textScore   BM25 score normalised to [0, 1) when the text facet matched, else null
 * @param fusedScore  score the ranking is based on (RRF in hybrid mode, the facet score otherwise)
 * @param rank        1-based position in the ranking; a neighbour shares the rank of the hit it expands
 * @param neighbourOf chunkId of the hit this chunk was pulled in next to, null for a chunk that matched itself
 */
public record RetrievedChunk(
        String chunkId,
        String text,
        Provenance provenance,
        @Nullable Double vectorScore,
        @Nullable Double textScore,
        double fusedScore,
        int rank,
        @Nullable String neighbourOf
) {

    /** True when the chunk matched the query rather than being continuation context around a match. */
    public boolean isHit() {
        return neighbourOf == null;
    }
}
