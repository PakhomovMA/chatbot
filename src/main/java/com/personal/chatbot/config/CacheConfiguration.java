package com.personal.chatbot.config;

import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.service.cache.PipelineFingerprint;
import com.personal.chatbot.service.chat.GroundingInstructions;
import com.personal.chatbot.service.embedding.KnowledgeEmbeddingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The result caches (docs/cache-plan.md). */
@Configuration(proxyBeanMethods = false)
class CacheConfiguration {

    /**
     * What every cached result is scoped by besides the knowledge-base revision (§3.1). Computed once:
     * each of its components changes only with a restart.
     */
    @Bean
    PipelineFingerprint pipelineFingerprint(@Value("${embabel.models.default-llm}") String llm,
                                            GroundingInstructions instructions, ChatbotProperties.Chat chat,
                                            ChatbotProperties.Retrieval retrieval,
                                            KnowledgeEmbeddingService embeddings, IndexManifest.Chunker chunker) {
        return PipelineFingerprint.of(llm, instructions.digest(), chat, retrieval, embeddings.fingerprint(), chunker);
    }
}
