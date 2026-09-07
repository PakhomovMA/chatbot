package com.personal.chatbot.utils;

import com.personal.chatbot.models.chat.AnswerLanguage;

/**
 * Resolves the answer language of one question and holds the wording that depends on it: the
 * instruction appended to the prompt and the fixed replies the application writes itself.
 *
 * <p>Detection is a script count, not a language model: a Russian question about English-named
 * services is full of Latin identifiers, so any noticeable share of Cyrillic letters means Russian.
 * The bias is deliberate — answering a Russian question in English is the failure that matters here.
 */
public final class AnswerLanguages {

    /** Share of the letters that must be Cyrillic for a question to count as Russian. */
    static final double CYRILLIC_SHARE = 0.2;

    private AnswerLanguages() {
    }

    /** The language to answer one question in: the configured one, or the question's own. */
    public static AnswerLanguage resolve(AnswerLanguage configured, String question) {
        return configured == AnswerLanguage.AUTO ? detect(question) : configured;
    }

    /** The language a question is written in; {@link AnswerLanguage#EN} when there is nothing to go on. */
    public static AnswerLanguage detect(String question) {
        int cyrillic = 0;
        int letters = 0;
        for (int i = 0; i < question.length(); ) {
            int codePoint = question.codePointAt(i);
            i += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            letters++;
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.CYRILLIC) {
                cyrillic++;
            }
        }
        return letters > 0 && (double) cyrillic / letters >= CYRILLIC_SHARE ? AnswerLanguage.RU : AnswerLanguage.EN;
    }

    /**
     * The closing line of the answer prompt. It repeats what the standing rules say because the
     * evidence in between is usually in another language and pulls the model towards it.
     */
    public static String instruction(AnswerLanguage language) {
        return switch (language) {
            case RU -> "Write the answer in Russian, even if the passages are in another language.";
            case EN -> "Write the answer in English, even if the passages are in another language.";
            case AUTO -> "Write the answer in the language of the question, even if the passages are in another language.";
        };
    }

    /** Reply used when retrieval found nothing at all, so no model call is made. */
    public static String noEvidenceAnswer(AnswerLanguage language) {
        return language == AnswerLanguage.RU
                ? "В базе знаний нет ничего по этому вопросу."
                : "I could not find anything about this in the knowledge base.";
    }

    /** Note shown next to {@link #noEvidenceAnswer}. */
    public static String noEvidenceNote(AnswerLanguage language) {
        return language == AnswerLanguage.RU
                ? "Подходящие фрагменты не найдены."
                : "No relevant passages were retrieved.";
    }
}
