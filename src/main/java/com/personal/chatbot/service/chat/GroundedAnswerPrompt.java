package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the grounded-answer prompt from {@code prompts/grounded-answer.md}: numbered evidence
 * passages with provenance, a compact conversation history and the question. Evidence is cut off at
 * a character budget so a local model never receives more context than it can use (docs/system-plan.md §6.7).
 */
public class GroundedAnswerPrompt {

    public static final String TEMPLATE_LOCATION = "prompts/grounded-answer.md";
    static final String HISTORY_HEADER = "Previous conversation (for context only; the evidence below is authoritative):";

    private final String template;
    private final int evidenceCharBudget;
    private final int historyTurns;

    public GroundedAnswerPrompt(int evidenceCharBudget, int historyTurns) {
        this(loadTemplate(), evidenceCharBudget, historyTurns);
    }

    GroundedAnswerPrompt(String template, int evidenceCharBudget, int historyTurns) {
        this.template = template;
        this.evidenceCharBudget = evidenceCharBudget;
        this.historyTurns = historyTurns;
    }

    public String build(String question, List<ConversationTurn> history, List<RetrievedChunk> hits) {
        return template
                .replace("{{history}}", renderHistory(history))
                .replace("{{evidence}}", renderEvidence(hits))
                .replace("{{question}}", question.strip())
                .strip();
    }

    /** Number of hits that fit into the budget; the prompt and the citations must agree on this. */
    public int includedHits(List<RetrievedChunk> hits) {
        int used = 0;
        int included = 0;
        for (RetrievedChunk hit : hits) {
            int length = hit.text().length();
            if (included > 0 && used + length > evidenceCharBudget) {
                break;
            }
            used += length;
            included++;
        }
        return included;
    }

    String renderEvidence(List<RetrievedChunk> hits) {
        if (hits.isEmpty()) {
            return "(no passages were found)";
        }
        StringBuilder out = new StringBuilder();
        int included = includedHits(hits);
        for (int i = 0; i < included; i++) {
            RetrievedChunk hit = hits.get(i);
            String where = hit.provenance().sectionPath().isEmpty() ? ""
                    : " › " + String.join(" › ", hit.provenance().sectionPath());
            out.append('[').append(i + 1).append("] Document \"").append(hit.provenance().documentTitle()).append('"')
                    .append(where).append('\n')
                    .append(hit.text().strip()).append("\n\n");
        }
        return out.toString().stripTrailing();
    }

    String renderHistory(List<ConversationTurn> history) {
        if (history.isEmpty()) {
            return "";
        }
        List<ConversationTurn> recent = history.size() > historyTurns ? history.subList(history.size() - historyTurns, history.size()) : history;
        StringBuilder out = new StringBuilder("\n").append(HISTORY_HEADER).append('\n');
        for (ConversationTurn turn : recent) {
            out.append(turn.role() == ConversationTurn.Role.USER ? "User: " : "Assistant: ")
                    .append(singleLine(turn.content(), 500)).append('\n');
        }
        return out.toString();
    }

    private static String singleLine(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    private static String loadTemplate() {
        try {
            return new ClassPathResource(TEMPLATE_LOCATION).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load prompt template " + TEMPLATE_LOCATION, e);
        }
    }
}
