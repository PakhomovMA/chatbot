package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.utils.CitationMarkers;

import java.util.List;
import java.util.SortedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the free text produced by the streaming prompt into a {@link GroundedAnswerDraft}: citation
 * markers are collected from the text, and a trailing {@code INSUFFICIENT: ...} line (see
 * {@code prompts/grounded-answer-stream.md}) marks the evidence as insufficient. Deterministic, so
 * the verifier treats streamed and structured drafts identically.
 */
public final class StreamedDraftParser {

    private static final Pattern INSUFFICIENT_LINE = Pattern.compile("(?im)^\\s*`?INSUFFICIENT:\\s*(.*?)`?\\s*$");

    private StreamedDraftParser() {
    }

    public static GroundedAnswerDraft parse(String streamedText) {
        String text = streamedText.strip();
        String unanswered = null;
        Matcher insufficient = INSUFFICIENT_LINE.matcher(text);
        if (insufficient.find()) {
            unanswered = insufficient.group(1).strip();
            text = text.substring(0, insufficient.start()).stripTrailing();
        }
        SortedSet<Integer> cited = CitationMarkers.collect(text);
        boolean sufficient = unanswered == null;
        return new GroundedAnswerDraft(text, List.copyOf(cited), sufficient, sufficient ? null : (unanswered.isEmpty() ? "not covered by the evidence" : unanswered));
    }
}
