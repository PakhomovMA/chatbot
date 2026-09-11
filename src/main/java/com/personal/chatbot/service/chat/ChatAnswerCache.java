package com.personal.chatbot.service.chat;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.agent.GroundedAnswer;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ConversationTurn;
import com.personal.chatbot.models.chat.Grounding;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.observability.CacheObservations;
import com.personal.chatbot.observability.CacheObservations.Layer;
import com.personal.chatbot.observability.CacheObservations.Lookup;
import com.personal.chatbot.observability.CacheObservations.Store;
import com.personal.chatbot.service.cache.AnswerCache;
import com.personal.chatbot.service.cache.CacheKeys;
import com.personal.chatbot.service.cache.CacheScope;
import com.personal.chatbot.service.cache.CachedAnswer;
import com.personal.chatbot.service.cache.PipelineFingerprint;
import com.personal.chatbot.service.index.IndexStatus;
import com.personal.chatbot.utils.Hashes;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * The answer cache as a chat run uses it (docs/cache-plan.md §3.3, §3.4, INV-12): whether a question may be
 * answered from it, under which key, and whether the answer a run computed may be kept.
 *
 * <p>A run asks once, after it has read its history and before the agent starts. A miss pins the
 * knowledge-base revision the run started at, and the answer is kept only if that revision still holds
 * once the answer is verified and the run is known to be wanted — the stale-put guard. The revision only
 * grows, so equality means that no change to the index crossed the run and that every retrieval pass it
 * made saw the same knowledge base.
 */
public class ChatAnswerCache {

    /** What the lookup decided for one run. */
    public sealed interface Decision {
    }

    /** The cache was not asked — the layer is off, or the question is not a first turn — and nothing is kept. */
    public record Bypass() implements Decision {
    }

    /** The question was answered before, at the same revision and with the same pipeline. */
    public record Hit(CachedAnswer answer) implements Decision {
    }

    /** Not answered yet: the run computes the answer and may keep it under {@code key} while {@code revision} holds. */
    public record Miss(String key, long revision) implements Decision {
    }

    private final AnswerCache cache;
    private final ChatbotProperties.AnswerCache settings;
    private final IndexStatus index;
    private final PipelineFingerprint pipeline;
    private final GroundedAnswerPrompt prompt;
    private final int defaultTopK;
    private final CacheObservations observations;
    private final Clock clock;

    /** @param defaultTopK the number of hits retrieval returns when a request names none */
    public ChatAnswerCache(AnswerCache cache, ChatbotProperties.AnswerCache settings, IndexStatus index,
                           PipelineFingerprint pipeline, GroundedAnswerPrompt prompt, int defaultTopK,
                           CacheObservations observations, Clock clock) {
        this.cache = cache;
        this.settings = settings;
        this.index = index;
        this.pipeline = pipeline;
        this.prompt = prompt;
        this.defaultTopK = defaultTopK;
        this.observations = observations;
        this.clock = clock;
    }

    /**
     * @param mode    the answer mode the run resolved
     * @param history the conversation so far, as the run read it under its lease
     */
    public Decision lookup(String question, AnswerMode mode, ChatRequest.Options options, List<ConversationTurn> history) {
        // A follow-up is answered from its history as well. Keyed by that history, an entry would outlive
        // the deletion of the conversation it came from, and it would hardly ever be hit (§3.3).
        if (!settings.enabled() || (!history.isEmpty() && !settings.followUps())) {
            observations.lookup(Layer.ANSWER, Lookup.BYPASS);
            return new Bypass();
        }
        long revision = index.revision();
        String key = CacheKeys.answer(question, mode, options.topK() != null ? options.topK() : defaultTopK,
                options.documentIds(), prompt.languageFor(question), historyDigest(history),
                new CacheScope(revision, pipeline));
        Optional<CachedAnswer> found = cache.find(key);
        observations.lookup(Layer.ANSWER, found.isPresent() ? Lookup.HIT : Lookup.MISS);
        return found.<Decision>map(Hit::new).orElseGet(() -> new Miss(key, revision));
    }

    /**
     * Keeps the answer a run computed after a miss, if the policy allows it and the knowledge base did not
     * move while it was computed. Called once the answer is verified and the run is known to be still
     * wanted: a run that failed, was cancelled or timed out never gets here, and nothing is counted for it.
     *
     * @param retrieval the retrieval the answer was verified against; null when neither the run nor the
     *                  trace ring holds it any more, and then a served copy would have no trace to point to
     */
    public void store(Miss miss, String question, GroundedAnswer answer, @Nullable RetrievalResult retrieval) {
        if (retrieval == null || !cacheable(answer.grounding(), retrieval)) {
            observations.store(Layer.ANSWER, Store.INELIGIBLE);
            return;
        }
        // A change landing between this check and the put leaves an entry under the old revision, which
        // no lookup builds a key for any more: harmless, and taken out by the bound or the TTL.
        if (index.revision() != miss.revision()) {
            observations.store(Layer.ANSWER, Store.STALE);
            return;
        }
        cache.put(miss.key(), new CachedAnswer(question, answer.answer(), answer.grounding(), answer.citations(),
                answer.notes(), retrieval, miss.revision(), clock.instant(), null));
        observations.store(Layer.ANSWER, Store.STORED);
    }

    private boolean cacheable(Grounding grounding, RetrievalResult retrieval) {
        return switch (grounding) {
            case GROUNDED, PARTIAL -> true;
            // "The knowledge base has nothing on this" is a fact about the revision only when retrieval
            // itself found too little. After sufficient evidence it is one generation gone wrong, and
            // keeping it would repeat that for as long as the entry lives (docs/eval-log.md, 2026-09-10).
            case INSUFFICIENT_EVIDENCE -> settings.cacheInsufficient() && !retrieval.evidenceSufficient();
        };
    }

    /** SHA-256 of exactly the history text the answer prompt carries; a first turn carries none. */
    private String historyDigest(List<ConversationTurn> history) {
        return history.isEmpty() ? CacheKeys.NO_HISTORY : Hashes.sha256(prompt.renderHistory(history));
    }
}
