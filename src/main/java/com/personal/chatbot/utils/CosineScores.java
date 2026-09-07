package com.personal.chatbot.utils;

/**
 * Conversion between plain cosine similarity in {@code [-1, 1]}, which configuration and the API
 * work in, and the {@code (1 + cos) / 2} score Lucene reports (docs/system-plan.md §6).
 */
public final class CosineScores {

    private CosineScores() {
    }

    /** Plain cosine to the Lucene score scale. */
    public static double toLuceneScore(double cosine) {
        return (1 + cosine) / 2;
    }

    /** Lucene score back to plain cosine. */
    public static double fromLuceneScore(double score) {
        return 2 * score - 1;
    }
}
