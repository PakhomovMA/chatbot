package com.personal.chatbot.service.knowledge;

import com.embabel.agent.rag.model.NavigableDocument;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.DocumentNotFoundException;
import com.personal.chatbot.exceptions.DocumentParseException;
import com.personal.chatbot.exceptions.IndexUnavailableException;
import com.personal.chatbot.exceptions.IndexWriteException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentError;
import com.personal.chatbot.models.knowledge.DocumentEvent;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.knowledge.dto.DocumentStatusView;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.parsing.DocumentParser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Drives {@code UPLOADED → PARSING → CHUNKING/INDEXING → READY | FAILED} with a single worker
 * (docs/system-plan.md §5, D7). Each document is all-or-nothing: on any failure its partial index
 * content is purged and the registry records the failing stage (INV-09). Startup reconciliation
 * repairs disagreements between the registry and the index after a crash.
 */
@Service
public class IngestionService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    public record QueueStatus(int pending, @Nullable String activeDocumentId) {
    }

    private final DocumentRegistry registry;
    private final BlobStore blobStore;
    private final DocumentParser parser;
    private final LuceneIndexStore indexStore;
    private final ApplicationEventPublisher events;
    private final ChatbotProperties.Ingestion settings;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("ingestion-worker").daemon(true).factory());
    private final Set<String> queued = new LinkedHashSet<>();
    private final AtomicReference<@Nullable String> active = new AtomicReference<>();

    public IngestionService(DocumentRegistry registry, BlobStore blobStore, DocumentParser parser,
                            LuceneIndexStore indexStore, ApplicationEventPublisher events,
                            ChatbotProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.blobStore = blobStore;
        this.parser = parser;
        this.indexStore = indexStore;
        this.events = events;
        this.settings = properties.ingestion();
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    // ---- reacting to document lifecycle -------------------------------------------------------

    @EventListener
    public void on(DocumentEvent event) {
        switch (event) {
            case DocumentEvent.Uploaded uploaded -> enqueue(uploaded.documentId());
            case DocumentEvent.ContentReplaced replaced -> enqueue(replaced.documentId());
            case DocumentEvent.Deleted deleted -> {
                dequeue(deleted.documentId());
                indexStore.deleteDocument(DocumentParser.uriOf(deleted.documentId()));
            }
            case DocumentEvent.StatusChanged _ -> {
                // produced here, consumed by the SSE feed
            }
        }
    }

    /** Queues a document for (re-)indexing; no-op if it is already queued or being processed. */
    public boolean enqueue(String documentId) {
        synchronized (queued) {
            if (documentId.equals(active.get()) || !queued.add(documentId)) {
                return false;
            }
        }
        worker.submit(() -> process(documentId));
        return true;
    }

    /** Marks a document for re-indexing and queues it. */
    public DocumentStatusView reindex(String documentId) {
        Document document = registry.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        Document pending = transition(document.id(), d -> d.withStatusAt(DocumentStatus.PENDING_REINDEX, now())
                .withStatusMessage("re-index requested").withError(null));
        enqueue(documentId);
        return DocumentStatusView.of(pending != null ? pending : document);
    }

    /** Rebuilds the index from scratch and re-queues every document. */
    public int reindexAll() {
        synchronized (queued) {
            queued.clear();
        }
        indexStore.rebuild();
        int count = 0;
        for (Document document : registry.findAll()) {
            transition(document.id(), d -> d.withStatusAt(DocumentStatus.PENDING_REINDEX, now())
                    .withStatusMessage("index rebuild").withError(null).withChunkCount(null).withIndexedAt(null));
            enqueue(document.id());
            count++;
        }
        log.info("Index rebuild requested: {} documents queued", count);
        return count;
    }

    public QueueStatus queueStatus() {
        synchronized (queued) {
            return new QueueStatus(queued.size(), active.get());
        }
    }

    // ---- startup ------------------------------------------------------------------------------

    /**
     * Brings registry and index back into agreement after a restart (docs/system-plan.md §5.9):
     * roots without a document are dropped, READY documents without a root go back to
     * PENDING_REINDEX, interrupted ingestions become FAILED, and pending work is re-queued.
     */
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
                        transition(document.id(), d -> d.withStatusAt(DocumentStatus.PENDING_REINDEX, now())
                                .withStatusMessage("missing from index after restart"));
                        queuedCount += resume(document.id());
                    }
                }
                case PARSING, CHUNKING, INDEXING -> {
                    log.warn("Ingestion of {} was interrupted in stage {}", document.id(), document.status());
                    indexStore.deleteDocument(uri);
                    transition(document.id(), d -> d.withStatusAt(DocumentStatus.FAILED, now())
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
        return settings.autoResume() && enqueue(documentId) ? 1 : 0;
    }

    // ---- the pipeline -------------------------------------------------------------------------

    void process(String documentId) {
        synchronized (queued) {
            if (!queued.remove(documentId)) {
                return;
            }
            active.set(documentId);
        }
        long started = System.nanoTime();
        try {
            Document document = registry.findById(documentId).orElse(null);
            if (document == null) {
                log.info("Document {} vanished before ingestion", documentId);
                return;
            }
            if (!indexStore.state().isWritable()) {
                transition(documentId, d -> d.withStatusMessage("waiting: index is " + indexStore.state()));
                log.warn("Skipping {}: index is {}", documentId, indexStore.state());
                return;
            }
            ingest(document);
        } catch (RuntimeException e) {
            log.error("Unexpected failure while ingesting {}", documentId, e);
            fail(documentId, "ingestion", e.getMessage());
        } finally {
            active.set(null);
            timer("total").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void ingest(Document document) {
        int version = document.version();
        String uri = DocumentParser.uriOf(document.id());
        log.info("Ingesting document {} '{}' v{}", document.id(), document.title(), version);

        NavigableDocument parsed;
        transition(document.id(), d -> d.withStatusAt(DocumentStatus.PARSING, now()).withStatusMessage(null).withError(null));
        long stage = System.nanoTime();
        try {
            Path original = blobStore.find(document.id(), version)
                    .orElseThrow(() -> new DocumentParseException("Stored original for version " + version + " is missing"));
            parsed = parser.parse(document, original);
        } catch (DocumentParseException e) {
            fail(document.id(), "parse", e.getMessage());
            return;
        } finally {
            timer("parse").record(System.nanoTime() - stage, TimeUnit.NANOSECONDS);
        }

        transition(document.id(), d -> d.withStatusAt(DocumentStatus.INDEXING, now()));
        stage = System.nanoTime();
        List<String> chunkIds;
        try {
            chunkIds = indexStore.writeDocument(parsed);
        } catch (IndexWriteException e) {
            fail(document.id(), e.stage(), e.getMessage());
            return;
        } catch (IndexUnavailableException e) {
            transition(document.id(), d -> d.withStatusAt(DocumentStatus.UPLOADED, now()).withStatusMessage(e.getMessage()));
            return;
        } finally {
            timer("index").record(System.nanoTime() - stage, TimeUnit.NANOSECONDS);
        }

        Optional<Document> current = registry.findById(document.id());
        if (current.isEmpty()) {
            log.info("Document {} was deleted during ingestion; purging its chunks", document.id());
            indexStore.deleteDocument(uri);
            return;
        }
        if (current.get().version() != version) {
            log.info("Document {} was replaced during ingestion (v{} -> v{}); newer version will be indexed", document.id(),
                    version, current.get().version());
            return;
        }
        transition(document.id(), d -> d.withStatusAt(DocumentStatus.READY, now())
                .withStatusMessage(null).withError(null)
                .withChunkCount(chunkIds.size()).withIndexedAt(now())
                .withEmbeddingFingerprint(indexStore.fingerprint().value()));
        log.info("Document {} indexed: {} chunks", document.id(), chunkIds.size());
    }

    private void fail(String documentId, String stage, @Nullable String message) {
        indexStore.deleteDocument(DocumentParser.uriOf(documentId));
        Counter.builder("chatbot.ingestion.failures").tag("stage", stage).register(meterRegistry).increment();
        String detail = message != null ? message : "unknown error";
        log.warn("Ingestion of {} failed at stage {}: {}", documentId, stage, detail);
        transition(documentId, d -> d.withStatusAt(DocumentStatus.FAILED, now())
                .withStatusMessage(null).withChunkCount(null).withIndexedAt(null)
                .withError(new DocumentError(stage, detail, now())));
    }

    /** Applies a change to the registry entry if it still exists and broadcasts the new status. */
    private @Nullable Document transition(String documentId, UnaryOperator<Document> change) {
        Document current = registry.findById(documentId).orElse(null);
        if (current == null) {
            return null;
        }
        Document updated = registry.save(change.apply(current));
        events.publishEvent(new DocumentEvent.StatusChanged(documentId, DocumentStatusView.of(updated)));
        return updated;
    }

    private void dequeue(String documentId) {
        synchronized (queued) {
            queued.remove(documentId);
        }
    }

    private Timer timer(String stage) {
        return Timer.builder("chatbot.ingestion.stage").tag("stage", stage).register(meterRegistry);
    }

    private Instant now() {
        return Instant.now(clock);
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
