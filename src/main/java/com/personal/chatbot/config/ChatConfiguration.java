package com.personal.chatbot.config;

import com.personal.chatbot.service.chat.ConversationStore;
import com.personal.chatbot.service.chat.GroundedAnswerPrompt;
import com.personal.chatbot.service.chat.GroundingVerifier;
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
}
