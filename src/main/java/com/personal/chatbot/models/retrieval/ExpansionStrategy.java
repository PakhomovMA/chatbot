package com.personal.chatbot.models.retrieval;

/**
 * How a second retrieval pass widens the search when the first one found weak evidence
 * (docs/system-plan.md Phase 9a). The strategies differ in what they change about the query, not in
 * how results are merged: every pass goes through the same deterministic retrieval (INV-01) and the
 * passes are fused by rank.
 */
public enum ExpansionStrategy {

    /** One retrieval per question; the branch never fires. */
    NONE,

    /** The same query again, with each hit widened by its neighbouring chunks in reading order. */
    NEIGHBOURS,

    /** The model rewrites the question into alternative phrasings, and each of them is searched. */
    REWRITE,

    /** The model writes the passage it would expect to answer the question; that text is the query (HyDE). */
    HYDE
}
