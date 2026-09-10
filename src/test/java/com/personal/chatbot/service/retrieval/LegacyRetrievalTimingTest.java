package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.retrieval.*;
import com.personal.chatbot.observability.RetrievalStrategy;
import com.personal.chatbot.observability.RetrievalWorkflow;
import com.personal.chatbot.support.TestObservations;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O01 v1 compatibility: retain these boundaries until an explicitly approved API migration.
 *
 * <p>Since O03 the durations come from the branch's own measurement rather than from a {@code long}
 * threaded through the search API, so they can be asserted exactly: the clock here only moves when
 * this test moves it (docs/observability-plan.md §4.3).
 */
class LegacyRetrievalTimingTest {
    private final ChatbotProperties.Retrieval settings = new ChatbotProperties.Retrieval(4, 3, 60, 0, 0, .5, 0, 1, 20);
    private final RetrievalTraceStore traces = new RetrievalTraceStore(20);
    private final RetrievalResult first = pass(new RetrievalTimings(4, 3, 2, 12));
    private final TestObservations observed = TestObservations.create();

    /** The branch as it stands when its model call is done: open for exactly {@code modelMs}. */
    private RetrievalWorkflow afterModelCall(RetrievalStrategy strategy, long modelMs) {
        return observed.workflowAfter(strategy, Duration.ofMillis(modelMs));
    }

    @Test
    void emptyExpansionRecordsOnlyQueryBuildTimeAndKeepsOriginalTotal() {
        var expander = new SearchExpander(_ -> { throw new AssertionError("No extra search expected"); }, traces, settings);
        RetrievalResult result;
        try (RetrievalWorkflow workflow = afterModelCall(RetrievalStrategy.EXPANSION, 25)) {
            result = expander.expand(RetrievalQuery.of("whole"), first, ExpansionStrategy.REWRITE, List.of(" "), workflow);
            workflow.succeeded();
        }
        assertThat(result.timings()).isEqualTo(first.timings());
        assertThat(result.expansion()).isEqualTo(SearchExpansion.none(ExpansionStrategy.REWRITE, 25));
        assertThat(result.hits()).isEmpty();
        assertThat(result.decomposition()).isNull();
    }

    @Test
    void undecomposedFallbackDropsBuildTimeAndReturnsUnmarkedOriginal() {
        var search = new SubQuestionSearch(traces, settings);
        RetrievalResult result;
        try (RetrievalWorkflow workflow = afterModelCall(RetrievalStrategy.DECOMPOSITION, 999)) {
            result = search.search(RetrievalQuery.of("whole"), List.of("whole", " "), workflow,
                    queries -> { assertThat(queries).hasSize(1); return List.of(first); });
            workflow.succeeded();
        }
        assertThat(result).isSameAs(first);
        assertThat(result.timings().totalMs()).isEqualTo(12);
        assertThat(result.decomposition()).isNull();
    }

    @Test
    void expansionAddsFirstPassTotalButDecompositionIncludesItInItsOwnWallTime() {
        var second = pass(new RetrievalTimings(40, 30, 20, 120));
        RetrievalResult expanded;
        try (RetrievalWorkflow workflow = afterModelCall(RetrievalStrategy.EXPANSION, 25)) {
            expanded = new SearchExpander(_ -> second, traces, settings)
                    .expand(RetrievalQuery.of("whole"), first, ExpansionStrategy.REWRITE, List.of("extra"), workflow);
            workflow.succeeded();
        }
        assertThat(expanded.expansion().tookMs()).isEqualTo(25);
        assertThat(expanded.timings()).isEqualTo(new RetrievalTimings(44, 33, 22, 12 + 25));

        RetrievalResult decomposed;
        try (RetrievalWorkflow workflow = afterModelCall(RetrievalStrategy.DECOMPOSITION, 35)) {
            decomposed = new SubQuestionSearch(traces, settings).search(RetrievalQuery.of("whole"),
                    List.of("part"), workflow, _ -> List.of(first, second));
            workflow.succeeded();
        }
        assertThat(decomposed.decomposition().tookMs()).isEqualTo(35);
        assertThat(decomposed.timings()).isEqualTo(new RetrievalTimings(44, 33, 22, 35));
    }

    /**
     * The v1 {@code tookMs} stops before the merge, as it always has. The measurement of the branch
     * carries on to the end of it, so the canonical timer is the wider of the two and the projection
     * is not read back out of it (§4.3).
     */
    @Test
    void theBranchIsStillBeingMeasuredWhenTheLegacyProjectionHasStoppedCounting() {
        var expander = new SearchExpander(_ -> pass(new RetrievalTimings(1, 1, 0, 2)), traces, settings);
        RetrievalResult expanded;
        try (RetrievalWorkflow workflow = afterModelCall(RetrievalStrategy.EXPANSION, 25)) {
            expanded = expander.expand(RetrievalQuery.of("whole"), first, ExpansionStrategy.REWRITE,
                    List.of("extra"), workflow);
            observed.advance(Duration.ofMillis(400)); // merging, tracing and logging, after the mark
            workflow.succeeded();
        }
        assertThat(expanded.expansion().tookMs()).isEqualTo(25);
        assertThat(observed.meters().get("chatbot.retrieval.workflow")
                .tags("strategy", "expansion", "outcome", "success").timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(425);
    }

    @Test
    void summedFacetWorkCanExceedParallelWallTime() {
        assertThat(PassDiagnostics.timings(List.of(first, first), 5)).isEqualTo(new RetrievalTimings(8, 6, 4, 5));
    }

    private static RetrievalResult pass(RetrievalTimings timings) {
        return new RetrievalResult("trace", "whole", RetrievalMode.HYBRID, 4, 12, List.of(), false, -1,
                timings, Instant.EPOCH);
    }
}
