package com.personal.chatbot.models.knowledge.dto;

import com.personal.chatbot.models.index.IndexState;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Knowledge-base overview for the admin UI (docs/system-plan.md §4, D14). */
public record KnowledgeBaseStatus(
        long documentCount,
        Map<DocumentStatus, Long> documentsByStatus,
        IndexSummary index,
        QueueSummary queue,
        EmbeddingInfo embedding,
        List<RecentFailure> recentFailures
) {

    public record IndexSummary(IndexState state, int chunkCount, int documentCount, @Nullable String path,
                               boolean persistent, @Nullable String incompatibilityReason, @Nullable String recoveredFrom) {
    }

    public record QueueSummary(int pending, @Nullable String activeDocumentId) {
    }

    public record EmbeddingInfo(String provider, String model, int dimensions, String fingerprint) {
    }

    /** One of the most recent ingestion failures, newest first. */
    public record RecentFailure(String documentId, String stage, String message, Instant at) {
    }
}
