package com.personal.chatbot.service.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.personal.chatbot.observability.CacheObservations;
import com.personal.chatbot.observability.CacheObservations.Eviction;
import com.personal.chatbot.observability.CacheObservations.Layer;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Optional;

/**
 * The answer cache in memory (docs/cache-plan.md §3.1): bounded by the size of the text it holds, each
 * entry living {@code ttl} after it was written. An entry of an older revision is not removed when the
 * revision moves — no key reaches it any more, and the bound or the TTL takes it out.
 */
public final class CaffeineAnswerCache implements AnswerCache {

    private final Cache<String, CachedAnswer> entries;

    /**
     * @param maxWeight total {@link CachedAnswer#weight} of the entries
     * @param ticker    the time expiry is measured against; a test advances its own
     */
    public CaffeineAnswerCache(Duration ttl, DataSize maxWeight, CacheObservations observations, Ticker ticker) {
        this.entries = Caffeine.newBuilder()
                .maximumWeight(maxWeight.toBytes())
                .weigher((String _, CachedAnswer answer) -> answer.weight())
                .expireAfterWrite(ttl)
                .ticker(ticker)
                // Maintenance and the listener run on the thread that caused them: counting an eviction
                // is not worth a hop to the common pool, and it is counted by the time the put returns.
                .executor(Runnable::run)
                .removalListener((String _, CachedAnswer _, RemovalCause cause) -> evicted(observations, cause))
                .build();
        observations.entries(Layer.ANSWER, entries, Cache::estimatedSize);
    }

    @Override
    public Optional<CachedAnswer> find(String key) {
        return Optional.ofNullable(entries.getIfPresent(key));
    }

    @Override
    public void put(String key, CachedAnswer answer) {
        entries.put(key, answer);
    }

    /** Runs the maintenance Caffeine would otherwise do on a later access, such as removing expired entries. */
    void cleanUp() {
        entries.cleanUp();
    }

    /** A replaced or invalidated entry was not evicted, and the catalog does not count it (§3.6). */
    private static void evicted(CacheObservations observations, RemovalCause cause) {
        switch (cause) {
            case SIZE -> observations.evicted(Layer.ANSWER, Eviction.SIZE);
            case EXPIRED -> observations.evicted(Layer.ANSWER, Eviction.EXPIRED);
            default -> {
            }
        }
    }
}
