package com.personal.chatbot.models.knowledge;

import com.personal.chatbot.models.knowledge.dto.DocumentStatusView;

/** In-process notifications about documents; the ingestion worker and the SSE feed react to them. */
public sealed interface DocumentEvent {

    String documentId();

    record Uploaded(String documentId, int version) implements DocumentEvent {
    }

    record ContentReplaced(String documentId, int version) implements DocumentEvent {
    }

    record Deleted(String documentId) implements DocumentEvent {
    }

    record StatusChanged(String documentId, DocumentStatusView view) implements DocumentEvent {
    }
}
