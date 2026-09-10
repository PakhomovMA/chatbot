package com.personal.chatbot.observability;

import com.personal.chatbot.config.ChatbotProperties;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The one place a destination for spans is chosen (docs/observability-plan.md §9, O05). Which one it
 * is comes from {@code chatbot.observability.trace-export} and from nothing else: no exporter appears
 * because another one is missing, and none is skipped because a bean of the same type happens to
 * exist.
 *
 * <p>Everything else about the pipeline stays where it already is. The SDK, the tracer provider and
 * the batch processor keep their existing owners — Embabel's auto-configuration for the provider,
 * Boot's for the processor and the sampler (docs/observability/o01/README.md) — so there is exactly
 * one path from an observation to a span to the wire. What is added here is a destination, the
 * execution identifiers every span of a run carries, one bounded flush on the way out, and the check
 * that the three agree.
 */
@Configuration(proxyBeanMethods = false)
class TraceExportConfiguration {

    /**
     * Spans in the log. Declared for the mode that asks for it, so that reading a run locally is a
     * choice rather than what is left when a collector was not configured.
     */
    @Bean
    @ConditionalOnProperty(name = "chatbot.observability.trace-export", havingValue = "logging")
    SpanExporter loggingSpanExporter() {
        return LoggingSpanExporter.create();
    }

    /**
     * The identifiers of the running execution, on every span of it. It enriches and exports nothing,
     * so it costs four thread-local reads per span that is created and nothing at all in the mode
     * where none is.
     */
    @Bean
    SpanProcessor executionAttributes() {
        return new ExecutionAttributes();
    }

    @Bean
    TelemetryFlush telemetryFlush(ObjectProvider<SdkTracerProvider> tracing,
                                  ChatbotProperties.Observability settings) {
        return new TelemetryFlush(tracing::getIfAvailable, settings.flushTimeout());
    }

    @Bean
    TraceExportCheck traceExportCheck(ChatbotProperties.Observability settings, Environment environment,
                                      ListableBeanFactory beans) {
        return new TraceExportCheck(settings.traceExport(), environment, beans);
    }
}
