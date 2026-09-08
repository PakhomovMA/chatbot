package com.personal.chatbot.models.retrieval;

import java.util.List;

/**
 * What a decomposed retrieval did (docs/system-plan.md Phase 9d): a question that asks for several
 * things is searched once per part, and the passes are merged into one evidence list. Present on the
 * {@link RetrievalResult} that merges them, so a trace says why the evidence looks as it does.
 *
 * @param subQuestions the parts that were searched for, besides the question as a whole
 * @param addedHits    hits in the merged result that searching the whole question had not found
 * @param tookMs       wall time of the whole decomposition, model call included
 */
public record QuestionDecomposition(List<String> subQuestions, int addedHits, long tookMs) {

    /** The branch ran but produced no parts to search for: the question was searched once, as usual. */
    public static QuestionDecomposition none(long tookMs) {
        return new QuestionDecomposition(List.of(), 0, tookMs);
    }
}
