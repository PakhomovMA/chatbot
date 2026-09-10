package com.personal.chatbot.observability;

import com.personal.chatbot.models.retrieval.RetrievalResult;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The diagnostics of one execution, kept for as long as that execution lasts and no longer
 * (docs/observability-plan.md §4.2). What a request answers with must not depend on a ring buffer
 * somebody else is filling, on a sampler, or on a span reaching an exporter: a retrieval trace this
 * run produced is held here until the run is over, and the shared
 * {@link com.personal.chatbot.service.retrieval.RetrievalTraceStore} stays what it is — the window
 * onto recent traces for the diagnostics API, which may well have evicted this one by the time the
 * answer is written.
 *
 * <p>It is bounded twice over: at most {@link #ENTRIES} traces, and only while the execution that
 * opened it is running. Closing restores whatever was installed before, so a nested execution cannot
 * take the outer one's diagnostics away from it.
 *
 * <p>Work handed to another thread carries the diagnostics explicitly through {@link #capture()}. The
 * thread-local is the accessor, not the owner: nothing outside this class reads it, and no pool
 * thread inherits it by accident.
 */
public final class ExecutionDiagnostics {

    /** Enough for a question split into parts, each pass and their merged result, with room to spare. */
    public static final int ENTRIES = 32;

    /** Collects nothing; what an execution that opened none is given, so callers never null-check. */
    public static final ExecutionDiagnostics NONE = new ExecutionDiagnostics(0);

    private static final ThreadLocal<ExecutionDiagnostics> CURRENT = new ThreadLocal<>();

    /** Closes an execution's diagnostics and puts back the ones that were installed before it. */
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }

    /** The diagnostics of an execution, ready to be installed on a thread it handed work to. */
    public interface Carrier {

        <T> T in(Supplier<T> work);
    }

    private final int capacity;
    private final Map<String, RetrievalResult> traces = new LinkedHashMap<>();

    private ExecutionDiagnostics(int capacity) {
        this.capacity = capacity;
    }

    /** Opens the diagnostics of one execution on this thread; the caller closes them exactly once. */
    public static ExecutionDiagnostics open() {
        return new ExecutionDiagnostics(ENTRIES);
    }

    /** Installs these diagnostics on the current thread until the scope is closed. */
    public Scope install() {
        ExecutionDiagnostics previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /**
     * Offers a retrieval result to whatever execution is running on this thread. A call from outside
     * one — a search issued by a test or a tool with no request behind it — is simply not collected.
     */
    public static void collect(RetrievalResult result) {
        ExecutionDiagnostics current = CURRENT.get();
        if (current != null) {
            current.record(result);
        }
    }

    /**
     * The diagnostics of this thread's execution, to be installed on a thread it hands work to. The
     * captured object is the execution's own, so results collected on the other thread reach it.
     */
    public static Carrier capture() {
        ExecutionDiagnostics captured = CURRENT.get();
        if (captured == null) {
            return new Carrier() {
                @Override
                public <T> T in(Supplier<T> work) {
                    return work.get();
                }
            };
        }
        return new Carrier() {
            @Override
            public <T> T in(Supplier<T> work) {
                try (Scope _ = captured.install()) {
                    return work.get();
                }
            }
        };
    }

    /** Keeps {@code result}, dropping the oldest once the bound is reached. */
    public void record(RetrievalResult result) {
        if (capacity == 0) {
            return;
        }
        synchronized (traces) {
            traces.remove(result.traceId());
            traces.put(result.traceId(), result);
            while (traces.size() > capacity) {
                traces.remove(traces.keySet().iterator().next());
            }
        }
    }

    /** The trace this execution produced under {@code traceId}, if it is still one of the newest ones. */
    public Optional<RetrievalResult> find(@Nullable String traceId) {
        if (traceId == null) {
            return Optional.empty();
        }
        synchronized (traces) {
            return Optional.ofNullable(traces.get(traceId));
        }
    }
}
