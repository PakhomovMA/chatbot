package com.personal.chatbot.service.retrieval;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.ChunkStructure;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.retrieval.Provenance;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.index.LuceneIndexStore;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import com.personal.chatbot.utils.CosineScores;
import com.personal.chatbot.utils.EmbeddingModeScope;
import com.personal.chatbot.utils.RankFusion;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic hybrid retrieval (docs/system-plan.md §6, D4, INV-01): vector k-NN and BM25 from the
 * Lucene store, reciprocal rank fusion, optional document filter, provenance on every hit and a
 * calibrated "evidence sufficient" flag. Every result is retained by {@link RetrievalTraceStore}.
 *
 * <p>Score conventions: Lucene reports cosine as {@code (1 + cos) / 2}; callers and configuration
 * work in plain cosine, converted here. BM25 comes back normalised to {@code [0, 1)} by Embabel.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final String NO_TITLE = "";

    private final LuceneIndexStore indexStore;
    private final RetrievalTraceStore traces;
    private final ChatbotProperties.Retrieval settings;
    private final MeterRegistry meterRegistry;

    public RetrievalService(LuceneIndexStore indexStore, RetrievalTraceStore traces, ChatbotProperties properties,
                            MeterRegistry meterRegistry) {
        this.indexStore = indexStore;
        this.traces = traces;
        this.settings = properties.retrieval();
        this.meterRegistry = meterRegistry;
    }

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

        long fusionStart = System.nanoTime();
        List<RetrievedChunk> hits = fuse(mode, vector, lexical, documentFilter, topK);
        long fusionMs = millisSince(fusionStart);

        double maxVector = hits.stream().map(RetrievedChunk::vectorScore).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(-1);
        boolean sufficient = !hits.isEmpty() && maxVector >= settings.sufficientCosine();
        RetrievalResult result = new RetrievalResult(UUID.randomUUID().toString(), text, mode, topK, candidates, hits,
                sufficient, maxVector, new RetrievalTimings(vectorMs, textMs, fusionMs, millisSince(started)), Instant.now());
        traces.record(result);
        Timer.builder("chatbot.retrieval").tag("mode", mode.name().toLowerCase()).register(meterRegistry)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        DistributionSummary.builder("chatbot.retrieval.hits").register(meterRegistry).record(hits.size());
        log.debug("Retrieval [{}] mode={} '{}' -> {} hits (maxCosine={}, sufficient={}) in {} ms", result.traceId(), mode,
                text, hits.size(), String.format("%.3f", maxVector), sufficient, result.timings().totalMs());
        return result;
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

    private List<RetrievedChunk> fuse(RetrievalMode mode, List<SimilarityResult<Chunk>> vector,
                                      List<SimilarityResult<Chunk>> lexical, @Nullable Set<String> documentFilter, int topK) {
        Map<String, Chunk> chunks = new LinkedHashMap<>();
        Map<String, Double> cosines = new LinkedHashMap<>();
        Map<String, Double> bm25 = new LinkedHashMap<>();
        List<String> vectorRanking = new ArrayList<>();
        List<String> textRanking = new ArrayList<>();
        for (SimilarityResult<Chunk> hit : vector) {
            if (accept(hit.getMatch(), documentFilter)) {
                chunks.putIfAbsent(hit.getMatch().getId(), hit.getMatch());
                cosines.put(hit.getMatch().getId(), CosineScores.fromLuceneScore(hit.getScore()));
                vectorRanking.add(hit.getMatch().getId());
            }
        }
        for (SimilarityResult<Chunk> hit : lexical) {
            if (accept(hit.getMatch(), documentFilter)) {
                chunks.putIfAbsent(hit.getMatch().getId(), hit.getMatch());
                bm25.put(hit.getMatch().getId(), hit.getScore());
                textRanking.add(hit.getMatch().getId());
            }
        }
        List<RankFusion.Fused> ranked = switch (mode) {
            case HYBRID -> RankFusion.reciprocalRank(List.of(vectorRanking, textRanking), settings.rrfK());
            case VECTOR -> singleFacet(vectorRanking, cosines);
            case TEXT -> singleFacet(textRanking, bm25);
        };
        List<RetrievedChunk> hits = new ArrayList<>(Math.min(topK, ranked.size()));
        for (RankFusion.Fused fused : ranked) {
            if (hits.size() == topK) {
                break;
            }
            Chunk chunk = chunks.get(fused.key());
            hits.add(new RetrievedChunk(chunk.getId(), originalText(chunk), provenanceOf(chunk),
                    cosines.get(fused.key()), bm25.get(fused.key()), fused.score(), hits.size() + 1));
        }
        return hits;
    }

    /** One facet keeps its own order and raw score; duplicates are dropped, as {@link RankFusion} does. */
    private static List<RankFusion.Fused> singleFacet(List<String> ranking, Map<String, Double> scores) {
        List<RankFusion.Fused> fused = new ArrayList<>(ranking.size());
        Set<String> seen = new HashSet<>();
        for (String key : ranking) {
            if (seen.add(key)) {
                fused.add(new RankFusion.Fused(key, scores.get(key), fused.size() + 1));
            }
        }
        return fused;
    }

    private static boolean accept(Chunk chunk, @Nullable Set<String> documentFilter) {
        if (documentFilter == null) {
            return true;
        }
        Object documentId = chunk.getMetadata().get(ProvenanceChunkTransformer.DOCUMENT_ID);
        return documentId != null && documentFilter.contains(documentId.toString());
    }

    /**
     * Search hits are rebuilt from Lucene documents, where Embabel sets {@code urtext = text} (the indexed
     * text with the provenance header). The original fragment is kept in metadata by the transformer.
     */
    static String originalText(Chunk chunk) {
        Object urtext = chunk.getMetadata().get(ProvenanceChunkTransformer.URTEXT);
        return urtext != null ? urtext.toString() : chunk.getUrtext();
    }

    /** Builds provenance from the metadata written by {@link ProvenanceChunkTransformer} and the chunker. */
    static Provenance provenanceOf(Chunk chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        ChunkStructure structure = chunk.getStructure();
        String path = string(metadata.get(ProvenanceChunkTransformer.SECTION_PATH), NO_TITLE);
        String separator = path.contains(ProvenanceChunkTransformer.PATH_SEPARATOR)
                ? ProvenanceChunkTransformer.PATH_SEPARATOR : ProvenanceChunkTransformer.LIST_SEPARATOR;
        List<String> sectionPath = path.isEmpty() ? List.of()
                : Arrays.stream(path.split(java.util.regex.Pattern.quote(separator))).map(String::strip).toList();
        return new Provenance(
                string(metadata.get(ProvenanceChunkTransformer.DOCUMENT_ID), string(structure.getRootDocumentId(), "")),
                string(metadata.get(ProvenanceChunkTransformer.DOCUMENT_TITLE), string(structure.getRootDocumentTitle(), NO_TITLE)),
                parseInt(metadata.get(ProvenanceChunkTransformer.DOCUMENT_VERSION), 1),
                string(metadata.get(ProvenanceChunkTransformer.SECTION_TITLE), NO_TITLE),
                sectionPath,
                chunk.getId(),
                Objects.requireNonNullElse(structure.getSequenceNumber(), parseInt(metadata.get(ChunkStructure.SEQUENCE_NUMBER), 0)),
                structure.getChunkIndex() != null ? structure.getChunkIndex() : parseInteger(metadata.get(ChunkStructure.CHUNK_INDEX)),
                structure.getTotalChunks() != null ? structure.getTotalChunks() : parseInteger(metadata.get(ChunkStructure.TOTAL_CHUNKS)),
                metadata.get(ProvenanceChunkTransformer.MEDIA_TYPE) != null ? metadata.get(ProvenanceChunkTransformer.MEDIA_TYPE).toString() : null);
    }

    private static String string(@Nullable Object value, String fallback) {
        return value != null ? value.toString() : fallback;
    }

    private static int parseInt(@Nullable Object value, int fallback) {
        Integer parsed = parseInteger(value);
        return parsed != null ? parsed : fallback;
    }

    private static @Nullable Integer parseInteger(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
