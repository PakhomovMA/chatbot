package com.personal.chatbot.eval;

import com.embabel.agent.rag.model.NavigableDocument;
import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.parsing.DocumentParser;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import com.personal.chatbot.support.FakeTextEmbedder;
import com.personal.chatbot.support.IndexStores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EvalFixtureTest {
    @TempDir
    Path dir;

    @Test
    void corpusHasTheApprovedFormatsAndParses() {
        List<Path> documents = EvalFixtures.documentPaths();

        assertThat(documents).hasSize(18);
        assertThat(documents.stream().filter(path -> path.toString().endsWith(".pdf"))).hasSize(2);
        assertThat(documents.stream().filter(path -> EvalFixtures.documentKey(path).startsWith("ru-"))).hasSizeGreaterThanOrEqualTo(4);
        assertThat(documents.stream().filter(path -> path.toString().matches(".*\\.(java|py|sql)"))).hasSize(3);
        DocumentParser parser = new DocumentParser();
        java.util.Map<String, String> parsedTexts = new java.util.HashMap<>();
        for (Path path : documents) {
            NavigableDocument parsed = parser.parse(document(path), path);
            assertThat(parsed.leaves()).as("parsed leaves for %s", path).isNotEmpty();
            StringBuilder text = new StringBuilder();
            parsed.leaves().forEach(leaf -> text.append(leaf.getText()).append('\n'));
            parsedTexts.put(EvalFixtures.documentKey(path), text.toString().replaceAll("\\s+", " "));
        }
        assertThat(parsedTexts.get("observability-handbook")).contains("telemetry retention is thirteen months");
        assertThat(parsedTexts.get("billing-operations")).contains("reconciliation pause code is HOLD-42");
        assertThat(parsedTexts.get("capacity-matrix")).contains("payments-api").contains("12");
        assertThat(parsedTexts.get("sla-matrix")).contains("SEV-1").contains("15 minutes");
        assertThat(parsedTexts.get("RetryPolicy")).contains("MAX_ATTEMPTS = 7");
        assertThat(parsedTexts.get("settlement_reconciler")).contains("LEDGER_TOLERANCE_MINOR = 14");
        assertThat(parsedTexts.get("rate_limiter")).contains("burst_limit integer not null default 90");
    }

    @Test
    void longPdfsProduceMultipleChunks() {
        DocumentParser parser = new DocumentParser();
        IndexManifest.Chunker chunker = new IndexManifest.Chunker(800, 100, ProvenanceChunkTransformer.TRANSFORMER_VERSION);
        try (LuceneIndexStore store = IndexStores.store(dir.resolve("pdf-index"), new FakeTextEmbedder(16), chunker).open()) {
            for (Path path : EvalFixtures.documentPaths().stream().filter(path -> path.toString().endsWith(".pdf")).toList()) {
                store.writeDocument(parser.parse(document(path), path));
            }
            for (String key : List.of("observability-handbook", "billing-operations")) {
                assertThat(store.allChunks().stream().filter(chunk -> key.equals(chunk.getMetadata()
                        .get(ProvenanceChunkTransformer.DOCUMENT_ID)))).as("chunks for %s", key).hasSizeGreaterThan(1);
            }
        }
    }

    @Test
    void goldenSetHasValidRelevanceAndDistinctChunks() {
        EvalQuestionSet questions = EvalFixtures.questions();
        EvalFixtureValidator.validateSchema(questions);
        DocumentParser parser = new DocumentParser();
        IndexManifest.Chunker chunker = new IndexManifest.Chunker(800, 100, ProvenanceChunkTransformer.TRANSFORMER_VERSION);
        try (LuceneIndexStore store = IndexStores.store(dir.resolve("index"), new FakeTextEmbedder(16), chunker).open()) {
            for (Path path : EvalFixtures.documentPaths()) {
                store.writeDocument(parser.parse(document(path), path));
            }
            EvalFixtureValidator.validateChunks(questions, store.allChunks());
        }
    }

    /** One entry of the pre-Eval-2.0 schema: a single expected document, or none for a negative. */
    record LegacyQuestion(String id, String question, @Nullable String expectedDocument, List<String> mustContain) {
    }

    record LegacySet(String description, List<LegacyQuestion> questions) {
    }

    @Test
    void everyLegacyQuestionIsKeptTaggedAndUnchanged() throws IOException {
        LegacySet legacy = JsonMapper.builder().build().readValue(
                Files.readString(Path.of("src/test/resources/eval/legacy-questions.json")), LegacySet.class);
        Map<String, EvalQuestion> current = EvalFixtures.questions().questions().stream()
                .collect(Collectors.toMap(EvalQuestion::id, question -> question));

        assertThat(legacy.questions()).hasSize(44);
        assertThat(current.values().stream().filter(question -> question.categories().contains("legacy"))
                .map(EvalQuestion::id)).as("questions tagged legacy")
                .containsExactlyInAnyOrderElementsOf(legacy.questions().stream().map(LegacyQuestion::id).toList());
        for (LegacyQuestion expected : legacy.questions()) {
            EvalQuestion actual = current.get(expected.id());
            assertThat(actual.question()).as("text of %s", expected.id()).isEqualTo(expected.question());
            List<EvalQuestion.RelevantPassage> relevance = expected.expectedDocument() == null ? List.of()
                    : List.of(new EvalQuestion.RelevantPassage(expected.expectedDocument(), expected.mustContain()));
            assertThat(actual.relevant()).as("relevance of %s", expected.id()).isEqualTo(relevance);
        }
    }

    private static Document document(Path path) {
        String key = EvalFixtures.documentKey(path);
        return new Document(key, key, path.getFileName().toString(), EvalFixtures.mediaType(path),
                path.toFile().length(), "eval-" + key, 1, Instant.EPOCH, Instant.EPOCH, DocumentStatus.READY,
                null, null, null, null, null);
    }
}
