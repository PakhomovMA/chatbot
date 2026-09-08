package com.personal.chatbot.service.lifecycle;

import com.personal.chatbot.service.embedding.PromptedEmbeddingService;
import com.personal.chatbot.service.knowledge.IngestionQueue;
import com.personal.chatbot.support.BlockingTextEmbedder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * C08 through Spring's own lifecycle: the ordered stop runs while the context stops, bean
 * destruction closes the backend afterwards, and a backend that keeps computing after the interrupt
 * is waited for instead of being closed underneath the call.
 */
class ApplicationShutdownTest {

    private final BlockingTextEmbedder backend = new BlockingTextEmbedder(4);
    private final PromptedEmbeddingService embeddings =
            new PromptedEmbeddingService(backend, 8, 1, true, new SimpleMeterRegistry());
    /** Stands in for the ingestion pipeline: the worker is inside the embedding backend when we stop. */
    private final IngestionQueue queue = new IngestionQueue(claim -> embeddings.embed(claim.documentId()), List::of);

    @Test
    void theWorkerIsStoppedBeforeTheBackendItUsesIsClosed() throws InterruptedException {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(PromptedEmbeddingService.class, () -> embeddings);
        context.registerBean(IngestionQueue.class, () -> queue);
        context.registerBean(ApplicationShutdown.class, () -> new ApplicationShutdown(
                new ShutdownSequence(List.of(queue), Duration.ofMillis(100), Duration.ofSeconds(20))));
        context.refresh();

        assertThat(queue.enqueue("doc-in-flight")).isTrue();
        backend.awaitEntered();

        Thread closing = Thread.ofPlatform().name("context-close").start(context::close);
        await().atMost(Duration.ofSeconds(10)).until(backend::wasInterrupted);
        assertThat(backend.closed()).isFalse();

        backend.release();
        assertThat(closing.join(Duration.ofSeconds(20))).isTrue();

        assertThat(queue.awaitQuiet(Duration.ZERO)).isTrue();
        assertThat(queue.enqueue("late-arrival")).isFalse();
        assertThat(backend.closed()).isTrue();
        assertThat(backend.closedWhileRunning()).isFalse();
    }
}
