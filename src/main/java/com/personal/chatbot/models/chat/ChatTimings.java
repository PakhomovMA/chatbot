package com.personal.chatbot.models.chat;

/** Milliseconds spent in retrieval, in the language model (everything that is not retrieval) and overall. */
public record ChatTimings(long retrievalMs, long llmMs, long totalMs) {
}
