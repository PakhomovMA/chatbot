package com.personal.chatbot.models.retrieval;

import java.util.List;

/**
 * What a second retrieval pass did (docs/system-plan.md Phase 9a). Present on a {@link RetrievalResult}
 * that merges several passes, absent on a plain one, so a trace says why the evidence looks as it does.
 *
 * @param strategy   how the extra queries were produced
 * @param queries    the queries of the extra passes, in the order they ran (the original is not repeated)
 * @param addedHits  hits in the merged result that the first pass had not found
 * @param tookMs     wall time of the whole second pass, model call included
 */
public record SearchExpansion(ExpansionStrategy strategy, List<String> queries, int addedHits, long tookMs) {

    /** The strategy ran but produced no query to search with (the model returned nothing usable). */
    public static SearchExpansion none(ExpansionStrategy strategy, long tookMs) {
        return new SearchExpansion(strategy, List.of(), 0, tookMs);
    }
}
