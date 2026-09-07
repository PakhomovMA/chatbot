package com.personal.chatbot.exceptions;

public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(String conversationId) {
        super("Conversation not found: " + conversationId);
    }
}
