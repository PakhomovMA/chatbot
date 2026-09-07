package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.agent.AgenticDraft;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.service.retrieval.EvidenceCollector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an {@link AgenticDraft} (chunk-id references) into the {@link GroundedAnswerDraft} shape the
 * verifier understands: every {@code {{chunk:<id>}}} the model used becomes a numbered marker
 * {@code [n]} pointing at the position of that chunk in the collected evidence. Ids the model never
 * saw are dropped here, and {@link GroundingVerifier} removes any marker that is left dangling. A draft
 * without any reference is attributed deterministically by {@link EvidenceAttributor}.
 */
public final class AgenticDraftMapper {

    private static final Pattern CHUNK_REF = Pattern.compile("\\{\\{\\s*chunk\\s*:\\s*([^}\\s]+)\\s*}}");

    private AgenticDraftMapper() {
    }

    public static GroundedAnswerDraft toNumbered(AgenticDraft draft, EvidenceCollector evidence) {
        String answer = draft.answer() != null ? draft.answer().strip() : "";
        Map<String, Integer> markers = new LinkedHashMap<>();
        Matcher matcher = CHUNK_REF.matcher(answer);
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            int index = evidence.indexOf(matcher.group(1));
            String replacement = index > 0 ? "[" + index + "]" : "";
            if (index > 0) {
                markers.put(matcher.group(1), index);
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        TreeSet<Integer> cited = new TreeSet<>(markers.values());
        for (String id : draft.citedChunkIdsOrEmpty()) {
            int index = evidence.indexOf(id);
            if (index > 0) {
                cited.add(index);
            }
        }
        String cleaned = rewritten.toString().replaceAll("[ \\t]+([.,;:!?])", "$1").replaceAll("[ \\t]{2,}", " ").strip();
        if (cited.isEmpty() && draft.evidenceSufficient() && !cleaned.isEmpty()) {
            // The model answered but forgot to reference chunks: credit the chunks it visibly copied from
            // and append the markers so the answer text and the citations stay consistent.
            List<Integer> attributed = EvidenceAttributor.attribute(cleaned, evidence.chunks());
            if (!attributed.isEmpty()) {
                cited.addAll(attributed);
                StringBuilder suffix = new StringBuilder();
                for (int n : attributed) {
                    suffix.append(" [").append(n).append(']');
                }
                cleaned = cleaned + suffix;
            }
        }
        return new GroundedAnswerDraft(cleaned, cited.stream().toList(), draft.evidenceSufficient(), draft.unansweredAspects());
    }
}
