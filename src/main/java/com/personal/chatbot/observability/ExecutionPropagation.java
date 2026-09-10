package com.personal.chatbot.observability;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Adds only application state to the snapshots taken by Embabel's real ExecutorAsyncer. */
@Component
final class ExecutionPropagation {
    private final ThreadLocalAccessor<Map<String, String>> metadata = new ThreadLocalAccessor<>() {
        public Object key() { return "chatbot.execution.metadata"; }
        public Map<String, String> getValue() { return ExecutionContext.metadata(); }
        public void setValue(Map<String, String> value) { ExecutionContext.restore(value); }
        public void setValue() { ExecutionContext.restore(Map.of()); }
    };
    private final ThreadLocalAccessor<ExecutionDiagnostics> diagnostics = new ThreadLocalAccessor<>() {
        public Object key() { return "chatbot.execution.diagnostics"; }
        public ExecutionDiagnostics getValue() { return ExecutionDiagnostics.current(); }
        public void setValue(ExecutionDiagnostics value) { ExecutionDiagnostics.restore(value); }
        public void setValue() { ExecutionDiagnostics.restore(null); }
    };

    @PostConstruct
    void register() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(metadata).registerThreadLocalAccessor(diagnostics);
    }

    @PreDestroy
    void unregister() {
        // A later application context may have replaced ours; never remove its accessor.
        for (ThreadLocalAccessor<?> accessor : new ThreadLocalAccessor<?>[]{metadata, diagnostics}) {
            if (ContextRegistry.getInstance().getThreadLocalAccessors().contains(accessor)) {
                ContextRegistry.getInstance().removeThreadLocalAccessor(accessor.key());
            }
        }
    }
}
