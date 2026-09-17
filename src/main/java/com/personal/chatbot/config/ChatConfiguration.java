package com.personal.chatbot.config;

import com.personal.chatbot.observability.ChatObservations;
import com.personal.chatbot.observability.RetrievalObservations;
import com.personal.chatbot.service.cache.DerivationCache;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Chat-side beans (docs/system-plan.md D10, D12). */
@Configuration(proxyBeanMethods = false)
class ChatConfiguration {

    @Bean
    ConversationQueryRewriter conversationQueryRewriter(GroundedAnswerPrompt prompt, GroundingInstructions instructions,
                                                       ChatbotProperties.Chat chat, ChatObservations observations,
                                                       DerivationCache derivations) {
        return new ConversationQueryRewriter(prompt, instructions, chat.historyTurns(), chat.queryRewriteTimeout(),
                observations, derivations);
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
                                ChatbotProperties.Chat chat, ChatObservations observations) {
        return new AnswerDrafter(prompt, instructions, chat, observations);
    }

    @Bean
    EvidenceExpander evidenceExpander(SearchExpander expander, GroundedAnswerPrompt prompt,
                                      GroundingInstructions instructions, ChatbotProperties.Chat chat,
                                      ChatObservations observations, RetrievalObservations retrievalObservations,
                                      DerivationCache derivations) {
        return new EvidenceExpander(expander, prompt, instructions, chat, observations, retrievalObservations, derivations);
    }

    @Bean
    QuestionDecomposer questionDecomposer(Retriever retriever, SubQuestionSearch search, GroundedAnswerPrompt prompt,
                                          GroundingInstructions instructions, ChatbotProperties.Chat chat,
                                          ChatObservations observations, RetrievalObservations retrievalObservations,
                                          DerivationCache derivations) {
        return new QuestionDecomposer(retriever, search, prompt, instructions, chat, observations, retrievalObservations,
                derivations);
    }

    @Bean
    SourceComparator sourceComparator(GroundedAnswerPrompt prompt, GroundingInstructions instructions,
                                      ChatbotProperties.Chat chat, ChatObservations observations) {
        return new SourceComparator(prompt, instructions, chat, observations);
    }

    @Bean
    AgenticResearcher agenticResearcher(LockedSearchOperations searchOperations, GroundedAnswerPrompt prompt,
                                        GroundingInstructions instructions, RetrievalTraceStore traces,
                                        ChatbotProperties.Chat chat, ChatbotProperties.Retrieval retrieval,
                                        ChatObservations observations, QuestionDecomposer decomposer,
                                        SectionCatalog catalog, Retriever retriever) {
        return new AgenticResearcher(searchOperations, prompt, instructions, traces, chat, retrieval, observations,
                decomposer, catalog, retriever);
    }
}
