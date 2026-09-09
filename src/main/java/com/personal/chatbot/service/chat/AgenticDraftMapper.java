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
 *
 * <p>Models rarely reproduce the marker exactly: they echo the {@code chunkId:} label the passages are
 * prefixed with, or drop the label and brace the bare id. Every such spelling is recognised, so that a
 * reference the model meant as a citation never reaches the reader as literal braces.
 */
public final class AgenticDraftMapper {

    /** A chunk id as the passages spell it: {@code <documentId>:<part>:<index>}. */
    private static final String CHUNK_ID = "[A-Za-z0-9][A-Za-z0-9_.-]{2,}(?::\\d{1,6}){1,3}";

    /**
     * A braced reference in either spelling the models produce: labelled ({@code chunk}, {@code chunkId},
     * {@code chunk_ids}, {@code chunk id}) with any id, or unlabelled with an id shaped like a chunk id.
     * The unlabelled half is deliberately narrow so that a template snippet quoted from a document
     * ({@code {{ user.name }}}) is left alone. One reference may name several ids.
     */
    private static final Pattern CHUNK_REF = Pattern.compile(
            "(?i)\\{\\{\\s*chunk[ _-]?(?:id)?s?\\s*[:=]\\s*([^}]+?)\\s*}}"
                    + "|\\{\\{\\s*(" + CHUNK_ID + "(?:\\s*[,;]\\s*" + CHUNK_ID + ")*)\\s*}}");

    /** Separators between ids inside one reference, and the punctuation a JSON-ish list drags along. */
    private static final Pattern ID_SEPARATOR = Pattern.compile("[\\s,;]+");
    private static final Pattern ID_NOISE = Pattern.compile("^[\\[\"'(]+|[\\]\"')]+$");

    private AgenticDraftMapper() {
    }

    public static GroundedAnswerDraft toNumbered(AgenticDraft draft, EvidenceCollector evidence) {
        String answer = draft.answer() != null ? draft.answer().strip() : "";
        Map<String, Integer> markers = new LinkedHashMap<>();
        Matcher matcher = CHUNK_REF.matcher(answer);
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            String reference = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            StringBuilder replacement = new StringBuilder();
            for (String raw : ID_SEPARATOR.split(reference)) {
                String id = ID_NOISE.matcher(raw).replaceAll("");
                int index = evidence.indexOf(id);
                if (index > 0) {
                    markers.put(id, index);
                    replacement.append('[').append(index).append(']');
                }
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement.toString()));
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
