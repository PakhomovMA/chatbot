package com.personal.chatbot.models.knowledge.dto;

import com.personal.chatbot.models.knowledge.DocumentStatus;

import java.util.Map;

/**
 * Knowledge-base overview for the admin UI. Index state and queue statistics are added in Phase 3.
 */
public record KnowledgeBaseStatus(
        long documentCount,
        Map<DocumentStatus, Long> documentsByStatus,
        EmbeddingInfo embedding
) {

    public record EmbeddingInfo(String provider, String model, int dimensions, String fingerprint) {
    }
}
