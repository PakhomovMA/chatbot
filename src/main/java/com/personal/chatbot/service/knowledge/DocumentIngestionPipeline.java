package com.personal.chatbot.service.knowledge;

import com.embabel.agent.rag.model.NavigableDocument;
import com.personal.chatbot.exceptions.DocumentParseException;
import com.personal.chatbot.exceptions.IndexUnavailableException;
import com.personal.chatbot.exceptions.IndexWriteException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentError;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.observability.RequestContext;
import com.personal.chatbot.service.index.LuceneIndexStore;
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
 * records the failing stage (INV-09). Concurrency is the queue's business; this class assumes it
 * owns the document for the duration of the call.
 */
public class DocumentIngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionPipeline.class);

    private final DocumentRegistry registry;
    private final BlobStore blobStore;
    private final DocumentParser parser;
    private final LuceneIndexStore indexStore;
    private final DocumentStatusUpdater status;
    private final IngestionFailureLog failures;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public DocumentIngestionPipeline(DocumentRegistry registry, BlobStore blobStore, DocumentParser parser,
                                     LuceneIndexStore indexStore, DocumentStatusUpdater status,
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
    public void process(String documentId) {
        long started = System.nanoTime();
        try (RequestContext.Scope _ = RequestContext.with(RequestContext.DOCUMENT_ID, documentId)) {
            Document document = registry.findById(documentId).orElse(null);
            if (document == null) {
                log.info("Document {} vanished before ingestion", documentId);
                return;
            }
            if (!indexStore.state().isWritable()) {
                status.transition(documentId, d -> d.withStatusMessage("waiting: index is " + indexStore.state()));
                log.warn("Skipping {}: index is {}", documentId, indexStore.state());
                return;
            }
            ingest(document);
        } catch (RuntimeException e) {
            log.error("Unexpected failure while ingesting {}", documentId, e);
            fail(documentId, "ingestion", e.getMessage());
        } finally {
            timer("total").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void ingest(Document document) {
        int version = document.version();
        String uri = DocumentParser.uriOf(document.id());
        log.info("Ingesting document {} '{}' v{}", document.id(), document.title(), version);

        NavigableDocument parsed;
        status.transition(document.id(), d -> d.withStatusAt(DocumentStatus.PARSING, now()).withStatusMessage(null).withError(null));
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

        status.transition(document.id(), d -> d.withStatusAt(DocumentStatus.INDEXING, now()));
        stage = System.nanoTime();
        List<String> chunkIds;
        try {
            chunkIds = indexStore.writeDocument(parsed);
        } catch (IndexWriteException e) {
            fail(document.id(), e.stage(), e.getMessage());
            return;
        } catch (IndexUnavailableException e) {
            status.transition(document.id(), d -> d.withStatusAt(DocumentStatus.UPLOADED, now()).withStatusMessage(e.getMessage()));
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
        status.transition(document.id(), d -> d.withStatusAt(DocumentStatus.READY, now())
                .withStatusMessage(null).withError(null)
                .withChunkCount(chunkIds.size()).withIndexedAt(now())
                .withEmbeddingFingerprint(indexStore.fingerprint().value()));
        log.info("Document {} indexed: {} chunks", document.id(), chunkIds.size());
    }

    private void fail(String documentId, String stage, @Nullable String message) {
        indexStore.deleteDocument(DocumentParser.uriOf(documentId));
        Counter.builder("chatbot.ingestion.failures").tag("stage", stage).register(meterRegistry).increment();
        String detail = message != null ? message : "unknown error";
        failures.record(new IngestionFailureLog.Failure(documentId, stage, detail, now()));
        log.warn("Ingestion of {} failed at stage {}: {}", documentId, stage, detail);
        status.transition(documentId, d -> d.withStatusAt(DocumentStatus.FAILED, now())
                .withStatusMessage(null).withChunkCount(null).withIndexedAt(null)
                .withError(new DocumentError(stage, detail, now())));
    }

    private Timer timer(String stage) {
        return Timer.builder("chatbot.ingestion.stage").tag("stage", stage).register(meterRegistry);
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
