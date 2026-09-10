package com.personal.chatbot.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O03 gate: telemetry has one owner (docs/observability-plan.md §3.1, §9). A service says which
 * operation it is running and how it ended; the meters, their names, their labels and the clock they
 * are measured against live in {@code com.personal.chatbot.observability} and nowhere else.
 *
 * <p>This is checked over the sources rather than left to review, because the failure mode is a slow
 * one: a single {@code Timer.builder} at a call site does not break anything today, and by the time
 * there are a dozen of them the schema cannot be verified in one place any more — which is the
 * problem this plan exists to fix.
 *
 * <p>The second rule is about the clock. Elapsed time that is <em>reported</em> goes through
 * {@link MonotonicClock}, so a test can advance it instead of sleeping. Elapsed time that
 * <em>controls execution</em> — a deadline for waiting, a bounded shutdown — is not a measurement at
 * all and stays a plain monotonic reading (§4.1); those classes are listed here by name, with the
 * reason, so that adding one is a decision rather than a habit.
 */
class TelemetryOwnershipTest {

    /** Where the rule does not apply: this is the package that owns telemetry. */
    private static final String OWNER = "com/personal/chatbot/observability/";

    /** Types no application code outside the owner may name, whatever it wants them for. */
    private static final Pattern TELEMETRY_SDK = Pattern.compile(
            "\\bio\\.micrometer\\.|\\bio\\.opentelemetry\\.|\\b(?:Timer|Counter|Gauge|DistributionSummary|LongTaskTimer)"
                    + "\\.builder\\(|\\bMeterRegistry\\b|\\bObservationRegistry\\b|\\bMeterFilter\\b");

    /**
     * Classes that read a monotonic clock to control execution rather than to report on it. Each is
     * listed with what its reading decides; none of them measures anything that reaches a meter.
     */
    private static final Map<String, String> DEADLINES = Map.of(
            "com/personal/chatbot/service/lifecycle/ShutdownSequence.java",
            "the bounded deadline each stage of the ordered stop is given (C08)",
            "com/personal/chatbot/service/embedding/PromptedEmbeddingService.java",
            "how long close() waits for a native call before refusing to close under it (C08)");

    private static final Pattern RAW_CLOCK = Pattern.compile("\\bSystem\\.(nanoTime|currentTimeMillis)\\(\\)");

    @Test
    void nothingOutsideTheObservabilityPackageRegistersAMeter() throws IOException {
        List<String> offenders = sources()
                .filter(source -> !relative(source).startsWith(OWNER))
                .filter(source -> TELEMETRY_SDK.matcher(read(source)).find())
                .map(TelemetryOwnershipTest::relative)
                .sorted()
                .toList();

        assertThat(offenders)
                .as("telemetry types belong to %s; declare the operation to a facade instead", OWNER)
                .isEmpty();
    }

    @Test
    void elapsedTimeIsReadThroughTheInjectedClockExceptWhereItIsADeadline() throws IOException {
        List<String> offenders = sources()
                .filter(source -> !relative(source).startsWith(OWNER))
                .filter(source -> !DEADLINES.containsKey(relative(source)))
                .filter(source -> RAW_CLOCK.matcher(read(source)).find())
                .map(TelemetryOwnershipTest::relative)
                .sorted()
                .toList();

        assertThat(offenders)
                .as("measure through MonotonicClock so a test can advance it; a deadline is listed in DEADLINES")
                .isEmpty();
    }

    /** The list above stays honest: a class that no longer keeps a deadline does not stay exempt. */
    @Test
    void everyListedDeadlineStillExists() throws IOException {
        Set<String> all = sources().map(TelemetryOwnershipTest::relative).collect(java.util.stream.Collectors.toSet());
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> deadline : DEADLINES.entrySet()) {
            if (!all.contains(deadline.getKey()) || !RAW_CLOCK.matcher(read(Path.of("src/main/java", deadline.getKey()))).find()) {
                stale.add(deadline.getKey());
            }
        }
        assertThat(stale).as("a class that stopped reading the clock directly should leave the exemption list").isEmpty();
    }

    private static Stream<Path> sources() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(Files.isDirectory(root)).as("this check is worthless if it audits nothing: %s", root.toAbsolutePath()).isTrue();
        return Files.walk(root).filter(path -> path.toString().endsWith(".java"));
    }

    private static String relative(Path source) {
        return Path.of("src/main/java").relativize(source).toString().replace('\\', '/');
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
