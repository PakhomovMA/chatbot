package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.GroundedAnswer;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.chat.Citation;
import com.personal.chatbot.models.chat.Grounding;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.utils.CitationMarkers;
import com.personal.chatbot.utils.Texts;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

/**
 * Deterministic check of a model draft against the evidence (docs/system-plan.md D10, INV-03):
 * citation markers are kept only when they point at a passage the model actually received; phantom
 * markers are removed from the text; the grounding status follows from what survives.
 */
public class GroundingVerifier {

    private final int quoteMaxChars;

    public GroundingVerifier(int quoteMaxChars) {
        this.quoteMaxChars = quoteMaxChars;
    }

    /**
     * @param passagesShown how many evidence hits were actually in the prompt (see {@link GroundedAnswerPrompt#includedHits})
     */
    public GroundedAnswer verify(Evidence evidence, GroundedAnswerDraft draft, int passagesShown) {
        List<RetrievedChunk> hits = evidence.hits();
        int valid = Math.min(passagesShown, hits.size());
        String answer = draft.answer() != null ? draft.answer().strip() : "";

        SortedSet<Integer> cited = CitationMarkers.collect(answer);
        cited.addAll(draft.citedEvidenceOrEmpty());
        List<Integer> phantoms = cited.stream().filter(n -> n < 1 || n > valid).toList();
        phantoms.forEach(cited::remove);
        String cleaned = CitationMarkers.remove(answer, phantoms);

        List<Citation> citations = new ArrayList<>();
        for (int n : cited) {
            RetrievedChunk hit = hits.get(n - 1);
            citations.add(new Citation(n, hit.provenance().documentId(), hit.provenance().documentTitle(),
                    hit.provenance().sectionTitle(), hit.provenance().sectionPath(), hit.chunkId(),
                    Texts.truncate(hit.text(), quoteMaxChars), hit.fusedScore()));
        }

        Grounding grounding;
        if (hits.isEmpty() || !draft.evidenceSufficient()) {
            grounding = Grounding.INSUFFICIENT_EVIDENCE;
        } else if (!citations.isEmpty() && evidence.sufficientByScore()) {
            grounding = Grounding.GROUNDED;
        } else {
            grounding = Grounding.PARTIAL;
        }
        String notes = draft.unansweredAspects() != null && !draft.unansweredAspects().isBlank()
                ? draft.unansweredAspects().strip() : null;
        return new GroundedAnswer(cleaned, grounding, citations, notes, evidence.retrieval().traceId(),
                evidence.retrieval().timings().totalMs());
    }
}
