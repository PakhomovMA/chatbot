package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedAnswerPromptTest {

    private final GroundedAnswerPrompt prompt = new GroundedAnswerPrompt(155, 2);

    @Test
    void agenticPromptCarriesHistoryAndQuestionButNoEvidenceBlock() {
        String rendered = prompt.buildForAgentic("How?", List.of(ConversationTurn.user("earlier", Instant.EPOCH)));
        assertThat(rendered)
                .contains(GroundedAnswerPrompt.HISTORY_HEADER)
                .contains("User: earlier")
                .doesNotContain("Evidence passages:")
                .endsWith("Question: How?");
    }

    @Test
    void rendersNumberedEvidenceHistoryAndQuestion() {
        List<RetrievedChunk> hits = List.of(
                GroundingVerifierTest.hit(1, "Run systemctl restart payments."),
                GroundingVerifierTest.hit(2, "Roll back with deploy.sh --rollback."));
        List<ConversationTurn> history = List.of(
                ConversationTurn.user("old question", Instant.EPOCH),
                ConversationTurn.user("previous   question\nwith newline", Instant.EPOCH),
                ConversationTurn.assistant("previous answer", List.of(), Instant.EPOCH));

        String rendered = prompt.build("  How do I restart?  ", history, hits);

        assertThat(rendered)
                .contains("[1] Document \"Runbook\" › Restart\nRun systemctl restart payments.")
                .contains("[2] Document \"Runbook\" › Restart\nRoll back with deploy.sh --rollback.")
                .contains(GroundedAnswerPrompt.HISTORY_HEADER)
                .contains("User: previous question with newline\nAssistant: previous answer")
                .doesNotContain("old question")
                .endsWith("Question: How do I restart?");
        assertThat(prompt.build("q", List.of(), hits))
                .doesNotContain(GroundedAnswerPrompt.HISTORY_HEADER)
                .startsWith("Evidence passages:");
    }

    @Test
    void evidenceBudgetLimitsPassagesButAlwaysKeepsTheFirst() {
        RetrievedChunk big = GroundingVerifierTest.hit(1, "x".repeat(100));
        RetrievedChunk second = GroundingVerifierTest.hit(2, "y".repeat(50));
        RetrievedChunk third = GroundingVerifierTest.hit(3, "z".repeat(10));
        assertThat(prompt.includedHits(List.of(big, second, third))).isEqualTo(2);
        assertThat(prompt.renderEvidence(List.of(big, second, third))).contains("[2]").doesNotContain("[3]");
        assertThat(prompt.includedHits(List.of(GroundingVerifierTest.hit(1, "x".repeat(1000))))).isEqualTo(1);
        assertThat(prompt.renderEvidence(List.of())).contains("no passages");
    }
}
