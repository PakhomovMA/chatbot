package com.personal.chatbot.utils;

import com.personal.chatbot.models.chat.AnswerLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerLanguagesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Как перезапустить сервис?",
            // The Russian question that matters here: mostly Latin identifiers around a Cyrillic core.
            "Как настроить RetrievalService и LuceneIndexStore при cold start?",
            "Что делает POST /api/knowledge/documents?"})
    void aQuestionWithCyrillicIsAnsweredInRussian(String question) {
        assertThat(AnswerLanguages.detect(question)).isEqualTo(AnswerLanguage.RU);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How do I restart the payments service?",
            "",
            "   ",
            "8080"})
    void anythingElseFallsBackToEnglish(String question) {
        assertThat(AnswerLanguages.detect(question)).isEqualTo(AnswerLanguage.EN);
    }

    @Test
    void aConfiguredLanguageWinsOverTheQuestion() {
        assertThat(AnswerLanguages.resolve(AnswerLanguage.EN, "Как перезапустить сервис?")).isEqualTo(AnswerLanguage.EN);
        assertThat(AnswerLanguages.resolve(AnswerLanguage.RU, "How do I restart it?")).isEqualTo(AnswerLanguage.RU);
        assertThat(AnswerLanguages.resolve(AnswerLanguage.AUTO, "How do I restart it?")).isEqualTo(AnswerLanguage.EN);
    }

    @Test
    void theFixedRepliesOfTheApplicationFollowTheLanguage() {
        assertThat(AnswerLanguages.noEvidenceAnswer(AnswerLanguage.RU)).isEqualTo("В базе знаний нет ничего по этому вопросу.");
        assertThat(AnswerLanguages.noEvidenceNote(AnswerLanguage.RU)).isEqualTo("Подходящие фрагменты не найдены.");
        assertThat(AnswerLanguages.noEvidenceAnswer(AnswerLanguage.EN)).startsWith("I could not find anything");
        assertThat(AnswerLanguages.instruction(AnswerLanguage.AUTO)).contains("in the language of the question");
    }
}
