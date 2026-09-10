package com.personal.chatbot.observability;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.opentelemetry.context.Context;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Immutable handoff; each callback opens and restores scopes on its own thread. */
public final class ExecutionContext {
    static final List<String> KEYS = List.of(RequestContext.REQUEST_ID, RequestContext.CONVERSATION_ID,
            RequestContext.MESSAGE_ID, RequestContext.DOCUMENT_ID);
    private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().clearMissing(true).build();
    private final ContextSnapshot snapshot = SNAPSHOTS.captureAll();
    private final Context otel = Context.current();
    private final Map<String, String> mdc = metadata();
    private final ExecutionDiagnostics.Carrier diagnostics = ExecutionDiagnostics.capture();

    private ExecutionContext() { }

    public static ExecutionContext capture() {
        return new ExecutionContext();
    }

    static Map<String, String> metadata() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : KEYS) {
            String value = MDC.get(key);
            if (value != null) result.put(key, value);
        }
        return Map.copyOf(result);
    }

    static void restore(Map<String, String> value) {
        KEYS.forEach(MDC::remove);
        value.forEach(MDC::put);
    }

    public <T> T in(Supplier<T> work) {
        Map<String, String> previous = metadata();
        try (var _ = snapshot.setThreadLocals(); var _ = otel.makeCurrent()) {
            restore(mdc);
            return diagnostics.in(work);
        } finally {
            restore(previous);
        }
    }

    public Runnable wrap(Runnable work) {
        return () -> in(() -> { work.run(); return null; });
    }

    public reactor.util.context.Context reactor(reactor.util.context.Context context) {
        return snapshot.updateContext(context);
    }
}
