package com.personal.chatbot.config;

import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RetrievalConfiguration {

    @Bean
    RetrievalTraceStore retrievalTraceStore(ChatbotProperties properties) {
        return new RetrievalTraceStore(properties.retrieval().traceBufferSize());
    }
}
