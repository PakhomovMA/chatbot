package com.personal.chatbot.models.chat;

import java.time.Instant;
import java.util.List;

/** One message of a conversation kept in memory (docs/system-plan.md D12). */
public record ConversationTurn(Role role, String content, List<Citation> citations, Instant at) {

    public enum Role {
        USER,
        ASSISTANT
    }

    public static ConversationTurn user(String content, Instant at) {
        return new ConversationTurn(Role.USER, content, List.of(), at);
    }

    public static ConversationTurn assistant(String content, List<Citation> citations, Instant at) {
        return new ConversationTurn(Role.ASSISTANT, content, citations, at);
    }
}
