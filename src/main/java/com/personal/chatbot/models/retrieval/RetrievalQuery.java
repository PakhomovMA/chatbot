package com.personal.chatbot.models.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * A retrieval request. Null options fall back to {@code chatbot.retrieval.*} defaults.
 *
 * @param documentIds      restrict hits to these documents (post-filter with inflated candidate count)
 * @param expandNeighbours chunks pulled in on each side of every hit as continuation context;
 *                         null for the configured default, 0 to switch expansion off for this query
 */
public record RetrievalQuery(
        @NotBlank String query,
        @Nullable @Min(1) @Max(50) Integer topK,
        @Nullable RetrievalMode mode,
        @Nullable Set<String> documentIds,
        @Nullable @Min(0) @Max(5) Integer expandNeighbours
) {

    public RetrievalQuery(String query, @Nullable Integer topK, @Nullable RetrievalMode mode,
                          @Nullable Set<String> documentIds) {
        this(query, topK, mode, documentIds, null);
    }

    public static RetrievalQuery of(String query) {
        return new RetrievalQuery(query, null, null, null, null);
    }

    /** The same request, asked with another query text; used by the expansion passes (Phase 9a). */
    public RetrievalQuery withQuery(String other) {
        return new RetrievalQuery(other, topK, mode, documentIds, expandNeighbours);
    }

    public RetrievalQuery withExpandNeighbours(@Nullable Integer chunksEachSide) {
        return new RetrievalQuery(query, topK, mode, documentIds, chunksEachSide);
    }
}
