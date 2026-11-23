package com.personal.chatbot.eval;

import com.personal.chatbot.models.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class EvalMetrics {
    private EvalMetrics() {
    }

    public static QuestionMetrics evaluate(EvalQuestion question, List<RetrievedChunk> hits, int recallK, int ndcgK) {
        if (question.isNegative()) {
            return new QuestionMetrics(-1, false, 0, 0, 0, false);
        }
        int firstRelevant = -1;
        double dcg = 0;
        Set<String> scoredChunks = new HashSet<>();
        for (RetrievedChunk hit : hits) {
            if (!matchesAny(hit, question.relevant())) {
                continue;
            }
            if (firstRelevant < 0 || hit.rank() < firstRelevant) {
                firstRelevant = hit.rank();
            }
            if (hit.rank() <= ndcgK && scoredChunks.add(hit.chunkId())) {
                dcg += discount(hit.rank());
            }
        }
        int matchedFragments = maximumFragmentMatches(question.relevant(), hits, recallK);
        double idealDcg = 0;
        for (int rank = 1; rank <= Math.min(question.relevant().size(), ndcgK); rank++) {
            idealDcg += discount(rank);
        }
        boolean recall = firstRelevant > 0 && firstRelevant <= recallK;
        return new QuestionMetrics(firstRelevant, recall, firstRelevant > 0 ? 1.0 / firstRelevant : 0,
                idealDcg == 0 ? 0 : dcg / idealDcg, matchedFragments / (double) question.relevant().size(),
                matchedFragments == question.relevant().size());
    }

    public static boolean isRelevant(RetrievedChunk hit, EvalQuestion.RelevantPassage target) {
        if (!hit.provenance().documentId().equals(target.document())) {
            return false;
        }
        if (target.mustContain().isEmpty()) {
            return true;
        }
        String text = normalise(hit.text());
        return target.mustContain().stream().anyMatch(phrase -> text.contains(normalise(phrase)));
    }

    private static boolean matchesAny(RetrievedChunk hit, List<EvalQuestion.RelevantPassage> targets) {
        return targets.stream().anyMatch(target -> isRelevant(hit, target));
    }

    private static int maximumFragmentMatches(List<EvalQuestion.RelevantPassage> targets,
                                              List<RetrievedChunk> hits, int recallK) {
        List<RetrievedChunk> eligible = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk hit : hits) {
            if (hit.rank() <= recallK && seen.add(hit.chunkId())) {
                eligible.add(hit);
            }
        }
        int[] targetByHit = new int[eligible.size()];
        Arrays.fill(targetByHit, -1);
        int matches = 0;
        for (int target = 0; target < targets.size(); target++) {
            if (assignTarget(target, targets, eligible, targetByHit, new boolean[eligible.size()])) {
                matches++;
            }
        }
        return matches;
    }

    private static boolean assignTarget(int target, List<EvalQuestion.RelevantPassage> targets,
                                        List<RetrievedChunk> hits, int[] targetByHit, boolean[] visited) {
        for (int hit = 0; hit < hits.size(); hit++) {
            if (visited[hit] || !isRelevant(hits.get(hit), targets.get(target))) {
                continue;
            }
            visited[hit] = true;
            if (targetByHit[hit] < 0 || assignTarget(targetByHit[hit], targets, hits, targetByHit, visited)) {
                targetByHit[hit] = target;
                return true;
            }
        }
        return false;
    }

    private static double discount(int rank) {
        return 1.0 / (Math.log(rank + 1) / Math.log(2));
    }

    private static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public record QuestionMetrics(int firstRelevantRank, boolean recallAtK, double reciprocalRank, double ndcgAtK,
                                  double fragmentRecallAtK, boolean fullCoverageAtK) {
    }
}
