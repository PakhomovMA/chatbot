package com.personal.chatbot.service.chat;

import com.personal.chatbot.models.agent.SourceComparison;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedAnswerPromptTest {

    private final GroundedAnswerPrompt prompt = new GroundedAnswerPrompt(155, 2, AnswerLanguage.AUTO);

    @Test
    void agenticPromptCarriesHistoryAndQuestionButNoEvidenceBlock() {
        String rendered = prompt.buildForAgentic("How?", List.of(ConversationTurn.user("earlier", Instant.EPOCH)));
        assertThat(rendered)
                .contains(GroundedAnswerPrompt.HISTORY_HEADER)
                .contains("User: earlier")
                .doesNotContain("Evidence passages:")
                .contains("Question: How?")
                .endsWith("Write the answer in English, even if the passages are in another language.");
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
                .contains("Question: How do I restart?")
                .endsWith("Write the answer in English, even if the passages are in another language.");
        assertThat(prompt.build("q", List.of(), hits))
                .doesNotContain(GroundedAnswerPrompt.HISTORY_HEADER)
                .startsWith("Evidence passages:");
    }

    @Test
    void theLanguageInstructionClosesThePromptAndFollowsTheQuestion() {
        List<RetrievedChunk> hits = List.of(GroundingVerifierTest.hit(1, "Run systemctl restart payments."));
        // Evidence in English, question in Russian: the last line has to pull the model back.
        assertThat(prompt.build("Как перезапустить сервис payments?", List.of(), hits))
                .endsWith("Write the answer in Russian, even if the passages are in another language.");
        assertThat(prompt.buildForAgentic("Где хранятся секреты?", List.of()))
                .endsWith("Write the answer in Russian, even if the passages are in another language.");
        assertThat(prompt.languageFor("Как перезапустить сервис payments?")).isEqualTo(AnswerLanguage.RU);

        GroundedAnswerPrompt forced = new GroundedAnswerPrompt(155, 2, AnswerLanguage.EN);
        assertThat(forced.build("Как перезапустить сервис payments?", List.of(), hits))
                .endsWith("Write the answer in English, even if the passages are in another language.");
        assertThat(forced.languageFor("Как перезапустить сервис payments?")).isEqualTo(AnswerLanguage.EN);
    }

    /** Phase 9d: what compareSources found is rendered between the passages and the question. */
    @Test
    void theSourceComparisonIsRenderedWithTheEvidenceItPointsAt() {
        List<RetrievedChunk> hits = List.of(
                GroundingVerifierTest.hit(1, "A rollback takes six minutes."),
                GroundingVerifierTest.hit(2, "An aborted canary rolls back on its own."));
        SourceComparison comparison = new SourceComparison(List.of(
                new SourceComparison.Aspect("who triggers it", "The runbook is manual, the guide automatic.",
                        List.of(1, 2), false),
                new SourceComparison.Aspect("how long it takes", "Six minutes against thirty.", List.of(1), true)));

        String rendered = prompt.build("How do they differ?", List.of(), hits, comparison);

        assertThat(rendered)
                .contains(GroundedAnswerPrompt.COMPARISON_HEADER)
                .contains("- who triggers it: The runbook is manual, the guide automatic. [1, 2]")
                .contains("- how long it takes (sources disagree): Six minutes against thirty. [1]");
        assertThat(rendered.indexOf("[1] Document")).isLessThan(rendered.indexOf(GroundedAnswerPrompt.COMPARISON_HEADER));
        assertThat(rendered.indexOf(GroundedAnswerPrompt.COMPARISON_HEADER)).isLessThan(rendered.indexOf("Question: How do they differ?"));
        assertThat(prompt.build("How do they differ?", List.of(), hits))
                .isEqualTo(prompt.build("How do they differ?", List.of(), hits, SourceComparison.none()))
                .doesNotContain(GroundedAnswerPrompt.COMPARISON_HEADER);
    }

    @Test
    void theComparisonPromptCarriesTheNumberedPassagesAndNoAnswerLanguage() {
        List<RetrievedChunk> hits = List.of(GroundingVerifierTest.hit(1, "A rollback takes six minutes."));
        assertThat(prompt.buildForComparison(" How do they differ? ", hits))
                .startsWith("Evidence passages:")
                .contains("[1] Document \"Runbook\" › Restart")
                .endsWith("Question: How do they differ?");
        assertThat(prompt.buildForDecomposition("  Restart and roll back?  ")).isEqualTo("Question: Restart and roll back?");
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
