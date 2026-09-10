package com.personal.chatbot.support;

import com.personal.chatbot.observability.EmbeddingObservations;
import com.personal.chatbot.observability.Observations;
import com.personal.chatbot.service.embedding.KnowledgeEmbeddingService;
import com.personal.chatbot.service.embedding.PromptedEmbeddingService;
import com.personal.chatbot.service.embedding.TextEmbedder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Supplies the embedding service when {@code chatbot.embedding.provider=fake} (test profile). */
@TestConfiguration(proxyBeanMethods = false)
public class FakeEmbeddingConfiguration {

    public static final int DIMENSIONS = 32;

    @Bean(destroyMethod = "close")
    KnowledgeEmbeddingService fakeKnowledgeEmbeddingService(Observations observations) {
        TextEmbedder backend = new FakeTextEmbedder(DIMENSIONS);
        PromptedEmbeddingService service = new PromptedEmbeddingService(backend, 4, 1, true,
                new EmbeddingObservations(observations, backend.provider(), backend.modelName()));
        service.warmUp();
        return service;
    }
}
