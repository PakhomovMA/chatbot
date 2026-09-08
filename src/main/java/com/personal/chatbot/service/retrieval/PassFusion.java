package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.utils.RankFusion;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Merges several retrieval passes over one question into a single evidence list, shared by the two
 * branches that search more than once: the widened second pass of {@code expandSearch} (Phase 9a) and
 * the per-part passes of {@code decomposeQuestion} (Phase 9d).
 *
 * <p>Passes are merged by reciprocal rank, the same rule that fuses the vector and lexical facets, so
 * a chunk that several passes agree on rises above one that only a single weak pass found — and a
 * part of the question that only its own pass answered still reaches the evidence. The merged hits
 * carry an RRF-over-passes {@code fusedScore}; per-facet scores keep the strongest match across the
 * passes, so a weak whole-question match cannot hide a strong match for a part. Each chunk appears
 * once, and a selected hit keeps its own rank even if another pass returned it as a neighbour.
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
                    byId.merge(chunk.chunkId(), chunk, PassFusion::strongestScores);
                    ranking.add(chunk.chunkId());
                } else {
                    neighbours.computeIfAbsent(chunk.neighbourOf(), _ -> new ArrayList<>()).add(chunk);
                }
            }
            rankings.add(ranking);
        }
        Set<String> firstPass = new LinkedHashSet<>(rankings.getFirst());

        List<RetrievedChunk> hits = new ArrayList<>();
        List<RankFusion.Fused> selected = RankFusion.reciprocalRank(rankings, rrfK).stream().limit(topK).toList();
        Set<String> selectedIds = selected.stream().map(RankFusion.Fused::key).collect(Collectors.toSet());
        Set<String> emitted = new LinkedHashSet<>();
        int added = 0;
        int rank = 0;
        for (RankFusion.Fused fused : selected) {
            RetrievedChunk hit = byId.get(fused.key()).withRanking(fused.score(), ++rank);
            hits.add(hit);
            if (!firstPass.contains(hit.chunkId())) {
                added++;
            }
            for (RetrievedChunk neighbour : neighbours.getOrDefault(hit.chunkId(), List.of())) {
                if (!selectedIds.contains(neighbour.chunkId()) && emitted.add(neighbour.chunkId())) {
                    hits.add(neighbour.withRanking(hit.fusedScore(), hit.rank()));
                }
            }
        }
        return new Merged(List.copyOf(hits), added);
    }

    private static RetrievedChunk strongestScores(RetrievedChunk first, RetrievedChunk next) {
        return new RetrievedChunk(first.chunkId(), first.text(), first.provenance(),
                max(first.vectorScore(), next.vectorScore()), max(first.textScore(), next.textScore()),
                first.fusedScore(), first.rank(), null);
    }

    private static @Nullable Double max(@Nullable Double first, @Nullable Double next) {
        if (first == null) {
            return next;
        }
        if (next == null) {
            return first;
        }
        return Math.max(first, next);
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
    static RetrievalTimings timings(List<RetrievalResult> passes, long totalMs) {
        return new RetrievalTimings(
                passes.stream().mapToLong(p -> p.timings().vectorMs()).sum(),
                passes.stream().mapToLong(p -> p.timings().textMs()).sum(),
                passes.stream().mapToLong(p -> p.timings().fusionMs()).sum(),
                totalMs);
    }
}
