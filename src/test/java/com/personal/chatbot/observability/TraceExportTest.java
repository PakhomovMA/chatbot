package com.personal.chatbot.observability;

import com.personal.chatbot.config.ChatbotProperties;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O05 gate: the destination of the spans is the one the mode names, and there is one of it
 * (docs/observability-plan.md §9).
 *
 * <p>The cases here are the ones that are invisible at runtime. A mode that quietly falls back to
 * another destination, an endpoint that is a collector's base URL where a signal URL was needed, a
 * profile that turns the pipeline off underneath a mode that needs it — each of those leaves an
 * application that works and telemetry that does not, which is exactly the failure the plan asks to
 * be caught at the start instead.
 *
 * <p>These run against the real auto-configurations, so the ownership of the SDK, the provider and
 * the batch processor is what the application will actually get (docs/observability/o01/README.md),
 * not what this test asserts about itself.
 */
class TraceExportTest {

    private static final String ENDPOINT = TraceExportCheck.OTLP_ENDPOINT;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class,
                    com.embabel.agent.autoconfigure.observability.OpenTelemetrySdkAutoConfiguration.class))
            .withUserConfiguration(TraceExportConfiguration.class);

    private ApplicationContextRunner with(TraceExport mode, String... properties) {
        return context
                .withBean(ChatbotProperties.Observability.class,
                        () -> new ChatbotProperties.Observability(true, mode, Duration.ofSeconds(5)))
                .withPropertyValues(properties)
                .withPropertyValues("chatbot.observability.trace-export=" + mode.name().toLowerCase());
    }

    @Test
    void theDefaultExportsNothingAndBuildsNoPipelineToDoItWith() {
        with(TraceExport.NONE, "management.opentelemetry.enabled=false").run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started.getBeansOfType(SpanExporter.class)).isEmpty();
            assertThat(started.getBeansOfType(SdkTracerProvider.class)).isEmpty();
            assertThat(started.getBeansOfType(BatchSpanProcessor.class)).isEmpty();
            // The enrichment is still declared; with no span to enrich it costs nothing.
            assertThat(started.getBeansOfType(SpanProcessor.class)).hasSize(1);
        });
    }

    @Test
    void theLoggingModeWritesSpansToTheLogAndNowhereElse() {
        with(TraceExport.LOGGING).run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started.getBeansOfType(SpanExporter.class).values().stream()
                    .map(SanitizingSpanExporter::unwrap).toList())
                    .singleElement().isInstanceOf(LoggingSpanExporter.class);
            assertThat(started.getBeansOfType(OtlpHttpSpanExporter.class)).isEmpty();
            assertThat(started.getBeansOfType(SdkTracerProvider.class)).hasSize(1);
            assertThat(started.getBeansOfType(BatchSpanProcessor.class)).hasSize(1);
        });
    }

    @Test
    void theCollectorModeSendsOtlpOverExactlyOneProviderAndOneBatchProcessor() {
        with(TraceExport.OTLP, ENDPOINT + "=http://localhost:4318/v1/traces").run(started -> {
            assertThat(started).hasNotFailed();
            assertThat(started.getBeansOfType(SpanExporter.class).values().stream()
                    .map(SanitizingSpanExporter::unwrap).toList())
                    .singleElement().isInstanceOf(OtlpHttpSpanExporter.class);
            assertThat(started.getBeansOfType(LoggingSpanExporter.class)).isEmpty();
            assertThat(started.getBeansOfType(SdkTracerProvider.class)).hasSize(1);
            assertThat(started.getBeansOfType(BatchSpanProcessor.class)).hasSize(1);
        });
    }

    /** A collector that is down is an outage; a collector that was never configured is a mistake. */
    @Test
    void theCollectorModeWithoutAnEndpointFailsTheStartRatherThanFallingBackToTheLog() {
        with(TraceExport.OTLP).run(started ->
                assertThat(started).hasFailed().getFailure()
                        .hasMessageContaining(ENDPOINT)
                        .hasMessageContaining("no destination to fall back to"));
    }

    @Test
    void aCollectorBaseUrlIsRefusedWhereATraceSignalUrlIsNeeded() {
        with(TraceExport.OTLP, ENDPOINT + "=http://localhost:4318").run(started ->
                assertThat(started).hasFailed().getFailure()
                        .hasMessageContaining("/v1/traces"));
    }

    @Test
    void anEndpointConfiguredForAModeThatDoesNotUseItIsAContradictionAndNotIgnored() {
        with(TraceExport.LOGGING, ENDPOINT + "=http://localhost:4318/v1/traces").run(started ->
                assertThat(started).hasFailed().getFailure()
                        .hasMessageContaining("trace-export=logging"));
    }

    @Test
    void aPipelineSwitchedOffUnderneathTheCollectorModeIsReported() {
        with(TraceExport.OTLP, ENDPOINT + "=http://localhost:4318/v1/traces",
                "management.tracing.export.enabled=false").run(started ->
                assertThat(started).hasFailed().getFailure()
                        .hasMessageContaining("management.tracing.export.enabled=false"));
    }

    /**
     * The shipped configuration, read as it will be read at runtime. These four keys are what makes
     * the default hermetic and content-free (docs/observability-plan.md §6, §7.3); each of them is
     * easy to remove by accident, and nothing at runtime would say that it had been.
     */
    @Test
    void theShippedDefaultsExportNothingAndCaptureNoContent() throws Exception {
        Map<String, Object> base = properties("src/main/resources/application.yaml");
        assertThat(base).containsEntry("chatbot.observability.trace-export", "none")
                .containsEntry("management.opentelemetry.enabled", false)
                .containsEntry("embabel.agent.platform.observability.tracing-enabled", false)
                .containsEntry("embabel.agent.platform.observability.capture-message-content", false)
                .containsEntry("embabel.agent.platform.observability.trace-http-details", false);

        Map<String, Object> logging = properties("src/main/resources/application-observability.yaml");
        assertThat(logging).containsEntry("chatbot.observability.trace-export", "logging");
        assertThat(logging).doesNotContainKey(ENDPOINT);

        Map<String, Object> collector = properties("src/main/resources/application-observability-otlp.yaml");
        assertThat(collector).containsEntry("chatbot.observability.trace-export", "otlp")
                .containsEntry("embabel.agent.platform.observability.capture-message-content", false);
        assertThat(String.valueOf(collector.get(ENDPOINT))).endsWith("/v1/traces}");
    }

    private static Map<String, Object> properties(String path) throws Exception {
        return new org.springframework.boot.env.YamlPropertySourceLoader()
                .load(path, new org.springframework.core.io.FileSystemResource(path)).stream()
                .flatMap(source -> java.util.Arrays.stream(
                        ((org.springframework.core.env.EnumerablePropertySource<?>) source).getPropertyNames())
                        .map(name -> Map.entry(name, java.util.Objects.requireNonNull(source.getProperty(name)))))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Nothing that is reported may carry credentials, including the configuration that was wrong. */
    @Test
    void credentialsInAnEndpointAreNotRepeatedInWhatIsReported() {
        with(TraceExport.OTLP, ENDPOINT + "=http://user:hunter2@collector.invalid:4318").run(started ->
                assertThat(started).hasFailed().getFailure()
                        .hasMessageContaining("http://***@collector.invalid:4318")
                        .hasMessageNotContaining("hunter2"));
        assertThat(TraceExportCheck.redact("http://pk-lf-1:sk-lf-2@host/api")).isEqualTo("http://***@host/api");
        assertThat(TraceExportCheck.redact("http://host/api")).isEqualTo("http://host/api");
    }
}
