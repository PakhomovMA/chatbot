package com.personal.chatbot.service.cache;

import com.personal.chatbot.models.chat.Grounding;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.observability.CacheObservations;
import com.personal.chatbot.observability.Observations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** K02: the answer layer is bounded by weight and TTL, and counts what leaves it (docs/cache-plan.md §3.1, §3.6). */
class CaffeineAnswerCacheTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CacheObservations observations = new CacheObservations(Observations.standalone(meters));
    private final AtomicLong nanos = new AtomicLong();

    private CaffeineAnswerCache cache(Duration ttl, DataSize maxWeight) {
        return new CaffeineAnswerCache(ttl, maxWeight, observations, nanos::get);
    }

    static CachedAnswer answer(String text) {
        RetrievalResult retrieval = new RetrievalResult("trace", "question", RetrievalMode.HYBRID, 8, 24, List.of(),
                true, 0.5, new RetrievalTimings(0, 0, 0, 0), Instant.EPOCH);
        return new CachedAnswer("question", text, Grounding.PARTIAL, List.of(), null, retrieval, 1, Instant.EPOCH, null);
    }

    @Test
    void anEntryIsFoundUnderItsKeyAndNoOtherAndTheGaugeReadsTheSize() {
        CaffeineAnswerCache cache = cache(Duration.ofHours(1), DataSize.ofMegabytes(1));
        cache.put("key", answer("Restart it."));

        assertThat(cache.find("key")).map(CachedAnswer::answer).contains("Restart it.");
        assertThat(cache.find("other")).isEmpty();
        assertThat(entries()).isEqualTo(1);
    }

    @Test
    void theTextTheEntriesHoldIsBoundedAndWhatLeavesIsCountedAsSize() {
        CaffeineAnswerCache cache = cache(Duration.ofHours(1), DataSize.ofBytes(3L * answer("answer 0").weight()));
        for (int i = 0; i < 10; i++) {
            cache.put("key " + i, answer("answer " + i));
        }
        cache.cleanUp();

        assertThat(entries()).isLessThanOrEqualTo(3);
        assertThat(evictions("size")).isEqualTo(10 - entries());
        assertThat(evictions("expired")).isZero();
    }

    @Test
    void anEntryLivesItsTtlAfterItWasWrittenAndIsThenCountedAsExpired() {
        CaffeineAnswerCache cache = cache(Duration.ofMinutes(10), DataSize.ofMegabytes(1));
        cache.put("key", answer("Restart it."));

        nanos.addAndGet(Duration.ofMinutes(9).toNanos());
        assertThat(cache.find("key")).isPresent();
        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        assertThat(cache.find("key")).isEmpty();
        cache.cleanUp();

        assertThat(evictions("expired")).isEqualTo(1);
        assertThat(evictions("size")).isZero();
    }

    @Test
    void aReplacedEntryIsNotAnEviction() {
        CaffeineAnswerCache cache = cache(Duration.ofHours(1), DataSize.ofMegabytes(1));
        cache.put("key", answer("first"));
        cache.put("key", answer("second"));
        cache.cleanUp();

        assertThat(cache.find("key")).map(CachedAnswer::answer).contains("second");
        assertThat(evictions("size") + evictions("expired")).isZero();
    }

    @Test
    void anEntryWeighsItsTextAtTwoBytesACharacterOnTopOfAFixedAllowance() {
        assertThat(answer("").weight()).isEqualTo(CachedAnswer.OVERHEAD_BYTES + 2 * ("question".length() * 2));
        assertThat(answer("x".repeat(1000)).weight() - answer("").weight()).isEqualTo(2000);
    }

    private double entries() {
        return meters.get("chatbot.cache.entries").tag("layer", "answer").gauge().value();
    }

    private double evictions(String cause) {
        return meters.get("chatbot.cache.evictions").tags("layer", "answer", "cause", cause).counter().count();
    }
}
