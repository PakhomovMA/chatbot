package com.personal.chatbot.utils;

import com.personal.chatbot.models.embedding.EmbeddingMode;

/**
 * Instruction prefixes required by EmbeddingGemma (model card, "Prompt instructions").
 * {@link #PREFIX_VERSION} is part of the index fingerprint: changing a prefix changes every vector.
 */
public final class EmbeddingPrompts {

    public static final String PREFIX_VERSION = "gemma-prefix-v1";

    static final String QUERY_PREFIX = "task: search result | query: ";
    static final String DOCUMENT_PREFIX = "title: none | text: ";

    private EmbeddingPrompts() {
    }

    public static String forMode(EmbeddingMode mode, String text) {
        return switch (mode) {
            case QUERY -> QUERY_PREFIX + text;
            case DOCUMENT -> DOCUMENT_PREFIX + text;
        };
    }
}
