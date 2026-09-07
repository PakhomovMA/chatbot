package com.personal.chatbot.models.retrieval;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** Where a chunk came from (docs/system-plan.md §4, INV-02). */
public record Provenance(
        String documentId,
        String documentTitle,
        int version,
        String sectionTitle,
        List<String> sectionPath,
        String chunkId,
        int sequenceNumber,
        @Nullable Integer chunkIndex,
        @Nullable Integer totalChunks,
        @Nullable String mediaType
) {
}
