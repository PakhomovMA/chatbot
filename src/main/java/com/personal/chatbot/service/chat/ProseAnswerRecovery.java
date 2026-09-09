package com.personal.chatbot.service.chat;

import com.embabel.agent.core.support.InvalidLlmReturnFormatException;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the answer a model wrote as prose where the structured call asked for JSON.
 *
 * <p>Embabel reports such a reply as an empty one, which is why it reads in a log as a model that
 * answered nothing: its {@code SuppressThinkingConverter} takes everything before the first
 * <code>{</code> for a thinking block, and prose that never mentions a brace is therefore removed
 * whole, leaving Jackson an empty string and the message "No content to map due to end-of-input".
 * The text itself survives on the exception, and it is the shape the streaming branch already parses
 * — Markdown with {@code [n]} markers — so it is read with {@link StreamedDraftParser} rather than
 * thrown away and asked for again. Only the JSON envelope was missing: {@link GroundingVerifier}
 * still checks every marker against the evidence, so nothing is published on the model's word.
 *
 * <p>A reply that did try to be JSON is not recovered — half-written JSON is not an answer to show
 * anyone — and neither is an empty one. Both are rethrown, and the platform retries as before.
 */
final class ProseAnswerRecovery {

    private static final Logger log = LoggerFactory.getLogger(ProseAnswerRecovery.class);

    private ProseAnswerRecovery() {
    }

    static GroundedAnswerDraft answerOrRethrow(InvalidLlmReturnFormatException e) {
        String text = e.getLlmReturn() != null ? e.getLlmReturn().strip() : "";
        if (text.isEmpty() || text.startsWith("{") || text.startsWith("[") || text.startsWith("```")) {
            throw e;
        }
        log.info("The model answered in prose where JSON was asked for; keeping its {}-character answer", text.length());
        return StreamedDraftParser.parse(text);
    }
}
