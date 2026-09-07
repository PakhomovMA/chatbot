package com.personal.chatbot.service.knowledge;

import com.embabel.agent.rag.model.NavigableDocument;
import com.personal.chatbot.exceptions.DocumentParseException;
import com.personal.chatbot.exceptions.IndexUnavailableException;
import com.personal.chatbot.exceptions.IndexWriteException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentError;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.observability.RequestContext;
import com.personal.chatbot.service.index.KnowledgeIndexWriter;
import com.personal.chatbot.service.knowledge.DocumentRegistry.Change;
import com.personal.chatbot.service.parsing.DocumentParser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Ingestion of one document: {@code PARSING → INDEXING → READY | FAILED} (docs/system-plan.md §5).
 * All-or-nothing — on any failure the document's partial index content is purged and the registry
 * records the failing stage (INV-09).
 *
 * <p>Concurrency is the queue's business, but two things are this class's: every registry change is
 * conditional on the version this run started from, so a run cannot push an older version back or
 * resurrect a deleted document; and the final {@code READY} is published through the run's claim, so
 * a run that a deletion, a replacement or a rebuild has taken over commits nothing.
 */
public class DocumentIngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionPipeline.class);

    private final DocumentRegistry registry;
    private final BlobStore blobStore;
    private final DocumentParser parser;
    private final KnowledgeIndexWriter indexStore;
    private final DocumentStatusUpdater status;
    private final IngestionFailureLog failures;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public DocumentIngestionPipeline(DocumentRegistry registry, BlobStore blobStore, DocumentParser parser,
                                     KnowledgeIndexWriter indexStore, DocumentStatusUpdater status,
                                     IngestionFailureLog failures, Clock clock, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.blobStore = blobStore;
        this.parser = parser;
        this.indexStore = indexStore;
        this.status = status;
        this.failures = failures;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /** Entry point for the ingestion worker; never throws. */
    public void process(IngestionClaim claim) {
        String documentId = claim.documentId();
        long started = System.nanoTime();
        try (RequestContext.Scope _ = RequestContext.with(RequestContext.DOCUMENT_ID, documentId)) {
            Document document = registry.findById(documentId).orElse(null);
            if (document == null) {
                log.info("Document {} vanished before ingestion", documentId);
                return;
            }
            if (superseded(claim, document, "start")) {
                return;
            }
            if (!indexStore.state().isWritable()) {
                status.transition(documentId, document.version(), d -> d.withStatusMessage("waiting: index is " + indexStore.state()));
                log.warn("Skipping {}: index is {}", documentId, indexStore.state());
                return;
            }
            try {
                ingest(claim, document);
            } catch (RuntimeException e) {
                log.error("Unexpected failure while ingesting {}", documentId, e);
                fail(claim, document, "ingestion", e.getMessage());
            }
        } finally {
            pruneSupersededBlobs(documentId);
            timer("total").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void ingest(IngestionClaim claim, Document document) {
        int version = document.version();
        String uri = DocumentParser.uriOf(document.id());
        log.info("Ingesting document {} '{}' v{}", document.id(), document.title(), version);

        NavigableDocument parsed;
        status.transition(document.id(), version, d -> d.withStatusAt(DocumentStatus.PARSING, now()).withStatusMessage(null).withError(null));
        long stage = System.nanoTime();
        try {
            Path original = blobStore.find(document.id(), version)
                    .orElseThrow(() -> new DocumentParseException("Stored original for version " + version + " is missing"));
            parsed = parser.parse(document, original);
        } catch (DocumentParseException e) {
            fail(claim, document, "parse", e.getMessage());
            return;
        } finally {
            timer("parse").record(System.nanoTime() - stage, TimeUnit.NANOSECONDS);
        }

        if (superseded(claim, document, "indexing")) {
            return;
        }
        status.transition(document.id(), version, d -> d.withStatusAt(DocumentStatus.INDEXING, now()));
        stage = System.nanoTime();
        List<String> chunkIds;
        try {
            chunkIds = indexStore.writeDocument(parsed);
        } catch (IndexWriteException e) {
            fail(claim, document, e.stage(), e.getMessage());
            return;
        } catch (IndexUnavailableException e) {
            status.transition(document.id(), version, d -> d.withStatusAt(DocumentStatus.UPLOADED, now()).withStatusMessage(e.getMessage()));
            return;
        } finally {
            timer("index").record(System.nanoTime() - stage, TimeUnit.NANOSECONDS);
        }

        // Deciding and writing happen under the claim, announcing after it: an SSE send must never
        // run while the queue is held.
        Optional<Change> committed = claim.ifCurrent(() -> status.apply(document.id(), version,
                d -> d.withStatusAt(DocumentStatus.READY, now())
                        .withStatusMessage(null).withError(null)
                        .withChunkCount(chunkIds.size()).withIndexedAt(now())
                        .withEmbeddingFingerprint(indexStore.fingerprint().value())));
        if (committed.isEmpty()) {
            log.info("Document {} v{} was taken over while indexing; dropping what this run wrote", document.id(), version);
            indexStore.deleteDocument(uri);
            return;
        }
        status.announce(committed.get());
        switch (committed.get()) {
            case Change.Applied _ -> log.info("Document {} indexed: {} chunks", document.id(), chunkIds.size());
            case Change.Missing _ -> {
                log.info("Document {} was deleted during ingestion; purging its chunks", document.id());
                indexStore.deleteDocument(uri);
            }
            case Change.Stale stale -> log.info("Document {} was replaced during ingestion (v{} -> v{}); the newer version will be indexed",
                    document.id(), version, stale.current().version());
        }
    }

    private void fail(IngestionClaim claim, Document document, String stage, @Nullable String message) {
        indexStore.deleteDocument(DocumentParser.uriOf(document.id()));
        Counter.builder("chatbot.ingestion.failures").tag("stage", stage).register(meterRegistry).increment();
        String detail = message != null ? message : "unknown error";
        failures.record(new IngestionFailureLog.Failure(document.id(), stage, detail, now()));
        log.warn("Ingestion of {} failed at stage {}: {}", document.id(), stage, detail);
        claim.ifCurrent(() -> status.apply(document.id(), document.version(), d -> d.withStatusAt(DocumentStatus.FAILED, now())
                .withStatusMessage(null).withChunkCount(null).withIndexedAt(null)
                .withError(new DocumentError(stage, detail, now()))))
                .ifPresent(status::announce);
    }

    /** True when a deletion, a newer request or a rebuild has taken this document over. */
    private boolean superseded(IngestionClaim claim, Document document, String before) {
        if (claim.isCurrent()) {
            return false;
        }
        log.info("Abandoning {} v{} before {}: another request took the document over", document.id(), document.version(), before);
        return true;
    }

    /**
     * A replaced version stays on disk while this run may still be reading it (see
     * {@link DocumentService#replaceContent}); once the run is over, only the current one is needed.
     */
    private void pruneSupersededBlobs(String documentId) {
        registry.findById(documentId).ifPresent(current -> blobStore.deleteVersionsBefore(documentId, current.version()));
    }

    private Timer timer(String stage) {
        return Timer.builder("chatbot.ingestion.stage").tag("stage", stage).register(meterRegistry);
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
