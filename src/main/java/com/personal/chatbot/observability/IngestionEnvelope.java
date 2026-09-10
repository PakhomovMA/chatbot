package com.personal.chatbot.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;

import java.util.Map;
import java.util.function.Supplier;

/** In-memory causality only: newest enqueue wins; restart captures no former upload context. */
public record IngestionEnvelope(Map<String, String> metadata, SpanContext cause) {
    static final ContextKey<SpanContext> CAUSE = ContextKey.named("chatbot.ingestion.cause");

    public IngestionEnvelope {
        metadata = Map.copyOf(metadata);
    }

    public static IngestionEnvelope capture() {
        SpanContext cause = Span.current().getSpanContext();
        SpanContext inherited = Context.current().get(CAUSE);
        return new IngestionEnvelope(ExecutionContext.metadata(),
                !cause.isValid() && inherited != null ? inherited : cause);
    }

    /** Worker-only boundary. No upload observation or diagnostic collector is retained. */
    public <T> T in(Supplier<T> work) {
        Map<String, String> previous = ExecutionContext.metadata();
        try (var _ = Context.root().with(CAUSE, cause).makeCurrent();
             var _ = ExecutionDiagnostics.NONE.install()) {
            ExecutionContext.restore(metadata);
            return work.get();
        } finally {
            ExecutionContext.restore(previous);
        }
    }
}
