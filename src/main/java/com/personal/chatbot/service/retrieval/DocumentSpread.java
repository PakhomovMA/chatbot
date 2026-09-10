package com.personal.chatbot.service.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps one document from taking every slot of a result while another document still has candidates
 * waiting (docs/eval-log.md, 2026-09-10).
 *
 * <p>Why a ranking needs this at all: the lexical facet scores a chunk for the terms it contains, and
 * in a corpus of long documents about one product each, the product's name is a term in nearly every
 * chunk of its own document. A question naming two of them ("context length of GLM-5.3 and kimi-k3")
 * therefore gets a BM25 ranking that is not a ranking of chunks but of documents: every chunk of the
 * better-matching document first, then every chunk of the other. Fused with the vector facet by
 * reciprocal rank, that pushes the whole second document down by as many places as the first one has
 * chunks — far enough that the passage answering the second half of the question falls outside
 * {@code topK}, and the answer says the corpus does not cover it.
 *
 * <p>The remedy is a quota rather than a reordering: a document may fill at most a share of the
 * returned hits, and what exceeds the quota is <em>demoted, never dropped</em>. If no other document
 * has candidates — a single-document corpus, a question restricted to one document, a corpus where
 * one document really does hold every answer — the demoted hits come straight back and the result is
 * the one fusion produced. What survives the quota keeps its fused order, so the quota decides which
 * passages are shown, never in which order they are read.
 */
final class DocumentSpread {

    /** Every slot may go to one document; selection is plain truncation. */
    static final DocumentSpread UNLIMITED = new DocumentSpread(1.0);

    private final double maxShare;

    DocumentSpread(double maxShare) {
        if (maxShare <= 0 || maxShare > 1) {
            throw new IllegalArgumentException("maxShare must be in (0, 1]: " + maxShare);
        }
        this.maxShare = maxShare;
    }

    /**
     * @param ranked     candidates best first; more than {@code topK} of them for the quota to have
     *                   anything to choose between
     * @param documentOf the document a candidate belongs to; a candidate without one is never held back
     * @return the {@code topK} candidates the quota allows, in the order they were ranked
     */
    <T> List<T> select(List<T> ranked, Function<? super T, String> documentOf, int topK) {
        int quota = Math.max(1, (int) Math.ceil(topK * maxShare));
        if (topK <= 0 || ranked.isEmpty()) {
            return List.of();
        }
        if (quota >= topK || ranked.size() <= quota) {
            return ranked.subList(0, Math.min(topK, ranked.size()));
        }
        Map<String, Integer> perDocument = new HashMap<>();
        List<Integer> selected = new ArrayList<>(topK);
        List<Integer> demoted = new ArrayList<>();
        for (int i = 0; i < ranked.size() && selected.size() < topK; i++) {
            String document = documentOf.apply(ranked.get(i));
            if (document == null || perDocument.merge(document, 1, Integer::sum) <= quota) {
                selected.add(i);
            } else {
                demoted.add(i);
            }
        }
        // Nothing is really taken away: what the quota held back fills the slots no other document
        // could, in the order it was ranked.
        for (int i = 0; i < demoted.size() && selected.size() < topK; i++) {
            selected.add(demoted.get(i));
        }
        // Membership is the quota's decision; the order stays the one fusion produced.
        return selected.stream().sorted().map(ranked::get).toList();
    }
}
