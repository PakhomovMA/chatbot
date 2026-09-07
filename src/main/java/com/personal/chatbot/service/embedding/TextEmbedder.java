package com.personal.chatbot.service.embedding;

import com.personal.chatbot.models.embedding.EmbeddingFingerprint;

import java.util.List;

/**
 * Raw embedding backend: turns already-prefixed texts into vectors, one call per batch.
 * Prefixing, batching, normalisation, metrics and concurrency limits live in
 * {@link PromptedEmbeddingService}; implementations only talk to the model.
 */
public interface TextEmbedder extends AutoCloseable {

    /** One vector per input, same order. */
    List<float[]> embed(List<String> texts);

    int dimensions();

    String provider();

    String modelName();

    /** Short digest of the weights actually loaded (see {@link EmbeddingFingerprint#artifactHash()}). */
    String artifactHash();

    @Override
    default void close() {
    }
}
