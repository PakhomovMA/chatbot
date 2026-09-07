package com.personal.chatbot.models.chat;

import com.personal.chatbot.models.retrieval.RetrievalResult;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Chat API response (docs/system-plan.md §4). {@code answer} is Markdown with {@code [n]} markers that
 * refer to {@code citations}; every marker left in the text has a matching citation.
 *
 * @param notes            what could not be answered from the knowledge base, if the model said so
 * @param retrievalTraceId id for {@code GET /api/diagnostics/retrieval/{id}}
 * @param diagnostics      the full retrieval result when requested via {@code options.includeDiagnostics}
 */
public record ChatResponse(
        String conversationId,
        String messageId,
        String answer,
        Grounding grounding,
        List<Citation> citations,
        @Nullable String notes,
        ChatTimings timings,
        String retrievalTraceId,
        @Nullable RetrievalResult diagnostics
) {
}
