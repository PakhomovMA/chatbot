package com.personal.chatbot.service.embedding;

import com.personal.chatbot.exceptions.EmbeddingModelUnavailableException;
import com.personal.chatbot.utils.EmbeddingModeScope;
import com.personal.chatbot.utils.VectorMath;

import com.personal.chatbot.service.embedding.ollama.OllamaTextEmbedder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Fallback provider against a running Ollama with {@code embeddinggemma:300m}: {@code ./gradlew test -PincludeTags=e2e}. */
@Tag("e2e")
class OllamaEmbeddingE2eTest {

    @Test
    void ollamaBackendProducesComparableVectors() {
        OllamaTextEmbedder backend;
        try {
            backend = new OllamaTextEmbedder("http://localhost:11434", "embeddinggemma:300m");
        } catch (EmbeddingModelUnavailableException e) {
            assumeTrue(false, e.getMessage());
            return;
        }
        try (PromptedEmbeddingService service = new PromptedEmbeddingService(backend, 16, 2, true, new SimpleMeterRegistry())) {
            service.warmUp();
            assertThat(service.dimensions()).isEqualTo(768);
            assertThat(service.fingerprint().value()).startsWith("ollama/embeddinggemma:300m/");
            float[] query = EmbeddingModeScope.inQueryMode(() -> service.embed("how do I restart the payment service?"));
            float[] relevant = service.embed("Run systemctl restart payments to restart the payment service.");
            float[] unrelated = service.embed("Bananas ripen after harvest.");
            assertThat(VectorMath.norm(query)).isCloseTo(1.0, within(1e-3));
            assertThat(VectorMath.cosine(query, relevant)).isGreaterThan(VectorMath.cosine(query, unrelated));
        }
    }
}
