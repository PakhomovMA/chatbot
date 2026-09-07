package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.utils.AnswerLanguages;
import com.personal.chatbot.utils.Texts;

import java.util.List;

/**
 * Builds the per-question half of the answer prompts: a compact conversation history, numbered
 * evidence passages with provenance and the question itself. The standing rules live apart, in
 * {@link GroundingInstructions}. Evidence is cut off at a character budget so a local model never
 * receives more context than it can use (docs/system-plan.md §6.7).
 *
 * <p>The prompt ends with the answer language, resolved per question: it is the last thing the model
 * reads, after evidence that is often in another language.
 */
public class GroundedAnswerPrompt {

    static final String HISTORY_HEADER = "Previous conversation (for context only; the evidence below is authoritative):";

    private final int evidenceCharBudget;
    private final int historyTurns;
    private final AnswerLanguage configuredLanguage;

    public GroundedAnswerPrompt(int evidenceCharBudget, int historyTurns, AnswerLanguage configuredLanguage) {
        this.evidenceCharBudget = evidenceCharBudget;
        this.historyTurns = historyTurns;
        this.configuredLanguage = configuredLanguage;
    }

    /** Prompt for the deterministic branch; the structured and the streaming draft share it. */
    public String build(String question, List<ConversationTurn> history, List<RetrievedChunk> hits) {
        return (renderHistory(history)
                + "\nEvidence passages:\n" + renderEvidence(hits)
                + "\n\nQuestion: " + question.strip()
                + "\n\n" + AnswerLanguages.instruction(languageFor(question))).strip();
    }

    /** Prompt for agentic research: no evidence block, the model searches through tools. */
    public String buildForAgentic(String question, List<ConversationTurn> history) {
        return (renderHistory(history) + "\nQuestion: " + question.strip()
                + "\n\n" + AnswerLanguages.instruction(languageFor(question))).strip();
    }

    /** The language this question is answered in; the fixed replies of the application follow it. */
    public AnswerLanguage languageFor(String question) {
        return AnswerLanguages.resolve(configuredLanguage, question);
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
                    .append(Texts.singleLine(turn.content(), 500)).append('\n');
        }
        return out.toString();
    }
}
