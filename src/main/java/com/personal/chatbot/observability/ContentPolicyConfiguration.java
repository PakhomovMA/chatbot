package com.personal.chatbot.observability;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;

@Configuration(proxyBeanMethods = false)
class ContentPolicyConfiguration {
    @Bean
    static BeanPostProcessor sanitizeExporters(Environment environment) {
        Binder binder = Binder.get(environment);
        ContentPolicy policy = binder.bind("chatbot.observability.content-policy", ContentPolicy.class)
                .orElse(ContentPolicy.METADATA_ONLY);
        List<String> redactions = binder.bind("chatbot.observability.redact-values", Bindable.listOf(String.class))
                .orElse(List.of());
        TelemetrySanitizer sanitizer = new TelemetrySanitizer(policy, redactions);
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String name) {
                if (bean instanceof com.embabel.agent.observability.ObservabilityProperties properties) {
                    properties.setCaptureMessageContent(policy == ContentPolicy.REDACTED_CONTENT);
                    properties.setTraceHttpDetails(false);
                }
                return bean instanceof SpanExporter exporter && !(bean instanceof SanitizingSpanExporter)
                        ? new SanitizingSpanExporter(exporter, sanitizer) : bean;
            }
        };
    }
}
