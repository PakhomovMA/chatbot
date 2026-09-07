package com.personal.chatbot.config;

import com.personal.chatbot.service.knowledge.BlobStore;
import com.personal.chatbot.service.knowledge.DocumentRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Persistence beans for the knowledge base, rooted at {@code chatbot.data-dir} (docs/system-plan.md §9). */
@Configuration(proxyBeanMethods = false)
class KnowledgeConfiguration {

    @Bean
    DocumentRegistry documentRegistry(ChatbotProperties properties) {
        return new DocumentRegistry(properties.dataDir().resolve("documents"));
    }

    @Bean
    BlobStore blobStore(ChatbotProperties properties) {
        return new BlobStore(properties.dataDir().resolve("blobs"));
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
