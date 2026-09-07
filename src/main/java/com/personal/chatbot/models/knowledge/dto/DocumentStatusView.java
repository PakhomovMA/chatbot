package com.personal.chatbot.models.knowledge.dto;

import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentError;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/** Lightweight polling view of a document's ingestion state. */
public record DocumentStatusView(
        String documentId,
        DocumentStatus status,
        int version,
        @Nullable String statusMessage,
        @Nullable DocumentError error,
        Instant updatedAt
) {

    public static DocumentStatusView of(Document document) {
        return new DocumentStatusView(document.id(), document.status(), document.version(),
                document.statusMessage(), document.error(), document.updatedAt());
    }
}
