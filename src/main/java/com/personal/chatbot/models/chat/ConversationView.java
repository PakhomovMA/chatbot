package com.personal.chatbot.models.chat;

import java.time.Instant;
import java.util.List;

public record ConversationView(String id, List<ConversationTurn> messages, Instant createdAt, Instant updatedAt) {
}
