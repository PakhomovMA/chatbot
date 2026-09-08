package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.exceptions.DocumentNotFoundException;
import com.personal.chatbot.exceptions.ServiceStoppingException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentEvent;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.knowledge.dto.DocumentStatusView;
import com.personal.chatbot.service.index.KnowledgeIndexWriter;
import com.personal.chatbot.service.parsing.DocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Entry point to ingestion (docs/system-plan.md §5, D7): reacts to document lifecycle events and
 * serves the admin operations of the knowledge-base API. The work itself belongs to its
 * collaborators — {@link IngestionQueue} (single writer, INV-11), {@link DocumentIngestionPipeline}
 * (one document, all-or-nothing, INV-09) and {@link IndexReconciler} (startup repair).
 *
 * <p>Not the queue's owner: stopping it belongs to the shutdown sequence, which drives the queue
 * itself (docs/concurrency-plan.md C08).
 */
@Service
public class IngestionService implements IngestionOperations {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final IngestionQueue queue;
    private final IndexReconciler reconciler;
    private final DocumentStatusUpdater status;
    private final IngestionFailureLog failures;
    private final DocumentRegistry registry;
    private final KnowledgeIndexWriter indexStore;
    private final Clock clock;

    public IngestionService(IngestionQueue queue, IndexReconciler reconciler, DocumentStatusUpdater status,
                            IngestionFailureLog failures, DocumentRegistry registry, KnowledgeIndexWriter indexStore,
                            Clock clock) {
        this.queue = queue;
        this.reconciler = reconciler;
        this.status = status;
        this.failures = failures;
        this.registry = registry;
        this.indexStore = indexStore;
        this.clock = clock;
    }

    // ---- reacting to document lifecycle -------------------------------------------------------

    @EventListener
    public void on(DocumentEvent event) {
        switch (event) {
            case DocumentEvent.Uploaded uploaded -> enqueue(uploaded.documentId());
            case DocumentEvent.ContentReplaced replaced -> enqueue(replaced.documentId());
            case DocumentEvent.Deleted deleted -> {
                queue.invalidate(deleted.documentId());
                indexStore.deleteDocument(DocumentParser.uriOf(deleted.documentId()));
            }
            case DocumentEvent.StatusChanged _ -> {
                // produced by the pipeline, consumed by the SSE feed
            }
        }
    }

    /** Queues a document for (re-)indexing; a document already in flight is queued again after it. */
    public boolean enqueue(String documentId) {
        return queue.enqueue(documentId);
    }

    // ---- admin operations ---------------------------------------------------------------------

    @Override
    public DocumentStatusView reindex(String documentId) {
        // No version is expected here, so the only way to miss is a document that is already gone.
        Document pending = status.transition(documentId, d -> d.withStatusAt(DocumentStatus.PENDING_REINDEX, now())
                .withStatusMessage("re-index requested").withError(null)).applied();
        if (pending == null) {
            throw new DocumentNotFoundException(documentId);
        }
        enqueue(documentId);
        return DocumentStatusView.of(pending);
    }

    /**
     * Schedules a full rebuild and marks every known document for re-indexing. The rebuild itself
     * runs as a command in the ingestion queue, after whatever is in flight; the documents to ingest
     * are collected then, so uploads and replacements made while it waits are not lost.
     *
     * @return how many documents were accepted for re-indexing at the time of the request
     */
    @Override
    public int reindexAll() {
        if (!queue.requestRebuild()) {
            throw new ServiceStoppingException("index rebuild");
        }
        List<Document> documents = registry.findAll();
        for (Document document : documents) {
            status.transition(document.id(), d -> d.withStatusAt(DocumentStatus.PENDING_REINDEX, now())
                    .withStatusMessage("index rebuild").withError(null).withChunkCount(null).withIndexedAt(null));
        }
        log.info("Index rebuild requested: {} documents accepted for re-indexing", documents.size());
        return documents.size();
    }

    @Override
    public IngestionQueue.Status queueStatus() {
        return queue.status();
    }

    @Override
    public List<IngestionFailureLog.Failure> recentFailures() {
        return failures.recent();
    }

    /** Startup repair of registry/index disagreements; see {@link IndexReconciler}. */
    public void reconcile() {
        reconciler.reconcile();
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
