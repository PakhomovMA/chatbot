package com.personal.chatbot.service.cache;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.agent.UserQuestion;
import com.personal.chatbot.observability.CacheObservations;
import com.personal.chatbot.support.TestObservations;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CaffeineDerivationCacheTest {
    private final TestObservations metrics = TestObservations.create();
    private final UserQuestion question = new UserQuestion("c", "m", "q", List.of(), null, null);

    private CaffeineDerivationCache cache(boolean enabled, boolean shadow, int maxEntries) {
        return new CaffeineDerivationCache(new ChatbotProperties.DerivationCache(enabled, shadow, Duration.ofMinutes(1), maxEntries, org.springframework.util.unit.DataSize.ofMegabytes(16)),
                "model", new PipelineFingerprint("pipeline"), new CacheObservations(metrics.observations()),
                metrics.clock()::monotonicTime);
    }

    private DerivationCache.Attempt lookup(CaffeineDerivationCache cache, String prompt) {
        return cache.lookup(Derivation.EXPAND_REWRITE, prompt, "instructions", 0.1, question);
    }

    @Test
    void storesImmutableResultsExpiresFromWriteAndBoundsEntries() {
        var cache = cache(true, false, 2);
        var result = new java.util.ArrayList<>(List.of("one"));
        lookup(cache, "p").usable(result);
        result.add("two");
        question.derivations().commit();
        assertThat(lookup(cache, "p").value()).contains(List.of("one"));
        metrics.advance(Duration.ofSeconds(40));
        assertThat(lookup(cache, "p").value()).isPresent();
        metrics.advance(Duration.ofSeconds(21));
        assertThat(lookup(cache, "p").value()).isEmpty();
        for (int i = 0; i < 5; i++) {
            lookup(cache, "p" + i).usable(List.of("value"));
            question.derivations().commit();
        }
        cache.cleanUp();
        assertThat(metrics.meters().get("chatbot.cache.entries").tag("layer", "derivation").gauge().value()).isLessThanOrEqualTo(2);
        assertThat(metrics.meters().get("chatbot.cache.evictions").tags("layer", "derivation", "cause", "size").counter().count()).isPositive();
        assertThat(metrics.meters().get("chatbot.cache.evictions").tags("layer", "derivation", "cause", "expired").counter().count()).isPositive();
    }

    @Test
    void shadowCountsWouldHitsButNeverReturnsAResultOrRefreshesItsTtl() {
        var cache = cache(false, true, 10);
        lookup(cache, "p").usable(List.of("secret result"));
        question.derivations().commit();
        metrics.advance(Duration.ofSeconds(40));
        var hit = lookup(cache, "p");
        assertThat(hit.value()).isEmpty();
        hit.usable(List.of("fresh result"));
        question.derivations().commit();
        assertThat(metrics.meters().get("chatbot.cache.lookup").tags("layer", "derivation", "result", "hit").counter().count()).isEqualTo(1);
        metrics.advance(Duration.ofSeconds(21));
        lookup(cache, "p");
        assertThat(metrics.meters().get("chatbot.cache.lookup").tags("layer", "derivation", "result", "miss").counter().count()).isEqualTo(2);
    }

    @Test
    void offDoesNotStoreAndConflictingModesFailFast() {
        var cache = cache(false, false, 10);
        lookup(cache, "p").usable(List.of("value"));
        question.derivations().commit();
        assertThat(lookup(cache, "p").value()).isEmpty();
        assertThat(metrics.meters().get("chatbot.cache.entries").tag("layer", "derivation").gauge().value()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> cache(true, true, 10));
    }
}
