package com.personal.chatbot.observability;

import com.personal.chatbot.config.ChatbotProperties;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O05 gate: what the pipeline actually does with a span, on the provider the application will run
 * (docs/observability-plan.md §8.3, §7.2).
 *
 * <p>Sampling is checked at both ends because the plan asks for it: a probability of zero has to mean
 * that nothing is exported — not that a little is — and a probability of one has to mean the span
 * arrives with the identifiers of the execution it belongs to. Both are properties of the wiring, not
 * of the SDK: which sampler bean the provider ends up with is exactly what a version upgrade can
 * change underneath us.
 */
class TracePipelineTest {

    /** A destination that keeps what it was given, so a test can look at it. */
    static final class Recorder implements SpanExporter {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();
        private final AtomicInteger flushes = new AtomicInteger();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            flushes.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingDestination {
        @Bean
        Recorder recorder() {
            return new Recorder();
        }
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    com.embabel.agent.autoconfigure.observability.OpenTelemetrySdkAutoConfiguration.class))
            .withUserConfiguration(TraceExportConfiguration.class, RecordingDestination.class)
            .withBean(ChatbotProperties.Observability.class,
                    () -> new ChatbotProperties.Observability(true, true, TraceExport.NONE, Duration.ofSeconds(5)))
            .withPropertyValues("chatbot.observability.trace-export=none");

    /** Records one span through the real provider and hands what was exported to {@code assertion}. */
    private void trace(String probability, Consumer<List<SpanData>> assertion) {
        context.withPropertyValues("management.tracing.sampling.probability=" + probability)
                .run((AssertableApplicationContext started) -> {
                    assertThat(started).hasNotFailed();
                    SdkTracerProvider provider = started.getBean(SdkTracerProvider.class);
                    Recorder recorder = started.getBean(Recorder.class);
                    try (MDC.MDCCloseable conversation = MDC.putCloseable(RequestContext.CONVERSATION_ID, "conv-1");
                         MDC.MDCCloseable request = MDC.putCloseable(RequestContext.REQUEST_ID, "req-1")) {
                        Span span = provider.get("test").spanBuilder("chatbot.retrieval.search").startSpan();
                        span.end();
                    }
                    provider.forceFlush().join(10, TimeUnit.SECONDS);
                    assertion.accept(List.copyOf(recorder.spans));
                });
    }

    @Test
    void samplingOneExportsTheSpanWithTheIdentifiersOfItsExecution() {
        trace("1.0", spans -> {
            assertThat(spans).singleElement().satisfies(span -> {
                assertThat(span.getName()).isEqualTo("chatbot.retrieval.search");
                assertThat(span.getAttributes().asMap())
                        .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("session.id"), "conv-1")
                        .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("chatbot.request.id"), "req-1");
                // Only identifiers, and only the ones that were set: nothing is invented for a key
                // the execution did not have (docs/observability-plan.md §7.3).
                assertThat(span.getAttributes().asMap().keySet().stream().map(Object::toString))
                        .containsExactlyInAnyOrder("session.id", "chatbot.request.id");
            });
        });
    }

    /**
     * The framework's own threads do not see the MDC of the request; they do see the span they were
     * started under. Without this, an agent, its actions and the model call inside them arrive at the
     * backend belonging to no conversation (docs/observability-plan.md §7.2).
     */
    @Test
    void aChildSpanStartedWithoutTheMdcInheritsTheIdentifiersOfItsParent() {
        context.withPropertyValues("management.tracing.sampling.probability=1.0").run(started -> {
            assertThat(started).hasNotFailed();
            SdkTracerProvider provider = started.getBean(SdkTracerProvider.class);
            Recorder recorder = started.getBean(Recorder.class);
            io.opentelemetry.api.trace.Tracer tracer = provider.get("test");
            Span parent;
            try (MDC.MDCCloseable conversation = MDC.putCloseable(RequestContext.CONVERSATION_ID, "conv-2")) {
                parent = tracer.spanBuilder("chatbot.chat.request").startSpan();
            }
            // No MDC here at all: this is the platform thread the agent actually runs on.
            Span child = tracer.spanBuilder("agent KnowledgeAssistantAgent")
                    .setParent(io.opentelemetry.context.Context.current().with(parent)).startSpan();
            child.end();
            parent.end();
            provider.forceFlush().join(10, TimeUnit.SECONDS);
            assertThat(recorder.spans).hasSize(2).allSatisfy(span ->
                    assertThat(span.getAttributes().asMap())
                            .as(span.getName())
                            .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("session.id"), "conv-2"));
        });
    }

    @Test
    void samplingZeroExportsNothingAtAll() {
        trace("0.0", spans -> assertThat(spans).isEmpty());
    }

    @Test
    void theShutdownFlushIsBoundedAndAFlushThatNeverFinishesDoesNotHoldTheProcess() {
        SdkTracerProvider hangs = SdkTracerProvider.builder()
                .addSpanProcessor(new io.opentelemetry.sdk.trace.SpanProcessor() {
                    @Override
                    public void onStart(io.opentelemetry.context.Context parent,
                                        io.opentelemetry.sdk.trace.ReadWriteSpan span) {
                    }

                    @Override
                    public boolean isStartRequired() {
                        return false;
                    }

                    @Override
                    public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) {
                    }

                    @Override
                    public boolean isEndRequired() {
                        return false;
                    }

                    @Override
                    public CompletableResultCode forceFlush() {
                        return new CompletableResultCode();
                    }
                })
                .build();
        TelemetryFlush flush = new TelemetryFlush(() -> hangs, Duration.ofMillis(200));
        flush.start();
        long started = System.nanoTime();
        flush.stop();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(flush.isRunning()).isFalse();
    }

    @Test
    void thereIsNothingToFlushWhenNothingExports() {
        TelemetryFlush flush = new TelemetryFlush(() -> null, Duration.ofSeconds(1));
        flush.start();
        flush.stop();
        assertThat(flush.isRunning()).isFalse();
    }
}
