package com.personal.chatbot.config;

import com.personal.chatbot.service.chat.AgenticResearcher;
import com.personal.chatbot.service.chat.AnswerDrafter;
import com.personal.chatbot.service.chat.ConversationQueryRewriter;
import com.personal.chatbot.service.chat.ConversationStore;
import com.personal.chatbot.service.chat.EvidenceExpander;
import com.personal.chatbot.service.chat.GroundedAnswerPrompt;
import com.personal.chatbot.service.chat.GroundingInstructions;
import com.personal.chatbot.service.chat.GroundingVerifier;
import com.personal.chatbot.service.chat.QuestionDecomposer;
import com.personal.chatbot.service.chat.SourceComparator;
import com.personal.chatbot.service.index.LockedSearchOperations;
import com.personal.chatbot.service.index.SectionCatalog;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import com.personal.chatbot.service.retrieval.Retriever;
import com.personal.chatbot.service.retrieval.SearchExpander;
import com.personal.chatbot.service.retrieval.SubQuestionSearch;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Chat-side beans (docs/system-plan.md D10, D12). */
@Configuration(proxyBeanMethods = false)
class ChatConfiguration {

    @Bean
    ConversationQueryRewriter conversationQueryRewriter(GroundedAnswerPrompt prompt, GroundingInstructions instructions,
                                                       ChatbotProperties.Chat chat, MeterRegistry meterRegistry) {
        return new ConversationQueryRewriter(prompt, instructions, chat.historyTurns(), meterRegistry);
    }

    @Bean
    ConversationStore conversationStore(ChatbotProperties.Chat chat, Clock clock) {
        return new ConversationStore(chat.historyTurns(), chat.maxConversations(), chat.conversationTtl(), clock);
    }

    @Bean
    GroundedAnswerPrompt groundedAnswerPrompt(ChatbotProperties.Chat chat) {
        return new GroundedAnswerPrompt(chat.evidenceCharBudget(), chat.historyTurns(), chat.answerLanguage());
    }

    @Bean
    GroundingInstructions groundingInstructions(ChatbotProperties.Chat chat) {
        return new GroundingInstructions(chat.agenticMaxSearches(), chat.expandSearch().queries(),
                chat.decompose().maxSubQuestions(), chat.compareSources().maxAspects());
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
    EvidenceExpander evidenceExpander(SearchExpander expander, GroundedAnswerPrompt prompt,
                                      GroundingInstructions instructions, ChatbotProperties.Chat chat,
                                      MeterRegistry meterRegistry) {
        return new EvidenceExpander(expander, prompt, instructions, chat, meterRegistry);
    }

    @Bean
    QuestionDecomposer questionDecomposer(Retriever retriever, SubQuestionSearch search, GroundedAnswerPrompt prompt,
                                          GroundingInstructions instructions, ChatbotProperties.Chat chat,
                                          MeterRegistry meterRegistry) {
        return new QuestionDecomposer(retriever, search, prompt, instructions, chat, meterRegistry);
    }

    @Bean
    SourceComparator sourceComparator(GroundedAnswerPrompt prompt, GroundingInstructions instructions,
                                      ChatbotProperties.Chat chat, MeterRegistry meterRegistry) {
        return new SourceComparator(prompt, instructions, chat, meterRegistry);
    }

    @Bean
    AgenticResearcher agenticResearcher(LockedSearchOperations searchOperations, GroundedAnswerPrompt prompt,
                                        GroundingInstructions instructions, RetrievalTraceStore traces,
                                        ChatbotProperties.Chat chat, ChatbotProperties.Retrieval retrieval,
                                        MeterRegistry meterRegistry, QuestionDecomposer decomposer,
                                        SectionCatalog catalog) {
        return new AgenticResearcher(searchOperations, prompt, instructions, traces, chat, retrieval, meterRegistry,
                decomposer, catalog);
    }
}
