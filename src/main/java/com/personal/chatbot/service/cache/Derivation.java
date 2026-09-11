package com.personal.chatbot.service.cache;

/**
 * The question-preparation steps the derivation cache may keep a result of (docs/cache-plan.md §3.2).
 * Each depends on the text of the question — the rewrite on the history too — and on nothing in the
 * knowledge base, so its result outlives a change to it.
 */
public enum Derivation {

    /** A follow-up rewritten into a standalone query (Phase 9b). */
    CONVERSATION_QUERY_REWRITE("conversation-query-rewrite"),

    /** Other wordings of a question the first pass found too little for (Phase 9a, REWRITE). */
    EXPAND_REWRITE("expand-rewrite"),

    /** The passage that would answer the question (Phase 9a, HYDE). */
    EXPAND_HYDE("expand-hyde"),

    /** The parts of a question that asks for several things (Phase 9d). */
    DECOMPOSE_QUESTION("decompose-question");

    private final String label;

    Derivation(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
