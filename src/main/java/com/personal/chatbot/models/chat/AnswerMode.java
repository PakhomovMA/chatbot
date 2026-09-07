package com.personal.chatbot.models.chat;

/** How the assistant obtains evidence (docs/system-plan.md D10, Phase 9c). */
public enum AnswerMode {
    /** One deterministic hybrid retrieval, then generation over the numbered passages (default). */
    DETERMINISTIC,
    /**
     * Agentic RAG via Embabel {@code ToolishRag}: the model searches the knowledge base itself with
     * vector/text/expansion tools, possibly several times, and cites the chunk ids it saw.
     */
    AGENTIC
}
