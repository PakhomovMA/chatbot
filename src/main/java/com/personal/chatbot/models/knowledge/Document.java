package com.personal.chatbot.models.knowledge;

import lombok.With;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Registry entry for one logical document (docs/system-plan.md §4). Identity is {@code id}; the
 * logical identity used for de-duplication is {@code contentHash} (INV-04). Index-related fields
 * are null until the document has been indexed (Phase 3).
 */
@With
public record Document(
        String id,
        String title,
        String originalFilename,
        String mediaType,
        long sizeBytes,
        String contentHash,
        int version,
        Instant uploadedAt,
        Instant updatedAt,
        DocumentStatus status,
        @Nullable String statusMessage,
        @Nullable DocumentError error,
        @Nullable Integer chunkCount,
        @Nullable Instant indexedAt,
        @Nullable String embeddingFingerprint
) {

    public static Document uploaded(String title, String originalFilename, String mediaType, long sizeBytes,
                                    String contentHash, Instant now) {
        return new Document(UUID.randomUUID().toString(), title, originalFilename, mediaType, sizeBytes, contentHash,
                1, now, now, DocumentStatus.UPLOADED, null, null, null, null, null);
    }

    /** New content under the same id: version bump, index state reset, back to {@link DocumentStatus#UPLOADED}. */
    public Document replacedContent(String originalFilename, String mediaType, long sizeBytes, String contentHash, Instant now) {
        return new Document(id, title, originalFilename, mediaType, sizeBytes, contentHash, version + 1, uploadedAt, now,
                DocumentStatus.UPLOADED, null, null, null, null, null);
    }

    public Document withStatusAt(DocumentStatus newStatus, Instant now) {
        return withStatus(newStatus).withUpdatedAt(now);
    }
}
