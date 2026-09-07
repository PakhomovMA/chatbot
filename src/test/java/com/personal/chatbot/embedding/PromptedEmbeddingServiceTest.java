package com.personal.chatbot.embedding;

import com.personal.chatbot.support.FakeTextEmbedder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PromptedEmbeddingServiceTest {

    private final FakeTextEmbedder backend = new FakeTextEmbedder(16);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private PromptedEmbeddingService service(int batchSize) {
        return new PromptedEmbeddingService(backend, batchSize, 2, true, registry);
    }

    @Test
    void appliesPrefixForAmbientMode() {
        PromptedEmbeddingService service = service(8);
        service.embed("alpha");
        EmbeddingModeScope.inQueryMode(() -> service.embed("beta"));
        assertThat(backend.batches()).containsExactly(
                List.of("title: none | text: alpha"),
                List.of("task: search result | query: beta"));
    }

    @Test
    void batchesLongestFirstButReturnsOriginalOrder() {
        PromptedEmbeddingService service = service(2);
        List<String> texts = List.of("a", "ccc ccc ccc", "bb bb", "dddd dddd dddd dddd", "e");
        List<float[]> vectors = service.embed(texts);

        assertThat(vectors).hasSize(5);
        assertThat(backend.batches()).hasSize(3);
        assertThat(backend.batches().getFirst()).containsExactly("title: none | text: dddd dddd dddd dddd", "title: none | text: ccc ccc ccc");
        for (int i = 0; i < texts.size(); i++) {
            float[] single = service.embed(texts.get(i));
            assertThat(VectorMath.cosine(single, vectors.get(i))).isCloseTo(1.0, within(1e-6));
        }
    }

    @Test
    void normalisesVectorsAndReportsDimensions() {
        PromptedEmbeddingService service = service(8);
        float[] vector = service.embed("normalise me please");
        assertThat(vector).hasSize(16);
        assertThat(VectorMath.norm(vector)).isCloseTo(1.0, within(1e-5));
        assertThat(service.dimensions()).isEqualTo(16);
    }

    @Test
    void similarTextsAreCloserThanUnrelatedOnes() {
        PromptedEmbeddingService service = service(8);
        float[] a = service.embed("restart the payment service");
        float[] b = service.embed("restart payment service now");
        float[] c = service.embed("bananas grow in tropical climates");
        assertThat(VectorMath.cosine(a, b)).isGreaterThan(VectorMath.cosine(a, c));
    }

    @Test
    void fingerprintAndWarmupAreExposed() {
        PromptedEmbeddingService service = service(8);
        assertThat(service.warmupDuration()).isEmpty();
        service.warmUp();
        assertThat(service.warmupDuration()).isPresent();
        assertThat(service.fingerprint().value()).isEqualTo("fake/fake-embedder/000000000000/16/" + EmbeddingPrompts.PREFIX_VERSION + "/l2");
        assertThat(registry.get("chatbot.embedding").tag("mode", "query").timer().count()).isEqualTo(1);
        assertThat(registry.get("chatbot.embedding.texts").counter().count()).isEqualTo(2.0);
    }

    @Test
    void emptyInputIsEmptyOutput() {
        assertThat(service(8).embed(List.of())).isEmpty();
        assertThat(backend.batches()).isEmpty();
    }
}
