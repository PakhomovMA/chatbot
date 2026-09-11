package com.personal.chatbot.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * What the result caches measure (docs/cache-plan.md §3.6, docs/observability/metric-catalog.json): one
 * lookup decision per layer for each request or preparation step, one store decision for each result
 * that got as far as being stored, why entries left, how many a layer holds and — for the semantic
 * layer's shadow mode — whether its candidate agreed with the fresh answer.
 *
 * <p>Every label is one of the enums below. A key, a question or anything else derived from a request
 * never becomes one (docs/observability-plan.md §7.3). A hit and a miss of the same run are told apart
 * here and by an attribute of its span, not by a label of {@code chatbot.chat.request}, which would
 * multiply its series.
 *
 * <p>The counters are registered up front, so a layer that never hit shows a zero rather than no series.
 */
public final class CacheObservations {

    /** The layers of docs/cache-plan.md §1. */
    public enum Layer { ANSWER, DERIVATION, SEMANTIC }

    /** How a lookup ended; {@code BYPASS} is a request the layer was not asked about — off, or not eligible. */
    public enum Lookup { HIT, MISS, BYPASS }

    /** What became of a result that reached the store decision: kept, not cacheable, or computed across a KB change. */
    public enum Store { STORED, INELIGIBLE, STALE }

    /** Why an entry left a layer; an explicit invalidation is not counted. */
    public enum Eviction { SIZE, EXPIRED }

    /** Whether a semantic candidate agreed with the answer computed for the same question (K05). */
    public enum ShadowVerdict { AGREE, DISAGREE }

    private final Observations observations;
    private final Map<Layer, Map<Lookup, Counter>> lookups = new EnumMap<>(Layer.class);
    private final Map<Layer, Map<Store, Counter>> stores = new EnumMap<>(Layer.class);
    private final Map<Layer, Map<Eviction, Counter>> evictions = new EnumMap<>(Layer.class);
    private final Map<ShadowVerdict, Counter> verdicts = new EnumMap<>(ShadowVerdict.class);

    public CacheObservations(Observations observations) {
        this.observations = observations;
        for (Layer layer : Layer.values()) {
            lookups.put(layer, counters(layer, "chatbot.cache.lookup", "Cache lookups by layer and result",
                    "result", Lookup.class));
            stores.put(layer, counters(layer, "chatbot.cache.store",
                    "Results that reached the store decision, by what became of them", "result", Store.class));
            evictions.put(layer, counters(layer, "chatbot.cache.evictions", "Entries that left a cache layer, by cause",
                    "cause", Eviction.class));
        }
        for (ShadowVerdict verdict : ShadowVerdict.values()) {
            verdicts.put(verdict, observations.counter("chatbot.cache.semantic.shadow",
                    "Semantic candidates compared with the fresh answer in shadow mode", "verdict", label(verdict)));
        }
    }

    public void lookup(Layer layer, Lookup result) {
        lookups.get(layer).get(result).increment();
    }

    public void store(Layer layer, Store result) {
        stores.get(layer).get(result).increment();
    }

    public void evicted(Layer layer, Eviction cause) {
        evictions.get(layer).get(cause).increment();
    }

    public void shadow(ShadowVerdict verdict) {
        verdicts.get(verdict).increment();
    }

    /**
     * Publishes the size of a layer, read on every scrape. The gauge holds {@code cache} weakly, like
     * every gauge of this application, so it must be the object the layer keeps; {@code size} must be
     * cheap and must not take a lock a scrape could wait on — Caffeine's {@code estimatedSize()} is both.
     */
    public <T> void entries(Layer layer, T cache, ToDoubleFunction<T> size) {
        Gauge.builder("chatbot.cache.entries", cache, size)
                .description("Entries a cache layer holds, estimated")
                .tag("layer", label(layer))
                .register(observations.meterRegistry());
    }

    private <E extends Enum<E>> Map<E, Counter> counters(Layer layer, String name, String description, String key,
                                                         Class<E> values) {
        Map<E, Counter> counters = new EnumMap<>(values);
        for (E value : values.getEnumConstants()) {
            counters.put(value, observations.counter(name, description, "layer", label(layer), key, label(value)));
        }
        return counters;
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
