package com.personal.chatbot.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

/**
 * Puts the identifiers of the execution a span belongs to on the span itself
 * (docs/observability-plan.md §7.2): the conversation a chat turn is part of, the request it came in
 * on, the message it is answering, the document being ingested.
 *
 * <p>Every span of the execution carries them, not only its root. That is what the trace backend
 * filters and groups by — Langfuse reads {@code session.id} as the session of a trace, and a filter
 * that keeps only the spans of one conversation has to be able to see it on each of them.
 *
 * <p>Application MDC arrives through execution snapshots. Parent attributes provide a fallback for
 * framework callbacks that carry a span but no application accessor. The still-open HTTP parent is
 * enriched when chat first learns its conversation/message, and detached ingestion roots link to
 * the enqueue cause held in their envelope.
 *
 * <p>The set is closed and each of them is an identifier. No prompt, answer, passage, query, tool
 * argument, file name or exception message is added here; content leaves the process only under the
 * separate policy of §7.3, which this class does not open.
 */
final class ExecutionAttributes implements SpanProcessor {

    /** Longest identifier written to a span; anything longer is truncated rather than dropped. */
    private static final int MAX_LENGTH = 128;

    /**
     * MDC key to span attribute. {@code conversationId} becomes {@code session.id} because that is
     * the vendor-neutral name a trace backend already understands as "the session this trace is part
     * of"; the rest keep the application's own namespace.
     */
    private static final List<Map.Entry<String, AttributeKey<String>>> KEYS = List.of(
            Map.entry(RequestContext.CONVERSATION_ID, AttributeKey.stringKey("session.id")),
            Map.entry(RequestContext.REQUEST_ID, AttributeKey.stringKey("chatbot.request.id")),
            Map.entry(RequestContext.MESSAGE_ID, AttributeKey.stringKey("chatbot.message.id")),
            Map.entry(RequestContext.DOCUMENT_ID, AttributeKey.stringKey("chatbot.document.id")));

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        var cause = parentContext.get(IngestionEnvelope.CAUSE);
        if (!span.getParentSpanContext().isValid() && cause != null && cause.isValid()) span.addLink(cause);
        ReadableSpan parent = Span.fromContext(parentContext) instanceof ReadableSpan readable ? readable : null;
        for (Map.Entry<String, AttributeKey<String>> key : KEYS) {
            String value = MDC.get(key.getKey());
            if (value == null || value.isBlank()) {
                value = inherited(parent, key.getValue());
            }
            if (value != null && !value.isBlank()) {
                String attribute = value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
                span.setAttribute(key.getValue(), attribute);
                // The HTTP observation starts before the controller knows the conversation/message.
                // Enrich its still-open local parent when the chat boundary first learns them.
                if (parent instanceof ReadWriteSpan writable && !parent.hasEnded()
                        && parent.getAttribute(key.getValue()) == null) {
                    writable.setAttribute(key.getValue(), attribute);
                }
            }
        }
    }

    private static @Nullable String inherited(@Nullable ReadableSpan parent, AttributeKey<String> key) {
        return parent == null ? null : parent.getAttribute(key);
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(@NonNull ReadableSpan span) {
        // Nothing: this processor enriches, it does not export.
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
