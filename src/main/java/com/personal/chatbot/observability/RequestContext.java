package com.personal.chatbot.observability;

import org.slf4j.MDC;

/**
 * MDC keys shared by the request filter, the ingestion worker and the chat service, so that every
 * log line of one HTTP request / ingestion run / chat turn can be correlated (docs/system-plan.md D14).
 */
public final class RequestContext {

    public static final String REQUEST_ID = "requestId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String MESSAGE_ID = "messageId";
    public static final String DOCUMENT_ID = "documentId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private RequestContext() {
    }

    /** Puts a key for the duration of a try-with-resources block. */
    public static Scope with(String key, String value) {
        String previous = MDC.get(key);
        MDC.put(key, value);
        return () -> {
            if (previous == null) MDC.remove(key);
            else MDC.put(key, previous);
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
