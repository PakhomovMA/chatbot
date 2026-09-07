package com.personal.chatbot.models.index;

/** Lifecycle of the Lucene index as seen by the application (docs/system-plan.md §4, D4). */
public enum IndexState {
    /** Open and compatible, no chunks yet. */
    EMPTY,
    /** Open, compatible, contains chunks. */
    READY,
    /** Manifest disagrees with the active embedding/chunker; retrieval and ingestion are blocked until rebuild. */
    INCOMPATIBLE,
    /** A rebuild is in progress. */
    REBUILDING;

    public boolean isWritable() {
        return this == EMPTY || this == READY;
    }
}
