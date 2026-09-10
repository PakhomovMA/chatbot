package com.personal.chatbot.observability;

import java.util.Locale;

/**
 * How a question was retrieved when one pass was not the whole of it
 * (docs/observability/metric-catalog.json, {@code chatbot.retrieval.workflow}).
 *
 * <p>The catalog also names {@code single}, and it is deliberately not published: a question that is
 * searched once is already measured, exactly and once, by {@code chatbot.retrieval.search}, and a
 * second timer over the same boundary would be a copy rather than a measurement
 * (docs/observability-plan.md §3.1 rule 2). What this family adds is the wall time of the branches
 * that search more than once — the model call that produces their queries and the merge included,
 * neither of which belongs to any single pass.
 */
public enum RetrievalStrategy {

    /** Phase 9a: the first pass found weak evidence, so the search was widened and merged. */
    EXPANSION,

    /** Phase 9d: the question asks for several things, so each part was searched for and merged. */
    DECOMPOSITION;

    private final String label = name().toLowerCase(Locale.ROOT);

    public String label() {
        return label;
    }
}
