package com.personal.chatbot.observability;

import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O03 gate: the diagnostics of one execution belong to that execution
 * (docs/observability-plan.md §4.2). They are bounded, they are released with the run, they survive a
 * shared trace ring that has moved on, and they reach whatever thread the run handed work to.
 */
class ExecutionDiagnosticsTest {

    @Test
    void aRunFindsItsOwnTraceEvenAfterTheSharedRingHasEvictedIt() {
        RetrievalTraceStore ring = new RetrievalTraceStore(1);
        ExecutionDiagnostics diagnostics = ExecutionDiagnostics.open();
        RetrievalResult mine = trace("mine");
        try (ExecutionDiagnostics.Scope _ = diagnostics.install()) {
            ring.record(mine);
        }
        // Somebody else's question, on another thread, pushes it out of the ring one entry deep.
        ring.record(trace("theirs"));

        assertThat(ring.find("mine")).isEmpty();
        assertThat(diagnostics.find("mine")).contains(mine);
    }

    @Test
    void nothingIsCollectedOutsideARunAndNothingLeaksIntoTheNextOne() {
        ExecutionDiagnostics.collect(trace("nobody-asked")); // no execution here: simply not collected

        ExecutionDiagnostics first = ExecutionDiagnostics.open();
        try (ExecutionDiagnostics.Scope _ = first.install()) {
            ExecutionDiagnostics second = ExecutionDiagnostics.open();
            try (ExecutionDiagnostics.Scope _ = second.install()) {
                ExecutionDiagnostics.collect(trace("inner"));
            }
            // The nested run gave the outer one its thread back, so this belongs to the outer one.
            ExecutionDiagnostics.collect(trace("outer"));
            assertThat(second.find("outer")).isEmpty();
        }
        ExecutionDiagnostics.collect(trace("after")); // the run is over; nowhere left to collect it

        assertThat(first.find("inner")).isEmpty();
        assertThat(first.find("outer")).isPresent();
        assertThat(first.find("after")).isEmpty();
        assertThat(first.find(null)).isEmpty();
    }

    @Test
    void theCollectorIsBoundedAndKeepsTheNewestTraces() {
        ExecutionDiagnostics diagnostics = ExecutionDiagnostics.open();
        try (ExecutionDiagnostics.Scope _ = diagnostics.install()) {
            IntStream.range(0, ExecutionDiagnostics.ENTRIES + 5)
                    .forEach(i -> ExecutionDiagnostics.collect(trace("pass-" + i)));
        }

        assertThat(diagnostics.find("pass-0")).isEmpty();
        assertThat(diagnostics.find("pass-" + (ExecutionDiagnostics.ENTRIES + 4))).isPresent();
    }

    /** What a decomposition does: its passes run on the platform's workers, and their traces are the run's. */
    @Test
    void whatParallelWorkersRecordReachesTheRunThatSentThem() throws InterruptedException {
        ExecutionDiagnostics diagnostics = ExecutionDiagnostics.open();
        List<Thread> workers;
        try (ExecutionDiagnostics.Scope _ = diagnostics.install()) {
            ExecutionDiagnostics.Carrier carried = ExecutionDiagnostics.capture();
            workers = IntStream.range(0, 4).mapToObj(i -> Thread.ofPlatform().start(() ->
                    carried.in(() -> {
                        ExecutionDiagnostics.collect(trace("part-" + i));
                        return null;
                    }))).toList();
            for (Thread worker : workers) {
                assertThat(worker.join(java.time.Duration.ofSeconds(20))).isTrue();
            }
        }

        assertThat(workers).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(diagnostics.find("part-" + i)).as("pass %d", i).isPresent();
        }
    }

    @Test
    void aCarrierCapturedOutsideARunSimplyRunsTheWork() {
        assertThat(ExecutionDiagnostics.capture().in(() -> "done")).isEqualTo("done");
    }

    private static RetrievalResult trace(String traceId) {
        return new RetrievalResult(traceId, "whole", RetrievalMode.HYBRID, 4, 12, List.of(), false, -1,
                new RetrievalTimings(1, 1, 0, 2), Instant.EPOCH);
    }
}
