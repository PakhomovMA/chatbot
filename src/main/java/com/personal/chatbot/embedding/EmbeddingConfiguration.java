package com.personal.chatbot.embedding;

import com.embabel.common.ai.model.EmbeddingService;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.embedding.ollama.OllamaTextEmbedder;
import com.personal.chatbot.embedding.onnx.OnnxModelFiles;
import com.personal.chatbot.embedding.onnx.OnnxTextEmbedder;
import io.micrometer.core.instrument.MeterRegistry;
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
    KnowledgeEmbeddingService onnxKnowledgeEmbeddingService(ChatbotProperties properties, MeterRegistry meterRegistry) {
        ChatbotProperties.Embedding embedding = properties.embedding();
        ChatbotProperties.Onnx onnx = embedding.onnx();
        Path modelDir = onnx.modelDir() != null
                ? onnx.modelDir()
                : properties.dataDir().resolve("models").resolve(onnx.modelName());
        OnnxModelFiles files = OnnxModelFiles.resolve(modelDir, onnx.modelFile(), onnx.tokenizerFile());
        TextEmbedder backend = new OnnxTextEmbedder(files, onnx.modelName(), onnx.maxTokens(), onnx.intraOpThreads(), onnx.dimensions());
        return build(backend, embedding, meterRegistry);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = PROVIDER_PROPERTY, havingValue = "ollama")
    KnowledgeEmbeddingService ollamaKnowledgeEmbeddingService(ChatbotProperties properties, MeterRegistry meterRegistry) {
        ChatbotProperties.Embedding embedding = properties.embedding();
        TextEmbedder backend = new OllamaTextEmbedder(embedding.ollama().baseUrl(), embedding.ollama().model());
        return build(backend, embedding, meterRegistry);
    }

    /** Embabel-facing view of the same service (used by the Lucene store from Phase 3). */
    @Bean
    EmbeddingService knowledgeEmbabelEmbeddingService(KnowledgeEmbeddingService service) {
        return new EmbabelEmbeddingServiceAdapter(service);
    }

    static PromptedEmbeddingService build(TextEmbedder backend, ChatbotProperties.Embedding embedding, MeterRegistry meterRegistry) {
        PromptedEmbeddingService service = new PromptedEmbeddingService(
                backend, embedding.batchSize(), embedding.maxConcurrentBatches(), embedding.normalize(), meterRegistry);
        service.warmUp();
        return service;
    }
}
