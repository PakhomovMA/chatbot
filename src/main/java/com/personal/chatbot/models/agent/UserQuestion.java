package com.personal.chatbot.models.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.personal.chatbot.models.chat.ConversationTurn;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Blackboard input of the knowledge assistant agent (docs/system-plan.md §7).
 *
 * @param history     recent turns of the conversation, oldest first
 * @param topK        retrieval override, null for the configured default
 * @param documentIds restrict retrieval to these documents, null for all
 * @param stream      progress sink for streaming callers, null otherwise (not part of the data model)
 */
public record UserQuestion(
        String conversationId,
        String messageId,
        String question,
        List<ConversationTurn> history,
        @Nullable Integer topK,
        @Nullable Set<String> documentIds,
        @JsonIgnore @Nullable AnswerStreamSink stream
) {

    public UserQuestion(String conversationId, String messageId, String question, List<ConversationTurn> history,
                        @Nullable Integer topK, @Nullable Set<String> documentIds) {
        this(conversationId, messageId, question, history, topK, documentIds, null);
    }

    public void notifyStage(String stage) {
        if (stream != null) {
            stream.stage(stage);
        }
    }
}
