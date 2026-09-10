package com.personal.chatbot.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The compatibility adapter for the timers that existed before the canonical schema
 * (docs/observability-plan.md §5.3): {@code chatbot.chat}, {@code chatbot.llm} and
 * {@code chatbot.retrieval} keep their names, labels, population and boundaries, and nothing but
 * this class writes them.
 *
 * <p>They are not the same measurement as the canonical families and must never be summed with them:
 * {@code chatbot.chat} and {@code chatbot.retrieval} record successful runs only, so availability
 * cannot be read from them, and {@code chatbot.llm} covers the narrower window each operation used to
 * time — for the agentic branch, the tool loop alone rather than the whole research. New dashboards
 * read the canonical names; these exist until their consumers have moved (O07).
 *
 * <p>Meters are created on first use, exactly as the old call sites created them, so a scrape shows
 * the same series as before rather than a wall of empty ones.
 */
public final class LegacyMetrics {

    /** Publishes nothing; the adapter switched off through {@code chatbot.observability.legacy-metrics}. */
    public static final LegacyMetrics DISABLED = new LegacyMetrics(null);

    private final @Nullable MeterRegistry meters;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public LegacyMetrics(@Nullable MeterRegistry meters) {
        this.meters = meters;
    }

    void record(MeasuredOperation operation, Map<String, String> labels, Outcome outcome, Duration elapsed) {
        if (meters == null) {
            return;
        }
        switch (operation) {
            // Only successful runs, as before: these two timers say nothing about availability.
            case CHAT_REQUEST -> {
                if (outcome == Outcome.SUCCESS) {
                    record("chatbot.chat", elapsed,
                            "grounding", label(labels, MeasuredOperation.Labels.GROUNDING),
                            "mode", label(labels, MeasuredOperation.Labels.MODE),
                            "answerMode", label(labels, MeasuredOperation.Labels.ANSWER_MODE));
                }
            }
            case RETRIEVAL_SEARCH -> {
                if (outcome == Outcome.SUCCESS) {
                    record("chatbot.retrieval", elapsed, "mode", label(labels, MeasuredOperation.Labels.MODE));
                }
            }
            // The old try/finally recorded whatever the operation ended in, cancellations included.
            case AI_OPERATION ->
                    record("chatbot.llm", elapsed, "operation", label(labels, MeasuredOperation.Labels.OPERATION));
            // The rest have no predecessor to keep: either the boundary was never measured before
            // (the two waits, the retrieval workflow and stages, ingestion processing) or the meter
            // kept its own name and is written by its facade under the canonical schema.
            case CHAT_WAIT, RETRIEVAL_WORKFLOW, RETRIEVAL_STAGE, EMBEDDING, EMBEDDING_WAIT,
                 INGESTION_PROCESSING, INGESTION_QUEUE_WAIT, INGESTION_STAGE -> {
            }
        }
    }

    private void record(String name, Duration elapsed, String... tags) {
        timers.computeIfAbsent(name + '/' + String.join("/", tags),
                        _ -> Timer.builder(name).tags(tags).register(meters))
                .record(elapsed);
    }

    private static String label(Map<String, String> labels, String key) {
        return labels.getOrDefault(key, MeasuredOperation.Labels.NONE);
    }
}
