package com.personal.chatbot.service.embedding;

import com.personal.chatbot.models.embedding.EmbeddingFingerprint;
import com.personal.chatbot.models.embedding.EmbeddingMode;
import com.personal.chatbot.utils.EmbeddingModeScope;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The application's embedding capability. Deliberately not a subtype of Embabel's
 * {@code EmbeddingService}: Embabel wraps every such bean in its own tracking decorator, which
 * would hide the fingerprint. {@link EmbabelEmbeddingServiceAdapter} bridges to Embabel where needed.
 *
 * <p>Vectors depend on the ambient {@link EmbeddingMode}; see {@link EmbeddingModeScope}.
 */
public interface KnowledgeEmbeddingService extends AutoCloseable {

    default float[] embed(String text) {
        return embed(List.of(text)).getFirst();
    }

    List<float[]> embed(List<String> texts);

    int dimensions();

    String provider();

    String modelName();

    EmbeddingFingerprint fingerprint();

    /** Duration of the startup warm-up, once it has run. */
    Optional<Duration> warmupDuration();

    @Override
    void close();
}
