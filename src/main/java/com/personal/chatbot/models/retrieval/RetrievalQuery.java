package com.personal.chatbot.models.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * A retrieval request. Null options fall back to {@code chatbot.retrieval.*} defaults.
 *
 * @param documentIds restrict hits to these documents (post-filter with inflated candidate count)
 */
public record RetrievalQuery(
        @NotBlank String query,
        @Nullable @Min(1) @Max(50) Integer topK,
        @Nullable RetrievalMode mode,
        @Nullable Set<String> documentIds
) {

    public static RetrievalQuery of(String query) {
        return new RetrievalQuery(query, null, null, null);
    }
}
