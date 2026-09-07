package com.personal.chatbot.observability;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Under the {@code observability} profile, Embabel's tracing (agent, action, LLM call, tool loop,
 * embedding and RAG spans) is exported to the log. Replace the exporter with an OTLP one to ship spans
 * to Langfuse, Jaeger or Zipkin (docs/observability.md).
 */
@Configuration(proxyBeanMethods = false)
@Profile("observability")
@ConditionalOnClass(LoggingSpanExporter.class)
class LoggingSpanExporterConfiguration {

    @Bean
    @ConditionalOnMissingBean(SpanExporter.class)
    SpanExporter loggingSpanExporter() {
        return LoggingSpanExporter.create();
    }
}
