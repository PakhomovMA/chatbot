package com.personal.chatbot.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal rank fusion (Cormack et al. 2009): {@code score(d) = Σ 1 / (k + rank_i(d))} over the
 * rankings that contain {@code d}. Rank-based, so it needs no score calibration between BM25 and
 * cosine (docs/system-plan.md D4).
 */
public final class RankFusion {

    public static final int DEFAULT_K = 60;

    private RankFusion() {
    }

    /** @param rankings ordered candidate keys per facet, best first; duplicates within one ranking are ignored */
    public static List<Fused> reciprocalRank(List<List<String>> rankings, int k) {
        if (k < 0) {
            throw new IllegalArgumentException("k must be >= 0");
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Integer> bestRank = new LinkedHashMap<>();
        for (List<String> ranking : rankings) {
            int rank = 0;
            List<String> seen = new ArrayList<>();
            for (String key : ranking) {
                if (seen.contains(key)) {
                    continue;
                }
                seen.add(key);
                rank++;
                scores.merge(key, 1.0 / (k + rank), Double::sum);
                bestRank.merge(key, rank, Math::min);
            }
        }
        List<Fused> fused = new ArrayList<>(scores.size());
        scores.forEach((key, score) -> fused.add(new Fused(key, score, bestRank.get(key))));
        // Ties (same score) are broken by the best single-facet rank, then by first appearance.
        fused.sort(Comparator.comparingDouble(Fused::score).reversed().thenComparingInt(Fused::bestRank));
        return fused;
    }

    public record Fused(String key, double score, int bestRank) {
    }
}
