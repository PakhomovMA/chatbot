package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.ServiceStoppingException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentEvent;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.knowledge.dto.UploadResponse;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.lifecycle.ShutdownSequence;
import com.personal.chatbot.service.parsing.DocumentParser;
import com.personal.chatbot.support.FailingTextEmbedder;
import com.personal.chatbot.support.FakeTextEmbedder;
import com.personal.chatbot.support.IndexStores;
import com.personal.chatbot.support.PausingDocumentParser;
import com.personal.chatbot.support.TestDocuments;
import com.personal.chatbot.support.TestObservations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Phase 3 gate for the pipeline: statuses, failures, deletion, reconciliation and rebuild. */
class IngestionServiceTest {

    @TempDir
    Path dir;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final PausingDocumentParser parser = new PausingDocumentParser();
    /** Set to make the queued rebuild command fail, as a broken or unreadable index directory would. */
    private final AtomicBoolean rebuildFails = new AtomicBoolean();
    /** Set to let the worker run the whole rebuild while the request that asked for it marks documents. */
    private final AtomicBoolean settleWhileMarkingRebuild = new AtomicBoolean();
    private final AtomicBoolean finishActiveWhileMarkingRebuild = new AtomicBoolean();
    /** What a rebuild did, in the order it happened: one entry per marked document, one per rebuild. */
    private final List<String> rebuildSteps = new CopyOnWriteArrayList<>();
    /** One facade for the whole test, so what every run reported survives a re-wiring of the index. */
    private final TestObservations observed = TestObservations.create();
    private DocumentRegistry registry;
    private BlobStore blobStore;
    private LuceneIndexStore indexStore;
    private IngestionQueue queue;
    private IngestionService ingestion;
    private DocumentService documents;

    private ChatbotProperties properties() {
        return new ChatbotProperties(dir,
                new ChatbotProperties.Embedding("fake", null, null, 16, 2, true),
                new ChatbotProperties.Knowledge(DataSize.ofMegabytes(5), Set.of("md", "txt", "pdf", "docx")),
                new ChatbotProperties.Index(null, true, 400, 50, 8),
                new ChatbotProperties.Ingestion(true, true),
                new ChatbotProperties.Retrieval(8, 3, 60, 0.0, 0.0, 0.5, 0, 1.0, 200),
                com.personal.chatbot.support.ChatSettings.defaults(),
                new ChatbotProperties.Sse(256),
                new ChatbotProperties.Observability(true, com.personal.chatbot.observability.TraceExport.NONE,
                        java.time.Duration.ofSeconds(5)));
    }

    /** Wires the same objects the Spring context would; events are dispatched directly to the service. */
    private void wire(LuceneIndexStore store) {
        indexStore = store.open();
        ApplicationEventPublisher publisher = event -> {
            published.add(event);
            if (event instanceof DocumentEvent documentEvent && ingestion != null) {
                ingestion.on(documentEvent);
            }
            onRebuildMark(event);
        };
        DocumentStatusUpdater status = new DocumentStatusUpdater(registry, publisher);
        IngestionFailureLog failures = new IngestionFailureLog();
        var observations = observed.ingestionObservations();
        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(registry, blobStore, parser,
                indexStore, status, failures, Clock.systemUTC(), observations);
        queue = new IngestionQueue(pipeline::process, () -> {
            rebuildSteps.add("rebuild");
            if (rebuildFails.get()) {
                throw new IllegalStateException("simulated rebuild failure");
            }
            indexStore.rebuild();
            return registry.findAll().stream().map(Document::id).toList();
        }, observations);
        IndexReconciler reconciler = new IndexReconciler(registry, indexStore, status, queue,
                properties().ingestion(), Clock.systemUTC());
        ingestion = new IngestionService(queue, reconciler, status, failures, registry, indexStore, Clock.systemUTC());
        documents = new DocumentService(registry, blobStore, properties().knowledge(), Clock.systemUTC(), publisher);
    }

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry(dir.resolve("documents"));
        blobStore = new BlobStore(dir.resolve("blobs"));
        wire(IndexStores.store(dir.resolve("index"), new FakeTextEmbedder(16)));
    }

    @AfterEach
    void tearDown() {
        stopIngestion();
        indexStore.close();
    }

    /** Stops the worker the way the application does, so a test never leaves one running. */
    private ShutdownSequence.Result stopIngestion() {
        return new ShutdownSequence(List.of(queue), Duration.ofSeconds(5), Duration.ofSeconds(5)).stop();
    }

    /**
     * How many runs ended in {@code outcome}, as the pipeline itself reported. Ingestion handles most
     * of its endings internally and returns nothing either way, so this is the only thing that can
     * tell a document that vanished from one that was indexed (docs/observability-plan.md §5.2).
     */
    private long processed(String outcome) {
        return observed.meters().find("chatbot.ingestion.processing").tag("outcome", outcome)
                .timers().stream().mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }

    /** How many accepted requests ended their wait for the single writer in {@code outcome}. */
    private long queued(String outcome) {
        return observed.meters().find("chatbot.ingestion.queue.wait").tag("outcome", outcome)
                .timers().stream().mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }

    private double failuresAt(String stage) {
        return observed.meters().find("chatbot.ingestion.failures").tag("stage", stage)
                .counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    /**
     * Watches the marks a rebuild request writes, and lets the first of them wait until the worker has
     * nothing left to do — the interleaving in which a mark written afterwards would outlive the run
     * it was meant to start.
     */
    private void onRebuildMark(Object event) {
        if (!(event instanceof DocumentEvent.StatusChanged status)
                || status.view().status() != DocumentStatus.PENDING_REINDEX
                || !"index rebuild".equals(status.view().statusMessage())) {
            return;
        }
        rebuildSteps.add("mark");
        if (finishActiveWhileMarkingRebuild.compareAndSet(true, false)) {
            parser.resume();
            awaitIdleQueue();
        }
        if (settleWhileMarkingRebuild.compareAndSet(true, false)) {
            awaitIdleQueue();
        }
    }

    private UploadResponse upload(String name, byte[] bytes) {
        return documents.upload(new DocumentService.Upload(name, null, bytes.length, new ByteArrayInputStream(bytes), null));
    }

    private Document awaitStatus(String id, DocumentStatus status) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(registry.findById(id).orElseThrow().status()).isEqualTo(status));
        return registry.findById(id).orElseThrow();
    }

    /** Waits until nothing is in flight and nothing is waiting; the queue reports both atomically. */
    private void awaitIdleQueue() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(ingestion.queueStatus().activeDocumentId()).isNull();
            assertThat(ingestion.queueStatus().pending()).isZero();
        });
    }

    @Test
    void uploadedDocumentsBecomeReadyWithChunksAndFingerprint() {
        String md = upload("runbook.md", TestDocuments.markdown()).documentId();
        String pdf = upload("guide.pdf", TestDocuments.pdf(List.of("Deployment guide", "Deploy with the deploy script."))).documentId();
        String docx = upload("policy.docx", TestDocuments.docx(List.of("Access policy", "Two reviewers must approve."))).documentId();

        for (String id : List.of(md, pdf, docx)) {
            Document ready = awaitStatus(id, DocumentStatus.READY);
            assertThat(ready.chunkCount()).isPositive();
            assertThat(ready.indexedAt()).isNotNull();
            assertThat(ready.embeddingFingerprint()).isEqualTo(indexStore.fingerprint().value());
            assertThat(ready.error()).isNull();
            assertThat(indexStore.containsDocument(DocumentParser.uriOf(id))).isTrue();
        }
        assertThat(indexStore.documentUris()).hasSize(3);
        // Three requests waited for the single writer and three runs ended in a READY commit.
        assertThat(queued("success")).isEqualTo(3);
        assertThat(processed("success")).isEqualTo(3);
        assertThat(published).filteredOn(DocumentEvent.StatusChanged.class::isInstance)
                .map(e -> ((DocumentEvent.StatusChanged) e).view().status())
                .contains(DocumentStatus.PARSING, DocumentStatus.INDEXING, DocumentStatus.READY);
    }

    @Test
    void parseFailureIsRecordedAndPurged() {
        String id = upload("broken.pdf", "%PDF-1.7 definitely not a pdf".getBytes()).documentId();
        Document failed = awaitStatus(id, DocumentStatus.FAILED);
        assertThat(failed.error()).isNotNull();
        assertThat(failed.error().stage()).isEqualTo("parse");
        assertThat(indexStore.containsDocument(DocumentParser.uriOf(id))).isFalse();
        assertThat(processed("error")).isEqualTo(1);
        assertThat(failuresAt("parse")).isEqualTo(1);
    }

    @Test
    void embeddingFailureIsRecordedAndPurged() {
        stopIngestion();
        indexStore.close();
        wire(IndexStores.store(dir.resolve("index2"), new FailingTextEmbedder(16, "Rollback")));
        String id = upload("runbook.md", TestDocuments.markdown()).documentId();
        Document failed = awaitStatus(id, DocumentStatus.FAILED);
        assertThat(failed.error().stage()).isEqualTo("embedding");
        assertThat(failed.chunkCount()).isNull();
        assertThat(indexStore.allChunks()).isEmpty();
    }

    @Test
    void deleteAndReplaceKeepIndexInSync() {
        String id = upload("runbook.md", TestDocuments.markdown()).documentId();
        awaitStatus(id, DocumentStatus.READY);

        documents.replaceContent(id, new DocumentService.Upload("runbook.md", null, 20,
                new ByteArrayInputStream("# New\n\nCompletely new content here.".getBytes()), null));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Document current = registry.findById(id).orElseThrow();
            assertThat(current.status()).isEqualTo(DocumentStatus.READY);
            assertThat(current.version()).isEqualTo(2);
        });
        assertThat(indexStore.allChunks()).allMatch(c -> c.getId().startsWith(id + ":2:"));

        documents.delete(id);
        assertThat(indexStore.containsDocument(DocumentParser.uriOf(id))).isFalse();
        assertThat(indexStore.allChunks()).isEmpty();
    }

    @Test
    void reindexDocumentAndRebuildAll() {
        String a = upload("a.md", TestDocuments.markdown()).documentId();
        String b = upload("b.md", "# Other\n\nSome other text about deployments.".getBytes()).documentId();
        awaitStatus(a, DocumentStatus.READY);
        awaitStatus(b, DocumentStatus.READY);

        assertThat(ingestion.reindex(a).status()).isEqualTo(DocumentStatus.PENDING_REINDEX);
        awaitStatus(a, DocumentStatus.READY);

        assertThat(ingestion.reindexAll()).isEqualTo(2);
        awaitStatus(a, DocumentStatus.READY);
        awaitStatus(b, DocumentStatus.READY);
        assertThat(indexStore.documentUris()).containsExactlyInAnyOrder(DocumentParser.uriOf(a), DocumentParser.uriOf(b));
        assertThat(ingestion.queueStatus().pending()).isZero();
    }

    /**
     * C10: the statuses a rebuild request writes belong before the request, never after it. With the
     * marks written afterwards, a rebuild that got through first had its result overwritten by a
     * {@code PENDING_REINDEX} that no queued run would ever take back.
     */
    @Test
    void aRebuildThatRunsFirstDoesNotLeaveDocumentsMarkedForEver() {
        String a = upload("a.md", TestDocuments.markdown()).documentId();
        String b = upload("b.md", "# Other\n\nSome other text about deployments.".getBytes()).documentId();
        awaitStatus(a, DocumentStatus.READY);
        awaitStatus(b, DocumentStatus.READY);
        settleWhileMarkingRebuild.set(true);

        assertThat(ingestion.reindexAll()).isEqualTo(2);

        awaitIdleQueue();
        assertThat(rebuildSteps).containsExactlyInAnyOrder("mark", "mark", "rebuild");
        assertThat(registry.findById(a).orElseThrow().status()).isEqualTo(DocumentStatus.READY);
        assertThat(registry.findById(b).orElseThrow().status()).isEqualTo(DocumentStatus.READY);
        assertThat(indexStore.documentUris()).containsExactlyInAnyOrder(DocumentParser.uriOf(a), DocumentParser.uriOf(b));
    }

    /** A request the stopping queue will not take is refused instead of being reported as queued. */
    @Test
    void reindexIsRefusedOnceTheQueueStopsTakingWork() {
        String id = upload("a.md", TestDocuments.markdown()).documentId();
        awaitStatus(id, DocumentStatus.READY);
        stopIngestion();
        Document before = registry.findById(id).orElseThrow();

        assertThatThrownBy(() -> ingestion.reindex(id)).isInstanceOf(ServiceStoppingException.class);
        assertThat(registry.findById(id)).contains(before);
        assertThatThrownBy(() -> ingestion.reindexAll()).isInstanceOf(ServiceStoppingException.class);
        assertThat(registry.findById(id)).contains(before);
    }

    @Test
    void anOldRunCannotPublishReadyBetweenRebuildMarkingAndAdmission() {
        parser.pauseNext(1);
        String id = upload("runbook.md", TestDocuments.markdown()).documentId();
        parser.awaitParsing();
        rebuildFails.set(true);
        finishActiveWhileMarkingRebuild.set(true);

        ingestion.reindexAll();

        awaitIdleQueue();
        assertThat(registry.findById(id).orElseThrow().status()).isEqualTo(DocumentStatus.PENDING_REINDEX);
    }

    @Test
    void reconciliationRepairsRegistryAndIndexAfterRestart() {
        String ready = upload("ready.md", TestDocuments.markdown()).documentId();
        String stuck = upload("stuck.md", "# Stuck\n\nInterrupted while parsing.".getBytes()).documentId();
        String pending = upload("pending.md", "# Pending\n\nNever picked up.".getBytes()).documentId();
        awaitStatus(ready, DocumentStatus.READY);
        awaitStatus(stuck, DocumentStatus.READY);
        awaitStatus(pending, DocumentStatus.READY);

        // Simulate a crash: registry says one doc is mid-flight, one is READY but its index content is gone,
        // one is still UPLOADED, and the index holds an orphan nobody knows about.
        registry.save(registry.findById(stuck).orElseThrow().withStatus(DocumentStatus.PARSING));
        registry.save(registry.findById(pending).orElseThrow().withStatus(DocumentStatus.UPLOADED));
        indexStore.deleteDocument(DocumentParser.uriOf(ready));
        Document orphan = Document.uploaded("Orphan", "orphan.md", "text/markdown", 5, "orphan-hash", java.time.Instant.now());
        blobStore.commit(blobStore.stage(new ByteArrayInputStream("# Orphan\n\nOrphan text.".getBytes())), orphan.id(), 1, "md");
        indexStore.writeDocument(new DocumentParser().parse(orphan, blobStore.find(orphan.id(), 1).orElseThrow()));
        assertThat(indexStore.documentUris()).contains(DocumentParser.uriOf(orphan.id()));

        ingestion.reconcile();

        assertThat(indexStore.containsDocument(DocumentParser.uriOf(orphan.id()))).isFalse();
        awaitStatus(ready, DocumentStatus.READY);
        awaitStatus(stuck, DocumentStatus.READY);
        awaitStatus(pending, DocumentStatus.READY);
        assertThat(published).filteredOn(DocumentEvent.StatusChanged.class::isInstance)
                .map(e -> (DocumentEvent.StatusChanged) e)
                .anyMatch(e -> e.documentId().equals(stuck) && e.view().status() == DocumentStatus.FAILED
                        && "interrupted by restart".equals(e.view().error().message()));
        assertThat(indexStore.documentUris()).hasSize(3);
    }

    @Test
    void incompatibleIndexParksDocumentsUntilRebuild() {
        stopIngestion();
        indexStore.close();
        try (LuceneIndexStore old = IndexStores.store(dir.resolve("index3"), new FakeTextEmbedder(8)).open()) {
            old.writeDocument(new DocumentParser().parse(
                    Document.uploaded("Seed", "seed.md", "text/markdown", 1, "seed", java.time.Instant.now()),
                    write("seed.md", TestDocuments.markdown())));
        }
        wire(IndexStores.store(dir.resolve("index3"), new FakeTextEmbedder(16)));
        assertThat(indexStore.state().name()).isEqualTo("INCOMPATIBLE");

        String id = upload("runbook.md", TestDocuments.markdown()).documentId();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(registry.findById(id).orElseThrow().statusMessage()).contains("INCOMPATIBLE"));
        assertThat(registry.findById(id).orElseThrow().status()).isEqualTo(DocumentStatus.UPLOADED);

        // Parked, not failed: the document is waiting for an index that can take it.
        assertThat(processed("waiting_index")).isEqualTo(1);
        assertThat(failuresAt("ingestion")).isZero();

        ingestion.reindexAll();
        awaitStatus(id, DocumentStatus.READY);
    }

    /**
     * Shutdown while a document is being ingested (docs/concurrency-plan.md C08): the run is
     * interrupted, but no failure is recorded for it — the document keeps its ingestion stage, which
     * is exactly what startup reconciliation knows how to pick up again.
     */
    @Test
    void shutdownDuringIngestionLeavesTheDocumentForReconciliation() {
        parser.pauseNext(1);
        String id = upload("runbook.md", TestDocuments.markdown()).documentId();
        parser.awaitParsing();
        awaitStatus(id, DocumentStatus.PARSING);

        ShutdownSequence.Result stopped = new ShutdownSequence(List.of(queue), Duration.ofMillis(200),
                Duration.ofSeconds(5)).stop();

        assertThat(stopped.complete()).isTrue();
        assertThat(registry.findById(id).orElseThrow().status()).isEqualTo(DocumentStatus.PARSING);
        assertThat(registry.findById(id).orElseThrow().error()).isNull();
        assertThat(ingestion.enqueue(id)).isFalse();
        assertThat(ingestion.recentFailures()).isEmpty();
        // A clean restart is not an ingestion that went wrong, and it is not counted as one.
        assertThat(processed("cancelled")).isEqualTo(1);
        assertThat(processed("error")).isZero();
        assertThat(failuresAt("parse")).isZero();

        // What the next start makes of it: an interrupted stage is re-queued and indexed.
        indexStore.close();
        wire(IndexStores.store(dir.resolve("index"), new FakeTextEmbedder(16)));
        ingestion.reconcile();
        awaitStatus(id, DocumentStatus.READY);
    }

    @Test
    void replacementDuringIngestionIsIndexedInsteadOfLost() {
        parser.pauseNext(1);
        String id = upload("runbook.md", TestDocuments.markdown()).documentId();
        parser.awaitParsing();

        documents.replaceContent(id, new DocumentService.Upload("runbook.md", null, 20,
                new ByteArrayInputStream("# New\n\nCompletely new content here.".getBytes()), null));
        parser.resume();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Document current = registry.findById(id).orElseThrow();
            assertThat(current.version()).isEqualTo(2);
            assertThat(current.status()).isEqualTo(DocumentStatus.READY);
        });
        awaitIdleQueue();
        assertThat(indexStore.allChunks()).isNotEmpty().allMatch(c -> c.getId().startsWith(id + ":2:"));
        // The run that was reading v1 lost the document to the replacement; only the second one committed.
        assertThat(processed("superseded")).isEqualTo(1);
        assertThat(processed("success")).isEqualTo(1);
    }

    @Test
    void rebuildDuringIngestionKeepsEveryDocument() {
        String settled = upload("settled.md", "# Settled\n\nAlready indexed text.".getBytes()).documentId();
        awaitStatus(settled, DocumentStatus.READY);
        parser.pauseNext(1);
        String inFlight = upload("in-flight.md", TestDocuments.markdown()).documentId();
        parser.awaitParsing();

        assertThat(ingestion.reindexAll()).isEqualTo(2);
        parser.resume();

        awaitStatus(inFlight, DocumentStatus.READY);
        awaitStatus(settled, DocumentStatus.READY);
        awaitIdleQueue();
        assertThat(indexStore.documentUris())
                .containsExactlyInAnyOrder(DocumentParser.uriOf(settled), DocumentParser.uriOf(inFlight));
    }

    @Test
    void uploadDuringAPendingRebuildIsIndexedToo() {
        parser.pauseNext(1);
        String inFlight = upload("in-flight.md", TestDocuments.markdown()).documentId();
        parser.awaitParsing();
        ingestion.reindexAll();

        String late = upload("late.md", "# Late\n\nUploaded while the rebuild was waiting.".getBytes()).documentId();
        parser.resume();

        awaitStatus(inFlight, DocumentStatus.READY);
        awaitStatus(late, DocumentStatus.READY);
        awaitIdleQueue();
        assertThat(indexStore.documentUris())
                .containsExactlyInAnyOrder(DocumentParser.uriOf(inFlight), DocumentParser.uriOf(late));
    }

    @Test
    void aFailedRebuildLeavesNoDocumentClaimingToBeIndexed() {
        rebuildFails.set(true);
        parser.pauseNext(1);
        String id = upload("runbook.md", TestDocuments.markdown()).documentId();
        parser.awaitParsing();

        ingestion.reindexAll();
        parser.resume();

        awaitIdleQueue();
        assertThat(registry.findById(id).orElseThrow().status()).isEqualTo(DocumentStatus.PENDING_REINDEX);
        assertThat(indexStore.containsDocument(DocumentParser.uriOf(id))).isFalse();
    }

    @Test
    void deletionDuringIngestionDoesNotBringTheDocumentBack() {
        parser.pauseNext(1);
        String id = upload("runbook.md", TestDocuments.markdown()).documentId();
        parser.awaitParsing();

        documents.delete(id);
        parser.resume();

        awaitIdleQueue();
        // Deleted under the run: the parser lost the original with the document, and that is a
        // supersession rather than a failure — there is no document left for a failure to belong to.
        assertThat(processed("superseded")).isEqualTo(1);
        assertThat(processed("error")).isZero();
        assertThat(failuresAt("parse")).isZero();
        assertThat(ingestion.recentFailures()).isEmpty();
        assertThat(registry.findById(id)).isEmpty();
        assertThat(registry.count()).isZero();
        assertThat(indexStore.containsDocument(DocumentParser.uriOf(id))).isFalse();
        assertThat(indexStore.allChunks()).isEmpty();
    }

    @Test
    void repeatedReindexOfAnActiveDocumentRunsExactlyOnceMore() {
        parser.pauseNext(1);
        String id = upload("runbook.md", TestDocuments.markdown()).documentId();
        parser.awaitParsing();
        assertThat(parser.parses()).isEqualTo(1);

        for (int i = 0; i < 5; i++) {
            assertThat(ingestion.reindex(id).status()).isEqualTo(DocumentStatus.PENDING_REINDEX);
        }
        parser.resume();

        awaitIdleQueue();
        assertThat(registry.findById(id).orElseThrow().status()).isEqualTo(DocumentStatus.READY);
        assertThat(parser.parses()).isEqualTo(2);
        // Five re-index requests collapsed into one re-run: four of them never waited for a worker.
        assertThat(queued("superseded")).isEqualTo(4);
        assertThat(queued("success")).isEqualTo(2);
    }

    @Test
    void replacedBlobSurvivesTheRunThatIsReadingItAndIsDroppedAfterwards() {
        parser.pauseNext(1);
        String id = upload("runbook.md", TestDocuments.markdown()).documentId();
        parser.awaitParsing();

        documents.replaceContent(id, new DocumentService.Upload("runbook.md", null, 20,
                new ByteArrayInputStream("# New\n\nCompletely new content here.".getBytes()), null));
        assertThat(blobStore.find(id, 1)).isPresent();
        parser.resume();

        awaitStatus(id, DocumentStatus.READY);
        awaitIdleQueue();
        assertThat(blobStore.find(id, 1)).isEmpty();
        assertThat(blobStore.find(id, 2)).isPresent();
    }

    private Path write(String name, byte[] bytes) {
        try {
            Path file = dir.resolve(name);
            java.nio.file.Files.write(file, bytes);
            return file;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
