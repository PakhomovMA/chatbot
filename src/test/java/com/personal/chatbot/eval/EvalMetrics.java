package com.personal.chatbot.eval;

import com.personal.chatbot.models.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;

public final class EvalMetrics {
    private EvalMetrics() {
    }

    public static QuestionMetrics evaluate(EvalQuestion question, List<RetrievedChunk> hits, int recallK, int ndcgK) {
        if (question.isNegative()) {
            return new QuestionMetrics(-1, false, 0, 0, 0, false);
        }
        List<EvalQuestion.RelevantPassage> targets = question.relevant();
        int firstRelevant = hits.stream().filter(hit -> matchesAny(hit, targets))
                .mapToInt(RetrievedChunk::rank).min().orElse(-1);
        // Binary relevance per labelled target: a chunk is credited only while it can take a target no
        // better-ranked chunk holds, so a phrase repeated across chunks cannot push DCG above ideal DCG.
        // Hits come in rank order, so their gains only fall; greedy augmentation is then the best assignment.
        FragmentMatching<RetrievedChunk> ranking = FragmentMatching.of(targets);
        double dcg = 0;
        for (RetrievedChunk hit : distinctUpTo(hits, ndcgK)) {
            if (ranking.add(hit)) {
                dcg += discount(hit.rank());
            }
        }
        double idealDcg = 0;
        for (int rank = 1; rank <= Math.min(targets.size(), ndcgK); rank++) {
            idealDcg += discount(rank);
        }
        Coverage coverage = coverage(question, distinctUpTo(hits, recallK));
        boolean recall = firstRelevant > 0 && firstRelevant <= recallK;
        return new QuestionMetrics(firstRelevant, recall, firstRelevant > 0 ? 1.0 / firstRelevant : 0,
                idealDcg == 0 ? 0 : dcg / idealDcg, coverage.fragmentRecall(), coverage.full());
    }

    /**
     * How many labelled targets the given passages cover, each passage serving at most one target: the
     * evidence a model would see when exactly these passages reach the prompt.
     */
    public static Coverage coverage(EvalQuestion question, List<RetrievedChunk> passages) {
        if (question.isNegative()) {
            return new Coverage(0, false);
        }
        FragmentMatching<RetrievedChunk> matching = FragmentMatching.of(question.relevant());
        Set<String> seen = new HashSet<>();
        passages.stream().filter(passage -> seen.add(passage.chunkId())).forEach(matching::add);
        int targets = question.relevant().size();
        return new Coverage(matching.matched() / (double) targets, matching.matched() == targets);
    }

    public static boolean isRelevant(RetrievedChunk hit, EvalQuestion.RelevantPassage target) {
        return hit.provenance().documentId().equals(target.document()) && containsAny(hit.text(), target);
    }

    /** Case- and whitespace-insensitive match of any alternative phrase; no phrases means the whole document. */
    static boolean containsAny(String text, EvalQuestion.RelevantPassage target) {
        if (target.mustContain().isEmpty()) {
            return true;
        }
        String normalised = normalise(text);
        return target.mustContain().stream().anyMatch(phrase -> normalised.contains(normalise(phrase)));
    }

    private static boolean matchesAny(RetrievedChunk hit, List<EvalQuestion.RelevantPassage> targets) {
        return targets.stream().anyMatch(target -> isRelevant(hit, target));
    }

    /** Hits ranked at most {@code k}, in rank order, each chunk once. */
    private static List<RetrievedChunk> distinctUpTo(List<RetrievedChunk> hits, int k) {
        Set<String> seen = new HashSet<>();
        return hits.stream().filter(hit -> hit.rank() <= k)
                .sorted(Comparator.comparingInt(RetrievedChunk::rank))
                .filter(hit -> seen.add(hit.chunkId()))
                .toList();
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

    /**
     * @param fragmentRecall share of labelled targets covered
     * @param full           every labelled target is covered
     */
    public record Coverage(double fragmentRecall, boolean full) {
    }

    /**
     * Incremental maximum bipartite matching between labelled targets and items (chunks), one target per item
     * and one item per target. {@link #add} tries an augmenting path from the new item; an item that fails
     * stays unmatched, which keeps the matching maximum (Kuhn's algorithm visits each item once).
     */
    static final class FragmentMatching<T> {
        private final List<EvalQuestion.RelevantPassage> targets;
        private final BiPredicate<T, EvalQuestion.RelevantPassage> relevant;
        private final List<T> items = new ArrayList<>();
        private final int[] itemByTarget;
        private int matched;

        FragmentMatching(List<EvalQuestion.RelevantPassage> targets, BiPredicate<T, EvalQuestion.RelevantPassage> relevant) {
            this.targets = List.copyOf(targets);
            this.relevant = relevant;
            this.itemByTarget = new int[targets.size()];
            Arrays.fill(itemByTarget, -1);
        }

        static FragmentMatching<RetrievedChunk> of(List<EvalQuestion.RelevantPassage> targets) {
            return new FragmentMatching<>(targets, EvalMetrics::isRelevant);
        }

        /** @return whether the item took a target, possibly by moving earlier items to other targets */
        boolean add(T item) {
            items.add(item);
            if (augment(items.size() - 1, new boolean[targets.size()])) {
                matched++;
                return true;
            }
            return false;
        }

        int matched() {
            return matched;
        }

        private boolean augment(int item, boolean[] visited) {
            for (int target = 0; target < targets.size(); target++) {
                if (visited[target] || !relevant.test(items.get(item), targets.get(target))) {
                    continue;
                }
                visited[target] = true;
                if (itemByTarget[target] < 0 || augment(itemByTarget[target], visited)) {
                    itemByTarget[target] = item;
                    return true;
                }
            }
            return false;
        }
    }
}
