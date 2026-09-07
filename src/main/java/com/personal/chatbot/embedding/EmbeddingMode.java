package com.personal.chatbot.embedding;

/**
 * EmbeddingGemma is asymmetric: queries and documents get different instruction prefixes.
 * The mode is ambient (see {@link EmbeddingModeScope}) because the embedding interface consumed by
 * the Lucene store cannot distinguish the two call sites.
 */
public enum EmbeddingMode {
    QUERY,
    DOCUMENT
}
