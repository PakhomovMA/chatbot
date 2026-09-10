package com.personal.chatbot.observability;

/**
 * The logical AI operations of the assistant, as fixed by
 * docs/observability/metric-catalog.json ({@code chatbot.ai.operation}, {@code operationBoundaries}).
 *
 * <p>One of these is a whole application operation — prompt, model call, whatever retries and tools
 * the platform ran underneath it, validation and recovery — and not one call to the provider. The
 * provider's own latency and token usage come from Spring AI's {@code gen_ai.client.operation}; a
 * single operation here may cover several of those, or none at all.
 */
public enum AiOperation {

    DRAFT_ANSWER("draft-answer"),
    DRAFT_ANSWER_STREAM("draft-answer-stream"),
    CONVERSATION_QUERY_REWRITE("conversation-query-rewrite"),
    EXPAND_SEARCH_REWRITE("expand-search-rewrite"),
    EXPAND_SEARCH_HYDE("expand-search-hyde"),
    DECOMPOSE_QUESTION("decompose-question"),
    COMPARE_SOURCES("compare-sources"),
    /** The whole agentic branch: seed decomposition, tool loop, mapping and the repair below. */
    RESEARCH_AGENTIC("research-agentic"),
    REPAIR_AGENTIC_ANSWER("repair-agentic-answer");

    private final String label;

    AiOperation(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
