package com.personal.chatbot.service.chat;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.agent.GroundedAnswer;
import com.personal.chatbot.models.chat.AnswerLanguage;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.chat.Grounding;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.observability.CacheObservations;
import com.personal.chatbot.observability.Observations;
import com.personal.chatbot.service.cache.AnswerCache;
import com.personal.chatbot.service.cache.CachedAnswer;
import com.personal.chatbot.service.cache.PipelineFingerprint;
import com.personal.chatbot.service.chat.ChatAnswerCache.Bypass;
import com.personal.chatbot.service.chat.ChatAnswerCache.Decision;
import com.personal.chatbot.service.chat.ChatAnswerCache.Hit;
import com.personal.chatbot.service.chat.ChatAnswerCache.Miss;
import com.personal.chatbot.service.index.IndexStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * K02: eligibility, the answer key and the store policy of the answer cache (docs/cache-plan.md §3.2, §3.3),
 * with the knowledge-base revision under the test's control.
 */
class ChatAnswerCacheTest {

    private static final String QUESTION = "How do I restart the payments service?";
    private static final ChatRequest.Options DEFAULT = ChatRequest.Options.DEFAULT;
    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CacheObservations observations = new CacheObservations(Observations.standalone(meters));
    private final Map<String, CachedAnswer> entries = new HashMap<>();
    private final AnswerCache cache = new AnswerCache() {
        @Override
        public Optional<CachedAnswer> find(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        @Override
        public void put(String key, CachedAnswer answer) {
            entries.put(key, answer);
        }
    };
    private final AtomicLong revision = new AtomicLong(1);
    private final IndexStatus index = mock(IndexStatus.class);

    @BeforeEach
    void revisionUnderTheTestsControl() {
        when(index.revision()).thenAnswer(_ -> revision.get());
    }

    private ChatAnswerCache layer(ChatbotProperties.AnswerCache settings, AnswerLanguage language) {
        return new ChatAnswerCache(cache, settings, index, new PipelineFingerprint("pipeline"),
                new GroundedAnswerPrompt(6000, 10, language), 8, observations, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ChatAnswerCache layer() {
        return layer(settings(true, false, true), AnswerLanguage.AUTO);
    }

    private static ChatbotProperties.AnswerCache settings(boolean enabled, boolean followUps, boolean cacheInsufficient) {
        return new ChatbotProperties.AnswerCache(enabled, Duration.ofHours(24), DataSize.ofMegabytes(16), followUps,
                cacheInsufficient);
    }

    private static GroundedAnswer answer(Grounding grounding) {
        return new GroundedAnswer("Run the restart script [1].", grounding, List.of(), null, "trace-1", 12);
    }

    private static RetrievalResult retrieval(boolean sufficient) {
        return new RetrievalResult("trace-1", QUESTION, RetrievalMode.HYBRID, 8, 24, List.of(), sufficient, 0.5,
                new RetrievalTimings(0, 0, 0, 0), NOW);
    }

    /** One computed run: a miss, then the store decision for what it produced. */
    private void computed(ChatAnswerCache layer, String question, AnswerMode mode, ChatRequest.Options options,
                          List<ConversationTurn> history, GroundedAnswer answer, @Nullable RetrievalResult retrieval) {
        Decision decision = layer.lookup(question, mode, options, history);
        assertThat(decision).isInstanceOf(Miss.class);
        layer.store((Miss) decision, question, answer, retrieval);
    }

    private void computed(ChatAnswerCache layer, GroundedAnswer answer, @Nullable RetrievalResult retrieval) {
        computed(layer, QUESTION, AnswerMode.DETERMINISTIC, DEFAULT, List.of(), answer, retrieval);
    }

    private Decision lookup(ChatAnswerCache layer, String question, AnswerMode mode, ChatRequest.Options options) {
        return layer.lookup(question, mode, options, List.of());
    }

    @Test
    void aFirstQuestionAskedAgainIsFoundInItsNormalisedForm() {
        ChatAnswerCache layer = layer();
        computed(layer, answer(Grounding.GROUNDED), retrieval(true));

        Decision again = lookup(layer, "  how do I RESTART the payments service ", AnswerMode.DETERMINISTIC, DEFAULT);

        assertThat(again).isInstanceOfSatisfying(Hit.class, hit -> {
            assertThat(hit.answer().answer()).isEqualTo("Run the restart script [1].");
            assertThat(hit.answer().question()).isEqualTo(QUESTION);
            assertThat(hit.answer().retrieval().traceId()).isEqualTo("trace-1");
            assertThat(hit.answer().revision()).isEqualTo(1);
            assertThat(hit.answer().createdAt()).isEqualTo(NOW);
        });
        assertThat(count("chatbot.cache.lookup", "result", "miss")).isEqualTo(1);
        assertThat(count("chatbot.cache.lookup", "result", "hit")).isEqualTo(1);
        assertThat(count("chatbot.cache.store", "result", "stored")).isEqualTo(1);
    }

    @Test
    void aLayerThatIsOffIsBypassed() {
        ChatAnswerCache layer = layer(settings(false, false, true), AnswerLanguage.AUTO);

        assertThat(lookup(layer, QUESTION, AnswerMode.DETERMINISTIC, DEFAULT)).isInstanceOf(Bypass.class);
        assertThat(count("chatbot.cache.lookup", "result", "bypass")).isEqualTo(1);
        assertThat(entries).isEmpty();
    }

    @Test
    void aFollowUpIsBypassedUnlessFollowUpsAreCachedAndThenItsHistoryIsPartOfTheKey() {
        List<ConversationTurn> history = List.of(ConversationTurn.user("What is the payments service?", NOW),
                ConversationTurn.assistant("It takes the payments [1].", List.of(), NOW));
        List<ConversationTurn> otherHistory = List.of(ConversationTurn.user("What is the orders service?", NOW),
                ConversationTurn.assistant("It takes the orders [1].", List.of(), NOW));

        assertThat(layer().lookup(QUESTION, AnswerMode.DETERMINISTIC, DEFAULT, history)).isInstanceOf(Bypass.class);

        ChatAnswerCache followUps = layer(settings(true, true, true), AnswerLanguage.AUTO);
        computed(followUps, QUESTION, AnswerMode.DETERMINISTIC, DEFAULT, history, answer(Grounding.PARTIAL), retrieval(true));
        assertThat(followUps.lookup(QUESTION, AnswerMode.DETERMINISTIC, DEFAULT, history)).isInstanceOf(Hit.class);
        assertThat(followUps.lookup(QUESTION, AnswerMode.DETERMINISTIC, DEFAULT, otherHistory)).isInstanceOf(Miss.class);
        assertThat(followUps.lookup(QUESTION, AnswerMode.DETERMINISTIC, DEFAULT, List.of())).isInstanceOf(Miss.class);
    }

    @Test
    void everyResolvedOptionTheAnswerDependsOnIsPartOfTheKey() {
        ChatAnswerCache layer = layer();
        computed(layer, answer(Grounding.GROUNDED), retrieval(true));

        assertThat(lookup(layer, QUESTION, AnswerMode.DETERMINISTIC, new ChatRequest.Options(3, null, null, null)))
                .as("another topK").isInstanceOf(Miss.class);
        assertThat(lookup(layer, QUESTION, AnswerMode.DETERMINISTIC, new ChatRequest.Options(8, null, null, null)))
                .as("the default topK, named").isInstanceOf(Hit.class);
        assertThat(lookup(layer, QUESTION, AnswerMode.DETERMINISTIC, new ChatRequest.Options(null, Set.of("doc-1"), null, null)))
                .as("a document filter").isInstanceOf(Miss.class);
        assertThat(lookup(layer, QUESTION, AnswerMode.AGENTIC, DEFAULT))
                .as("another answer mode").isInstanceOf(Miss.class);
        assertThat(lookup(layer, QUESTION, AnswerMode.DETERMINISTIC, new ChatRequest.Options(null, null, true, null)))
                .as("diagnostics are served from the entry, not keyed").isInstanceOf(Hit.class);
        assertThat(lookup(layer(settings(true, false, true), AnswerLanguage.RU), QUESTION, AnswerMode.DETERMINISTIC, DEFAULT))
                .as("another answer language").isInstanceOf(Miss.class);
        assertThat(lookup(layer(settings(true, false, true), AnswerLanguage.EN), QUESTION, AnswerMode.DETERMINISTIC, DEFAULT))
                .as("the language AUTO resolves this question to").isInstanceOf(Hit.class);
    }

    @Test
    void aKnowledgeBaseChangeBetweenTwoRunsIsAMiss() {
        ChatAnswerCache layer = layer();
        computed(layer, answer(Grounding.GROUNDED), retrieval(true));
        revision.incrementAndGet();

        assertThat(lookup(layer, QUESTION, AnswerMode.DETERMINISTIC, DEFAULT)).isInstanceOf(Miss.class);
    }

    @Test
    void aKnowledgeBaseChangeDuringTheRunKeepsItsAnswerOutOfTheCache() {
        ChatAnswerCache layer = layer();
        Miss miss = (Miss) lookup(layer, QUESTION, AnswerMode.DETERMINISTIC, DEFAULT);
        revision.incrementAndGet();
        layer.store(miss, QUESTION, answer(Grounding.GROUNDED), retrieval(true));

        assertThat(count("chatbot.cache.store", "result", "stale")).isEqualTo(1);
        assertThat(count("chatbot.cache.store", "result", "stored")).isZero();
        assertThat(entries).isEmpty();
        assertThat(lookup(layer, QUESTION, AnswerMode.DETERMINISTIC, DEFAULT)).isInstanceOf(Miss.class);
    }

    @Test
    void whatIsKeptFollowsTheGroundingAndWhatRetrievalFound() {
        assertThat(kept(layer(), Grounding.GROUNDED, retrieval(true))).isTrue();
        assertThat(kept(layer(), Grounding.PARTIAL, retrieval(false))).isTrue();
        assertThat(kept(layer(), Grounding.INSUFFICIENT_EVIDENCE, retrieval(false)))
                .as("retrieval itself found too little: a fact about this revision").isTrue();
        assertThat(kept(layer(), Grounding.INSUFFICIENT_EVIDENCE, retrieval(true)))
                .as("sufficient evidence, but the answer did not use it: one generation").isFalse();
        assertThat(kept(layer(settings(true, false, false), AnswerLanguage.AUTO), Grounding.INSUFFICIENT_EVIDENCE,
                retrieval(false))).as("cache-insufficient off").isFalse();
        assertThat(kept(layer(), Grounding.GROUNDED, null)).as("no retrieval to serve it with").isFalse();

        assertThat(count("chatbot.cache.store", "result", "stored")).isEqualTo(3);
        assertThat(count("chatbot.cache.store", "result", "ineligible")).isEqualTo(3);
    }

    private boolean kept(ChatAnswerCache layer, Grounding grounding, @Nullable RetrievalResult retrieval) {
        entries.clear();
        computed(layer, answer(grounding), retrieval);
        return !entries.isEmpty();
    }

    private double count(String name, String key, String value) {
        return meters.get(name).tags("layer", "answer", key, value).counter().count();
    }
}
