package com.personal.chatbot.config;

import com.personal.chatbot.service.chat.AgenticResearcher;
import com.personal.chatbot.service.chat.AnswerDrafter;
import com.personal.chatbot.service.chat.ConversationStore;
import com.personal.chatbot.service.chat.GroundedAnswerPrompt;
import com.personal.chatbot.service.chat.GroundingInstructions;
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
    ConversationStore conversationStore(ChatbotProperties.Chat chat, Clock clock) {
        return new ConversationStore(chat.historyTurns(), chat.maxConversations(), chat.conversationTtl(), clock);
    }

    @Bean
    GroundedAnswerPrompt groundedAnswerPrompt(ChatbotProperties.Chat chat) {
        return new GroundedAnswerPrompt(chat.evidenceCharBudget(), chat.historyTurns());
    }

    @Bean
    GroundingInstructions groundingInstructions(ChatbotProperties.Chat chat) {
        return new GroundingInstructions(chat.agenticMaxSearches());
    }

    @Bean
    GroundingVerifier groundingVerifier(ChatbotProperties.Chat chat) {
        return new GroundingVerifier(chat.quoteMaxChars());
    }

    @Bean
    AnswerDrafter answerDrafter(GroundedAnswerPrompt prompt, GroundingInstructions instructions,
                                ChatbotProperties.Chat chat, MeterRegistry meterRegistry) {
        return new AnswerDrafter(prompt, instructions, chat, meterRegistry);
    }

    @Bean
    AgenticResearcher agenticResearcher(LockedSearchOperations searchOperations, GroundedAnswerPrompt prompt,
                                        GroundingInstructions instructions, RetrievalTraceStore traces,
                                        ChatbotProperties.Chat chat, MeterRegistry meterRegistry) {
        return new AgenticResearcher(searchOperations, prompt, instructions, traces, chat, meterRegistry);
    }
}
