package com.personal.chatbot.embedding;

import java.util.function.Supplier;

/**
 * Ambient {@link EmbeddingMode} for the current thread of execution. Defaults to
 * {@link EmbeddingMode#DOCUMENT}; retrieval wraps its search in {@link #inQueryMode(Supplier)}.
 * Built on {@link ScopedValue}, so the mode is inherited by structured-concurrency children but
 * never leaks past the scope.
 */
public final class EmbeddingModeScope {

    private static final ScopedValue<EmbeddingMode> MODE = ScopedValue.newInstance();

    private EmbeddingModeScope() {
    }

    public static EmbeddingMode current() {
        return MODE.orElse(EmbeddingMode.DOCUMENT);
    }

    public static <T> T inQueryMode(Supplier<T> action) {
        return inMode(EmbeddingMode.QUERY, action);
    }

    public static <T> T inMode(EmbeddingMode mode, Supplier<T> action) {
        return ScopedValue.where(MODE, mode).call(action::get);
    }
}
