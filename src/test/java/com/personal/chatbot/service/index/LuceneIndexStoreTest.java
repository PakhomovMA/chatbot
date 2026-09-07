package com.personal.chatbot.service.index;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.NavigableDocument;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import com.personal.chatbot.exceptions.IndexUnavailableException;
import com.personal.chatbot.exceptions.IndexWriteException;
import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.models.index.IndexState;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.service.parsing.DocumentParser;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import com.personal.chatbot.support.FailingTextEmbedder;
import com.personal.chatbot.support.FakeTextEmbedder;
import com.personal.chatbot.support.IndexStores;
import com.personal.chatbot.support.TestDocuments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 3 gate for the index store: provenance, atomicity, persistence, manifest, recovery, concurrency. */
class LuceneIndexStoreTest {

    @TempDir
    Path dir;

    private final DocumentParser parser = new DocumentParser();

    private NavigableDocument parsedMarkdown(String id, int version, String markdown) throws Exception {
        Path file = dir.resolve(id + "-v" + version + ".md");
        Files.writeString(file, markdown);
        Document document = new Document(id, "Payments Runbook", file.getFileName().toString(), "text/markdown", 1,
                "hash-" + id + version, version, Instant.now(), Instant.now(),
                com.personal.chatbot.models.knowledge.DocumentStatus.UPLOADED, null, null, null, null, null);
        return parser.parse(document, file);
    }

    @Test
    void writesChunksWithDeterministicIdsAndProvenance() throws Exception {
        try (LuceneIndexStore store = IndexStores.store(null, new FakeTextEmbedder(16)).open()) {
            assertThat(store.state()).isEqualTo(IndexState.EMPTY);
            List<String> ids = store.writeDocument(parsedMarkdown("doc-1", 1, TestDocuments.MARKDOWN));

            assertThat(ids).isNotEmpty().allMatch(id -> id.matches("doc-1:1:\\d+"));
            assertThat(store.state()).isEqualTo(IndexState.READY);
            assertThat(store.containsDocument("kb://documents/doc-1")).isTrue();
            assertThat(store.documentUris()).containsExactly("kb://documents/doc-1");

            List<Chunk> chunks = store.allChunks();
            assertThat(chunks).hasSize(ids.size());
            Chunk restart = chunks.stream().filter(c -> c.getUrtext().contains("systemctl restart payments")).findFirst().orElseThrow();
            assertThat(restart.getMetadata())
                    .containsEntry(ProvenanceChunkTransformer.DOCUMENT_ID, "doc-1")
                    .containsEntry(ProvenanceChunkTransformer.DOCUMENT_VERSION, "1")
                    .containsEntry(ProvenanceChunkTransformer.DOCUMENT_TITLE, "Payments Runbook")
                    .containsEntry(ProvenanceChunkTransformer.MEDIA_TYPE, "text/markdown")
                    .containsKey("sequence_number");
            assertThat(restart.getMetadata().get(ProvenanceChunkTransformer.SECTION_PATH).toString()).contains("Restart");
            assertThat(restart.getText()).startsWith("Document: Payments Runbook");
            assertThat(restart.getMetadata().get(ProvenanceChunkTransformer.URTEXT).toString()).doesNotStartWith("Document:");
            // The long nested section is split into several chunks, each carrying the full heading path.
            List<Chunk> nested = chunks.stream().filter(c -> c.getUrtext().contains("snapshot")).toList();
            assertThat(nested).hasSizeGreaterThan(1);
            assertThat(nested).allSatisfy(c -> {
                assertThat(c.getMetadata()).containsEntry(ProvenanceChunkTransformer.SECTION_PATH, "Rollback › Database rollback")
                        .containsEntry(ProvenanceChunkTransformer.SECTION_TITLE, "Database rollback");
                assertThat(c.getText()).startsWith("Document: Payments Runbook › Rollback › Database rollback");
            });

            // Re-writing the same document replaces rather than duplicates (INV-04).
            List<String> again = store.writeDocument(parsedMarkdown("doc-1", 1, TestDocuments.MARKDOWN));
            assertThat(again).containsExactlyElementsOf(ids);
            assertThat(store.allChunks()).hasSize(ids.size());

            assertThat(store.deleteDocument("kb://documents/doc-1")).isTrue();
            assertThat(store.deleteDocument("kb://documents/doc-1")).isFalse();
            assertThat(store.allChunks()).isEmpty();
            assertThat(store.state()).isEqualTo(IndexState.EMPTY);
        }
    }

    @Test
    void embeddingFailureLeavesNothingBehind() throws Exception {
        try (LuceneIndexStore store = IndexStores.store(null, new FailingTextEmbedder(16, "Rollback")).open()) {
            assertThatThrownBy(() -> store.writeDocument(parsedMarkdown("doc-2", 1, TestDocuments.MARKDOWN)))
                    .isInstanceOf(IndexWriteException.class)
                    .hasMessageContaining("simulated embedding failure")
                    .extracting("stage").isEqualTo("embedding");
            assertThat(store.allChunks()).isEmpty();
            assertThat(store.containsDocument("kb://documents/doc-2")).isFalse();
            assertThat(store.state()).isEqualTo(IndexState.EMPTY);
        }
    }

    @Test
    void persistsAcrossReopenIncludingUrtextAndSearch() throws Exception {
        Path indexDir = dir.resolve("index");
        int written;
        try (LuceneIndexStore store = IndexStores.store(indexDir, new FakeTextEmbedder(16)).open()) {
            written = store.writeDocument(parsedMarkdown("doc-3", 2, TestDocuments.MARKDOWN)).size();
        }
        assertThat(indexDir.resolve("manifest.json")).exists();
        assertThat(Files.readString(indexDir.resolve("manifest.json"))).contains("fake/fake-embedder/000000000000/16/");

        try (LuceneIndexStore reopened = IndexStores.store(indexDir, new FakeTextEmbedder(16)).open()) {
            assertThat(reopened.state()).isEqualTo(IndexState.READY);
            assertThat(reopened.info().chunkCount()).isEqualTo(written);
            assertThat(reopened.containsDocument("kb://documents/doc-3")).isTrue();
            List<Chunk> chunks = reopened.allChunks();
            assertThat(chunks).hasSize(written).allMatch(c -> c.getId().startsWith("doc-3:2:"));
            assertThat(chunks).allMatch(c -> c.getMetadata().containsKey(ProvenanceChunkTransformer.URTEXT));
            var hits = reopened.search(ops -> ops.textSearch(TextSimilaritySearchRequest.create("systemctl", 0.0, 5), Chunk.class));
            assertThat(hits).isNotEmpty();
            assertThat(hits.getFirst().getMatch().getMetadata().get(ProvenanceChunkTransformer.URTEXT).toString()).contains("systemctl");

            // Deletion and re-indexing must work on reloaded content too (Embabel does not persist the chunk parent chain).
            assertThat(reopened.writeDocument(parsedMarkdown("doc-3", 2, TestDocuments.MARKDOWN))).hasSize(written);
            assertThat(reopened.allChunks()).hasSize(written);
            assertThat(reopened.deleteDocument("kb://documents/doc-3")).isTrue();
            assertThat(reopened.allChunks()).isEmpty();
            assertThat(reopened.documentUris()).isEmpty();
            assertThat(reopened.state()).isEqualTo(IndexState.EMPTY);
        }
    }

    @Test
    void incompatibleManifestBlocksUntilRebuild() throws Exception {
        Path indexDir = dir.resolve("index");
        try (LuceneIndexStore store = IndexStores.store(indexDir, new FakeTextEmbedder(16)).open()) {
            store.writeDocument(parsedMarkdown("doc-4", 1, TestDocuments.MARKDOWN));
        }
        // Different embedder (dimensions and hash differ) => different fingerprint.
        try (LuceneIndexStore other = IndexStores.store(indexDir, new FakeTextEmbedder(8)).open()) {
            assertThat(other.state()).isEqualTo(IndexState.INCOMPATIBLE);
            assertThat(other.info().incompatibilityReason()).contains("was built with");
            assertThatThrownBy(() -> other.search(ops -> ops.textSearch(TextSimilaritySearchRequest.create("x", 0.0, 1), Chunk.class)))
                    .isInstanceOf(IndexUnavailableException.class);
            assertThatThrownBy(() -> other.writeDocument(parsedMarkdown("doc-5", 1, TestDocuments.MARKDOWN)))
                    .isInstanceOf(IndexUnavailableException.class);

            other.rebuild();
            assertThat(other.state()).isEqualTo(IndexState.EMPTY);
            assertThat(other.documentUris()).isEmpty();
            assertThat(other.writeDocument(parsedMarkdown("doc-5", 1, TestDocuments.MARKDOWN))).isNotEmpty();
            assertThat(Files.readString(indexDir.resolve("manifest.json"))).contains("/8/");
        }
        // A changed chunker configuration is incompatible too.
        var otherChunker = new IndexManifest.Chunker(800, 50, ProvenanceChunkTransformer.TRANSFORMER_VERSION);
        try (LuceneIndexStore store = IndexStores.store(indexDir, new FakeTextEmbedder(8), otherChunker).open()) {
            assertThat(store.state()).isEqualTo(IndexState.INCOMPATIBLE);
        }
    }

    @Test
    void indexWithoutManifestIsIncompatible() throws Exception {
        Path indexDir = dir.resolve("index");
        try (LuceneIndexStore store = IndexStores.store(indexDir, new FakeTextEmbedder(16)).open()) {
            store.writeDocument(parsedMarkdown("doc-6", 1, TestDocuments.MARKDOWN));
        }
        Files.delete(indexDir.resolve("manifest.json"));
        try (LuceneIndexStore store = IndexStores.store(indexDir, new FakeTextEmbedder(16)).open()) {
            assertThat(store.state()).isEqualTo(IndexState.INCOMPATIBLE);
            assertThat(store.info().incompatibilityReason()).contains("no manifest");
        }
    }

    @Test
    void corruptIndexIsQuarantinedAndStartsEmpty() throws Exception {
        Path indexDir = dir.resolve("index");
        try (LuceneIndexStore store = IndexStores.store(indexDir, new FakeTextEmbedder(16)).open()) {
            store.writeDocument(parsedMarkdown("doc-7", 1, TestDocuments.MARKDOWN));
        }
        Path lucene = indexDir.resolve("lucene");
        try (Stream<Path> files = Files.list(lucene)) {
            for (Path file : files.toList()) {
                Files.write(file, new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
            }
        }
        try (LuceneIndexStore store = IndexStores.store(indexDir, new FakeTextEmbedder(16)).open()) {
            assertThat(store.state()).isEqualTo(IndexState.EMPTY);
            assertThat(store.info().recoveredFrom()).contains("lucene.corrupt-");
            assertThat(store.writeDocument(parsedMarkdown("doc-7", 1, TestDocuments.MARKDOWN))).isNotEmpty();
        }
        try (Stream<Path> siblings = Files.list(indexDir)) {
            assertThat(siblings.map(p -> p.getFileName().toString())).anyMatch(name -> name.startsWith("lucene.corrupt-"));
        }
    }

    @Test
    void concurrentSearchesDuringIngestionDoNotFail() throws Exception {
        try (LuceneIndexStore store = IndexStores.store(dir.resolve("index"), new FakeTextEmbedder(16)).open()) {
            store.writeDocument(parsedMarkdown("seed", 1, TestDocuments.MARKDOWN));
            AtomicInteger searches = new AtomicInteger();
            CompletableFuture<Void> searcher = CompletableFuture.runAsync(() -> {
                long end = System.nanoTime() + 1_500_000_000L;
                while (System.nanoTime() < end) {
                    store.search(ops -> ops.textSearch(TextSimilaritySearchRequest.create("payments", 0.0, 5), Chunk.class));
                    store.search(ops -> ops.vectorSearch(TextSimilaritySearchRequest.create("restart the service", 0.0, 5), Chunk.class));
                    searches.incrementAndGet();
                }
            });
            for (int i = 0; i < 15; i++) {
                store.writeDocument(parsedMarkdown("doc-" + i, 1, TestDocuments.MARKDOWN.replace("Payments", "Service " + i)));
                if (i % 3 == 0) {
                    store.deleteDocument("kb://documents/doc-" + i);
                }
            }
            searcher.join();
            assertThat(searches.get()).isPositive();
            assertThat(store.documentUris()).hasSize(1 + 15 - 5);
        }
    }
}
