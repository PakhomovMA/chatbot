package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.SourceComparison;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.retrieval.Provenance;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.support.ChatSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Phase 9d: when {@code compareSources} fires, what it keeps of the model's answer, and how it fails. */
class SourceComparatorTest {

    private final OperationContext context = mock(OperationContext.class, RETURNS_DEEP_STUBS);

    private SourceComparator comparator(boolean enabled) {
        return comparator(enabled, 6000);
    }

    private SourceComparator comparator(boolean enabled, int evidenceCharBudget) {
        ChatbotProperties.Chat chat = ChatSettings.of(ChatSettings.NO_EXPANSION, ChatSettings.NO_DECOMPOSITION,
                new ChatbotProperties.CompareSources(enabled, 2, 2));
        return new SourceComparator(new GroundedAnswerPrompt(evidenceCharBudget, 10, AnswerLanguage.AUTO),
                new GroundingInstructions(4, 3, 3, 4), chat, new SimpleMeterRegistry());
    }

    private void compares(SourceComparison comparison) {
        when(context.ai().withLlm(any(LlmOptions.class)).withPromptContributor(any())
                .creating(SourceComparison.class).fromPrompt(anyString())).thenReturn(comparison);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What is the difference between a canary rollback and a manual rollback?",
            "How does the runbook rollback differ from the deployment guide's?",
            "Чем откат в runbook отличается от отката в guide?",
            "Should I roll back manually instead of waiting for the canary?"})
    void aComparingQuestionOverSeveralDocumentsIsEligible(String text) {
        assertThat(comparator(true).shouldCompare(evidence(text, hit(1, "doc-a"), hit(2, "doc-b")))).isTrue();
    }

    @Test
    void oneDocumentAnOrdinaryQuestionOrASwitchedOffBranchNeedNoModelCall() {
        assertThat(comparator(true).shouldCompare(
                evidence("What is the difference between the two rollbacks?", hit(1, "doc-a"), hit(2, "doc-a")))).isFalse();
        assertThat(comparator(true).shouldCompare(
                evidence("How do I roll back the payments service?", hit(1, "doc-a"), hit(2, "doc-b")))).isFalse();
        assertThat(comparator(true).shouldCompare(evidence("What is the difference?"))).isFalse();
        assertThat(comparator(false).shouldCompare(
                evidence("What is the difference between the two rollbacks?", hit(1, "doc-a"), hit(2, "doc-b")))).isFalse();
        verifyNoInteractions(context);
    }

    /** Only passages that fit the prompt budget are numbered for the model, so only they count as sources. */
    @Test
    void documentsOutsideThePromptBudgetDoNotMakeAQuestionWorthComparing() {
        Evidence evidence = evidence("What is the difference between the two rollbacks?",
                hit(1, "doc-a", "x".repeat(200)), hit(2, "doc-b", "y".repeat(200)));
        assertThat(comparator(true, 6000).shouldCompare(evidence)).isTrue();
        assertThat(comparator(true, 100).shouldCompare(evidence)).isFalse();
    }

    @Test
    void theComparisonIsKeptOnlyWhereItPointsAtPassagesTheModelWasShown() {
        compares(new SourceComparison(List.of(
                new SourceComparison.Aspect("how long it takes", "The runbook says six minutes.",
                        Arrays.asList(2, null, 1, 2), false),
                new SourceComparison.Aspect("who triggers it", "Invented from nowhere.", List.of(9), true),
                new SourceComparison.Aspect("empty refs", "Also unusable.", null, false),
                new SourceComparison.Aspect("beyond the cap", "Dropped by maxAspects.", List.of(1), false),
                new SourceComparison.Aspect("one more", "Never reached.", List.of(2), true))));

        Evidence compared = comparator(true).compare(
                evidence("What is the difference between the two rollbacks?", hit(1, "doc-a"), hit(2, "doc-b")), context);

        assertThat(compared.compared()).isTrue();
        assertThat(compared.comparison().aspectsOrEmpty()).hasSize(2);
        assertThat(compared.comparison().aspectsOrEmpty().getFirst().passages()).containsExactly(1, 2);
        assertThat(compared.comparison().aspectsOrEmpty().getLast().aspect()).isEqualTo("beyond the cap");
        assertThat(compared.comparison().hasConflict()).isFalse();
        assertThat(compared.hits()).isEqualTo(compared.hits());
        // Comparing again would be work the planner never asks for: the evidence is marked.
        assertThat(comparator(true).shouldCompare(compared)).isFalse();
    }

    @Test
    void aFailedOrEmptyComparisonStillMarksTheEvidenceSoTheBranchRunsOnce() {
        Evidence evidence = evidence("What is the difference between the two rollbacks?", hit(1, "doc-a"), hit(2, "doc-b"));
        for (SourceComparison answer : new SourceComparison[]{null, SourceComparison.none()}) {
            compares(answer);
            Evidence compared = comparator(true).compare(evidence, context);
            assertThat(compared.compared()).isTrue();
            assertThat(compared.comparison().isEmpty()).isTrue();
            assertThat(comparator(true).shouldCompare(compared)).isFalse();
        }
        OperationContext failing = mock(OperationContext.class);
        when(failing.ai()).thenThrow(new IllegalStateException("no model today"));
        Evidence compared = comparator(true).compare(evidence, failing);
        assertThat(compared.compared()).isTrue();
        assertThat(compared.comparison().isEmpty()).isTrue();
        assertThat(compared.hits()).isEqualTo(evidence.hits());
    }

    @Test
    void cancellationDuringTheComparisonStopsTheRunInsteadOfDrafting() {
        Evidence evidence = evidence("What is the difference between the two rollbacks?", hit(1, "doc-a"), hit(2, "doc-b"));
        OperationContext cancelling = mock(OperationContext.class);
        when(cancelling.ai()).thenAnswer(_ -> {
            evidence.question().cancellation().cancel("disconnected");
            throw new IllegalStateException("model stopped");
        });
        assertThatThrownBy(() -> comparator(true).compare(evidence, cancelling))
                .isInstanceOf(ChatCancelledException.class);
    }

    @Test
    void malformedAspectsDoNotDiscardValidComparisonFindings() {
        compares(new SourceComparison(Arrays.asList(null,
                new SourceComparison.Aspect(" ", "Missing aspect.", List.of(1), false),
                new SourceComparison.Aspect("trigger", "The canary rolls back automatically.", List.of(2), false))));
        Evidence original = evidence("What is the difference?", hit(1, "doc-a"), hit(2, "doc-b"));

        Evidence compared = comparator(true).compare(original, context);

        assertThat(compared.comparison().aspectsOrEmpty()).extracting(SourceComparison.Aspect::aspect)
                .containsExactly("trigger");
        assertThat(compared.hits()).isEqualTo(original.hits());
    }

    static RetrievedChunk hit(int rank, String documentId) {
        return hit(rank, documentId, "text of passage " + rank);
    }

    static RetrievedChunk hit(int rank, String documentId, String text) {
        Provenance provenance = new Provenance(documentId, documentId + " title", 1, "Rollback", List.of("Rollback"),
                documentId + ":1:" + rank, rank, 0, 3, "text/markdown");
        return new RetrievedChunk(documentId + ":1:" + rank, text, provenance, 0.6, 0.1, 1.0 / rank, rank, null);
    }

    static Evidence evidence(String question, RetrievedChunk... hits) {
        RetrievalResult retrieval = new RetrievalResult("trace-1", question, RetrievalMode.HYBRID, 8, 24, List.of(hits),
                true, 0.6, new RetrievalTimings(1, 1, 0, 3), Instant.now());
        return new Evidence(new UserQuestion("c", "m", question, List.of(), null, null), retrieval);
    }
}
