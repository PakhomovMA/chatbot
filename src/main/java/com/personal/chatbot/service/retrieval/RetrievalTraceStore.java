package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.observability.ExecutionDiagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Bounded in-memory ring of recent retrieval results for the diagnostics API (docs/system-plan.md D14).
 *
 * <p>Retrieval threads write and the diagnostics endpoint reads, so the deque is guarded by its own
 * monitor: adding an entry and trimming the oldest are one step, and a reader copies rather than
 * iterating a deque somebody is trimming (docs/concurrency-plan.md C09).
 */
public class RetrievalTraceStore {

    private final int capacity;
    private final Deque<RetrievalResult> traces = new ArrayDeque<>();

    public RetrievalTraceStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    /**
     * Keeps the result here and offers it to the execution that produced it. The ring is shared and
     * short: by the time an answer is written, a busy period may already have pushed its own trace
     * out of it, so the run that needs the trace for its response holds on to it itself
     * (docs/observability-plan.md §4.2). Nothing here depends on that having happened.
     *
     * <p>A result recorded again — an answer served from the cache brings its retrieval back
     * (docs/cache-plan.md §3.4) — moves to the front instead of being listed twice.
     */
    public void record(RetrievalResult result) {
        ExecutionDiagnostics.collect(result);
        synchronized (traces) {
            traces.removeIf(trace -> trace.traceId().equals(result.traceId()));
            traces.addFirst(result);
            while (traces.size() > capacity) {
                traces.removeLast();
            }
        }
    }

    public Optional<RetrievalResult> find(String traceId) {
        synchronized (traces) {
            return traces.stream().filter(t -> t.traceId().equals(traceId)).findFirst();
        }
    }

    /** Newest first. */
    public List<RetrievalResult> recent(int limit) {
        synchronized (traces) {
            List<RetrievalResult> out = new ArrayList<>(Math.min(limit, traces.size()));
            for (RetrievalResult trace : traces) {
                if (out.size() == limit) {
                    break;
                }
                out.add(trace);
            }
            return out;
        }
    }

    public int size() {
        synchronized (traces) {
            return traces.size();
        }
    }
}
