package com.personal.chatbot.models.retrieval;

/** Wall-clock breakdown of one search, in milliseconds (docs/system-plan.md D14). */
public record RetrievalTimings(long vectorMs, long textMs, long fusionMs, long totalMs) {
}
