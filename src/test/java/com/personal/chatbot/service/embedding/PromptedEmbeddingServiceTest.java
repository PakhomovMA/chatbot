package com.personal.chatbot.service.embedding;

import com.personal.chatbot.utils.EmbeddingModeScope;
import com.personal.chatbot.utils.EmbeddingPrompts;
import com.personal.chatbot.utils.VectorMath;

import com.personal.chatbot.support.BlockingTextEmbedder;
import com.personal.chatbot.observability.EmbeddingObservations;
import com.personal.chatbot.support.FakeTextEmbedder;
import com.personal.chatbot.support.TestObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PromptedEmbeddingServiceTest {

    private final FakeTextEmbedder backend = new FakeTextEmbedder(16);
    private final TestObservations observed = TestObservations.create();
    private final SimpleMeterRegistry registry = observed.meters();

    private PromptedEmbeddingService service(int batchSize) {
        return new PromptedEmbeddingService(backend, batchSize, 2, true, embedding(backend));
    }

    /** The facade of the backend under test, over the one registry these assertions read. */
    private EmbeddingObservations embedding(TextEmbedder embedder) {
        return observed.embeddingObservations(embedder.provider(), embedder.modelName());
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

    // ---- shutdown (docs/concurrency-plan.md C08) ----------------------------------------------

    /**
     * The one thing shutdown must not do: close a backend that is still computing. Interrupting the
     * thread proves nothing — a native call ignores it — so close() has to wait, and give up rather
     * than close while the call is inside.
     */
    @Test
    void neverClosesTheBackendWhileItIsEmbedding() throws InterruptedException {
        BlockingTextEmbedder blocking = new BlockingTextEmbedder(4);
        PromptedEmbeddingService service = new PromptedEmbeddingService(blocking, 8, 2, true, embedding(blocking));
        Thread embedding = Thread.ofPlatform().name("embedding").start(() -> service.embed("held open"));
        blocking.awaitEntered();

        embedding.interrupt();
        assertThat(service.closeWithin(Duration.ofMillis(200))).isFalse();
        assertThat(blocking.closed()).isFalse();

        blocking.release();
        assertThat(embedding.join(Duration.ofSeconds(20))).isTrue();
        assertThat(blocking.wasInterrupted()).isTrue();

        assertThat(service.closeWithin(Duration.ofSeconds(5))).isTrue();
        assertThat(blocking.closed()).isTrue();
        assertThat(blocking.closedWhileRunning()).isFalse();
    }

    /** A call that was queued behind the last free slot must not reach a backend that is closing. */
    @Test
    void aCallWaitingForCapacityDoesNotReachAClosingBackend() throws InterruptedException {
        BlockingTextEmbedder blocking = new BlockingTextEmbedder(4);
        PromptedEmbeddingService service = new PromptedEmbeddingService(blocking, 8, 1, true, embedding(blocking));
        Thread first = Thread.ofPlatform().start(() -> service.embed("first"));
        blocking.awaitEntered();

        AtomicReference<Throwable> refused = new AtomicReference<>();
        Thread second = Thread.ofPlatform().start(() -> {
            try {
                service.embed("second");
            } catch (RuntimeException e) {
                refused.set(e);
            }
        });
        assertThat(service.closeWithin(Duration.ofMillis(200))).isFalse();

        blocking.release();
        assertThat(first.join(Duration.ofSeconds(20))).isTrue();
        assertThat(second.join(Duration.ofSeconds(20))).isTrue();
        assertThat(refused.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("closing");
        assertThat(blocking.batches()).hasSize(1);
        assertThat(service.closeWithin(Duration.ofSeconds(5))).isTrue();
        assertThat(blocking.closedWhileRunning()).isFalse();
    }

    /** A refused call must hand back the capacity it claimed, or the next one waits for a slot forever. */
    @Test
    @Timeout(20)
    void closeIsIdempotentAndRefusesLaterCallsWithoutHoldingCapacity() {
        PromptedEmbeddingService service = new PromptedEmbeddingService(backend, 8, 1, true, embedding(backend));
        service.close();
        service.close();
        for (String text : List.of("too late", "still too late")) {
            assertThatThrownBy(() -> service.embed(text))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closing");
        }
    }
}
