package com.personal.chatbot.support;

import com.personal.chatbot.embedding.KnowledgeEmbeddingService;
import com.personal.chatbot.embedding.PromptedEmbeddingService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Supplies the embedding service when {@code chatbot.embedding.provider=fake} (test profile). */
@TestConfiguration(proxyBeanMethods = false)
public class FakeEmbeddingConfiguration {

    public static final int DIMENSIONS = 32;

    @Bean(destroyMethod = "close")
    KnowledgeEmbeddingService fakeKnowledgeEmbeddingService(MeterRegistry meterRegistry) {
        PromptedEmbeddingService service = new PromptedEmbeddingService(new FakeTextEmbedder(DIMENSIONS), 4, 1, true, meterRegistry);
        service.warmUp();
        return service;
    }
}
