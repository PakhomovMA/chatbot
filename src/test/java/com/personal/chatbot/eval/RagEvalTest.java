package com.personal.chatbot.eval;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.embedding.EmbeddingFingerprint;
import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.retrieval.ExpansionStrategy;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.service.chat.GroundedAnswerPrompt;
import com.personal.chatbot.service.embedding.EmbabelEmbeddingServiceAdapter;
import com.personal.chatbot.service.embedding.PromptedEmbeddingService;
import com.personal.chatbot.service.embedding.onnx.OnnxModelFiles;
import com.personal.chatbot.service.embedding.onnx.OnnxTextEmbedder;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.parsing.DocumentParser;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import com.personal.chatbot.service.retrieval.RetrievalService;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import com.personal.chatbot.service.retrieval.SearchExpander;
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
    /** The production evidence budget (chatbot.chat.evidence-char-budget), so "in budget" means what the model sees. */
    private static final int EVIDENCE_CHAR_BUDGET = 6000;
    private static final int NDCG_K = 10;
    /** The production default of chatbot.chat.expand-search.queries. */
    private static final int EXPANSION_QUERIES = 3;

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
                  List<QuestionOutcome> outcomes, List<ExpansionSummary> expansion) {
    }

    /**
     * What neighbour expansion buys and costs, measured on HYBRID. Ranking metrics are computed over
     * matched hits only, so they stay comparable; the rest describes the evidence the model would see.
     *
     * @param answerCoverage   share of positive questions whose expected phrase appears in some returned passage
     * @param answerInBudget   the same, but only counting passages that fit the prompt's evidence budget
     * @param passages         average passages returned per question (hits plus neighbours)
     * @param passagesInBudget average passages that fit the evidence budget
     * @param hitsInBudget     average matched hits that fit it: what expansion displaces
     * @param evidenceChars    average characters of returned passage text
     */
    record ExpansionSummary(int expandNeighbours, double recallAt5, double mrr, double answerCoverage,
                            double answerInBudget, double passages, double passagesInBudget, double hitsInBudget,
                            double evidenceChars, double p50Ms) {
    }

    /**
     * What the {@code expandSearch} branch does to a question set (docs/system-plan.md Phase 9a).
     * NONE is the baseline: one retrieval per question, the behaviour before this phase.
     *
     * @param forced         the branch was run on every question, not only where the condition fired: what the
     *                       strategy is worth as retrieval, separated from how often the condition is right
     * @param triggered      positives the branch fired on
     * @param recovered      of those, how many were under the floor before and above it after the widened pass
     * @param recallAt5      share of positives with a relevant chunk in the first five hits
     * @param answerCoverage share of positives whose expected phrase is in some returned passage
     * @param answerInBudget the same, counting only passages that fit the prompt's evidence budget
     * @param negSufficient  share of negatives the merged evidence claims to answer (must stay at zero)
     * @param p50Ms          median wall time per question, model call included
     * @param modelP50Ms     median wall time of the query-writing model call, zero for the strategies without one
     */
    record StrategySummary(String questionSet, ExpansionStrategy strategy, boolean forced, int positives, int negatives,
                           int triggered, int recovered, double recallAt5, double mrr, double answerCoverage,
                           double answerInBudget, double negSufficient, double p50Ms, double modelP50Ms) {
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
    private ChatbotProperties.Retrieval retrievalSettings;
    private QuestionSet questionSet;
    private Map<String, String> documentIdsByKey;

    private static QuestionSet questionSet(String file) throws IOException {
        return JsonMapper.builder().build()
                .readValue(Files.readString(Path.of("src/test/resources/eval", file)), QuestionSet.class);
    }

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
        retrievalSettings = new ChatbotProperties.Retrieval(8, 3, 60, 0.0, 0.0, sufficientCosine, 0, 500);
        retrieval = new RetrievalService(store, new RetrievalTraceStore(500), retrievalSettings, new SimpleMeterRegistry());

        questionSet = questionSet("questions.json");
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
                chunkSize, overlap, store.info().chunkCount(), summaries, outcomes, List.of());
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

    /**
     * Neighbour expansion trade-off (docs/system-plan.md §6, post-filter 5): the knob is a corpus
     * decision, so it is measured rather than guessed. Reported, not asserted — the default stays 0
     * until a corpus shows a reason to raise it.
     */
    @Test
    void neighbourExpansionTradeOff() throws IOException {
        List<ExpansionSummary> summaries = new ArrayList<>();
        for (int expand : List.of(0, 1, 2)) {
            summaries.add(measureExpansion(expand));
        }
        Path reports = Path.of("build/reports/rag-eval");
        Files.createDirectories(reports);
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.writeString(reports.resolve("expansion-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(java.time.LocalDateTime.now()) + ".json"), mapper.writeValueAsString(summaries));
        for (ExpansionSummary summary : summaries) {
            log.info(String.format(Locale.ROOT,
                    "expand=%d recall@5=%.3f mrr=%.3f answerCoverage=%.3f inBudget=%.3f passages=%.1f (%.1f in budget, %.1f of them hits) chars=%.0f p50=%.0fms",
                    summary.expandNeighbours(), summary.recallAt5(), summary.mrr(), summary.answerCoverage(),
                    summary.answerInBudget(), summary.passages(), summary.passagesInBudget(), summary.hitsInBudget(),
                    summary.evidenceChars(), summary.p50Ms()));
        }
    }

    private ExpansionSummary measureExpansion(int expandNeighbours) {
        var settings = new ChatbotProperties.Retrieval(8, 3, 60, 0.0, 0.0, sufficientCosine, expandNeighbours, 500);
        RetrievalService service = new RetrievalService(store, new RetrievalTraceStore(500), settings, new SimpleMeterRegistry());
        GroundedAnswerPrompt prompt = new GroundedAnswerPrompt(EVIDENCE_CHAR_BUDGET, 0, AnswerLanguage.EN);
        int positives = 0;
        double recallHits = 0;
        double reciprocalRanks = 0;
        double covered = 0;
        double coveredInBudget = 0;
        double passages = 0;
        double passagesInBudget = 0;
        double hitsInBudget = 0;
        double chars = 0;
        List<Long> latencies = new ArrayList<>();
        for (Question question : questionSet.questions()) {
            RetrievalResult result = service.search(new RetrievalQuery(question.question(), NDCG_K, RetrievalMode.HYBRID, null));
            latencies.add(result.timings().totalMs());
            if (question.isNegative()) {
                continue;
            }
            positives++;
            List<RetrievedChunk> all = result.hits();
            int inBudget = prompt.includedHits(all);
            passages += all.size();
            passagesInBudget += inBudget;
            hitsInBudget += all.subList(0, inBudget).stream().filter(RetrievedChunk::isHit).count();
            chars += all.stream().mapToInt(hit -> hit.text().length()).sum();
            // Ranking metrics see matched chunks only, so expansion cannot flatter them.
            int firstRelevant = all.stream().filter(RetrievedChunk::isHit).filter(hit -> isRelevant(hit, question))
                    .mapToInt(RetrievedChunk::rank).min().orElse(-1);
            if (firstRelevant > 0) {
                reciprocalRanks += 1.0 / firstRelevant;
                if (firstRelevant <= RECALL_K) {
                    recallHits++;
                }
            }
            if (all.stream().anyMatch(hit -> isRelevant(hit, question))) {
                covered++;
            }
            if (all.subList(0, inBudget).stream().anyMatch(hit -> isRelevant(hit, question))) {
                coveredInBudget++;
            }
        }
        latencies.sort(null);
        return new ExpansionSummary(expandNeighbours, recallHits / positives, reciprocalRanks / positives,
                covered / positives, coveredInBudget / positives, passages / positives, passagesInBudget / positives,
                hitsInBudget / positives, chars / positives, percentile(latencies, 0.5));
    }

    /**
     * Phase 9a: what each {@code expandSearch} strategy is worth. The branch fires on the questions the
     * first pass answers badly, so it is measured on two sets: the golden questions, where it should
     * almost never fire, and the hard set, which is written in user words rather than documentation
     * words. Reported, not asserted — the default strategy follows from these numbers, and the
     * model-driven strategies need Ollama, which the gate does not require.
     */
    @Test
    void expandSearchStrategies() throws IOException {
        List<Map.Entry<String, QuestionSet>> sets = List.of(
                Map.entry("golden", questionSet), Map.entry("hard", questionSet("questions-hard.json")));
        boolean withModel = EvalQueryWriter.ollamaAvailable();
        if (!withModel) {
            log.warn("Ollama is not reachable at {}: measuring the strategies that need no model call only",
                    EvalQueryWriter.baseUrl());
        }
        List<StrategySummary> summaries = new ArrayList<>();
        try (EvalQueryWriter writer = withModel
                ? new EvalQueryWriter(System.getProperty("eval.llm", "qwen3:14b"), 0.1, EXPANSION_QUERIES) : null) {
            for (Map.Entry<String, QuestionSet> set : sets) {
                for (ExpansionStrategy strategy : ExpansionStrategy.values()) {
                    if (writer == null && needsModel(strategy)) {
                        continue;
                    }
                    summaries.add(measureStrategy(set.getKey(), set.getValue(), strategy, false, writer));
                }
            }
            // The condition fires on few questions, which says little about the strategies themselves;
            // the hard set is therefore also measured with the branch forced on every question.
            for (ExpansionStrategy strategy : ExpansionStrategy.values()) {
                if (strategy != ExpansionStrategy.NONE && (writer != null || !needsModel(strategy))) {
                    summaries.add(measureStrategy("hard", questionSet("questions-hard.json"), strategy, true, writer));
                }
            }
        }
        Path reports = Path.of("build/reports/rag-eval");
        Files.createDirectories(reports);
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.writeString(reports.resolve("expand-search-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(java.time.LocalDateTime.now()) + ".json"), mapper.writeValueAsString(summaries));
        for (StrategySummary summary : summaries) {
            log.info(String.format(Locale.ROOT,
                    "%-6s %-10s%s recall@5=%.3f mrr=%.3f answer=%.3f inBudget=%.3f fired=%d/%d recovered=%d negSufficient=%.2f p50=%.0fms (model %.0fms)",
                    summary.questionSet(), summary.strategy(), summary.forced() ? " forced" : "       ", summary.recallAt5(), summary.mrr(),
                    summary.answerCoverage(), summary.answerInBudget(), summary.triggered(), summary.positives(),
                    summary.recovered(), summary.negSufficient(), summary.p50Ms(), summary.modelP50Ms()));
        }
    }

    private static boolean needsModel(ExpansionStrategy strategy) {
        return strategy == ExpansionStrategy.REWRITE || strategy == ExpansionStrategy.HYDE;
    }

    private StrategySummary measureStrategy(String setName, QuestionSet questions, ExpansionStrategy strategy,
                                            boolean forced, @Nullable EvalQueryWriter writer) {
        SearchExpander expander = new SearchExpander(retrieval, new RetrievalTraceStore(500), retrievalSettings);
        GroundedAnswerPrompt prompt = new GroundedAnswerPrompt(EVIDENCE_CHAR_BUDGET, 0, AnswerLanguage.EN);
        int positives = 0;
        int negatives = 0;
        int triggered = 0;
        int recovered = 0;
        int negativeSufficient = 0;
        double recallHits = 0;
        double reciprocalRanks = 0;
        double covered = 0;
        double coveredInBudget = 0;
        List<Long> latencies = new ArrayList<>();
        List<Long> modelLatencies = new ArrayList<>();
        for (Question question : questions.questions()) {
            RetrievalQuery query = new RetrievalQuery(question.question(), NDCG_K, RetrievalMode.HYBRID, null);
            long started = System.nanoTime();
            RetrievalResult result = retrieval.search(query);
            boolean weakBefore = !result.evidenceSufficient();
            double cosineBefore = result.maxVectorScore();
            boolean fired = strategy != ExpansionStrategy.NONE
                    && (forced ? !result.hits().isEmpty() : expander.worthExpanding(result));
            if (fired) {
                long modelMs = 0;
                List<String> extra = switch (strategy) {
                    case NEIGHBOURS -> List.of(question.question());
                    case REWRITE -> writer.rewrite(question.question(), EXPANSION_QUERIES);
                    case HYDE -> writer.hypothetical(question.question());
                    case NONE -> List.of();
                };
                if (needsModel(strategy)) {
                    modelMs = writer.lastCallMs();
                    modelLatencies.add(modelMs);
                }
                result = expander.expand(query, result, strategy, extra, modelMs);
                log.info(String.format(Locale.ROOT, "[%s %s%s] %s widened with %d queries: cosine %.3f -> %.3f%s",
                        setName, strategy, forced ? " forced" : "", question.id(), result.expansion().queries().size(),
                        cosineBefore, result.maxVectorScore(), weakBefore && result.evidenceSufficient() ? " (now sufficient)" : ""));
            }
            long tookMs = (System.nanoTime() - started) / 1_000_000;
            latencies.add(tookMs);
            if (question.isNegative()) {
                negatives++;
                if (result.evidenceSufficient()) {
                    negativeSufficient++;
                    log.warn("[{} {}] {} negative claims sufficient evidence (cosine {})", setName, strategy,
                            question.id(), String.format(Locale.ROOT, "%.3f", result.maxVectorScore()));
                }
                continue;
            }
            positives++;
            if (fired) {
                triggered++;
                if (weakBefore && result.evidenceSufficient()) {
                    recovered++;
                }
            }
            List<RetrievedChunk> all = result.hits();
            int firstRelevant = all.stream().filter(RetrievedChunk::isHit).filter(hit -> isRelevant(hit, question))
                    .mapToInt(RetrievedChunk::rank).min().orElse(-1);
            if (firstRelevant > 0) {
                reciprocalRanks += 1.0 / firstRelevant;
                if (firstRelevant <= RECALL_K) {
                    recallHits++;
                }
            } else {
                log.info("[{} {}] {} miss: '{}'", setName, strategy, question.id(), question.question());
            }
            if (all.stream().anyMatch(hit -> isRelevant(hit, question))) {
                covered++;
            }
            if (all.subList(0, prompt.includedHits(all)).stream().anyMatch(hit -> isRelevant(hit, question))) {
                coveredInBudget++;
            }
        }
        latencies.sort(null);
        modelLatencies.sort(null);
        return new StrategySummary(setName, strategy, forced, positives, negatives, triggered, recovered,
                recallHits / positives, reciprocalRanks / positives, covered / positives, coveredInBudget / positives,
                negatives == 0 ? 0 : (double) negativeSufficient / negatives, percentile(latencies, 0.5),
                percentile(modelLatencies, 0.5));
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
