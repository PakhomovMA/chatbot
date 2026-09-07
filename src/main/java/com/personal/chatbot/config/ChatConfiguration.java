package com.personal.chatbot.config;

import com.personal.chatbot.service.chat.AgenticResearcher;
import com.personal.chatbot.service.chat.AnswerDrafter;
import com.personal.chatbot.service.chat.ConversationStore;
import com.personal.chatbot.service.chat.GroundedAnswerPrompt;
import com.personal.chatbot.service.chat.GroundingVerifier;
import com.personal.chatbot.service.index.LockedSearchOperations;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Chat-side beans (docs/system-plan.md D10, D12). */
@Configuration(proxyBeanMethods = false)
class ChatConfiguration {

    @Bean
    ConversationStore conversationStore(ChatbotProperties properties, Clock clock) {
        ChatbotProperties.Chat chat = properties.chat();
        return new ConversationStore(chat.historyTurns(), chat.maxConversations(), chat.conversationTtl(), clock);
    }

    @Bean
    GroundedAnswerPrompt groundedAnswerPrompt(ChatbotProperties properties) {
        ChatbotProperties.Chat chat = properties.chat();
        return new GroundedAnswerPrompt(chat.evidenceCharBudget(), chat.historyTurns());
    }

    @Bean
    GroundingVerifier groundingVerifier(ChatbotProperties properties) {
        return new GroundingVerifier(properties.chat().quoteMaxChars());
    }

    @Bean
    AnswerDrafter answerDrafter(GroundedAnswerPrompt prompt, ChatbotProperties properties, MeterRegistry meterRegistry) {
        return new AnswerDrafter(prompt, properties.chat(), meterRegistry);
    }

    @Bean
    AgenticResearcher agenticResearcher(LockedSearchOperations searchOperations, GroundedAnswerPrompt prompt,
                                        RetrievalTraceStore traces, ChatbotProperties properties,
                                        MeterRegistry meterRegistry) {
        return new AgenticResearcher(searchOperations, prompt, traces, properties.chat(), meterRegistry);
    }
}
