package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.Evidence;
import com.personal.chatbot.models.agent.SubQuestions;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.retrieval.Provenance;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import com.personal.chatbot.service.retrieval.Retriever;
import com.personal.chatbot.service.retrieval.SubQuestionSearch;
import com.personal.chatbot.support.ChatSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.personal.chatbot.support.TestObservations;

/** Phase 9d: when the {@code decomposeQuestion} branch fires, what it searches for, and what it does when the model does not help. */
class QuestionDecomposerTest {

    private static final ChatbotProperties.Retrieval RETRIEVAL =
            new ChatbotProperties.Retrieval(4, 3, 60, 0.0, 0.0, 0.5, 0, 1.0, 200);

    private final List<String> searched = new ArrayList<>();
    private final Retriever retriever = query -> {
        searched.add(query.query());
        return result(query.query());
    };
    private final OperationContext context = mock(OperationContext.class, RETURNS_DEEP_STUBS);

    private QuestionDecomposer decomposer(boolean enabled) {
        ChatbotProperties.Chat chat = ChatSettings.of(ChatSettings.NO_EXPANSION,
                new ChatbotProperties.Decompose(enabled, 3, 4), ChatSettings.NO_COMPARISON);
        return new QuestionDecomposer(retriever, new SubQuestionSearch(new RetrievalTraceStore(20), RETRIEVAL),
                new GroundedAnswerPrompt(6000, 10, AnswerLanguage.AUTO), new GroundingInstructions(4, 3, 3, 4), chat,
                TestObservations.chat());
    }

    private void splitsInto(SubQuestions split) {
        when(context.ai().withLlm(any(LlmOptions.class)).withPromptContributor(any())
                .creating(SubQuestions.class).fromPrompt(anyString())).thenReturn(split);
        // The passes are run through the platform asyncer in production; here they run in order.
        // Stubbed with doAnswer: with when() the stubbing call itself would reach the answer.
        doAnswer(call -> {
            List<RetrievalQuery> queries = List.copyOf(call.<List<RetrievalQuery>>getArgument(0));
            return queries.stream().map(retriever::search).toList();
        }).when(context).parallelMap(any(), anyInt(), any());
    }

    private static UserQuestion question(String text) {
        return new UserQuestion("c", "m", text, List.of(), null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How do I roll back the payments service and how is the canary aborted?",
            "What is the difference between a canary rollback and a manual rollback of payments?",
            "Как перезапустить сервис payments и чем это отличается от отката релиза?",
            "Who declares an incident? Who writes the postmortem?"})
    void aQuestionThatAsksForSeveralThingsIsEligible(String text) {
        assertThat(decomposer(true).shouldDecompose(question(text))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How do I restart the payments service?",
            "Where are secrets stored and rotated?",
            "Как перезапустить сервис payments?",
            "Which header prevents duplicate orders?"})
    void anOrdinaryQuestionIsRetrievedOnceWithNoModelCallInFrontOfIt(String text) {
        assertThat(decomposer(true).shouldDecompose(question(text))).isFalse();
    }

    @Test
    void theBranchCanBeSwitchedOffEntirely() {
        assertThat(decomposer(false).shouldDecompose(
                question("How do I roll back the payments service and how is the canary aborted?"))).isFalse();
        verifyNoInteractions(context);
    }

    @Test
    void everyPartIsSearchedForAlongsideTheWholeQuestionAndTheHitsAreMerged() {
        splitsInto(new SubQuestions(List.of("  roll back the payments service  ", "", "canary abort rules",
                "roll back the payments service", "CANARY ABORT RULES")));
        UserQuestion question = question("How do I roll back payments and when is the canary aborted?");

        Evidence evidence = decomposer(true).decompose(question, context);

        assertThat(searched).containsExactly(question.question(), "roll back the payments service", "canary abort rules");
        assertThat(evidence.retrieval().decomposed()).isTrue();
        assertThat(evidence.retrieval().decomposition().subQuestions())
                .containsExactly("roll back the payments service", "canary abort rules");
        assertThat(evidence.retrieval().query()).isEqualTo(
                question.question() + " | roll back the payments service | canary abort rules");
        assertThat(evidence.hits()).extracting(RetrievedChunk::chunkId)
                .containsExactly("chunk-" + question.question(), "chunk-roll back the payments service",
                        "chunk-canary abort rules");
        assertThat(evidence.retrieval().decomposition().addedHits()).isEqualTo(2);
    }

    @Test
    void aSplitThatSaysTheQuestionAsksForOneThingLeavesItSearchedOnce() {
        UserQuestion question = question("How do I roll back payments and when is the canary aborted?");
        for (SubQuestions split : List.of(new SubQuestions(null), new SubQuestions(List.of()),
                new SubQuestions(List.of("  ")), new SubQuestions(List.of("only one part")))) {
            searched.clear();
            splitsInto(split);
            Evidence evidence = decomposer(true).decompose(question, context);
            assertThat(searched).containsExactly(question.question());
            // Unmarked, so the widening branch of Phase 9a may still do its own work on this evidence.
            assertThat(evidence.retrieval().decomposed()).isFalse();
        }
    }

    @Test
    void repeatedOrEchoedPartsDoNotDisableSearchExpansion() {
        UserQuestion question = question("How do I roll back payments and when is the canary aborted?");
        for (SubQuestions split : List.of(
                new SubQuestions(List.of("rollback", " ROLLBACK ")),
                new SubQuestions(List.of(question.question(), "rollback")))) {
            searched.clear();
            splitsInto(split);
            Evidence evidence = decomposer(true).decompose(question, context);
            assertThat(searched).containsExactly(question.question());
            assertThat(evidence.retrieval().multiPass()).isFalse();
        }
    }

    @Test
    void invalidAndDuplicatePartsDoNotConsumeTheLimit() {
        UserQuestion question = question("How do I roll back payments and when is the canary aborted?");
        splitsInto(new SubQuestions(Arrays.asList(null, "x".repeat(1001), "bad\u0000query",
                question.question(), "rollback", "ROLLBACK", "canary", "restart", "fourth")));

        Evidence evidence = decomposer(true).decompose(question, context);

        assertThat(searched).containsExactly(question.question(), "rollback", "canary", "restart");
        assertThat(evidence.retrieval().decomposition().subQuestions()).hasSize(3);
    }

    @Test
    void cancellationDuringParallelSearchPreventsQueuedPassesAndFallback() {
        splitsInto(new SubQuestions(List.of("first part", "second part")));
        UserQuestion question = question("Who declares an incident? Who writes the postmortem?");
        doAnswer(call -> {
            List<RetrievalQuery> queries = call.getArgument(0);
            kotlin.jvm.functions.Function1<RetrievalQuery, RetrievalResult> transform = call.getArgument(2);
            transform.invoke(queries.getFirst());
            question.cancellation().cancel("disconnected");
            return List.of(transform.invoke(queries.get(1)), transform.invoke(queries.get(2)));
        }).when(context).parallelMap(any(), anyInt(), any());

        assertThatThrownBy(() -> decomposer(true).decompose(question, context))
                .isInstanceOf(ChatCancelledException.class);
        assertThat(searched).containsExactly(question.question());
    }

    @Test
    void aFailedModelCallLeavesTheQuestionWithTheEvidenceOneSearchFinds() {
        UserQuestion question = question("How do I roll back payments and when is the canary aborted?");
        when(context.ai()).thenThrow(new IllegalStateException("no model today"));

        Evidence evidence = decomposer(true).decompose(question, context);

        assertThat(searched).containsExactly(question.question());
        assertThat(evidence.hits()).hasSize(1);
    }

    @Test
    void aFailedPassFallsBackToTheWholeQuestionRatherThanLosingTheAnswer() {
        splitsInto(new SubQuestions(List.of("first part", "second part")));
        doThrow(new IllegalStateException("index busy")).when(context).parallelMap(any(), anyInt(), any());
        UserQuestion question = question("How do I roll back payments and when is the canary aborted?");

        Evidence evidence = decomposer(true).decompose(question, context);

        assertThat(searched).containsExactly(question.question());
        assertThat(evidence.retrieval().decomposed()).isFalse();
    }

    @Test
    void cancellationDuringTheSplitStopsTheRunBeforeAnythingIsSearched() {
        UserQuestion question = question("How do I roll back payments and when is the canary aborted?");
        when(context.ai()).thenAnswer(_ -> {
            question.cancellation().cancel("disconnected");
            throw new IllegalStateException("model stopped");
        });
        assertThatThrownBy(() -> decomposer(true).decompose(question, context))
                .isInstanceOf(ChatCancelledException.class);
        assertThat(searched).isEmpty();
    }

    private static RetrievalResult result(String query) {
        Provenance provenance = new Provenance("doc-" + query.hashCode(), "Runbook", 1, "Restart", List.of("Restart"),
                "chunk-" + query, 1, 0, 3, "text/markdown");
        RetrievedChunk hit = new RetrievedChunk("chunk-" + query, "text for " + query, provenance, 0.6, 0.1, 1.0, 1, null);
        return new RetrievalResult(UUID.randomUUID().toString(), query, RetrievalMode.HYBRID, 4, 12, List.of(hit),
                false, 0.6, new RetrievalTimings(1, 1, 0, 3), Instant.now());
    }
}
