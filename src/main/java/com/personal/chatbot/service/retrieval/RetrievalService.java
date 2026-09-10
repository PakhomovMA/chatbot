package com.personal.chatbot.service.retrieval;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.service.ResultExpander;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.observability.Measured;
import com.personal.chatbot.observability.RetrievalObservations;
import com.personal.chatbot.observability.RetrievalStage;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.utils.CosineScores;
import com.personal.chatbot.utils.EmbeddingModeScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Deterministic hybrid retrieval (docs/system-plan.md §6, D4, INV-01): runs the vector and BM25
 * facets against the Lucene store, hands them to {@link HitFusion}, optionally widens each hit with
 * its section neighbours ({@link NeighbourExpansion}), decides whether the evidence is good enough
 * and records timings, metrics and a {@link RetrievalTraceStore} entry.
 *
 * <p>Score conventions: Lucene reports cosine as {@code (1 + cos) / 2}; callers and configuration
 * work in plain cosine, converted through {@link CosineScores}. BM25 comes back normalised to
 * {@code [0, 1)} by Embabel.
 */
@Service
public class RetrievalService implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final LuceneIndexStore indexStore;
    private final RetrievalTraceStore traces;
    private final ChatbotProperties.Retrieval settings;
    private final HitFusion fusion;
    private final RetrievalObservations observations;

    public RetrievalService(LuceneIndexStore indexStore, RetrievalTraceStore traces, ChatbotProperties.Retrieval settings,
                            RetrievalObservations observations) {
        this.indexStore = indexStore;
        this.traces = traces;
        this.settings = settings;
        this.fusion = new HitFusion(settings.rrfK(), settings.maxDocumentShare());
        this.observations = observations;
    }

    @Override
    public RetrievalResult search(RetrievalQuery query) {
        RetrievalMode mode = Objects.requireNonNullElse(query.mode(), RetrievalMode.HYBRID);
        // The pass is measured whatever it ends in, index lock and query embedding included; the timer
        // it replaces counted only the passes that came back with something.
        try (Measured pass = observations.startSearch(mode)) {
            try {
                RetrievalResult result = run(query, mode, pass);
                observations.passages(result.hits().size());
                pass.succeeded();
                return result;
            } catch (Exception e) {
                pass.failed(e);
                throw e;
            }
        }
    }

    private RetrievalResult run(RetrievalQuery query, RetrievalMode mode, Measured pass) {
        String text = query.query().strip();
        int topK = query.topK() != null ? query.topK() : settings.topK();
        Set<String> documentFilter = query.documentIds() == null || query.documentIds().isEmpty() ? null : query.documentIds();
        int candidates = topK * settings.candidateMultiplier() * (documentFilter != null ? 2 : 1);

        // A facet the mode does not run publishes no stage: zero milliseconds and "did not happen" are
        // different statements, and only the second one is true here.
        Staged<List<SimilarityResult<Chunk>>> vector = mode == RetrievalMode.TEXT ? Staged.skipped()
                : stage(RetrievalStage.VECTOR, mode, () -> vectorSearch(text, candidates));
        Staged<List<SimilarityResult<Chunk>>> lexical = mode == RetrievalMode.VECTOR ? Staged.skipped()
                : stage(RetrievalStage.TEXT, mode, () -> textSearch(text, candidates));

        // Expansion is timed with fusion: both are post-processing of the two facet queries.
        Staged<List<RetrievedChunk>> postprocessed = stage(RetrievalStage.POSTPROCESS, mode, () ->
                neighbourExpansion(query).expand(fusion.fuse(mode, vector.value(), lexical.value(), documentFilter, topK)));
        List<RetrievedChunk> hits = postprocessed.value();

        double maxVector = maxVectorScore(hits);
        boolean sufficient = sufficient(hits, maxVector, settings.sufficientCosine());
        RetrievalTimings timings = new RetrievalTimings(vector.millis(), lexical.millis(), postprocessed.millis(),
                pass.elapsed().toMillis());
        RetrievalResult result = new RetrievalResult(UUID.randomUUID().toString(), text, mode, topK, candidates, hits,
                sufficient, maxVector, timings, Instant.now());
        traces.record(result);
        log.debug("Retrieval [{}] mode={} '{}' -> {} passages (maxCosine={}, sufficient={}) in {} ms", result.traceId(), mode,
                text, hits.size(), String.format("%.3f", maxVector), sufficient, result.timings().totalMs());
        return result;
    }

    /** What a stage produced and how long it took, in the milliseconds the v1 diagnostics report. */
    private record Staged<T>(T value, long millis) {

        static <T> Staged<List<T>> skipped() {
            return new Staged<>(List.of(), 0);
        }
    }

    private <T> Staged<T> stage(RetrievalStage stage, RetrievalMode mode, Supplier<T> work) {
        try (Measured measured = observations.startStage(stage, mode)) {
            try {
                T value = work.get();
                measured.succeeded();
                return new Staged<>(value, measured.elapsed().toMillis());
            } catch (RuntimeException e) {
                measured.failed(e);
                throw e;
            }
        }
    }

    /** Best cosine among the hits, or -1 when the vector facet matched nothing. */
    static double maxVectorScore(List<RetrievedChunk> hits) {
        return hits.stream().map(RetrievedChunk::vectorScore).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(-1);
    }

    /** The sufficiency rule, shared with the merged result of an expanded search (Phase 9a). */
    static boolean sufficient(List<RetrievedChunk> hits, double maxVectorScore, double floor) {
        return !hits.isEmpty() && maxVectorScore >= floor;
    }

    private NeighbourExpansion neighbourExpansion(RetrievalQuery query) {
        int chunksEachSide = query.expandNeighbours() != null ? query.expandNeighbours() : settings.expandNeighbours();
        return new NeighbourExpansion(
                (chunkId, each) -> indexStore.expand(chunkId, ResultExpander.Method.SEQUENCE, each), chunksEachSide);
    }

    private List<SimilarityResult<Chunk>> vectorSearch(String text, int candidates) {
        double luceneFloor = CosineScores.toLuceneScore(settings.minCosine());
        return EmbeddingModeScope.inQueryMode(() -> indexStore.search(ops ->
                ops.vectorSearch(TextSimilaritySearchRequest.create(text, luceneFloor, candidates), Chunk.class)));
    }

    private List<SimilarityResult<Chunk>> textSearch(String text, int candidates) {
        return indexStore.search(ops ->
                ops.textSearch(TextSimilaritySearchRequest.create(text, settings.minTextScore(), candidates), Chunk.class));
    }
}
