package com.personal.chatbot.support;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * What the stages of an e2e class cost, read from the meters the running application publishes
 * (docs/cache-plan.md §2.1, K01): whole runs ({@code chatbot.chat.request}), model calls per operation
 * ({@code chatbot.ai.operation}) and retrieval passes ({@code chatbot.retrieval.search}). Written to
 * {@code build/reports/stage-costs/<class>.json} after every test, so the file always holds the class so
 * far and a later checkpoint can be measured against it on the same questions.
 *
 * <p>Import {@link WholeRun} into the test's context: by default Micrometer keeps percentiles and the
 * maximum over a two-minute window, which a minutes-long e2e class outlives.
 */
public final class StageCosts {

    static final List<String> FAMILIES = List.of("chatbot.chat.request", "chatbot.ai.operation", "chatbot.retrieval.search");
    private static final Path REPORTS = Path.of("build/reports/stage-costs");

    /** One timer — a family under one combination of its labels — in milliseconds. */
    public record Row(String name, Map<String, String> labels, long count, double meanMs, double p50Ms,
                      double p95Ms, double maxMs) {
    }

    private StageCosts() {
    }

    /** Keeps the distribution of the measured families over the whole run instead of a sliding window. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class WholeRun {

        @Bean
        MeterFilter stageCostsOverTheWholeRun() {
            return new MeterFilter() {
                @Override
                public DistributionStatisticConfig configure(Meter.@NonNull Id id, @NonNull DistributionStatisticConfig config) {
                    if (!FAMILIES.contains(id.getName())) {
                        return config;
                    }
                    return DistributionStatisticConfig.builder()
                            .percentiles(0.5, 0.95)
                            .percentilePrecision(3)
                            .expiry(Duration.ofDays(1))
                            .bufferLength(1)
                            .build()
                            .merge(config);
                }
            };
        }
    }

    public static List<Row> write(MeterRegistry meters, Class<?> testClass) throws IOException {
        List<Row> rows = FAMILIES.stream()
                .flatMap(name -> meters.find(name).timers().stream())
                .filter(timer -> timer.count() > 0)
                .map(StageCosts::row)
                .sorted(Comparator.comparing(Row::name).thenComparing(row -> row.labels().toString()))
                .toList();
        Files.createDirectories(REPORTS);
        Files.writeString(REPORTS.resolve(testClass.getSimpleName() + ".json"),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(rows) + "\n");
        return rows;
    }

    private static Row row(Timer timer) {
        HistogramSnapshot snapshot = timer.takeSnapshot();
        Map<String, String> labels = new TreeMap<>();
        timer.getId().getTags().forEach(tag -> labels.put(tag.getKey(), tag.getValue()));
        return new Row(timer.getId().getName(), labels, snapshot.count(), rounded(snapshot.mean(MILLISECONDS)),
                percentile(snapshot, 0.5), percentile(snapshot, 0.95), rounded(snapshot.max(MILLISECONDS)));
    }

    private static double percentile(HistogramSnapshot snapshot, double percentile) {
        return Arrays.stream(snapshot.percentileValues())
                .filter(value -> value.percentile() == percentile)
                .mapToDouble(value -> rounded(value.value(MILLISECONDS)))
                .findFirst()
                .orElse(Double.NaN);
    }

    private static double rounded(double millis) {
        return Math.round(millis * 10) / 10.0;
    }
}
