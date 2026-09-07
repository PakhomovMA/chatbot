package com.personal.chatbot.config;

import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import com.personal.chatbot.service.retrieval.Retriever;
import com.personal.chatbot.service.retrieval.SearchExpander;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RetrievalConfiguration {

    @Bean
    RetrievalTraceStore retrievalTraceStore(ChatbotProperties.Retrieval settings) {
        return new RetrievalTraceStore(settings.traceBufferSize());
    }

    @Bean
    SearchExpander searchExpander(Retriever retriever, RetrievalTraceStore traces, ChatbotProperties.Retrieval settings) {
        return new SearchExpander(retriever, traces, settings);
    }
}
