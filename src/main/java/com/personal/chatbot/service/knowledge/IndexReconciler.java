package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentError;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.service.index.KnowledgeIndexWriter;
import com.personal.chatbot.service.parsing.DocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Brings registry and index back into agreement after a restart (docs/system-plan.md §5.9): roots
 * without a document are dropped, READY documents without a root go back to PENDING_REINDEX,
 * interrupted ingestions become FAILED, and pending work is re-queued.
 */
public class IndexReconciler {

    private static final Logger log = LoggerFactory.getLogger(IndexReconciler.class);

    private final DocumentRegistry registry;
    private final KnowledgeIndexWriter indexStore;
    private final DocumentStatusUpdater status;
    private final IngestionQueue queue;
    private final ChatbotProperties.Ingestion settings;
    private final Clock clock;

    public IndexReconciler(DocumentRegistry registry, KnowledgeIndexWriter indexStore, DocumentStatusUpdater status,
                           IngestionQueue queue, ChatbotProperties.Ingestion settings, Clock clock) {
        this.registry = registry;
        this.indexStore = indexStore;
        this.status = status;
        this.queue = queue;
        this.settings = settings;
        this.clock = clock;
    }

    public void reconcile() {
        Set<String> indexed = indexStore.documentUris();
        List<Document> documents = registry.findAll();
        Set<String> known = new LinkedHashSet<>();
        for (Document document : documents) {
            known.add(DocumentParser.uriOf(document.id()));
        }
        for (String uri : indexed) {
            if (!known.contains(uri)) {
                log.warn("Index contains {} which is not in the registry; removing", uri);
                indexStore.deleteDocument(uri);
            }
        }
        int queuedCount = 0;
        for (Document document : documents) {
            String uri = DocumentParser.uriOf(document.id());
            switch (document.status()) {
                case READY -> {
                    if (!indexed.contains(uri)) {
                        log.warn("Document {} is READY but missing from the index; scheduling re-index", document.id());
                        status.transition(document.id(), d -> d.withStatusAt(DocumentStatus.PENDING_REINDEX, now())
                                .withStatusMessage("missing from index after restart"));
                        queuedCount += resume(document.id());
                    }
                }
                case PARSING, CHUNKING, INDEXING -> {
                    log.warn("Ingestion of {} was interrupted in stage {}", document.id(), document.status());
                    indexStore.deleteDocument(uri);
                    status.transition(document.id(), d -> d.withStatusAt(DocumentStatus.FAILED, now())
                            .withError(new DocumentError(d.status().name().toLowerCase(), "interrupted by restart", now())));
                    if (settings.retryInterrupted()) {
                        queuedCount += resume(document.id());
                    }
                }
                case UPLOADED, PENDING_REINDEX -> queuedCount += resume(document.id());
                case FAILED -> {
                    // stays failed until the user retries
                }
            }
        }
        log.info("Reconciliation done: {} documents, {} indexed roots, {} queued", documents.size(), indexed.size(), queuedCount);
    }

    private int resume(String documentId) {
        return settings.autoResume() && queue.enqueue(documentId) ? 1 : 0;
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
