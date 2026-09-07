package com.personal.chatbot.models.agent;

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
 */
public record UserQuestion(
        String conversationId,
        String messageId,
        String question,
        List<ConversationTurn> history,
        @Nullable Integer topK,
        @Nullable Set<String> documentIds
) {
}
