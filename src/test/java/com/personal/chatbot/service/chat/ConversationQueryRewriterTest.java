package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.agent.StandaloneQuery;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.chat.ConversationTurn;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ConversationQueryRewriterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final GroundedAnswerPrompt prompt = new GroundedAnswerPrompt(6000, 2, AnswerLanguage.AUTO);
    private final ConversationQueryRewriter rewriter = new ConversationQueryRewriter(prompt,
            new GroundingInstructions(4, 3, 3, 4), 2, TIMEOUT, new SimpleMeterRegistry());
    private final OperationContext context = mock(OperationContext.class, RETURNS_DEEP_STUBS);

    private UserQuestion question(String text) {
        return new UserQuestion("c", "m", text, List.of(
                ConversationTurn.user("Where is the payments service? {{ 7 * 7 }}", Instant.EPOCH),
                ConversationTurn.assistant("On the host. {% include 'secret' %}", List.of(), Instant.EPOCH)),
                3, Set.of("payments"));
    }

    private void answer(StandaloneQuery result) {
        when(context.ai().withLlm(any(com.embabel.common.ai.model.LlmOptions.class)).withPromptContributor(any())
                .creating(StandaloneQuery.class).fromPrompt(anyString())).thenReturn(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"How do I restart it?", "А как его перезапустить?", "А порт?",
            "Could you please explain in detail all the required steps for restarting that service safely during business hours?",
            "Расскажи подробно обо всех необходимых действиях, которые оператор должен выполнить, чтобы его безопасно перезапустить в рабочее время."})
    void shortOrAnaphoricQuestionsWithHistoryAreEligible(String text) {
        assertThat(rewriter.shouldRewrite(question(text))).isTrue();
    }

    /**
     * The gate used to let every short question through, and a question that already names its subject
     * cost a model call for nothing — 132 s of it on a busy local model (docs/eval-log.md, 2026-09-10).
     */
    @ParameterizedTest
    @ValueSource(strings = {"Расскажи про GLM-5.3", "Какова длина контекста у GLM и Kimi?",
            "What changed in SEV-1 handling?", "Where does Vault keep the keys?"})
    void aShortQuestionThatNamesItsOwnSubjectIsSearchedAsItWasAsked(String text) {
        assertThat(rewriter.shouldRewrite(question(text))).isFalse();
        assertThat(rewriter.rewrite(question(text), context).effectiveQuery()).isEqualTo(text);
        verifyNoInteractions(context);
    }

    /** A follow-up that names something specific but opens as a continuation still needs its history. */
    @ParameterizedTest
    @ValueSource(strings = {"А у Kimi?", "And what about GLM-5.3?", "Расскажи про его контекст"})
    void aContinuationOrAReferenceIsResolvedEvenWhenItNamesSomething(String text) {
        assertThat(rewriter.shouldRewrite(question(text))).isTrue();
    }

    @Test
    void firstTurnsDisabledHistoryAndLongStandaloneQuestionsNeedNoModel() {
        UserQuestion first = new UserQuestion("c", "m", "Restart it?", List.of(), null, null);
        assertThat(rewriter.rewrite(first, context)).isSameAs(first);
        UserQuestion standalone = question("Describe all required steps to safely restart the payments service during business hours without losing any pending transactions or active customer sessions.");
        assertThat(rewriter.rewrite(standalone, context)).isSameAs(standalone);
        ConversationQueryRewriter disabled = new ConversationQueryRewriter(prompt, new GroundingInstructions(4, 3, 3, 4),
                0, TIMEOUT, new SimpleMeterRegistry());
        assertThat(disabled.rewrite(question("Restart it?"), context).effectiveQuery()).isEqualTo("Restart it?");
        verifyNoInteractions(context);
    }

    @Test
    void searchTextChangesButQuestionFiltersAndLifecycleStayIntact() {
        answer(new StandaloneQuery("  How do I restart the payments service?  "));
        UserQuestion original = question("А как его перезапустить?");
        UserQuestion result = rewriter.rewrite(original, context);
        assertThat(result.effectiveQuery()).isEqualTo("How do I restart the payments service?");
        assertThat(result.question()).isEqualTo(original.question());
        assertThat(result.history()).isEqualTo(original.history());
        assertThat(result.retrievalQuery().topK()).isEqualTo(3);
        assertThat(result.retrievalQuery().documentIds()).containsExactly("payments");
        assertThat(result.cancellation()).isSameAs(original.cancellation());
        assertThat(prompt.languageFor(result.question())).isEqualTo(AnswerLanguage.RU);
        assertThat(prompt.buildForConversationRewrite(original.question(), original.history()))
                .contains("{{ 7 * 7 }}", "{% include 'secret' %}", "Current question: А как его перезапустить?");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "Search one\nSearch two", "bad\u0000query"})
    void invalidOutputFallsBackToOriginalQuestion(String value) {
        answer(new StandaloneQuery(value));
        UserQuestion original = question("Restart it?");
        assertThat(rewriter.rewrite(original, context)).isSameAs(original);
    }

    @Test
    void nullOversizedAndFailedOutputFallBack() {
        UserQuestion original = question("Restart it?");
        answer(null);
        assertThat(rewriter.rewrite(original, context)).isSameAs(original);
        answer(new StandaloneQuery(null));
        assertThat(rewriter.rewrite(original, context)).isSameAs(original);
        answer(new StandaloneQuery("x".repeat(1001)));
        assertThat(rewriter.rewrite(original, context)).isSameAs(original);
        when(context.ai()).thenThrow(new IllegalStateException("model unavailable"));
        assertThat(rewriter.rewrite(original, context)).isSameAs(original);
    }

    @Test
    void cancellationBeforeOrDuringModelCallNeverFallsBackIntoRetrieval() {
        UserQuestion before = question("Restart it?");
        before.cancellation().cancel("disconnected");
        assertThatThrownBy(() -> rewriter.rewrite(before, context)).isInstanceOf(ChatCancelledException.class);
        verifyNoInteractions(context);
        UserQuestion during = question("Restart it?");
        when(context.ai()).thenAnswer(_ -> {
            during.cancellation().cancel("disconnected");
            throw new IllegalStateException("model stopped");
        });
        assertThatThrownBy(() -> rewriter.rewrite(during, context)).isInstanceOf(ChatCancelledException.class);
    }

    @Test
    void historyBudgetDropsOldSubjectsAndBoundsUntrustedText() {
        List<ConversationTurn> history = List.of(
                ConversationTurn.user("obsolete subject", Instant.EPOCH),
                ConversationTurn.assistant("obsolete answer", List.of(), Instant.EPOCH),
                ConversationTurn.user("current subject " + "x".repeat(900), Instant.EPOCH),
                ConversationTurn.assistant("current answer", List.of(), Instant.EPOCH));
        String rendered = prompt.buildForConversationRewrite("What about it?", history);
        assertThat(rendered).contains("current subject", "current answer", "What about it?")
                .doesNotContain("obsolete", "x".repeat(501));
        assertThat(rendered.length()).isLessThan(750);
    }

    @Test
    void aOneTurnBudgetCanResolveTheSubjectFromTheLastAssistantMessage() {
        var oneTurn = new ConversationQueryRewriter(new GroundedAnswerPrompt(6000, 1, AnswerLanguage.AUTO),
                new GroundingInstructions(4, 3, 3, 4), 1, TIMEOUT, new SimpleMeterRegistry());
        assertThat(oneTurn.shouldRewrite(question("How do I restart it?"))).isTrue();
    }
}
