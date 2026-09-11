package com.personal.chatbot.service.index;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.NavigableDocument;
import com.embabel.agent.rag.service.ResultExpander;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.IndexWriteException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import com.personal.chatbot.service.knowledge.DocumentStatusUpdater;
import com.personal.chatbot.service.knowledge.IndexReconciler;
import com.personal.chatbot.service.knowledge.IngestionQueue;
import com.personal.chatbot.service.parsing.DocumentParser;
import com.personal.chatbot.support.FailingTextEmbedder;
import com.personal.chatbot.support.FakeTextEmbedder;
import com.personal.chatbot.support.IndexStores;
import com.personal.chatbot.support.TestDocuments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * K01 gate (docs/cache-plan.md §3.1): the index revision moves with every operation that may change what
 * a search finds, and with nothing that only reads. The answer cache is scoped by it, so a missed bump
 * would serve an answer computed from content that is gone.
 */
class IndexRevisionTest {

    @TempDir
    Path dir;

    private final DocumentParser parser = new DocumentParser();

    @Test
    void openingTheIndexIsARevision() {
        for (Path indexDir : new Path[]{null, dir.resolve("index")}) {
            try (LuceneIndexStore store = IndexStores.store(indexDir, new FakeTextEmbedder(16))) {
                long before = store.revision();
                store.open();
                assertThat(store.revision()).as("open of %s", indexDir).isGreaterThan(before);
            }
        }
    }

    @Test
    void aWriteADeleteAndARebuildEachMoveTheRevision() throws Exception {
        try (LuceneIndexStore store = IndexStores.store(null, new FakeTextEmbedder(16)).open()) {
            long opened = store.revision();
            store.writeDocument(parsed("doc-1", 1, TestDocuments.MARKDOWN));
            long written = store.revision();
            assertThat(written).isGreaterThan(opened);

            assertThat(store.deleteDocument("kb://documents/doc-1")).isTrue();
            long deleted = store.revision();
            assertThat(deleted).isGreaterThan(written);

            store.writeDocument(parsed("doc-2", 1, TestDocuments.MARKDOWN));
            long beforeRebuild = store.revision();
            store.rebuild();
            assertThat(store.revision()).isGreaterThan(beforeRebuild);
        }
    }

    /**
     * A new version that fails to embed takes the old one with it: the old version was deleted before
     * the write, and the purge removed what of the new one had been written. The content changed.
     */
    @Test
    void aWriteThatFailsToEmbedAndPurgesMovesTheRevision() throws Exception {
        try (LuceneIndexStore store = IndexStores.store(null, new FailingTextEmbedder(16, "Rollback")).open()) {
            store.writeDocument(parsed("doc-1", 1, "# Payments\n\nRestart it with `systemctl restart payments`.\n"));
            long before = store.revision();

            assertThatThrownBy(() -> store.writeDocument(parsed("doc-1", 2, TestDocuments.MARKDOWN)))
                    .isInstanceOf(IndexWriteException.class)
                    .extracting("stage").isEqualTo("embedding");

            assertThat(store.containsDocument("kb://documents/doc-1")).isFalse();
            assertThat(store.revision()).isGreaterThan(before);
        }
    }

    /** Startup reconciliation drops a root the registry does not know, through the same delete. */
    @Test
    void aRootDroppedByReconciliationMovesTheRevision() throws Exception {
        try (LuceneIndexStore store = IndexStores.store(null, new FakeTextEmbedder(16)).open()) {
            store.writeDocument(parsed("orphan", 1, TestDocuments.MARKDOWN));
            long before = store.revision();
            DocumentRegistry registry = mock(DocumentRegistry.class);
            when(registry.findAll()).thenReturn(List.of());

            new IndexReconciler(registry, store, mock(DocumentStatusUpdater.class), mock(IngestionQueue.class),
                    new ChatbotProperties.Ingestion(true, true), Clock.systemUTC()).reconcile();

            assertThat(store.documentUris()).isEmpty();
            assertThat(store.revision()).isGreaterThan(before);
        }
    }

    @Test
    void readingTheIndexLeavesTheRevisionAlone() throws Exception {
        try (LuceneIndexStore store = IndexStores.store(null, new FakeTextEmbedder(16)).open()) {
            List<String> ids = store.writeDocument(parsed("doc-1", 1, TestDocuments.MARKDOWN));
            long revision = store.revision();

            store.search(ops -> ops.textSearch(TextSimilaritySearchRequest.create("restart", 0.0, 5), Chunk.class));
            store.search(ops -> ops.vectorSearch(TextSimilaritySearchRequest.create("restart", 0.0, 5), Chunk.class));
            store.expand(ids.getFirst(), ResultExpander.Method.SEQUENCE, 1);
            store.info();
            store.state();
            store.documentUris();
            store.containsDocument("kb://documents/doc-1");
            store.allChunks();
            store.indexedChunks();

            assertThat(store.revision()).isEqualTo(revision);
        }
    }

    /**
     * While a search holds the read lock the revision cannot move, so the revision a search reads names
     * exactly the content it searched — never one from a write that has not finished. Checked the only
     * way that means anything, with writes running alongside: one revision, one result.
     */
    @Test
    void aSearchUnderTheReadLockSeesOneRevisionAndOnlyItsContent() throws Exception {
        try (LuceneIndexStore store = IndexStores.store(dir.resolve("index"), new FakeTextEmbedder(16)).open()) {
            store.writeDocument(parsed("seed", 1, TestDocuments.MARKDOWN));
            Map<Long, Set<List<String>>> resultsByRevision = new ConcurrentHashMap<>();
            AtomicInteger searches = new AtomicInteger();
            AtomicBoolean writing = new AtomicBoolean(true);
            CompletableFuture<Void> searcher = CompletableFuture.runAsync(() -> {
                while (writing.get()) {
                    store.search(ops -> {
                        long before = store.revision();
                        List<String> ids = ops.textSearch(TextSimilaritySearchRequest.create("restart", 0.0, 100), Chunk.class)
                                .stream().map(hit -> hit.getMatch().getId()).sorted().toList();
                        long after = store.revision();
                        assertThat(after).as("revision moved under the read lock").isEqualTo(before);
                        resultsByRevision.computeIfAbsent(before, r -> ConcurrentHashMap.newKeySet()).add(ids);
                        return ids;
                    });
                    searches.incrementAndGet();
                }
            });

            for (int i = 0; i < 10; i++) {
                store.writeDocument(parsed("doc-" + i, 1, TestDocuments.MARKDOWN.replace("Payments", "Service " + i)));
                if (i % 3 == 0) {
                    store.deleteDocument("kb://documents/doc-" + i);
                }
                // Let at least one whole search see this revision before the next write.
                int seen = searches.get();
                await().atMost(Duration.ofSeconds(10)).until(() -> searches.get() > seen + 1);
            }
            writing.set(false);
            searcher.join();

            assertThat(resultsByRevision).hasSizeGreaterThan(10);
            assertThat(resultsByRevision.values()).allSatisfy(results -> assertThat(results).hasSize(1));
        }
    }

    private NavigableDocument parsed(String id, int version, String markdown) throws Exception {
        Path file = dir.resolve(id + "-v" + version + ".md");
        Files.writeString(file, markdown);
        Document document = new Document(id, "Payments Runbook", file.getFileName().toString(), "text/markdown", 1,
                "hash-" + id + version, version, Instant.now(), Instant.now(), DocumentStatus.UPLOADED,
                null, null, null, null, null);
        return parser.parse(document, file);
    }
}
