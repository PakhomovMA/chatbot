package com.personal.chatbot.config;

import com.github.benmanes.caffeine.cache.Ticker;
import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.observability.CacheObservations;
import com.personal.chatbot.service.cache.AnswerCache;
import com.personal.chatbot.service.cache.CaffeineAnswerCache;
import com.personal.chatbot.service.cache.CaffeineDerivationCache;
import com.personal.chatbot.service.cache.DerivationCache;
import com.personal.chatbot.service.cache.PipelineFingerprint;
import com.personal.chatbot.service.chat.ChatAnswerCache;
import com.personal.chatbot.service.chat.GroundedAnswerPrompt;
import com.personal.chatbot.service.chat.GroundingInstructions;
import com.personal.chatbot.service.embedding.KnowledgeEmbeddingService;
import com.personal.chatbot.service.index.IndexStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

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

    @Bean
    DerivationCache derivationCache(ChatbotProperties.Cache settings,
                                    @Value("${embabel.models.default-llm}") String llm,
                                    PipelineFingerprint pipeline, CacheObservations observations) {
        return new CaffeineDerivationCache(settings.derivation(), llm, pipeline, observations, Ticker.systemTicker());
    }

    /** Finished answers; disabled layers stay empty and still expose their entry gauge. */
    @Bean
    AnswerCache answerCache(ChatbotProperties.Cache settings, CacheObservations observations) {
        return new CaffeineAnswerCache(settings.answer().ttl(), settings.answer().maxWeight(), observations,
                Ticker.systemTicker());
    }

    @Bean
    ChatAnswerCache chatAnswerCache(AnswerCache cache, ChatbotProperties.Cache settings, IndexStatus index,
                                    PipelineFingerprint pipeline, GroundedAnswerPrompt prompt,
                                    ChatbotProperties.Retrieval retrieval, CacheObservations observations,
                                    Clock clock) {
        return new ChatAnswerCache(cache, settings.answer(), index, pipeline, prompt, retrieval.topK(), observations,
                clock);
    }
}
