package com.personal.chatbot.models.knowledge;

/** Ingestion lifecycle of a knowledge-base document (docs/system-plan.md §5, D7). */
public enum DocumentStatus {
    UPLOADED,
    PARSING,
    CHUNKING,
    INDEXING,
    READY,
    FAILED,
    PENDING_REINDEX;

    /** Terminal states are never advanced by the ingestion worker on its own. */
    public boolean isTerminal() {
        return switch (this) {
            case READY, FAILED -> true;
            case UPLOADED, PARSING, CHUNKING, INDEXING, PENDING_REINDEX -> false;
        };
    }
}
