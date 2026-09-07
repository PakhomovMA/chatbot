package com.personal.chatbot.service.retrieval;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.common.core.types.SimilarityResult;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import com.personal.chatbot.utils.CosineScores;
import com.personal.chatbot.utils.RankFusion;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges the vector and lexical facets into one ranked result (docs/system-plan.md D4): optional
 * document filter, reciprocal rank fusion in HYBRID mode, the facet's own order otherwise, then
 * truncation to {@code topK}. Both raw scores travel with every hit so the playground can show them.
 */
public class HitFusion {

    private final int rrfK;

    public HitFusion(int rrfK) {
        this.rrfK = rrfK;
    }

    public List<RetrievedChunk> fuse(RetrievalMode mode, List<SimilarityResult<Chunk>> vector,
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
            case HYBRID -> RankFusion.reciprocalRank(List.of(vectorRanking, textRanking), rrfK);
            case VECTOR -> singleFacet(vectorRanking, cosines);
            case TEXT -> singleFacet(textRanking, bm25);
        };
        List<RetrievedChunk> hits = new ArrayList<>(Math.min(topK, ranked.size()));
        for (RankFusion.Fused fused : ranked) {
            if (hits.size() == topK) {
                break;
            }
            hits.add(ChunkMapper.toRetrievedChunk(chunks.get(fused.key()), cosines.get(fused.key()),
                    bm25.get(fused.key()), fused.score(), hits.size() + 1));
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
}
