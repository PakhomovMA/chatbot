package com.personal.chatbot.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentPolicyTest {
    private static final String SECRET = "sentinel-secret-Q7x9";

    @Test
    void contentPolicyControlsFrameworkCaptureAndRefusesUnknownModes() {
        var context = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(ContentPolicyConfiguration.class)
                .withBean(com.embabel.agent.observability.ObservabilityProperties.class);
        context.run(started -> assertThat(started.getBean(com.embabel.agent.observability.ObservabilityProperties.class)
                .isCaptureMessageContent()).isFalse());
        context.withPropertyValues("chatbot.observability.content-policy=redacted-content").run(started -> {
            var properties = started.getBean(com.embabel.agent.observability.ObservabilityProperties.class);
            assertThat(properties.isCaptureMessageContent()).isTrue();
            assertThat(properties.isTraceHttpDetails()).isFalse();
        });
        context.withPropertyValues("chatbot.observability.content-policy=all-content")
                .run(started -> assertThat(started).hasFailed());
    }

    @Test
    void metadataOnlyRemovesEveryContentSurfaceAndKeepsParentsAndLinks() {
        var destination = new TracePipelineTest.Recorder();
        var sanitizer = new TelemetrySanitizer(ContentPolicy.METADATA_ONLY, List.of());
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(
                new SanitizingSpanExporter(destination, sanitizer))).build()) {
            var tracer = provider.get("test");
            var upload = tracer.spanBuilder("upload").startSpan();
            var span = tracer.spanBuilder("chatbot.chat.request").addLink(upload.getSpanContext(),
                    Attributes.builder().put("tool.result", SECRET).build()).startSpan();
            span.setAttribute("gen_ai.input.messages", "question/history/document: " + SECRET);
            span.setAttribute("gen_ai.output.messages", SECRET);
            span.setAttribute("tool.arguments", SECRET);
            span.setAttribute("tool.result", SECRET);
            span.setAttribute("http.request.header.authorization", SECRET);
            span.setAttribute("session.id", "conversation-1");
            span.setStatus(StatusCode.ERROR, SECRET);
            span.recordException(new IllegalStateException(SECRET));
            span.addEvent(SECRET, Attributes.builder().put("body", SECRET).build());
            span.end();
            upload.end();
        }
        assertThat(destination.spans).hasSize(2);
        SpanData span = destination.spans.getFirst();
        assertThat(span.toString()).doesNotContain(SECRET);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("session.id")))
                .isEqualTo("conversation-1");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getLinks()).singleElement().satisfies(link ->
                assertThat(link.getSpanContext()).isEqualTo(destination.spans.getLast().getSpanContext()));
        assertThat(span.getEvents()).anySatisfy(event -> assertThat(event.getName()).isEqualTo("exception"));
    }

    @Test
    void optInRedactsBeforeTruncationAndEnforcesPayloadBounds() {
        var sanitizer = new TelemetrySanitizer(ContentPolicy.REDACTED_CONTENT, List.of(SECRET));
        String input = "A benign explanation password=unsafe Bearer eyTOKEN user@example.org " + SECRET;
        assertThat(sanitizer.text(input)).contains("A benign explanation", "[REDACTED]")
                .doesNotContain("unsafe", "eyTOKEN", "user@example.org", SECRET);
        assertThat(sanitizer.text("a".repeat(9000) + SECRET)).hasSize(TelemetrySanitizer.MAX_TEXT);
        var destination = new TracePipelineTest.Recorder();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(
                new SanitizingSpanExporter(destination, sanitizer))).build()) {
            var span = provider.get("test").spanBuilder("chatbot.ai.operation").startSpan();
            span.setAttribute("input.value", input);
            span.setAttribute("http.request.header.authorization", SECRET);
            span.end();
        }
        assertThat(destination.spans.getFirst().toString()).contains("A benign explanation")
                .doesNotContain(SECRET, "unsafe", "eyTOKEN");
    }

    @Test
    void consoleAndStructuredLogsSuppressContentAndExposeTheRealCurrentSpan() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.personal.chatbot.service.chat.ChatService");
        var event = new LoggingEvent("test", logger, Level.ERROR, "Streaming chat failed",
                new IllegalStateException(SECRET), new Object[]{SECRET});
        try (var provider = SdkTracerProvider.builder().build()) {
            var span = provider.get("test").spanBuilder("chatbot.chat.request").startSpan();
            try (var _ = span.makeCurrent()) {
                String console = new SafeMessageConverter().convert(event) + new CorrelationConverter().convert(event);
                String structured = new SafeStructuredLogFormatter().format(event);
                assertThat(console).contains(span.getSpanContext().getTraceId(), span.getSpanContext().getSpanId())
                        .doesNotContain(SECRET);
                assertThat(structured).contains(span.getSpanContext().getTraceId(), span.getSpanContext().getSpanId())
                        .doesNotContain(SECRET);
            } finally { span.end(); }
        }
    }
}
