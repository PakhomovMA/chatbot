package com.personal.chatbot.eval;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.RewrittenQueries;
import com.personal.chatbot.models.agent.HypotheticalPassage;
import com.personal.chatbot.service.chat.EvidenceExpander;
import com.personal.chatbot.support.DerivationCaches;
import com.personal.chatbot.models.agent.StandaloneQuery;
import com.personal.chatbot.models.agent.SubQuestions;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.embedding.EmbeddingFingerprint;
import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.retrieval.ExpansionStrategy;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.chat.ConversationQueryRewriter;
import com.personal.chatbot.service.chat.GroundedAnswerPrompt;
import com.personal.chatbot.service.chat.GroundingInstructions;
import com.personal.chatbot.service.chat.QuestionDecomposer;
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
import com.personal.chatbot.service.retrieval.SubQuestionSearch;
import com.personal.chatbot.observability.RetrievalStrategy;
import com.personal.chatbot.observability.RetrievalWorkflow;
import com.personal.chatbot.support.ChatSettings;
import com.personal.chatbot.support.TestObservations;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    /** An evidence budget too small for a multi-part question, standing in for a larger corpus. */
    private static final int TIGHT_TOP_K = 3;

    /** @param expectedDocument null marks a negative question: nothing in the corpus answers it. */
    record Question(String id, String question, @Nullable String expectedDocument, List<String> mustContain) {
        boolean isNegative() {
            return expectedDocument == null;
        }
    }

    record QuestionSet(String description, List<Question> questions) {
    }

    /** @param hitDocuments the document of every returned hit, in rank order: what one document filled */
    record QuestionOutcome(String id, RetrievalMode mode, int firstRelevantRank, double topCosine, boolean sufficient,
                           long totalMs, List<String> hitDocuments) {
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
    /** The production chatbot.retrieval.max-document-share; sweep it with -Peval.maxDocumentShare. */
    private final double maxDocumentShare = Double.parseDouble(System.getProperty("eval.maxDocumentShare", "0.6"));

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
                16, 2, true, TestObservations.embedding("onnx", "embeddinggemma-300m"));
        embeddings.warmUp();
        EmbeddingFingerprint fingerprint = embeddings.fingerprint();
        store = new LuceneIndexStore(dir.resolve("index"), new EmbabelEmbeddingServiceAdapter(embeddings), fingerprint,
                new IndexManifest.Chunker(chunkSize, overlap, ProvenanceChunkTransformer.TRANSFORMER_VERSION), 16,
                new ProvenanceChunkTransformer()).open();
        retrievalSettings = new ChatbotProperties.Retrieval(8, 3, 60, 0.0, 0.0, sufficientCosine, 0, maxDocumentShare, 500);
        retrieval = new RetrievalService(store, new RetrievalTraceStore(500), retrievalSettings, TestObservations.retrieval());

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

    record ConversationCase(String id, List<String> history, String question, @Nullable String expectedDocument,
                            List<String> mustContain) {
    }

    record ConversationSet(String description, List<ConversationCase> questions) {
    }

    /** Paired Phase 9b gate with real embeddings and Ollama; production gate, validation and fallback run unchanged. */
    @Test
    void conversationRewritingDoesNotDegradeRetrieval() throws IOException {
        assumeTrue(EvalQueryWriter.ollamaAvailable(), "Ollama required for conversational rewriting eval");
        var cases = JsonMapper.builder().build().readValue(
                Files.readString(Path.of("src/test/resources/eval/questions-conversation.json")), ConversationSet.class);
        var cacheMetrics = TestObservations.create();
        var derivations = DerivationCaches.serving(cacheMetrics);
        var rewriter = new ConversationQueryRewriter(
                new GroundedAnswerPrompt(6000, 10, AnswerLanguage.AUTO),
                new GroundingInstructions(4, 3, 3, 4), 10, Duration.ofSeconds(20), TestObservations.chat(), derivations);
        var context = Mockito.mock(OperationContext.class,
                Mockito.RETURNS_DEEP_STUBS);
        List<Map<String, Object>> outcomes = new ArrayList<>();
        double baselineRecall = 0, rewrittenRecall = 0, baselineMrr = 0, rewrittenMrr = 0;
        double baselineCoverage = 0, rewrittenCoverage = 0;
        int positives = 0, baselineNegative = 0, rewrittenNegative = 0;
        var budget = new GroundedAnswerPrompt(EVIDENCE_CHAR_BUDGET, 10, AnswerLanguage.AUTO);
        try (var writer = new EvalQueryWriter(System.getProperty("eval.llm", "qwen3:14b"), 0.0, 3)) {
            // No-history questions must be byte-for-byte identical before/after preparation, with no model calls.
            for (Question golden : questionSet.questions()) {
                var input = new UserQuestion("eval", golden.id(), golden.question(),
                        List.of(), null, null);
                assertThat(rewriter.rewrite(input, context)).isSameAs(input);
            }
            Mockito.verifyNoInteractions(context);
            var output = context.ai().withLlm(ArgumentMatchers.any(LlmOptions.class))
                    .withPromptContributor(ArgumentMatchers.any()).creating(StandaloneQuery.class);
            for (ConversationCase item : cases.questions()) {
                List<ConversationTurn> history = new ArrayList<>();
                for (int i = 0; i < item.history().size(); i++) {
                    history.add(i % 2 == 0
                            ? ConversationTurn.user(item.history().get(i), Instant.EPOCH)
                            : ConversationTurn.assistant(item.history().get(i), List.of(), Instant.EPOCH));
                }
                var input = new UserQuestion("eval", item.id(), item.question(), history, null, null);
                // Only Embabel's transport is replaced; the model uses the shipped instructions, temperature and history prompt.
                Mockito.doAnswer(_ -> writer.resolveConversation(input)).when(output).fromPrompt(ArgumentMatchers.anyString());
                long started = System.nanoTime();
                var prepared = rewriter.rewrite(input, context);
                long rewriteMs = (System.nanoTime() - started) / 1_000_000;
                input.derivations().commit();
                Mockito.clearInvocations(output);
                var cached = rewriter.rewrite(input, context);
                assertThat(cached.effectiveQuery()).isEqualTo(prepared.effectiveQuery());
                Mockito.verifyNoInteractions(output);
                if (!item.id().equals("conv-07") && !item.id().equals("conv-09")) {
                    assertThat(prepared.effectiveQuery()).as("resolved reference for %s", item.id())
                            .isNotEqualTo(item.question());
                }
                RetrievalResult before = retrieval.search(input.retrievalQuery());
                RetrievalResult after = retrieval.search(prepared.retrievalQuery());
                assertThat(retrieval.search(cached.retrievalQuery()).hits()).isEqualTo(after.hits());
                Question relevance = new Question(item.id(), item.question(), item.expectedDocument(), item.mustContain());
                int beforeRank = relevantRank(before, relevance), afterRank = relevantRank(after, relevance);
                if (!relevance.isNegative()) {
                    positives++;
                    baselineRecall += beforeRank > 0 && beforeRank <= RECALL_K ? 1 : 0;
                    rewrittenRecall += afterRank > 0 && afterRank <= RECALL_K ? 1 : 0;
                    baselineMrr += beforeRank > 0 ? 1.0 / beforeRank : 0;
                    rewrittenMrr += afterRank > 0 ? 1.0 / afterRank : 0;
                    baselineCoverage += before.hits().stream().limit(budget.includedHits(before.hits()))
                            .anyMatch(h -> isRelevant(h, relevance)) ? 1 : 0;
                    rewrittenCoverage += after.hits().stream().limit(budget.includedHits(after.hits()))
                            .anyMatch(h -> isRelevant(h, relevance)) ? 1 : 0;
                } else {
                    baselineNegative += before.evidenceSufficient() ? 1 : 0;
                    rewrittenNegative += after.evidenceSufficient() ? 1 : 0;
                }
                outcomes.add(Map.of("id", item.id(), "question", item.question(), "effectiveQuery", prepared.effectiveQuery(),
                        "beforeRank", beforeRank, "afterRank", afterRank, "rewriteMs", rewriteMs));
            }
        }
        Map<String, Object> report = Map.of("outcomes", outcomes, "positives", positives,
                "baselineRecallAt5", baselineRecall / positives, "rewrittenRecallAt5", rewrittenRecall / positives,
                "baselineMrr", baselineMrr / positives, "rewrittenMrr", rewrittenMrr / positives,
                "baselineAnswerInBudget", baselineCoverage / positives, "rewrittenAnswerInBudget", rewrittenCoverage / positives,
                "baselineNegativeSufficient", baselineNegative, "rewrittenNegativeSufficient", rewrittenNegative);
        Path reports = Path.of("build/reports/rag-eval");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("conversation-rewrite.json"), JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT).build().writeValueAsString(report));
        log.info("Conversation rewriting eval: {}", report);
        System.out.println("K03 conversation cache: " + cacheMetrics.meters().get("chatbot.cache.lookup")
                .tags("layer", "derivation", "result", "hit").counter().count() + " hits; identical warm retrieval");
        assertThat(rewrittenRecall).as("paired Recall@5").isGreaterThanOrEqualTo(baselineRecall);
        assertThat(rewrittenMrr).as("paired MRR").isGreaterThanOrEqualTo(baselineMrr);
        assertThat(rewrittenCoverage).as("paired answer evidence within budget").isGreaterThanOrEqualTo(baselineCoverage);
        assertThat(rewrittenRecall / positives).isGreaterThanOrEqualTo(minRecall);
        assertThat(rewrittenNegative).as("negative sufficiency").isLessThanOrEqualTo(baselineNegative);
    }

    private static int relevantRank(RetrievalResult result, Question question) {
        for (int i = 0; i < result.hits().size(); i++) {
            if (isRelevant(result.hits().get(i), question)) {
                return i + 1;
            }
        }
        return -1;
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
        var settings = new ChatbotProperties.Retrieval(8, 3, 60, 0.0, 0.0, sufficientCosine, expandNeighbours, maxDocumentShare, 500);
        RetrievalService service = new RetrievalService(store, new RetrievalTraceStore(500), settings, TestObservations.retrieval());
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
        var metrics = TestObservations.create();
        var derivations = DerivationCaches.serving(metrics);
        var settings = ChatSettings.of(new ChatbotProperties.ExpandSearch(strategy, EXPANSION_QUERIES),
                ChatSettings.NO_DECOMPOSITION, ChatSettings.NO_COMPARISON);
        var service = new EvidenceExpander(expander,
                new GroundedAnswerPrompt(6000, 10, AnswerLanguage.AUTO), new GroundingInstructions(4, 3, 3, 4),
                settings, metrics.chatObservations(), metrics.retrievalObservations(), derivations);
        var context = Mockito.mock(OperationContext.class, Mockito.RETURNS_DEEP_STUBS);
        var runner = context.ai().withLlm(ArgumentMatchers.any(LlmOptions.class))
                .withPromptContributor(ArgumentMatchers.any());
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
            long validationNanos = 0;
            RetrievalResult result = retrieval.search(query);
            boolean weakBefore = !result.evidenceSufficient();
            double cosineBefore = result.maxVectorScore();
            boolean fired = strategy != ExpansionStrategy.NONE
                    && (forced ? !result.hits().isEmpty() : expander.worthExpanding(result));
            if (fired) {
                if (strategy == ExpansionStrategy.REWRITE) {
                    var output = runner.creating(RewrittenQueries.class);
                    Mockito.doAnswer(_ -> new RewrittenQueries(writer.rewrite(question.question(), EXPANSION_QUERIES)))
                            .when(output).fromPrompt(ArgumentMatchers.anyString());
                } else if (strategy == ExpansionStrategy.HYDE) {
                    var output = runner.creating(HypotheticalPassage.class);
                    Mockito.doAnswer(_ -> new HypotheticalPassage(writer.hypothetical(question.question()).stream()
                            .findFirst().orElse("")))
                            .when(output).fromPrompt(ArgumentMatchers.anyString());
                }
                var input = new UserQuestion("eval", question.id(), question.question(), List.of(), NDCG_K, null);
                var first = new Evidence(input, result);
                result = service.expand(first, context).retrieval();
                input.derivations().commit();
                if (needsModel(strategy)) {
                    modelLatencies.add(writer.lastCallMs());
                    if (!result.expansion().queries().isEmpty()) {
                        long validating = System.nanoTime();
                        Mockito.clearInvocations(context);
                        var warm = service.expand(first, context).retrieval();
                        assertThat(warm.hits()).as("warm expansion %s %s", strategy, question.id()).isEqualTo(result.hits());
                        assertThat(warm.evidenceSufficient()).isEqualTo(result.evidenceSufficient());
                        assertThat(warm.expansion().queries()).isEqualTo(result.expansion().queries());
                        Mockito.verifyNoInteractions(context);
                        validationNanos += System.nanoTime() - validating;
                    }
                }
                log.info(String.format(Locale.ROOT, "[%s %s%s] %s widened with %d queries: cosine %.3f -> %.3f%s",
                        setName, strategy, forced ? " forced" : "", question.id(), result.expansion().queries().size(),
                        cosineBefore, result.maxVectorScore(), weakBefore && result.evidenceSufficient() ? " (now sufficient)" : ""));
            }
            long tookMs = (System.nanoTime() - started - validationNanos) / 1_000_000;
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

    /** A question that asks for several things; every part carries its own relevance judgement. */
    record MultiPartCase(String id, String question, List<Question> parts) {
    }

    record MultiPartSet(String description, List<MultiPartCase> questions) {
    }

    /**
     * What {@code decomposeQuestion} (Phase 9d) does to a question set. The baseline is one search for
     * the whole question, the behaviour before this phase; coverage is counted per part, because that
     * is what a multi-part question loses when its parts share one query.
     *
     * @param topK                hits the question is answered from: the production default, and a
     *                            deliberately tight budget, where the parts have to compete for it
     * @param fired               questions the shipped heuristic decided to split
     * @param partsCovered        answerable parts with a relevant passage anywhere in the result
     * @param partsCoveredInBudget the same, counting only passages that fit the prompt's evidence budget
     * @param partsRecallAt5      answerable parts with a relevant passage in the first five hits
     * @param fullyCovered        questions whose every answerable part is covered within the budget
     * @param sufficientShare     share of questions whose merged evidence clears the sufficiency floor
     * @param modelP50Ms          median wall time of the splitting call, zero for the baseline
     */
    record DecompositionSummary(String questionSet, int topK, boolean decomposed, int questions, int parts, int fired,
                                double partsCovered, double partsCoveredInBudget, double partsRecallAt5,
                                double fullyCovered, double sufficientShare, double p50Ms, double modelP50Ms) {
    }

    /**
     * Phase 9d gate: searching a multi-part question per part must not cost the golden set anything
     * where the heuristic fires on it, and must cover more of the parts where they have to compete
     * for the evidence budget. Everything below the model call is the shipped code — the same
     * heuristic, the same instructions, the same merge; only Embabel's transport is replaced.
     *
     * <p>Measured at two budgets, because whether the parts compete at all is a property of the corpus:
     * at the production {@code top-k} these five small documents cover both parts of every question
     * from a single query, and the tight budget is an attempt to make them compete. Neither shows a
     * gain here (docs/eval-log.md), so the assertion is non-degradation and the numbers decide the
     * default, as they did for neighbour expansion.
     */
    @Test
    void decompositionCoversMorePartsWithoutCostingTheGoldenSet() throws IOException {
        assumeTrue(EvalQueryWriter.ollamaAvailable(), "Ollama required for question decomposition eval");
        MultiPartSet multiPart = JsonMapper.builder().build().readValue(
                Files.readString(Path.of("src/test/resources/eval/questions-multipart.json")), MultiPartSet.class);
        List<MultiPartCase> golden = questionSet.questions().stream()
                .filter(q -> !q.isNegative())
                .map(q -> new MultiPartCase(q.id(), q.question(), List.of(q)))
                .toList();
        List<DecompositionSummary> summaries = new ArrayList<>();
        List<Map<String, Object>> outcomes = new ArrayList<>();
        // The split is the model's, so it is asked once per question and reused across the budgets.
        Map<String, List<String>> splits = new LinkedHashMap<>();
        try (EvalQueryWriter writer = new EvalQueryWriter(System.getProperty("eval.llm", "qwen3:14b"), 0.0, EXPANSION_QUERIES)) {
            for (int topK : List.of(retrievalSettings.topK(), TIGHT_TOP_K)) {
                for (Map.Entry<String, List<MultiPartCase>> set
                        : Map.of("multipart", multiPart.questions(), "golden", golden).entrySet()) {
                    summaries.add(measureDecomposition(set.getKey(), set.getValue(), topK, false, writer, splits, outcomes));
                    summaries.add(measureDecomposition(set.getKey(), set.getValue(), topK, true, writer, splits, outcomes));
                }
            }
        }
        Path reports = Path.of("build/reports/rag-eval");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("decompose-question.json"), JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT).build()
                .writeValueAsString(Map.of("summary", summaries, "outcomes", outcomes)));
        for (DecompositionSummary summary : summaries) {
            log.info(String.format(Locale.ROOT,
                    "%-9s top-k %-2d %-10s parts=%d fired=%d/%d partsCovered=%.3f inBudget=%.3f partRecall@5=%.3f fullyCovered=%.3f sufficient=%.2f p50=%.0fms (model %.0fms)",
                    summary.questionSet(), summary.topK(), summary.decomposed() ? "decomposed" : "baseline",
                    summary.parts(), summary.fired(), summary.questions(), summary.partsCovered(),
                    summary.partsCoveredInBudget(), summary.partsRecallAt5(), summary.fullyCovered(),
                    summary.sufficientShare(), summary.p50Ms(), summary.modelP50Ms()));
        }
        for (int topK : List.of(retrievalSettings.topK(), TIGHT_TOP_K)) {
            for (String set : List.of("multipart", "golden")) {
                DecompositionSummary baseline = summaryOf(summaries, set, topK, false);
                DecompositionSummary decomposed = summaryOf(summaries, set, topK, true);
                assertThat(decomposed.partsCoveredInBudget())
                        .as("%s parts within the evidence budget at top-k %d", set, topK)
                        .isGreaterThanOrEqualTo(baseline.partsCoveredInBudget());
                assertThat(decomposed.fullyCovered()).as("%s questions fully covered at top-k %d", set, topK)
                        .isGreaterThanOrEqualTo(baseline.fullyCovered());
            }
        }
        assertThat(summaryOf(summaries, "golden", retrievalSettings.topK(), true).partsRecallAt5())
                .as("golden recall@5").isGreaterThanOrEqualTo(minRecall);
    }

    private static DecompositionSummary summaryOf(List<DecompositionSummary> summaries, String set, int topK,
                                                  boolean decomposed) {
        return summaries.stream().filter(s -> s.questionSet().equals(set) && s.topK() == topK && s.decomposed() == decomposed)
                .findFirst().orElseThrow();
    }

    private DecompositionSummary measureDecomposition(String setName, List<MultiPartCase> cases, int topK,
                                                      boolean decomposed, EvalQueryWriter writer,
                                                      Map<String, List<String>> splits,
                                                      List<Map<String, Object>> outcomes) {
        GroundedAnswerPrompt prompt = new GroundedAnswerPrompt(EVIDENCE_CHAR_BUDGET, 0, AnswerLanguage.EN);
        QuestionDecomposer decomposer = new QuestionDecomposer(retrieval,
                new SubQuestionSearch(new RetrievalTraceStore(500), retrievalSettings), prompt,
                new GroundingInstructions(4, EXPANSION_QUERIES, 3, 4),
                ChatSettings.of(ChatSettings.NO_EXPANSION, new ChatbotProperties.Decompose(true, 3, 4),
                        ChatSettings.NO_COMPARISON),
                TestObservations.chat(), TestObservations.retrieval());
        OperationContext context = Mockito.mock(OperationContext.class, Mockito.RETURNS_DEEP_STUBS);
        var splitCall = context.ai().withLlm(ArgumentMatchers.any(LlmOptions.class))
                .withPromptContributor(ArgumentMatchers.any()).creating(SubQuestions.class);
        Mockito.doAnswer(call -> {
            // The passes run in parallel in production; sequentially here, so the numbers are stable.
            List<RetrievalQuery> queries = List.copyOf(call.<List<RetrievalQuery>>getArgument(0));
            return queries.stream().map(retrieval::search).toList();
        }).when(context).parallelMap(ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.any());

        int fired = 0;
        int parts = 0;
        double covered = 0;
        double coveredInBudget = 0;
        double recallHits = 0;
        double fullyCovered = 0;
        double sufficient = 0;
        List<Long> latencies = new ArrayList<>();
        List<Long> modelLatencies = new ArrayList<>();
        for (MultiPartCase item : cases) {
            UserQuestion question = new UserQuestion("eval", item.id(), item.question(), List.of(), topK, null);
            long started = System.nanoTime();
            RetrievalResult result;
            if (decomposed && decomposer.shouldDecompose(question)) {
                fired++;
                boolean asked = !splits.containsKey(item.id());
                List<String> subQuestions = splits.computeIfAbsent(item.id(), _ -> writer.split(item.question()));
                Mockito.doAnswer(_ -> new SubQuestions(subQuestions)).when(splitCall)
                        .fromPrompt(ArgumentMatchers.anyString());
                Evidence evidence = decomposer.decompose(question, context);
                result = evidence.retrieval();
                if (asked) {
                    modelLatencies.add(writer.lastCallMs());
                }
            } else {
                result = retrieval.search(question.retrievalQuery());
            }
            latencies.add((System.nanoTime() - started) / 1_000_000);
            sufficient += result.evidenceSufficient() ? 1 : 0;

            List<RetrievedChunk> hits = result.hits();
            List<RetrievedChunk> inBudget = hits.subList(0, prompt.includedHits(hits));
            int answerable = 0;
            int coveredParts = 0;
            for (Question part : item.parts()) {
                if (part.isNegative()) {
                    continue;
                }
                answerable++;
                parts++;
                covered += hits.stream().anyMatch(hit -> isRelevant(hit, part)) ? 1 : 0;
                boolean inBudgetHit = inBudget.stream().anyMatch(hit -> isRelevant(hit, part));
                coveredInBudget += inBudgetHit ? 1 : 0;
                coveredParts += inBudgetHit ? 1 : 0;
                int firstRelevant = hits.stream().filter(RetrievedChunk::isHit).filter(hit -> isRelevant(hit, part))
                        .mapToInt(RetrievedChunk::rank).min().orElse(-1);
                recallHits += firstRelevant > 0 && firstRelevant <= RECALL_K ? 1 : 0;
                if (!inBudgetHit) {
                    log.info("[{} top-k {} {}] {} misses part '{}'", setName, topK,
                            decomposed ? "decomposed" : "baseline", item.id(), part.question());
                }
            }
            fullyCovered += answerable > 0 && coveredParts == answerable ? 1 : 0;
            outcomes.add(Map.of("set", setName, "topK", topK, "id", item.id(), "decomposed", decomposed,
                    "subQuestions", result.decomposed() ? result.decomposition().subQuestions() : List.of(),
                    "coveredParts", coveredParts, "answerableParts", answerable, "query", result.query()));
        }
        latencies.sort(null);
        modelLatencies.sort(null);
        return new DecompositionSummary(setName, topK, decomposed, cases.size(), parts, fired, covered / parts,
                coveredInBudget / parts, recallHits / parts, fullyCovered / cases.size(), sufficient / cases.size(),
                percentile(latencies, 0.5), percentile(modelLatencies, 0.5));
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
                    result.hits().stream().map(h -> h.provenance().documentId()).toList()));
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
