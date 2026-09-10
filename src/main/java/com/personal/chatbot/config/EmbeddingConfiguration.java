package com.personal.chatbot.config;

import com.personal.chatbot.service.embedding.EmbabelEmbeddingServiceAdapter;
import com.personal.chatbot.service.embedding.KnowledgeEmbeddingService;
import com.personal.chatbot.service.embedding.PromptedEmbeddingService;
import com.personal.chatbot.service.embedding.TextEmbedder;

import com.embabel.common.ai.model.EmbeddingService;
import com.personal.chatbot.observability.EmbeddingObservations;
import com.personal.chatbot.observability.Observations;
import com.personal.chatbot.service.embedding.ollama.OllamaTextEmbedder;
import com.personal.chatbot.service.embedding.onnx.OnnxModelFiles;
import com.personal.chatbot.service.embedding.onnx.OnnxTextEmbedder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires the embedding provider selected by {@code chatbot.embedding.provider}. The service is warmed
 * up while the context starts, so a missing or broken model fails fast with an actionable message.
 */
@Configuration(proxyBeanMethods = false)
class EmbeddingConfiguration {

    static final String PROVIDER_PROPERTY = "chatbot.embedding.provider";

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = PROVIDER_PROPERTY, havingValue = "onnx", matchIfMissing = true)
    KnowledgeEmbeddingService onnxKnowledgeEmbeddingService(ChatbotProperties properties, Observations observations) {
        ChatbotProperties.Embedding embedding = properties.embedding();
        ChatbotProperties.Onnx onnx = embedding.onnx();
        Path modelDir = onnx.modelDir() != null
                ? onnx.modelDir()
                : properties.dataDir().resolve("models").resolve(onnx.modelName());
        OnnxModelFiles files = OnnxModelFiles.resolve(modelDir, onnx.modelFile(), onnx.tokenizerFile());
        TextEmbedder backend = new OnnxTextEmbedder(files, onnx.modelName(), onnx.maxTokens(), onnx.intraOpThreads(), onnx.dimensions());
        return build(backend, embedding, observations);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = PROVIDER_PROPERTY, havingValue = "ollama")
    KnowledgeEmbeddingService ollamaKnowledgeEmbeddingService(ChatbotProperties properties, Observations observations) {
        ChatbotProperties.Embedding embedding = properties.embedding();
        TextEmbedder backend = new OllamaTextEmbedder(embedding.ollama().baseUrl(), embedding.ollama().model());
        return build(backend, embedding, observations);
    }

    /** Embabel-facing view of the same service (used by the Lucene store from Phase 3). */
    @Bean
    EmbeddingService knowledgeEmbabelEmbeddingService(KnowledgeEmbeddingService service) {
        return new EmbabelEmbeddingServiceAdapter(service);
    }

    /**
     * The facade belongs to one backend: its provider and model are the labels of every batch it
     * measures, and they come from the backend rather than from configuration text
     * (docs/observability/metric-catalog.json, {@code modelPolicy}).
     */
    static PromptedEmbeddingService build(TextEmbedder backend, ChatbotProperties.Embedding embedding,
                                          Observations observations) {
        PromptedEmbeddingService service = new PromptedEmbeddingService(
                backend, embedding.batchSize(), embedding.maxConcurrentBatches(), embedding.normalize(),
                new EmbeddingObservations(observations, backend.provider(), backend.modelName()));
        service.warmUp();
        return service;
    }
}
