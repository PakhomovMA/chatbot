package com.personal.chatbot.models.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Blackboard input of the knowledge assistant agent (docs/system-plan.md §7).
 *
 * @param history     recent turns of the conversation, oldest first
 * @param topK        retrieval override, null for the configured default
 * @param documentIds restrict retrieval to these documents, null for all
 * @param mode         deterministic retrieval or agentic research (Phase 9c)
 * @param stream       progress sink for streaming callers, null otherwise (not part of the data model)
 * @param cancellation the request's completion signal, polled before every expensive step (not part
 *                     of the data model)
 * @param effectiveQuery standalone search text after conversation reference resolution; never answer evidence
 * @param derivations request-owned pending cache writes, shared by prepared copies and never serialized
 */
public record UserQuestion(
        String conversationId,
        String messageId,
        String question,
        List<ConversationTurn> history,
        @Nullable Integer topK,
        @Nullable Set<String> documentIds,
        AnswerMode mode,
        @JsonIgnore @Nullable AnswerStreamSink stream,
        @JsonIgnore ChatCancellation cancellation,
        String effectiveQuery,
        @JsonIgnore PendingDerivations derivations
) {

    public UserQuestion(String conversationId, String messageId, String question, List<ConversationTurn> history,
                        @Nullable Integer topK, @Nullable Set<String> documentIds, AnswerMode mode,
                        @Nullable AnswerStreamSink stream, ChatCancellation cancellation, String effectiveQuery) {
        this(conversationId, messageId, question, history, topK, documentIds, mode, stream, cancellation,
                effectiveQuery, new PendingDerivations());
    }

    public UserQuestion(String conversationId, String messageId, String question, List<ConversationTurn> history,
                        @Nullable Integer topK, @Nullable Set<String> documentIds, AnswerMode mode,
                        @Nullable AnswerStreamSink stream, ChatCancellation cancellation) {
        this(conversationId, messageId, question, history, topK, documentIds, mode, stream, cancellation,
                question, new PendingDerivations());
    }

    /** Changes search text only; the original question still controls the answer, language and history. */
    public UserQuestion withEffectiveQuery(String query) {
        return new UserQuestion(conversationId, messageId, question, history, topK, documentIds, mode, stream,
                cancellation, query, derivations);
    }

    public UserQuestion(String conversationId, String messageId, String question, List<ConversationTurn> history,
                        @Nullable Integer topK, @Nullable Set<String> documentIds) {
        this(conversationId, messageId, question, history, topK, documentIds, AnswerMode.DETERMINISTIC, null,
                ChatCancellation.none());
    }

    /** The retrieval this question asks for; the first pass and any widening of it share the options. */
    public RetrievalQuery retrievalQuery() {
        return new RetrievalQuery(effectiveQuery, topK, null, documentIds);
    }

    public void notifyStage(String stage) {
        if (stream != null) {
            stream.stage(stage);
        }
    }

    /** Stops the run when the caller is gone; called before retrieval, model calls and tool calls. */
    public void abortIfCancelled() {
        cancellation.abortIfCancelled(messageId);
    }
}
