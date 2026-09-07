package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.GroundedAnswer;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.Citation;
import com.personal.chatbot.models.chat.Grounding;
import com.personal.chatbot.models.retrieval.Provenance;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingVerifierTest {

    private final GroundingVerifier verifier = new GroundingVerifier(40);

    static RetrievedChunk hit(int rank, String text) {
        Provenance provenance = new Provenance("doc-1", "Runbook", 1, "Restart", List.of("Restart"), "doc-1:1:" + rank, rank, 0, 1, "text/markdown");
        return new RetrievedChunk("doc-1:1:" + rank, text, provenance, 0.6, 0.2, 0.03 / rank, rank, null);
    }

    static Evidence evidence(boolean sufficientByScore, RetrievedChunk... hits) {
        UserQuestion question = new UserQuestion("c", "m", "how?", List.of(), null, null);
        RetrievalResult result = new RetrievalResult("trace-1", "how?", RetrievalMode.HYBRID, 8, 24, List.of(hits),
                sufficientByScore, sufficientByScore ? 0.6 : 0.2, new RetrievalTimings(1, 1, 0, 7), Instant.now());
        return new Evidence(question, result);
    }

    @Test
    void keepsValidCitationsAndDropsPhantoms() {
        Evidence evidence = evidence(true, hit(1, "Run systemctl restart payments on the host, then check the status."), hit(2, "Roll back with deploy.sh"));
        GroundedAnswerDraft draft = new GroundedAnswerDraft("Run `systemctl restart payments` [1]. Then verify [7] and see [2].", List.of(1, 9), true, null);

        GroundedAnswer answer = verifier.verify(evidence, draft, 2);

        assertThat(answer.grounding()).isEqualTo(Grounding.GROUNDED);
        assertThat(answer.answer()).isEqualTo("Run `systemctl restart payments` [1]. Then verify and see [2].");
        assertThat(answer.citations()).extracting(Citation::marker).containsExactly(1, 2);
        assertThat(answer.citations().getFirst().quote()).hasSizeLessThanOrEqualTo(41).endsWith("…");
        assertThat(answer.citations().getFirst().chunkId()).isEqualTo("doc-1:1:1");
        assertThat(answer.retrievalTraceId()).isEqualTo("trace-1");
        assertThat(answer.retrievalMs()).isEqualTo(7);
        assertThat(answer.notes()).isNull();
    }

    @Test
    void markersBeyondShownPassagesAreDropped() {
        Evidence evidence = evidence(true, hit(1, "one"), hit(2, "two"), hit(3, "three"));
        GroundedAnswerDraft draft = new GroundedAnswerDraft("See [3] and [1].", List.of(3), true, null);
        GroundedAnswer answer = verifier.verify(evidence, draft, 2);
        assertThat(answer.answer()).isEqualTo("See and [1].");
        assertThat(answer.citations()).extracting(Citation::marker).containsExactly(1);
    }

    @Test
    void partialWhenNoCitationsSurviveOrScoresAreLow() {
        Evidence evidence = evidence(true, hit(1, "one"));
        assertThat(verifier.verify(evidence, new GroundedAnswerDraft("Just prose.", List.of(), true, null), 1).grounding())
                .isEqualTo(Grounding.PARTIAL);
        assertThat(verifier.verify(evidence(false, hit(1, "one")), new GroundedAnswerDraft("Cited [1].", List.of(1), true, null), 1).grounding())
                .isEqualTo(Grounding.PARTIAL);
    }

    @Test
    void insufficientWhenModelSaysSoOrNothingRetrieved() {
        GroundedAnswer noEvidence = verifier.verify(evidence(false), GroundedAnswerDraft.insufficient("Nothing found.", "everything"), 0);
        assertThat(noEvidence.grounding()).isEqualTo(Grounding.INSUFFICIENT_EVIDENCE);
        assertThat(noEvidence.citations()).isEmpty();
        assertThat(noEvidence.notes()).isEqualTo("everything");

        GroundedAnswer modelSaysNo = verifier.verify(evidence(true, hit(1, "one")),
                new GroundedAnswerDraft("The docs mention restarts [1] but not the port.", List.of(1), false, "the port number"), 1);
        assertThat(modelSaysNo.grounding()).isEqualTo(Grounding.INSUFFICIENT_EVIDENCE);
        assertThat(modelSaysNo.citations()).hasSize(1);
        assertThat(modelSaysNo.notes()).isEqualTo("the port number");
    }
}
