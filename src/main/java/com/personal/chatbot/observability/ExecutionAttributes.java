package com.personal.chatbot.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
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
 * <p>A span takes them from two places, in this order:
 *
 * <ol>
 *   <li>the MDC, which is where this application already keeps them (docs/system-plan.md D14), so a
 *       span is labelled wherever the log lines of the same work are;</li>
 *   <li>failing that, the span it is a child of. The agent, its actions, the tool loop and the model
 *       call run on the platform's own threads, which the MDC of the request does not reach; they are
 *       still children of the measurement that started them, and what that measurement knows is
 *       inherited rather than looked up again.</li>
 * </ol>
 *
 * <p>Inheritance is what makes the second case work at all today. Carrying the MDC across those
 * threads is a separate change (O06); until then a framework span is labelled by its parent, and a
 * span with no local parent — an ingestion worker that starts a trace of its own — is not labelled
 * until that context is passed to it explicitly.
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
        ReadableSpan parent = Span.fromContext(parentContext) instanceof ReadableSpan readable ? readable : null;
        for (Map.Entry<String, AttributeKey<String>> key : KEYS) {
            String value = MDC.get(key.getKey());
            if (value == null || value.isBlank()) {
                value = inherited(parent, key.getValue());
            }
            if (value != null && !value.isBlank()) {
                span.setAttribute(key.getValue(), value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value);
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
    public void onEnd(ReadableSpan span) {
        // Nothing: this processor enriches, it does not export.
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
