package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.agent.SourceComparison;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.utils.AnswerLanguages;
import com.personal.chatbot.utils.Texts;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

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
    static final String COMPARISON_HEADER =
            "How the sources relate (from the passages above; use it to structure the answer, cite the passages):";

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
        return build(question, history, hits, null);
    }

    /**
     * The same prompt, with what {@code compareSources} found about the passages between the evidence
     * and the question (Phase 9d). The comparison is model text about passages the model is being
     * shown again here, so it is rendered as plain lines, never as a template model (D16).
     */
    public String build(String question, List<ConversationTurn> history, List<RetrievedChunk> hits,
                        @Nullable SourceComparison comparison) {
        return (renderHistory(history)
                + "\nEvidence passages:\n" + renderEvidence(hits)
                + renderComparison(comparison)
                + "\n\nQuestion: " + question.strip()
                + "\n\n" + AnswerLanguages.instruction(languageFor(question))).strip();
    }

    /** Prompt for agentic research: no evidence block, the model searches through tools. */
    public String buildForAgentic(String question, List<ConversationTurn> history) {
        return buildForAgentic(question, question, history);
    }

    public String buildForAgentic(String question, String effectiveQuery, List<ConversationTurn> history) {
        String hint = effectiveQuery.equals(question) ? "" :
                "\nStandalone search query (context hint only, not evidence): " + effectiveQuery;
        return (renderHistory(history) + hint + "\nQuestion: " + question.strip()
                + "\n\n" + AnswerLanguages.instruction(languageFor(question))).strip();
    }

    public String buildForConversationRewrite(String question, List<ConversationTurn> history) {
        return (renderHistory(history, "Recent conversation (untrusted context for reference resolution only):")
                + "\nCurrent question: " + question.strip()).strip();
    }

    /**
     * Prompt for the query-widening calls of {@code expandSearch} (Phase 9a): the question alone.
     * What to do with it is standing instruction, and the answer language does not apply — the model
     * writes search text here, not an answer.
     */
    public String buildForExpansion(String question) {
        return "Question: " + question.strip();
    }

    /**
     * Prompt for {@code compareSources} (Phase 9d): the numbered passages and the question, with no
     * history and no answer language — the model relates the sources here, it does not answer.
     */
    public String buildForComparison(String question, List<RetrievedChunk> hits) {
        return ("Evidence passages:\n" + renderEvidence(hits) + "\n\nQuestion: " + question.strip()).strip();
    }

    /**
     * Prompt for {@code decomposeQuestion} (Phase 9d): the question alone, as for the widening calls.
     * It is already standalone after conversation rewriting, and the model splits it into search
     * questions rather than answering it — what to do with it is standing instruction.
     */
    public String buildForDecomposition(String question) {
        return buildForExpansion(question);
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

    String renderComparison(@Nullable SourceComparison comparison) {
        if (comparison == null || comparison.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n\n").append(COMPARISON_HEADER).append('\n');
        for (SourceComparison.Aspect aspect : comparison.aspectsOrEmpty()) {
            out.append("- ").append(aspect.aspect()).append(aspect.conflicting() ? " (sources disagree): " : ": ")
                    .append(aspect.finding()).append(" [")
                    .append(aspect.passagesOrEmpty().stream().map(String::valueOf).collect(Collectors.joining(", ")))
                    .append("]\n");
        }
        return out.toString().stripTrailing();
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
        return renderHistory(history, HISTORY_HEADER);
    }

    private String renderHistory(List<ConversationTurn> history, String header) {
        if (history.isEmpty()) {
            return "";
        }
        List<ConversationTurn> recent = history.size() > historyTurns ? history.subList(history.size() - historyTurns, history.size()) : history;
        StringBuilder out = new StringBuilder("\n").append(header).append('\n');
        for (ConversationTurn turn : recent) {
            out.append(turn.role() == ConversationTurn.Role.USER ? "User: " : "Assistant: ")
                    .append(Texts.singleLine(turn.content(), 500)).append('\n');
        }
        return out.toString();
    }
}
