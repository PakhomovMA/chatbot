package com.personal.chatbot.observability;

import com.personal.chatbot.observability.CacheObservations.Eviction;
import com.personal.chatbot.observability.CacheObservations.Layer;
import com.personal.chatbot.observability.CacheObservations.Lookup;
import com.personal.chatbot.observability.CacheObservations.ShadowVerdict;
import com.personal.chatbot.observability.CacheObservations.Store;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** K01: the cache facade publishes the catalog's series and counts each decision once (docs/cache-plan.md §3.6). */
class CacheObservationsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CacheObservations cache = new CacheObservations(Observations.standalone(meters));

    @Test
    void eachDecisionIsCountedUnderItsOwnLayerAndResult() {
        cache.lookup(Layer.ANSWER, Lookup.HIT);
        cache.lookup(Layer.ANSWER, Lookup.MISS);
        cache.lookup(Layer.ANSWER, Lookup.MISS);
        cache.store(Layer.ANSWER, Store.STALE);
        cache.evicted(Layer.DERIVATION, Eviction.EXPIRED);
        cache.shadow(ShadowVerdict.DISAGREE);

        assertThat(count("chatbot.cache.lookup", "layer", "answer", "result", "hit")).isEqualTo(1);
        assertThat(count("chatbot.cache.lookup", "layer", "answer", "result", "miss")).isEqualTo(2);
        assertThat(count("chatbot.cache.lookup", "layer", "semantic", "result", "hit")).isZero();
        assertThat(count("chatbot.cache.store", "layer", "answer", "result", "stale")).isEqualTo(1);
        assertThat(count("chatbot.cache.store", "layer", "answer", "result", "stored")).isZero();
        assertThat(count("chatbot.cache.evictions", "layer", "derivation", "cause", "expired")).isEqualTo(1);
        assertThat(count("chatbot.cache.semantic.shadow", "verdict", "disagree")).isEqualTo(1);
    }

    @Test
    void theSizeOfALayerIsReadWhenTheGaugeIs() {
        AtomicLong size = new AtomicLong(3);
        cache.entries(Layer.ANSWER, size, AtomicLong::get);
        size.set(5);

        assertThat(meters.get("chatbot.cache.entries").tag("layer", "answer").gauge().value()).isEqualTo(5);
    }

    /** Every series of the catalog is there before the first request, and nothing the catalog does not list. */
    @Test
    void theSeriesAreExactlyTheOnesTheCatalogDeclares() throws IOException {
        List<AtomicLong> sizes = List.of(new AtomicLong(), new AtomicLong(), new AtomicLong());
        for (Layer layer : Layer.values()) {
            cache.entries(layer, sizes.get(layer.ordinal()), AtomicLong::get);
        }

        Map<String, Set<Map<String, String>>> published = meters.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().startsWith("chatbot.cache."))
                .collect(Collectors.groupingBy(Meter.Id::getName,
                        Collectors.mapping(id -> id.getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)),
                                Collectors.toSet())));

        assertThat(published).isEqualTo(catalogSeries());
    }

    private double count(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    /** Every label combination of every {@code chatbot.cache.*} family in docs/observability/metric-catalog.json. */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<Map<String, String>>> catalogSeries() throws IOException {
        Map<String, Object> catalog = JsonMapper.builder().build()
                .readValue(Files.readString(Path.of("docs/observability/metric-catalog.json")), Map.class);
        Map<String, Set<Map<String, String>>> series = new HashMap<>();
        for (Map<String, Object> metric : (List<Map<String, Object>>) catalog.get("metrics")) {
            String name = (String) metric.get("name");
            if (name.startsWith("chatbot.cache.")) {
                series.put(name, combinations((Map<String, List<String>>) metric.get("labels")));
            }
        }
        return series;
    }

    private static Set<Map<String, String>> combinations(Map<String, List<String>> labels) {
        Set<Map<String, String>> combinations = Set.of(Map.of());
        for (Map.Entry<String, List<String>> label : labels.entrySet()) {
            combinations = combinations.stream()
                    .flatMap(partial -> label.getValue().stream().map(value -> {
                        Map<String, String> next = new HashMap<>(partial);
                        next.put(label.getKey(), value);
                        return Map.copyOf(next);
                    }))
                    .collect(Collectors.toSet());
        }
        return combinations;
    }
}
