package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.utils.RankFusion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges several retrieval passes over one question into a single evidence list, shared by the two
 * branches that search more than once: the widened second pass of {@code expandSearch} (Phase 9a) and
 * the per-part passes of {@code decomposeQuestion} (Phase 9d).
 *
 * <p>Passes are merged by reciprocal rank, the same rule that fuses the vector and lexical facets, so
 * a chunk that several passes agree on rises above one that only a single weak pass found — and a
 * part of the question that only its own pass answered still reaches the evidence. The merged hits
 * carry an RRF-over-passes {@code fusedScore}; the per-facet scores stay as the pass that found the
 * chunk reported them, and a hit keeps the neighbours it was returned with.
 *
 * <p>{@link #query} and {@link #timings} describe the merged result the same way for both branches.
 */
final class PassFusion {

    /** @param addedHits hits that the first pass had not found, the value of the extra passes */
    record Merged(List<RetrievedChunk> hits, int addedHits) {
    }

    private PassFusion() {
    }

    static Merged merge(List<RetrievalResult> passes, int topK, int rrfK) {
        Map<String, RetrievedChunk> byId = new LinkedHashMap<>();
        Map<String, List<RetrievedChunk>> neighbours = new LinkedHashMap<>();
        List<List<String>> rankings = new ArrayList<>(passes.size());
        for (RetrievalResult pass : passes) {
            List<String> ranking = new ArrayList<>();
            for (RetrievedChunk chunk : pass.hits()) {
                if (chunk.isHit()) {
                    byId.putIfAbsent(chunk.chunkId(), chunk);
                    ranking.add(chunk.chunkId());
                } else {
                    neighbours.computeIfAbsent(chunk.neighbourOf(), _ -> new ArrayList<>()).add(chunk);
                }
            }
            rankings.add(ranking);
        }
        Set<String> firstPass = new LinkedHashSet<>(rankings.getFirst());

        List<RetrievedChunk> hits = new ArrayList<>();
        int added = 0;
        int rank = 0;
        for (RankFusion.Fused fused : RankFusion.reciprocalRank(rankings, rrfK)) {
            if (rank == topK) {
                break;
            }
            RetrievedChunk hit = byId.get(fused.key()).withRanking(fused.score(), ++rank);
            hits.add(hit);
            if (!firstPass.contains(hit.chunkId())) {
                added++;
            }
            for (RetrievedChunk neighbour : neighbours.getOrDefault(hit.chunkId(), List.of())) {
                if (!byId.containsKey(neighbour.chunkId())) {
                    hits.add(neighbour.withRanking(hit.fusedScore(), hit.rank()));
                }
            }
        }
        return new Merged(List.copyOf(hits), added);
    }

    /** The queries that produced the evidence, in the order they ran, as one diagnostics string. */
    static String query(String first, List<String> rest) {
        List<String> all = new ArrayList<>(rest.size() + 1);
        all.add(first);
        all.addAll(rest);
        return String.join(" | ", all.stream().distinct().toList());
    }

    /**
     * Facet times add up over the passes, but the total is the wall time of the branch: the extra
     * passes may have run in parallel, and the time spent producing their queries counts too.
     */
    static RetrievalTimings timings(List<RetrievalResult> passes, long tookMs) {
        return new RetrievalTimings(
                passes.stream().mapToLong(p -> p.timings().vectorMs()).sum(),
                passes.stream().mapToLong(p -> p.timings().textMs()).sum(),
                passes.stream().mapToLong(p -> p.timings().fusionMs()).sum(),
                passes.getFirst().timings().totalMs() + tookMs);
    }
}
