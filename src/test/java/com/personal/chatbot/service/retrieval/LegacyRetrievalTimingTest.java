package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.retrieval.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** O01 v1 compatibility: retain these boundaries until an explicitly approved API migration. */
class LegacyRetrievalTimingTest {
    private final ChatbotProperties.Retrieval settings = new ChatbotProperties.Retrieval(4, 3, 60, 0, 0, .5, 0, 1, 20);
    private final RetrievalTraceStore traces = new RetrievalTraceStore(20);
    private final RetrievalResult first = pass(new RetrievalTimings(4, 3, 2, 12));

    @Test
    void emptyExpansionRecordsOnlyQueryBuildTimeAndKeepsOriginalTotal() {
        var expander = new SearchExpander(_ -> { throw new AssertionError("No extra search expected"); }, traces, settings);
        var result = expander.expand(RetrievalQuery.of("whole"), first, ExpansionStrategy.REWRITE, List.of(" "), 25);
        assertThat(result.timings()).isEqualTo(first.timings());
        assertThat(result.expansion()).isEqualTo(SearchExpansion.none(ExpansionStrategy.REWRITE, 25));
        assertThat(result.hits()).isEmpty();
        assertThat(result.decomposition()).isNull();
    }

    @Test
    void undecomposedFallbackDropsBuildTimeAndReturnsUnmarkedOriginal() {
        var search = new SubQuestionSearch(traces, settings);
        var result = search.search(RetrievalQuery.of("whole"), List.of("whole", " "), 999,
                queries -> { assertThat(queries).hasSize(1); return List.of(first); });
        assertThat(result).isSameAs(first);
        assertThat(result.timings().totalMs()).isEqualTo(12);
        assertThat(result.decomposition()).isNull();
    }

    @Test
    void expansionAddsFirstPassTotalButDecompositionIncludesItInItsOwnWallTime() {
        var second = pass(new RetrievalTimings(40, 30, 20, 120));
        var expanded = new SearchExpander(_ -> second, traces, settings)
                .expand(RetrievalQuery.of("whole"), first, ExpansionStrategy.REWRITE, List.of("extra"), 25);
        assertThat(expanded.expansion().tookMs()).isGreaterThanOrEqualTo(25);
        assertThat(expanded.timings()).isEqualTo(new RetrievalTimings(44, 33, 22, 12 + expanded.expansion().tookMs()));
        var decomposed = new SubQuestionSearch(traces, settings).search(RetrievalQuery.of("whole"),
                List.of("part"), 35, _ -> List.of(first, second));
        assertThat(decomposed.decomposition().tookMs()).isGreaterThanOrEqualTo(35);
        assertThat(decomposed.timings()).isEqualTo(new RetrievalTimings(44, 33, 22, decomposed.decomposition().tookMs()));
    }

    @Test
    void summedFacetWorkCanExceedParallelWallTime() {
        assertThat(PassFusion.timings(List.of(first, first), 5)).isEqualTo(new RetrievalTimings(8, 6, 4, 5));
    }

    private static RetrievalResult pass(RetrievalTimings timings) {
        return new RetrievalResult("trace", "whole", RetrievalMode.HYBRID, 4, 12, List.of(), false, -1,
                timings, Instant.EPOCH);
    }
}
