package com.personal.chatbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes each section of {@link ChatbotProperties} as its own bean, so a service depends on the
 * settings it actually reads instead of on the whole configuration tree.
 */
@Configuration(proxyBeanMethods = false)
class ChatbotSettings {

    @Bean
    ChatbotProperties.Knowledge knowledgeSettings(ChatbotProperties properties) {
        return properties.knowledge();
    }

    @Bean
    ChatbotProperties.Ingestion ingestionSettings(ChatbotProperties properties) {
        return properties.ingestion();
    }

    @Bean
    ChatbotProperties.Retrieval retrievalSettings(ChatbotProperties properties) {
        return properties.retrieval();
    }

    @Bean
    ChatbotProperties.Chat chatSettings(ChatbotProperties properties) {
        return properties.chat();
    }

    @Bean
    ChatbotProperties.Sse sseSettings(ChatbotProperties properties) {
        return properties.sse();
    }
}
