package com.personal.chatbot.service.index;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.NavigableDocument;
import com.embabel.agent.rag.service.SectionSummary;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.service.parsing.DocumentParser;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import com.personal.chatbot.support.FakeTextEmbedder;
import com.personal.chatbot.support.IndexStores;
import com.personal.chatbot.support.TestDocuments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The table of contents the agentic section tools read (docs/system-plan.md Phase 9c follow-up): it is
 * derived from chunk provenance, so these assertions are also a check that the outline the model is
 * shown matches the section headers written into the chunks themselves.
 */
class SectionCatalogTest {

    private static final String OTHER_MARKDOWN = """
            # Incident Process

            ## Rollback

            The incident commander decides whether to roll back.
            """;

    @TempDir
    Path dir;

    private final DocumentParser parser = new DocumentParser();
    private LuceneIndexStore store;
    private SectionCatalog catalog;

    @BeforeEach
    void openStore() {
        store = IndexStores.store(null, new FakeTextEmbedder(16)).open();
        catalog = new SectionCatalog(store);
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    private void index(String id, String title, String markdown) throws Exception {
        Path file = dir.resolve(id + ".md");
        Files.writeString(file, markdown);
        Document document = new Document(id, title, file.getFileName().toString(), "text/markdown", 1,
                "hash-" + id, 1, Instant.now(), Instant.now(), DocumentStatus.UPLOADED, null, null, null, null, null);
        NavigableDocument parsed = parser.parse(document, file);
        store.writeDocument(parsed);
    }

    @Test
    void listsEverySectionOfEveryDocumentInTheOrderTheyAreWritten() throws Exception {
        index("doc-1", "Payments Runbook", TestDocuments.MARKDOWN);
        index("doc-2", "Incident Process", OTHER_MARKDOWN);

        List<SectionSummary> sections = catalog.list(null, null);

        assertThat(sections).extracting(SectionSummary::getDocumentTitle).contains("Payments Runbook", "Incident Process");
        List<String> runbook = sections.stream().filter(s -> s.getDocumentTitle().equals("Payments Runbook"))
                .map(SectionSummary::getTitle).toList();
        // Sections follow the document, and a nested heading is named by its full path — the same string
        // the chunk header carries, so what the model reads here is what it can pass back to readSection.
        assertThat(runbook).containsExactly("Restart", "Rollback", "Rollback › Database rollback");
        assertThat(sections).allSatisfy(section -> assertThat(section.getChunkCount()).isPositive());
        assertThat(sections).filteredOn(s -> s.getTitle().equals("Rollback › Database rollback"))
                .singleElement().satisfies(s -> assertThat(s.getChunkCount()).isGreaterThan(1));
    }

    @Test
    void narrowsToADocumentByPartOfItsTitleWhateverTheCase() throws Exception {
        index("doc-1", "Payments Runbook", TestDocuments.MARKDOWN);
        index("doc-2", "Incident Process", OTHER_MARKDOWN);

        assertThat(catalog.list("payments", null)).isNotEmpty()
                .allSatisfy(section -> assertThat(section.getDocumentTitle()).isEqualTo("Payments Runbook"));
        assertThat(catalog.list("INCIDENT", null)).isNotEmpty()
                .allSatisfy(section -> assertThat(section.getDocumentTitle()).isEqualTo("Incident Process"));
        assertThat(catalog.list("nothing of the sort", null)).isEmpty();
    }

    @Test
    void readsEveryChunkOfASectionInDocumentOrder() throws Exception {
        index("doc-1", "Payments Runbook", TestDocuments.MARKDOWN);

        List<Chunk> chunks = catalog.read("Rollback › Database rollback", null, null);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getMetadata())
                .containsEntry(ProvenanceChunkTransformer.SECTION_PATH, "Rollback › Database rollback"));
        assertThat(chunks).extracting(chunk -> chunk.getStructure().getSequenceNumber()).isSorted();
        // Whole and in order: the first sentence of the section and the last both arrive.
        assertThat(chunks.getFirst().getUrtext()).contains("Restore the latest snapshot");
        assertThat(chunks.getLast().getUrtext()).contains("Notify the payments on-call channel");
    }

    @Test
    void acceptsTheLeafHeadingAsWellAsTheFullPath() throws Exception {
        index("doc-1", "Payments Runbook", TestDocuments.MARKDOWN);

        assertThat(catalog.read("Database rollback", null, null))
                .isEqualTo(catalog.read("Rollback › Database rollback", null, null));
        assertThat(catalog.read("no such section", null, null)).isEmpty();
    }

    @Test
    void returnsSectionsOfBothDocumentsWhenAHeadingIsSharedAndOneWhenNarrowed() throws Exception {
        index("doc-1", "Payments Runbook", TestDocuments.MARKDOWN);
        index("doc-2", "Incident Process", OTHER_MARKDOWN);

        // Left to the caller to disambiguate: SectionReadingTools asks the model to narrow by document.
        assertThat(catalog.read("Rollback", null, null)).extracting(SectionCatalogTest::documentOf)
                .contains("doc-1", "doc-2");
        assertThat(catalog.read("Rollback", "Incident", null)).isNotEmpty()
                .allSatisfy(chunk -> assertThat(documentOf(chunk)).isEqualTo("doc-2"));
    }

    @Test
    void staysInsideTheDocumentsTheRequestIsScopedTo() throws Exception {
        index("doc-1", "Payments Runbook", TestDocuments.MARKDOWN);
        index("doc-2", "Incident Process", OTHER_MARKDOWN);
        Set<String> onlyIncidents = Set.of("doc-2");

        assertThat(catalog.list(null, onlyIncidents)).isNotEmpty()
                .allSatisfy(section -> assertThat(section.getDocumentTitle()).isEqualTo("Incident Process"));
        assertThat(catalog.read("Rollback", null, onlyIncidents)).isNotEmpty()
                .allSatisfy(chunk -> assertThat(documentOf(chunk)).isEqualTo("doc-2"));
        // A section of a document outside the scope cannot be read even when named exactly.
        assertThat(catalog.read("Rollback › Database rollback", null, onlyIncidents)).isEmpty();
        assertThat(catalog.list("Payments", onlyIncidents)).isEmpty();
    }

    @Test
    void survivesARestartOfTheStore() throws Exception {
        Path indexDir = dir.resolve("index");
        try (LuceneIndexStore persistent = IndexStores.store(indexDir, new FakeTextEmbedder(16)).open()) {
            store.close();
            store = persistent;
            catalog = new SectionCatalog(persistent);
            index("doc-1", "Payments Runbook", TestDocuments.MARKDOWN);
            index("doc-2", "Incident Process", OTHER_MARKDOWN);
        }
        try (LuceneIndexStore reopened = IndexStores.store(indexDir, new FakeTextEmbedder(16)).open()) {
            store = reopened;
            SectionCatalog afterRestart = new SectionCatalog(reopened);
            assertThat(afterRestart.list(null, null)).isNotEmpty();
            List<Chunk> shared = afterRestart.read("Rollback", null, null);
            assertThat(shared).extracting(SectionCatalogTest::documentOf).contains("doc-1", "doc-2");
            // Embabel's readSection tool notices a heading that spans two documents through the chunk
            // structure rather than through our metadata. The parent chain of reloaded chunks is broken
            // (ProvenanceChunkTransformer), so check the part it does rely on still tells them apart.
            assertThat(shared).extracting(chunk -> chunk.getStructure().getRootDocumentTitle())
                    .doesNotContainNull().containsOnlyOnce("Payments Runbook", "Incident Process");
        }
    }

    @Test
    void hasNothingToShowForAnEmptyIndex() {
        assertThat(catalog.list(null, null)).isEmpty();
        assertThat(catalog.read("Restart", null, null)).isEmpty();
    }

    private static String documentOf(Chunk chunk) {
        return chunk.getMetadata().get(ProvenanceChunkTransformer.DOCUMENT_ID).toString();
    }
}
