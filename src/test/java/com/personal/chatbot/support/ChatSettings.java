package com.personal.chatbot.support;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.retrieval.ExpansionStrategy;

import java.time.Duration;

/**
 * Chat settings for tests that construct a service directly: production defaults, with every branch
 * that costs a model call switched off, so a test enables only the one it is about.
 */
public final class ChatSettings {

    public static final ChatbotProperties.ExpandSearch NO_EXPANSION =
            new ChatbotProperties.ExpandSearch(ExpansionStrategy.NONE, 3);
    public static final ChatbotProperties.Decompose NO_DECOMPOSITION =
            new ChatbotProperties.Decompose(false, 3, 4);
    public static final ChatbotProperties.CompareSources NO_COMPARISON =
            new ChatbotProperties.CompareSources(false, 2, 4);
    /** Section tools are on in production and cost no model call, so tests get them too. */
    public static final ChatbotProperties.SectionTools SECTION_TOOLS =
            new ChatbotProperties.SectionTools(true, 6000);
    public static final ChatbotProperties.SectionTools NO_SECTION_TOOLS =
            new ChatbotProperties.SectionTools(false, 6000);

    private ChatSettings() {
    }

    public static ChatbotProperties.Chat defaults() {
        return of(NO_EXPANSION, NO_DECOMPOSITION, NO_COMPARISON);
    }

    public static ChatbotProperties.Chat of(ChatbotProperties.ExpandSearch expandSearch,
                                            ChatbotProperties.Decompose decompose,
                                            ChatbotProperties.CompareSources compareSources) {
        return of(expandSearch, decompose, compareSources, SECTION_TOOLS);
    }

    public static ChatbotProperties.Chat of(ChatbotProperties.ExpandSearch expandSearch,
                                            ChatbotProperties.Decompose decompose,
                                            ChatbotProperties.CompareSources compareSources,
                                            ChatbotProperties.SectionTools sectionTools) {
        return new ChatbotProperties.Chat(AnswerMode.DETERMINISTIC, AnswerLanguage.AUTO, 4, 0.2, Duration.ofSeconds(20),
                Duration.ofMinutes(10), 0.1, 6000, 600, 10, 1000, Duration.ofHours(24), expandSearch, decompose,
                compareSources, sectionTools);
    }
}
