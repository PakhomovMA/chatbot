package com.personal.chatbot.service.knowledge;

import com.embabel.agent.rag.model.NavigableDocument;
import com.personal.chatbot.exceptions.DocumentParseException;
import com.personal.chatbot.exceptions.IndexUnavailableException;
import com.personal.chatbot.exceptions.IndexWriteException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentError;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.observability.IngestionObservations;
import com.personal.chatbot.observability.IngestionStage;
import com.personal.chatbot.observability.Measured;
import com.personal.chatbot.observability.Outcome;
import com.personal.chatbot.observability.RequestContext;
import com.personal.chatbot.service.index.KnowledgeIndexWriter;
import com.personal.chatbot.service.knowledge.DocumentRegistry.Change;
import com.personal.chatbot.service.parsing.DocumentParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Ingestion of one document: {@code PARSING → INDEXING → READY | FAILED} (docs/system-plan.md §5).
 * All-or-nothing — on any failure the document's partial index content is purged and the registry
 * records the failing stage (INV-09).
 *
 * <p>Concurrency is the queue's business, but three things are this class's: every registry change is
 * conditional on the version this run started from, so a run cannot push an older version back or
 * resurrect a deleted document; the final {@code READY} is published through the run's claim, so a
 * run that a deletion, a replacement or a rebuild has taken over commits nothing; and a run stopped
 * by an interrupt records no failure, so shutdown leaves the document where reconciliation can pick
 * it up again (docs/concurrency-plan.md C08).
 *
 * <p>Every path therefore says how it ended, and none of them is inferred from the fact that this
 * class returns nothing: a document that vanished, one a newer upload took over and one that could
 * not be parsed all "return normally", and reporting them as the same success would make the
 * ingestion metrics say the opposite of the truth (docs/observability-plan.md §5.2).
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
    private final IngestionObservations observations;

    public DocumentIngestionPipeline(DocumentRegistry registry, BlobStore blobStore, DocumentParser parser,
                                     KnowledgeIndexWriter indexStore, DocumentStatusUpdater status,
                                     IngestionFailureLog failures, Clock clock, IngestionObservations observations) {
        this.registry = registry;
        this.blobStore = blobStore;
        this.parser = parser;
        this.indexStore = indexStore;
        this.status = status;
        this.failures = failures;
        this.clock = clock;
        this.observations = observations;
    }

    /**
     * Entry point for the ingestion worker. Everything that can go wrong with a document is handled
     * here and reported as an outcome; only a failure of the registry or the status layer underneath
     * reaches the worker, which logs it.
     */
    public void process(IngestionClaim claim) {
        String documentId = claim.documentId();
        try (Measured processing = observations.startProcessing();
             RequestContext.Scope _ = RequestContext.with(RequestContext.DOCUMENT_ID, documentId);
             Measured total = observations.startStage(IngestionStage.TOTAL)) {
            try {
                Outcome outcome = ingest(claim, documentId);
                processing.finished(outcome);
                total.finished(outcome);
            } catch (RuntimeException e) {
                processing.failed(e);
                total.failed(e);
                throw e;
            } finally {
                pruneSupersededBlobs(documentId);
            }
        }
    }

    private Outcome ingest(IngestionClaim claim, String documentId) {
        Document document = registry.findById(documentId).orElse(null);
        if (document == null) {
            log.info("Document {} vanished before ingestion", documentId);
            return Outcome.SKIPPED;
        }
        if (superseded(claim, document, "start")) {
            return Outcome.SUPERSEDED;
        }
        if (!indexStore.state().isWritable()) {
            status.transition(documentId, document.version(), d -> d.withStatusMessage("waiting: index is " + indexStore.state()));
            log.warn("Skipping {}: index is {}", documentId, indexStore.state());
            return Outcome.WAITING_INDEX;
        }
        try {
            return ingest(claim, document);
        } catch (RuntimeException e) {
            log.error("Unexpected failure while ingesting {}", documentId, e);
            return fail(claim, document, IngestionObservations.FailedStage.INGESTION, e.getMessage());
        }
    }

    private Outcome ingest(IngestionClaim claim, Document document) {
        int version = document.version();
        String uri = DocumentParser.uriOf(document.id());
        log.info("Ingesting document {} '{}' v{}", document.id(), document.title(), version);

        NavigableDocument parsed;
        status.transition(document.id(), version, d -> d.withStatusAt(DocumentStatus.PARSING, now()).withStatusMessage(null).withError(null));
        try (Measured stage = observations.startStage(IngestionStage.PARSE)) {
            try {
                Path original = blobStore.find(document.id(), version)
                        .orElseThrow(() -> new DocumentParseException("Stored original for version " + version + " is missing"));
                parsed = parser.parse(document, original);
                stage.succeeded();
            } catch (DocumentParseException e) {
                stage.failed(e);
                return fail(claim, document, IngestionObservations.FailedStage.PARSE, e.getMessage());
            }
        }

        if (superseded(claim, document, "indexing")) {
            return Outcome.SUPERSEDED;
        }
        status.transition(document.id(), version, d -> d.withStatusAt(DocumentStatus.INDEXING, now()));
        List<String> chunkIds;
        try (Measured stage = observations.startStage(IngestionStage.INDEX)) {
            try {
                chunkIds = indexStore.writeDocument(parsed);
                stage.succeeded();
            } catch (IndexWriteException e) {
                stage.failed(e);
                return fail(claim, document, IngestionObservations.FailedStage.of(e.stage()), e.getMessage());
            } catch (IndexUnavailableException e) {
                stage.finished(Outcome.WAITING_INDEX);
                status.transition(document.id(), version, d -> d.withStatusAt(DocumentStatus.UPLOADED, now()).withStatusMessage(e.getMessage()));
                return Outcome.WAITING_INDEX;
            }
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
            return Outcome.SUPERSEDED;
        }
        status.announce(committed.get());
        return switch (committed.get()) {
            case Change.Applied _ -> {
                log.info("Document {} indexed: {} chunks", document.id(), chunkIds.size());
                yield Outcome.SUCCESS;
            }
            case Change.Missing _ -> {
                log.info("Document {} was deleted during ingestion; purging its chunks", document.id());
                indexStore.deleteDocument(uri);
                yield Outcome.SKIPPED;
            }
            case Change.Stale stale -> {
                log.info("Document {} was replaced during ingestion (v{} -> v{}); the newer version will be indexed",
                        document.id(), version, stale.current().version());
                yield Outcome.SUPERSEDED;
            }
        };
    }

    /** @return the outcome of the run: a real failure, or the cancellation that shutdown made of it */
    private Outcome fail(IngestionClaim claim, Document document, IngestionObservations.FailedStage stage,
                         @Nullable String message) {
        if (Thread.currentThread().isInterrupted()) {
            // Shutdown interrupted this run (docs/concurrency-plan.md C08). The document stays in its
            // ingestion stage, which startup reconciliation recognises and re-queues; FAILED would sit
            // there until somebody asked for a re-index by hand. It is not counted as a failure either:
            // a clean restart is not an ingestion that went wrong.
            log.warn("Ingestion of {} was interrupted at stage {}; leaving it in {} for startup reconciliation",
                    document.id(), stage.label(), document.status());
            return Outcome.CANCELLED;
        }
        indexStore.deleteDocument(DocumentParser.uriOf(document.id()));
        if (!claim.isCurrent()) {
            // A deletion, a replacement or a rebuild took the document over while this run was working,
            // and what broke is a consequence of that — a deleted document takes its stored original
            // with it, so the parser fails on a file that is gone. There is no document left to mark
            // FAILED, and recording the failure would leave the knowledge-base status reporting one
            // against an id nobody can look up (docs/observability/metric-catalog.json,
            // chatbot.ingestion.failures).
            log.info("Ingestion of {} stopped at stage {} after another request took the document over: {}",
                    document.id(), stage.label(), message);
            return Outcome.SUPERSEDED;
        }
        observations.failed(stage);
        String detail = message != null ? message : "unknown error";
        failures.record(new IngestionFailureLog.Failure(document.id(), stage.label(), detail, now()));
        log.warn("Ingestion of {} failed at stage {}: {}", document.id(), stage.label(), detail);
        claim.ifCurrent(() -> status.apply(document.id(), document.version(), d -> d.withStatusAt(DocumentStatus.FAILED, now())
                .withStatusMessage(null).withChunkCount(null).withIndexedAt(null)
                .withError(new DocumentError(stage.label(), detail, now()))))
                .ifPresent(status::announce);
        return Outcome.ERROR;
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

    private Instant now() {
        return Instant.now(clock);
    }
}
