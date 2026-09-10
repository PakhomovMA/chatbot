package com.personal.chatbot.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

import java.util.List;
import java.util.Map;

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
     * Which timer gets which buckets. The legacy names are deliberately absent: they keep the shape
     * they had, and a percentile over a population of successful runs alone would mislead anyway.
     */
    private static final Map<String, double[]> HISTOGRAMS = Map.of(
            "chatbot.chat.request", CHAT_SECONDS,
            "chatbot.chat.wait", CHAT_SECONDS,
            "chatbot.ai.operation", CHAT_SECONDS,
            "chatbot.sse.first.delta", CHAT_SECONDS,
            "chatbot.ingestion.processing", CHAT_SECONDS,
            "chatbot.ingestion.queue.wait", CHAT_SECONDS,
            "chatbot.retrieval.search", RETRIEVAL_SECONDS,
            "chatbot.embedding", EMBEDDING_SECONDS,
            "chatbot.embedding.wait", EMBEDDING_SECONDS,
            "chatbot.sse.send", SSE_SECONDS);

    private MeterSchema() {
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
            public Meter.Id map(Meter.Id id) {
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
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
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
