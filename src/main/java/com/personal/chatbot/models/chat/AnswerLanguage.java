package com.personal.chatbot.models.chat;

import com.personal.chatbot.utils.AnswerLanguages;

/**
 * Language the assistant writes its answer in (docs/system-plan.md §7). {@code AUTO} follows the
 * language of the question, which is decided deterministically in {@link AnswerLanguages} rather
 * than left to the model: a local model reading English evidence tends to drift into English even
 * when it was asked in Russian.
 */
public enum AnswerLanguage {

    /** Follow the question: Russian question, Russian answer. */
    AUTO,

    /** Always answer in Russian. */
    RU,

    /** Always answer in English. */
    EN
}
