package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentEvent;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.knowledge.dto.UploadResponse;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.parsing.DocumentParser;
import com.personal.chatbot.support.FailingTextEmbedder;
import com.personal.chatbot.support.FakeTextEmbedder;
import com.personal.chatbot.support.IndexStores;
import com.personal.chatbot.support.TestDocuments;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Phase 3 gate for the pipeline: statuses, failures, deletion, reconciliation and rebuild. */
class IngestionServiceTest {

    @TempDir
    Path dir;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private DocumentRegistry registry;
    private BlobStore blobStore;
    private LuceneIndexStore indexStore;
    private IngestionService ingestion;
    private DocumentService documents;

    private ChatbotProperties properties() {
        return new ChatbotProperties(dir,
                new ChatbotProperties.Embedding("fake", null, null, 16, 2, true),
                new ChatbotProperties.Knowledge(DataSize.ofMegabytes(5), Set.of("md", "txt", "pdf", "docx")),
                new ChatbotProperties.Index(null, true, 400, 50, 8),
                new ChatbotProperties.Ingestion(true, true),
                new ChatbotProperties.Retrieval(8, 3, 60, 0.0, 0.0, 0.5, 0, 200),
                new ChatbotProperties.Chat(com.personal.chatbot.models.chat.AnswerMode.DETERMINISTIC,
                        com.personal.chatbot.models.chat.AnswerLanguage.AUTO, 4, 0.2, 0.1, 6000, 600, 10, 1000,
                        java.time.Duration.ofHours(24)));
    }

    /** Wires the same objects the Spring context would; events are dispatched directly to the service. */
    private void wire(LuceneIndexStore store) {
        indexStore = store.open();
        ApplicationEventPublisher publisher = event -> {
            published.add(event);
            if (event instanceof DocumentEvent documentEvent && ingestion != null) {
                ingestion.on(documentEvent);
            }
        };
        DocumentStatusUpdater status = new DocumentStatusUpdater(registry, publisher);
        IngestionFailureLog failures = new IngestionFailureLog();
        DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(registry, blobStore, new DocumentParser(),
                indexStore, status, failures, Clock.systemUTC(), new SimpleMeterRegistry());
        IngestionQueue queue = new IngestionQueue(pipeline::process);
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
        ingestion.close();
        indexStore.close();
    }

    private UploadResponse upload(String name, byte[] bytes) {
        return documents.upload(new DocumentService.Upload(name, null, bytes.length, new ByteArrayInputStream(bytes), null));
    }

    private Document awaitStatus(String id, DocumentStatus status) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(registry.findById(id).orElseThrow().status()).isEqualTo(status));
        return registry.findById(id).orElseThrow();
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
    }

    @Test
    void embeddingFailureIsRecordedAndPurged() {
        ingestion.close();
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
        ingestion.close();
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

        ingestion.reindexAll();
        awaitStatus(id, DocumentStatus.READY);
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
