package com.personal.chatbot.service.chat;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.common.ai.model.LlmOptions;
import com.personal.chatbot.models.agent.*;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.retrieval.*;
import com.personal.chatbot.service.cache.Derivation;
import com.personal.chatbot.service.cache.DerivationCache;
import com.personal.chatbot.service.retrieval.SearchExpander;
import com.personal.chatbot.service.retrieval.SubQuestionSearch;
import com.personal.chatbot.support.ChatSettings;
import com.personal.chatbot.support.DerivationCaches;
import com.personal.chatbot.support.TestObservations;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DerivationCacheStepsTest {
    private final TestObservations metrics = TestObservations.create();
    private DerivationCache cache = DerivationCaches.serving(metrics);
    private final OperationContext context = mock(OperationContext.class, RETURNS_DEEP_STUBS);
    private final AnswerStreamSink sink = mock(AnswerStreamSink.class);
    private final GroundedAnswerPrompt prompt = new GroundedAnswerPrompt(6000, 10, AnswerLanguage.AUTO);
    private final GroundingInstructions instructions = new GroundingInstructions(4, 3, 3, 4);

    private UserQuestion question(String previousAnswer) {
        return new UserQuestion("c", "m", "How do I restart it and check its health?", List.of(
                ConversationTurn.user("Tell me about payments", Instant.EPOCH),
                ConversationTurn.assistant(previousAnswer, List.of(), Instant.EPOCH)), null, null,
                AnswerMode.DETERMINISTIC, sink, ChatCancellation.none());
    }

    private void model(boolean usable) {
        var runner = context.ai().withLlm(any(LlmOptions.class)).withPromptContributor(any());
        when(runner.creating(StandaloneQuery.class).fromPrompt(anyString()))
                .thenReturn(new StandaloneQuery(usable ? "restart payments and check payments health" : ""));
        when(runner.creating(RewrittenQueries.class).fromPrompt(anyString()))
                .thenReturn(new RewrittenQueries(usable ? List.of("restart payments", "payments health") : List.of()));
        when(runner.creating(HypotheticalPassage.class).fromPrompt(anyString()))
                .thenReturn(new HypotheticalPassage(usable ? "Restart payments and inspect its health endpoint." : ""));
        when(runner.creating(SubQuestions.class).fromPrompt(anyString()))
                .thenReturn(new SubQuestions(usable ? List.of("restart payments", "payments health") : List.of("only one")));
        clearInvocations(context);
    }

    private List<String> run(Derivation step, UserQuestion question) {
        return switch (step) {
            case CONVERSATION_QUERY_REWRITE -> List.of(new ConversationQueryRewriter(prompt, instructions, 10,
                    Duration.ofSeconds(20), metrics.chatObservations(), cache).rewrite(question, context).effectiveQuery());
            case EXPAND_REWRITE, EXPAND_HYDE -> {
                var search = mock(SearchExpander.class);
                when(search.expand(any(), any(), any(), anyList(), any())).thenAnswer(call ->
                        result(new SearchExpansion(call.getArgument(2), call.getArgument(3), 0, 0), null));
                var strategy = step == Derivation.EXPAND_REWRITE ? ExpansionStrategy.REWRITE : ExpansionStrategy.HYDE;
                var settings = ChatSettings.of(new com.personal.chatbot.config.ChatbotProperties.ExpandSearch(strategy, 3),
                        ChatSettings.NO_DECOMPOSITION, ChatSettings.NO_COMPARISON);
                yield new EvidenceExpander(search, prompt, instructions, settings, metrics.chatObservations(),
                        metrics.retrievalObservations(), cache).expand(new Evidence(question, result(null, null)), context)
                        .retrieval().expansion().queries();
            }
            case DECOMPOSE_QUESTION -> {
                var search = mock(SubQuestionSearch.class);
                when(search.search(any(), anyList(), any(), any())).thenAnswer(call ->
                        result(null, new QuestionDecomposition(call.getArgument(1), 0, 0)));
                yield new QuestionDecomposer(_ -> result(null, null), search, prompt, instructions, ChatSettings.defaults(),
                        metrics.chatObservations(), metrics.retrievalObservations(), cache).decompose(question, context)
                        .retrieval().decomposition().subQuestions();
            }
        };
    }

    private static RetrievalResult result(SearchExpansion expansion, QuestionDecomposition decomposition) {
        return new RetrievalResult("trace", "query", RetrievalMode.HYBRID, 4, 0, List.of(), false, 0,
                new RetrievalTimings(0, 0, 0, 0), Instant.EPOCH, expansion, decomposition);
    }

    @ParameterizedTest
    @EnumSource(Derivation.class)
    void repeatKeepsTheValidatedResultWithoutModelStageOrAiOperation(Derivation step) {
        model(true);
        var first = question("Payments is a service");
        List<String> expected = run(step, first);
        first.derivations().commit();
        long operations = operations();
        double rewrites = metrics.meters().find("chatbot.chat.query.rewrite").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        clearInvocations(context, sink);
        assertThat(run(step, question("Payments is a service"))).isEqualTo(expected);
        verifyNoInteractions(context);
        verify(sink, never()).stage(argThat(s -> List.of("rewriting", "expanding", "decomposing").contains(s)));
        assertThat(operations()).isEqualTo(operations);
        assertThat(metrics.meters().find("chatbot.chat.query.rewrite").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum()).isEqualTo(rewrites);
    }

    @ParameterizedTest
    @EnumSource(Derivation.class)
    void unusableOutputIsNotCached(Derivation step) {
        model(false);
        var first = question("Payments is a service");
        run(step, first);
        first.derivations().commit();
        clearInvocations(context);
        run(step, question("Payments is a service"));
        verify(context).ai();
    }

    @ParameterizedTest
    @EnumSource(Derivation.class)
    void successfulStepOfUnfinishedRunIsNotVisible(Derivation step) {
        model(true);
        run(step, question("Payments is a service"));
        clearInvocations(context);
        run(step, question("Payments is a service"));
        verify(context).ai();
    }

    @ParameterizedTest
    @EnumSource(Derivation.class)
    void shadowWouldHitStillRunsTheModelForEveryStep(Derivation step) {
        cache = new com.personal.chatbot.service.cache.CaffeineDerivationCache(
                new com.personal.chatbot.config.ChatbotProperties.DerivationCache(false, true, Duration.ofDays(7), 5000,
                        org.springframework.util.unit.DataSize.ofMegabytes(16)), "model",
                new com.personal.chatbot.service.cache.PipelineFingerprint("pipeline"),
                new com.personal.chatbot.observability.CacheObservations(metrics.observations()),
                com.github.benmanes.caffeine.cache.Ticker.systemTicker());
        model(true);
        var first = question("Payments is a service");
        run(step, first);
        first.derivations().commit();
        clearInvocations(context);
        run(step, question("Payments is a service"));
        verify(context).ai();
        assertThat(metrics.meters().get("chatbot.cache.lookup")
                .tags("layer", "derivation", "result", "hit").counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Derivation.class)
    void modelExceptionsNeverPopulateTheCache(Derivation step) {
        when(context.ai()).thenThrow(new IllegalStateException("model unavailable"));
        var first = question("Payments is a service");
        run(step, first);
        first.derivations().commit();
        clearInvocations(context);
        run(step, question("Payments is a service"));
        verify(context).ai();
    }

    @org.junit.jupiter.api.Test
    void unchangedRewriteIsUsableButDifferentHistoryIsADifferentKey() {
        var first = question("Payments is a service");
        when(context.ai().withLlm(any(LlmOptions.class)).withPromptContributor(any())
                .creating(StandaloneQuery.class).fromPrompt(anyString())).thenReturn(new StandaloneQuery(first.question()));
        run(Derivation.CONVERSATION_QUERY_REWRITE, first);
        first.derivations().commit();
        clearInvocations(context);
        run(Derivation.CONVERSATION_QUERY_REWRITE, question("Payments is a service"));
        verifyNoInteractions(context);
        run(Derivation.CONVERSATION_QUERY_REWRITE, question("Search is another service"));
        verify(context).ai();
    }

    private long operations() {
        return metrics.meters().find("chatbot.ai.operation").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }
}
