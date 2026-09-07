package com.personal.chatbot.models.knowledge.dto;

import com.personal.chatbot.models.index.IndexState;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/** Knowledge-base overview for the admin UI (docs/system-plan.md §4). */
public record KnowledgeBaseStatus(
        long documentCount,
        Map<DocumentStatus, Long> documentsByStatus,
        IndexSummary index,
        QueueSummary queue,
        EmbeddingInfo embedding
) {

    public record IndexSummary(IndexState state, int chunkCount, int documentCount, @Nullable String path,
                               boolean persistent, @Nullable String incompatibilityReason, @Nullable String recoveredFrom) {
    }

    public record QueueSummary(int pending, @Nullable String activeDocumentId) {
    }

    public record EmbeddingInfo(String provider, String model, int dimensions, String fingerprint) {
    }
}
