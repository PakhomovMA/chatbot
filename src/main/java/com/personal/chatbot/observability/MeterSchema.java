package com.personal.chatbot.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What holds the application's meters to docs/observability/metric-catalog.json: the label keys they
 * may carry, and the buckets of the timers that need a percentile. Both are filters over the registry
 * rather than arguments at the call sites, so the schema stays in one place
 * (docs/observability-plan.md §5.3).
 */
public final class MeterSchema {

    /** The bucket sets of the catalog, in seconds. Starting calibration, not agreed SLOs. */
    private static final double[] RETRIEVAL_SECONDS = {0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};
    private static final double[] EMBEDDING_SECONDS = {0.01, 0.05, 0.1, 0.5, 1, 5, 10, 30};
    private static final double[] CHAT_SECONDS = {1, 5, 15, 30, 60, 120, 300, 600};
    private static final double[] SSE_SECONDS = {0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5};

    /**
     * Which timer gets which buckets. A timer not listed here keeps count, sum and max only.
     */
    private static final Map<String, double[]> HISTOGRAMS = Map.ofEntries(
            Map.entry("chatbot.chat.request", CHAT_SECONDS),
            Map.entry("chatbot.chat.wait", CHAT_SECONDS),
            Map.entry("chatbot.ai.operation", CHAT_SECONDS),
            Map.entry("gen_ai.client.operation", CHAT_SECONDS),
            Map.entry("chatbot.sse.first.delta", CHAT_SECONDS),
            Map.entry("chatbot.ingestion.processing", CHAT_SECONDS),
            Map.entry("chatbot.ingestion.queue.wait", CHAT_SECONDS),
            Map.entry("chatbot.retrieval.search", RETRIEVAL_SECONDS),
            Map.entry("chatbot.retrieval.workflow", RETRIEVAL_SECONDS),
            Map.entry("chatbot.retrieval.stage", RETRIEVAL_SECONDS),
            Map.entry("chatbot.embedding", EMBEDDING_SECONDS),
            Map.entry("chatbot.embedding.wait", EMBEDDING_SECONDS),
            Map.entry("chatbot.sse.send", SSE_SECONDS));

    private MeterSchema() {
    }

    /** Configured model names only; response metadata must not create unbounded series. */
    public static MeterFilter models(Set<String> models) {
        return new MeterFilter() {
            @Override
            public Meter.@NonNull Id map(Meter.@NonNull Id id) {
                if (!id.getName().startsWith("chatbot.") && !id.getName().startsWith("gen_ai.")) {
                    return id;
                }
                return id.replaceTags(id.getTags().stream().map(tag -> {
                    if (Set.of("model", "gen_ai.request.model", "gen_ai.response.model").contains(tag.getKey())
                            && !models.contains(tag.getValue()) && !"none".equals(tag.getValue())) {
                        return Tag.of(tag.getKey(), "unknown");
                    }
                    if ("error".equals(tag.getKey()) && !"none".equals(tag.getValue())) {
                        return Tag.of("error", "error");
                    }
                    return tag;
                }).toList());
            }
        };
    }

    /**
     * The application's own meters carry {@code outcome}, and only the values the catalog lists. The
     * standard Observation handler adds an {@code error} tag of its own whose value is the class name
     * of whatever was thrown — an open set that would multiply the series of every timer over time.
     * The exception stays on the observation, so a span still reports it; it just does not become a
     * label.
     */
    public static MeterFilter labels() {
        return new MeterFilter() {
            @Override
            public Meter.@NonNull Id map(Meter.@NonNull Id id) {
                if (!id.getName().startsWith("chatbot.") || id.getTag("error") == null) {
                    return id;
                }
                List<Tag> kept = id.getTags().stream().filter(tag -> !"error".equals(tag.getKey())).toList();
                return id.replaceTags(kept);
            }
        };
    }

    /**
     * Explicit buckets for the timers that need a percentile, rather than a histogram on everything
     * the framework publishes. Native histograms are a later decision, once the whole path to Grafana
     * has been checked.
     */
    public static MeterFilter histograms(boolean enabled) {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.@NonNull Id id, @NonNull DistributionStatisticConfig config) {
                double[] seconds = enabled ? HISTOGRAMS.get(id.getName()) : null;
                if (seconds == null || id.getType() != Meter.Type.TIMER) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(nanos(seconds))
                        .percentilesHistogram(false)
                        .build()
                        .merge(config);
            }
        };
    }

    /** Timer boundaries are configured in nanoseconds, whatever unit the scrape publishes. */
    private static double[] nanos(double[] seconds) {
        double[] converted = new double[seconds.length];
        for (int i = 0; i < seconds.length; i++) {
            converted[i] = seconds[i] * 1_000_000_000d;
        }
        return converted;
    }
}
