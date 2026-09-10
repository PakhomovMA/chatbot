package com.personal.chatbot.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;

/** One transport delegate, one sanitization pass, no second processor or SDK. */
final class SanitizingSpanExporter implements SpanExporter {
    private final SpanExporter delegate;
    private final TelemetrySanitizer sanitizer;

    SanitizingSpanExporter(SpanExporter delegate, TelemetrySanitizer sanitizer) {
        this.delegate = delegate;
        this.sanitizer = sanitizer;
    }

    static SpanExporter unwrap(SpanExporter exporter) {
        return exporter instanceof SanitizingSpanExporter safe ? safe.delegate : exporter;
    }

    @Override public CompletableResultCode export(Collection<SpanData> spans) {
        return delegate.export(spans.stream().map(sanitizer::span).toList());
    }
    @Override public CompletableResultCode flush() { return delegate.flush(); }
    @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
}
