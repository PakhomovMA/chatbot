package com.personal.chatbot.eval;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.embedding.EmbeddingFingerprint;
import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.embedding.EmbabelEmbeddingServiceAdapter;
import com.personal.chatbot.service.embedding.PromptedEmbeddingService;
import com.personal.chatbot.service.embedding.onnx.OnnxModelFiles;
import com.personal.chatbot.service.embedding.onnx.OnnxTextEmbedder;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.parsing.DocumentParser;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import com.personal.chatbot.service.retrieval.RetrievalService;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Retrieval quality gate (docs/system-plan.md §11, Phase 4): indexes the golden documents with the
 * real EmbeddingGemma model and measures Recall@5, MRR and nDCG@10 per retrieval mode.
 * Run with {@code ./gradlew ragEval} (optionally {@code -Peval.chunkSize=800 -Peval.overlap=100});
 * reports land in {@code build/reports/rag-eval/}.
 */
@Tag("eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagEvalTest {

    private static final Logger log = LoggerFactory.getLogger(RagEvalTest.class);
    private static final int RECALL_K = 5;
    private static final int NDCG_K = 10;

    /** @param expectedDocument null marks a negative question: nothing in the corpus answers it. */
    record Question(String id, String question, @Nullable String expectedDocument, List<String> mustContain) {
        boolean isNegative() {
            return expectedDocument == null;
        }
    }

    record QuestionSet(String description, List<Question> questions) {
    }

    record QuestionOutcome(String id, RetrievalMode mode, int firstRelevantRank, double topCosine, boolean sufficient,
                           long totalMs, List<String> topDocuments) {
    }

    /**
     * @param sufficientShare         share of positive questions flagged as sufficient evidence (want high)
     * @param negativeSufficientShare share of negative questions flagged as sufficient (want zero)
     * @param positiveMinCosine       lowest top-hit cosine among answered positives
     * @param negativeMaxCosine       highest top-hit cosine among negatives; the sufficiency floor must sit above it
     */
    record ModeSummary(RetrievalMode mode, int positives, int negatives, double recallAt5, double mrr, double ndcgAt10,
                       double sufficientShare, double negativeSufficientShare, double positiveMinCosine,
                       double negativeMaxCosine, double p50Ms, double p95Ms) {
    }

    record Report(Instant at, String fingerprint, int chunkSize, int overlap, int chunks, List<ModeSummary> summary,
                  List<QuestionOutcome> outcomes) {
    }

    @TempDir
    static Path dir;

    private final int chunkSize = Integer.getInteger("eval.chunkSize", 800);
    private final int overlap = Integer.getInteger("eval.overlap", 100);
    private final double minRecall = Double.parseDouble(System.getProperty("eval.minRecall", "0.8"));
    private final double sufficientCosine = Double.parseDouble(System.getProperty("eval.sufficientCosine", "0.3"));

    private PromptedEmbeddingService embeddings;
    private LuceneIndexStore store;
    private RetrievalService retrieval;
    private QuestionSet questionSet;
    private Map<String, String> documentIdsByKey;

    static Path modelDir() {
        String override = System.getenv("CHATBOT_MODEL_DIR");
        return override != null ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".chatbot", "models", "embeddinggemma-300m");
    }

    @BeforeAll
    void indexGoldenDocuments() throws IOException {
        assumeTrue(Files.isRegularFile(modelDir().resolve("model.onnx")), "model files not present in " + modelDir());
        OnnxModelFiles files = OnnxModelFiles.resolve(modelDir(), "model.onnx", "tokenizer.json");
        embeddings = new PromptedEmbeddingService(new OnnxTextEmbedder(files, "embeddinggemma-300m", 2048, 0, 768),
                16, 2, true, new SimpleMeterRegistry());
        embeddings.warmUp();
        EmbeddingFingerprint fingerprint = embeddings.fingerprint();
        store = new LuceneIndexStore(dir.resolve("index"), new EmbabelEmbeddingServiceAdapter(embeddings), fingerprint,
                new IndexManifest.Chunker(chunkSize, overlap, ProvenanceChunkTransformer.TRANSFORMER_VERSION), 16,
                new ProvenanceChunkTransformer()).open();
        ChatbotProperties properties = new ChatbotProperties(dir,
                new ChatbotProperties.Embedding("onnx", null, null, 16, 2, true),
                new ChatbotProperties.Knowledge(DataSize.ofMegabytes(20), Set.of("md")),
                new ChatbotProperties.Index(dir.resolve("index"), false, chunkSize, overlap, 16),
                new ChatbotProperties.Ingestion(true, true),
                new ChatbotProperties.Retrieval(8, 3, 60, 0.0, 0.0, sufficientCosine, 500),
                new ChatbotProperties.Chat(com.personal.chatbot.models.chat.AnswerMode.DETERMINISTIC, 4, 0.2, 0.1, 6000, 600, 10, 1000, java.time.Duration.ofHours(24)));
        retrieval = new RetrievalService(store, new RetrievalTraceStore(500), properties, new SimpleMeterRegistry());

        JsonMapper mapper = JsonMapper.builder().build();
        questionSet = mapper.readValue(Files.readString(Path.of("src/test/resources/eval/questions.json")), QuestionSet.class);
        documentIdsByKey = new LinkedHashMap<>();
        DocumentParser parser = new DocumentParser();
        long started = System.nanoTime();
        try (Stream<Path> docs = Files.list(Path.of("src/test/resources/eval/docs"))) {
            for (Path file : docs.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                String key = file.getFileName().toString().replace(".md", "");
                Document document = new Document(key, titleOf(file), file.getFileName().toString(), "text/markdown",
                        Files.size(file), "eval-" + key, 1, Instant.now(), Instant.now(), DocumentStatus.READY,
                        null, null, null, null, null);
                store.writeDocument(parser.parse(document, file));
                documentIdsByKey.put(key, key);
            }
        }
        log.info("Indexed {} documents into {} chunks (chunkSize={}, overlap={}) in {} ms", documentIdsByKey.size(),
                store.info().chunkCount(), chunkSize, overlap, (System.nanoTime() - started) / 1_000_000);
    }

    @AfterAll
    void close() {
        if (store != null) {
            store.close();
        }
        if (embeddings != null) {
            embeddings.close();
        }
    }

    @Test
    void hybridRetrievalMeetsRecallTarget() throws IOException {
        List<ModeSummary> summaries = new ArrayList<>();
        List<QuestionOutcome> outcomes = new ArrayList<>();
        for (RetrievalMode mode : RetrievalMode.values()) {
            summaries.add(evaluate(mode, outcomes));
        }
        Report report = new Report(Instant.now().truncatedTo(ChronoUnit.SECONDS), embeddings.fingerprint().value(),
                chunkSize, overlap, store.info().chunkCount(), summaries, outcomes);
        writeReport(report);
        for (ModeSummary summary : summaries) {
            log.info(String.format(Locale.ROOT,
                    "%-7s recall@5=%.3f mrr=%.3f ndcg@10=%.3f sufficient=%.2f negSufficient=%.2f posMinCos=%.3f negMaxCos=%.3f p50=%.0fms p95=%.0fms",
                    summary.mode(), summary.recallAt5(), summary.mrr(), summary.ndcgAt10(), summary.sufficientShare(),
                    summary.negativeSufficientShare(), summary.positiveMinCosine(), summary.negativeMaxCosine(),
                    summary.p50Ms(), summary.p95Ms()));
        }
        ModeSummary hybrid = summaries.stream().filter(s -> s.mode() == RetrievalMode.HYBRID).findFirst().orElseThrow();
        assertThat(hybrid.recallAt5()).as("HYBRID recall@5").isGreaterThanOrEqualTo(minRecall);
    }

    private ModeSummary evaluate(RetrievalMode mode, List<QuestionOutcome> outcomes) {
        List<Question> questions = questionSet.questions();
        int positives = 0;
        int negatives = 0;
        double recallHits = 0;
        double reciprocalRanks = 0;
        double ndcgSum = 0;
        double sufficient = 0;
        double negativeSufficient = 0;
        double positiveMinCosine = Double.MAX_VALUE;
        double negativeMaxCosine = -1;
        List<Long> latencies = new ArrayList<>();
        for (Question question : questions) {
            RetrievalResult result = retrieval.search(new RetrievalQuery(question.question(), NDCG_K, mode, null));
            latencies.add(result.timings().totalMs());
            int firstRelevant = -1;
            if (question.isNegative()) {
                negatives++;
                if (result.evidenceSufficient()) {
                    negativeSufficient++;
                }
                negativeMaxCosine = Math.max(negativeMaxCosine, result.maxVectorScore());
            } else {
                positives++;
                double dcg = 0;
                for (RetrievedChunk hit : result.hits()) {
                    if (isRelevant(hit, question)) {
                        if (firstRelevant < 0) {
                            firstRelevant = hit.rank();
                        }
                        dcg += 1.0 / (Math.log(hit.rank() + 1) / Math.log(2));
                    }
                }
                if (firstRelevant > 0 && firstRelevant <= RECALL_K) {
                    recallHits++;
                }
                if (firstRelevant > 0) {
                    reciprocalRanks += 1.0 / firstRelevant;
                    positiveMinCosine = Math.min(positiveMinCosine, result.maxVectorScore());
                }
                ndcgSum += dcg / idealDcg(question, result);
                if (result.evidenceSufficient()) {
                    sufficient++;
                }
                if (firstRelevant < 0 || firstRelevant > RECALL_K) {
                    log.warn("[{}] {} miss: '{}' first relevant rank {} top docs {}", mode, question.id(), question.question(),
                            firstRelevant, result.hits().stream().limit(3).map(h -> h.provenance().documentId()).toList());
                }
            }
            outcomes.add(new QuestionOutcome(question.id(), mode, firstRelevant, result.maxVectorScore(),
                    result.evidenceSufficient(), result.timings().totalMs(),
                    result.hits().stream().limit(3).map(h -> h.provenance().documentId()).toList()));
        }
        latencies.sort(null);
        return new ModeSummary(mode, positives, negatives, recallHits / positives, reciprocalRanks / positives,
                ndcgSum / positives, sufficient / positives, negatives == 0 ? 0 : negativeSufficient / negatives,
                positiveMinCosine == Double.MAX_VALUE ? -1 : positiveMinCosine, negativeMaxCosine,
                percentile(latencies, 0.5), percentile(latencies, 0.95));
    }

    private static boolean isRelevant(RetrievedChunk hit, Question question) {
        if (question.isNegative() || !hit.provenance().documentId().equals(question.expectedDocument())) {
            return false;
        }
        if (question.mustContain() == null || question.mustContain().isEmpty()) {
            return true;
        }
        String text = normalise(hit.text());
        return question.mustContain().stream().anyMatch(phrase -> text.contains(normalise(phrase)));
    }

    /** Case- and whitespace-insensitive comparison (markdown paragraphs contain line breaks). */
    private static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Ideal DCG assumes at most one relevant chunk is needed (questions are answered by a single passage). */
    private static double idealDcg(Question question, RetrievalResult result) {
        return 1.0;
    }

    private static double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
    }

    private void writeReport(Report report) throws IOException {
        Path reports = Path.of("build/reports/rag-eval");
        Files.createDirectories(reports);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now());
        Path file = reports.resolve("eval-" + stamp + "-c" + chunkSize + "-o" + overlap + ".json");
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.writeString(file, mapper.writeValueAsString(report));
        log.info("Eval report written to {}", file.toAbsolutePath());
    }

    private static String titleOf(Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.filter(l -> l.startsWith("# ")).map(l -> l.substring(2).strip()).findFirst()
                    .orElse(file.getFileName().toString());
        }
    }
}
