package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.models.knowledge.dto.DocumentStatusView;

import java.util.List;

/**
 * Ingestion as the API sees it (docs/system-plan.md §8): admin actions and queue reporting, without
 * the event listener, the reconciler or the lifecycle that {@link IngestionService} also owns.
 */
public interface IngestionOperations {

    /** Marks a document for re-indexing and queues it. */
    DocumentStatusView reindex(String documentId);

    /** Rebuilds the index from scratch and re-queues every document; returns how many were queued. */
    int reindexAll();

    IngestionQueue.Status queueStatus();

    /** Most recent ingestion failures, newest first. */
    List<IngestionFailureLog.Failure> recentFailures();
}
