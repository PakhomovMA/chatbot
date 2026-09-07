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
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.utils.CosineScores;
import com.personal.chatbot.utils.EmbeddingModeScope;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
    private final MeterRegistry meterRegistry;

    public RetrievalService(LuceneIndexStore indexStore, RetrievalTraceStore traces, ChatbotProperties.Retrieval settings,
                            MeterRegistry meterRegistry) {
        this.indexStore = indexStore;
        this.traces = traces;
        this.settings = settings;
        this.fusion = new HitFusion(settings.rrfK());
        this.meterRegistry = meterRegistry;
    }

    @Override
    public RetrievalResult search(RetrievalQuery query) {
        long started = System.nanoTime();
        String text = query.query().strip();
        RetrievalMode mode = Objects.requireNonNullElse(query.mode(), RetrievalMode.HYBRID);
        int topK = query.topK() != null ? query.topK() : settings.topK();
        Set<String> documentFilter = query.documentIds() == null || query.documentIds().isEmpty() ? null : query.documentIds();
        int candidates = topK * settings.candidateMultiplier() * (documentFilter != null ? 2 : 1);

        long vectorStart = System.nanoTime();
        List<SimilarityResult<Chunk>> vector = mode == RetrievalMode.TEXT ? List.of() : vectorSearch(text, candidates);
        long vectorMs = millisSince(vectorStart);
        long textStart = System.nanoTime();
        List<SimilarityResult<Chunk>> lexical = mode == RetrievalMode.VECTOR ? List.of() : textSearch(text, candidates);
        long textMs = millisSince(textStart);

        // Expansion is timed with fusion: both are post-processing of the two facet queries.
        long fusionStart = System.nanoTime();
        List<RetrievedChunk> hits = neighbourExpansion(query).expand(fusion.fuse(mode, vector, lexical, documentFilter, topK));
        long fusionMs = millisSince(fusionStart);

        double maxVector = maxVectorScore(hits);
        boolean sufficient = sufficient(hits, maxVector, settings.sufficientCosine());
        RetrievalResult result = new RetrievalResult(UUID.randomUUID().toString(), text, mode, topK, candidates, hits,
                sufficient, maxVector, new RetrievalTimings(vectorMs, textMs, fusionMs, millisSince(started)), Instant.now());
        traces.record(result);
        Timer.builder("chatbot.retrieval").tag("mode", mode.name().toLowerCase()).register(meterRegistry)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        DistributionSummary.builder("chatbot.retrieval.hits").register(meterRegistry).record(hits.size());
        log.debug("Retrieval [{}] mode={} '{}' -> {} passages (maxCosine={}, sufficient={}) in {} ms", result.traceId(), mode,
                text, hits.size(), String.format("%.3f", maxVector), sufficient, result.timings().totalMs());
        return result;
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

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
