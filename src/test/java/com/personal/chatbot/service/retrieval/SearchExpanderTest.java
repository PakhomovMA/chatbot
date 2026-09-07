package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.retrieval.ExpansionStrategy;
import com.personal.chatbot.models.retrieval.Provenance;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.models.retrieval.SearchExpansion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 9a: the deterministic half of {@code expandSearch} — extra passes and how they are merged. */
class SearchExpanderTest {

    private static final ChatbotProperties.Retrieval SETTINGS =
            new ChatbotProperties.Retrieval(4, 3, 60, 0.0, 0.0, 0.5, 0, 200);

    private final RetrievalTraceStore traces = new RetrievalTraceStore(20);
    private final List<RetrievalQuery> asked = new ArrayList<>();

    /** A retriever that answers each query with a canned ranking of chunk ids. */
    private SearchExpander expanderOver(Map<String, List<String>> rankings) {
        Retriever retriever = query -> {
            asked.add(query);
            return result(query.query(), rankings.getOrDefault(query.query(), List.of()));
        };
        return new SearchExpander(retriever, traces, SETTINGS);
    }

    @Test
    void passesAreFusedByRankSoAgreementBeatsTheWeakFirstPass() {
        SearchExpander expander = expanderOver(Map.of(
                "how to restart", List.of("c9", "c3"),
                "rollout restart deployment", List.of("c3", "c1")));
        RetrievalResult first = result("how do I bounce it", List.of("c9", "c3"));

        RetrievalResult merged = expander.expand(RetrievalQuery.of("how do I bounce it"), first, ExpansionStrategy.REWRITE,
                List.of("how to restart", "rollout restart deployment"), 120);

        // c3 is found by all three passes, c9 by two, c1 only by the last one.
        assertThat(merged.hits()).extracting(RetrievedChunk::chunkId).containsExactly("c3", "c9", "c1");
        assertThat(merged.hits()).extracting(RetrievedChunk::rank).containsExactly(1, 2, 3);
        assertThat(merged.expansion().strategy()).isEqualTo(ExpansionStrategy.REWRITE);
        assertThat(merged.expansion().addedHits()).isEqualTo(1);
        assertThat(merged.expansion().queries()).containsExactly("how to restart", "rollout restart deployment");
        assertThat(merged.expansion().tookMs()).isGreaterThanOrEqualTo(120);
        assertThat(merged.query()).isEqualTo("how do I bounce it | how to restart | rollout restart deployment");
        assertThat(merged.expanded()).isTrue();
        assertThat(traces.find(merged.traceId())).contains(merged);
        assertThat(merged.traceId()).isNotEqualTo(first.traceId());
    }

    @Test
    void theMergedResultIsCappedAtTopKAndItsSufficiencyIsDecidedAgain() {
        SearchExpander expander = expanderOver(Map.of("wider", List.of("c1", "c2", "c3", "c4", "c5")));
        RetrievalResult first = new RetrievalResult(UUID.randomUUID().toString(), "narrow", RetrievalMode.HYBRID, 4, 12,
                List.of(hit("c9", 1, 0.2)), false, 0.2, new RetrievalTimings(1, 1, 0, 3), Instant.now());

        RetrievalResult merged = expander.expand(RetrievalQuery.of("narrow"), first, ExpansionStrategy.REWRITE, List.of("wider"), 0);

        assertThat(merged.hits()).hasSize(4);
        // The canned pass scores every chunk at cosine 0.6, above the 0.5 floor the first pass missed.
        assertThat(first.evidenceSufficient()).isFalse();
        assertThat(merged.evidenceSufficient()).isTrue();
        assertThat(merged.maxVectorScore()).isEqualTo(0.6);
        assertThat(merged.candidates()).isEqualTo(first.candidates() + 12);
    }

    @Test
    void neighboursKeepTheQuestionAndWidenTheReadingWindowInstead() {
        SearchExpander expander = expanderOver(Map.of("how do I bounce it", List.of("c3")));
        RetrievalResult first = result("how do I bounce it", List.of("c3"));

        RetrievalResult merged = expander.expand(RetrievalQuery.of("how do I bounce it"), first,
                ExpansionStrategy.NEIGHBOURS, List.of("how do I bounce it"), 0);

        assertThat(asked).singleElement().satisfies(query -> {
            assertThat(query.query()).isEqualTo("how do I bounce it");
            assertThat(query.expandNeighbours()).isEqualTo(1);
        });
        assertThat(merged.query()).isEqualTo("how do I bounce it");
        assertThat(merged.expansion().strategy()).isEqualTo(ExpansionStrategy.NEIGHBOURS);
    }

    @Test
    void aStrategyThatProducedNothingLeavesTheEvidenceAloneButMarksItAsWidened() {
        SearchExpander expander = expanderOver(Map.of());
        RetrievalResult first = result("how do I bounce it", List.of("c9"));

        RetrievalResult merged = expander.expand(RetrievalQuery.of("how do I bounce it"), first, ExpansionStrategy.HYDE,
                List.of("  ", ""), 40);

        assertThat(asked).isEmpty();
        assertThat(merged.hits()).isEqualTo(first.hits());
        assertThat(merged.expansion()).isEqualTo(SearchExpansion.none(ExpansionStrategy.HYDE, 40));
        // Marked as widened, so the agent's evidenceReady condition turns true and the branch cannot repeat.
        assertThat(expander.worthExpanding(merged)).isFalse();
        assertThat(expander.worthExpanding(first)).isTrue();
        assertThat(traces.find(merged.traceId())).contains(merged);
    }

    @Test
    void neighboursOfASurvivingHitAreCarriedIntoTheMergedEvidence() {
        RetrievedChunk hit = hit("c3", 1, 0.6);
        RetrievedChunk neighbour = new RetrievedChunk("c4", "text of c4", provenance("c4"), null, null, 1.0, 1, "c3");
        Retriever retriever = query -> new RetrievalResult(UUID.randomUUID().toString(), query.query(), RetrievalMode.HYBRID,
                4, 12, List.of(hit, neighbour), true, 0.6, new RetrievalTimings(1, 1, 0, 2), Instant.now());
        SearchExpander expander = new SearchExpander(retriever, traces, SETTINGS);
        RetrievalResult first = result("q", List.of("c9"));

        RetrievalResult merged = expander.expand(RetrievalQuery.of("q"), first, ExpansionStrategy.NEIGHBOURS, List.of("q"), 0);

        // c9 and c3 tie on rank; the tie goes to the pass that ran first, and c4 follows the hit it expands.
        assertThat(merged.hits()).extracting(RetrievedChunk::chunkId).containsExactly("c9", "c3", "c4");
        assertThat(merged.hits().getLast().neighbourOf()).isEqualTo("c3");
        assertThat(merged.hits().getLast().rank()).isEqualTo(merged.hits().get(1).rank());
        assertThat(merged.expansion().addedHits()).isEqualTo(1);
    }

    private static RetrievalResult result(String query, List<String> chunkIds) {
        List<RetrievedChunk> hits = new ArrayList<>();
        for (String chunkId : chunkIds) {
            hits.add(hit(chunkId, hits.size() + 1, 0.6));
        }
        // A first pass only reaches the expander when it was found insufficient.
        return new RetrievalResult(UUID.randomUUID().toString(), query, RetrievalMode.HYBRID, 4, 12, hits,
                false, hits.isEmpty() ? -1 : 0.6, new RetrievalTimings(2, 1, 0, 5), Instant.now());
    }

    private static RetrievedChunk hit(String chunkId, int rank, double cosine) {
        return new RetrievedChunk(chunkId, "text of " + chunkId, provenance(chunkId), cosine, 0.1, 1.0 / rank, rank, null);
    }

    private static Provenance provenance(String chunkId) {
        return new Provenance("doc-1", "Runbook", 1, "Restart", List.of("Restart"), chunkId, 1, 0, 5, "text/markdown");
    }
}
