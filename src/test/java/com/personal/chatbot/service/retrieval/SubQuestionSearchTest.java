package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.retrieval.Provenance;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 9d: the deterministic half of {@code decomposeQuestion} — a pass per part and how they merge. */
class SubQuestionSearchTest {

    private static final ChatbotProperties.Retrieval SETTINGS =
            new ChatbotProperties.Retrieval(4, 3, 60, 0.0, 0.0, 0.5, 0, 1.0, 200);

    private final RetrievalTraceStore traces = new RetrievalTraceStore(20);
    private final SubQuestionSearch search = new SubQuestionSearch(traces, SETTINGS);
    private final List<RetrievalQuery> asked = new ArrayList<>();

    /** Runs the passes in order, as the platform's parallelMap does but without the threads. */
    private Function<List<RetrievalQuery>, List<RetrievalResult>> passesOver(Map<String, List<String>> rankings) {
        return queries -> queries.stream().map(query -> {
            asked.add(query);
            return result(query.query(), rankings.getOrDefault(query.query(), List.of()));
        }).toList();
    }

    @Test
    void everyPartContributesItsOwnBestPassagesToTheMergedEvidence() {
        RetrievalResult merged = search.search(RetrievalQuery.of("how do I roll back and abort the canary"),
                List.of("roll back a release", "abort the canary"), 90,
                passesOver(Map.of(
                        "how do I roll back and abort the canary", List.of("c1", "c9"),
                        "roll back a release", List.of("c1", "c2"),
                        "abort the canary", List.of("c7", "c8"))));

        assertThat(asked).extracting(RetrievalQuery::query).containsExactly(
                "how do I roll back and abort the canary", "roll back a release", "abort the canary");
        // c1 is found by two passes; then the second part's own best hit, which the whole question ranked
        // nowhere, ahead of the whole question's second hit — the reason the branch exists.
        assertThat(merged.hits()).extracting(RetrievedChunk::chunkId).containsExactly("c1", "c7", "c9", "c2");
        assertThat(merged.hits()).extracting(RetrievedChunk::rank).containsExactly(1, 2, 3, 4);
        assertThat(merged.decomposition().subQuestions()).containsExactly("roll back a release", "abort the canary");
        assertThat(merged.decomposition().addedHits()).isEqualTo(2);
        assertThat(merged.decomposition().tookMs()).isGreaterThanOrEqualTo(90);
        assertThat(merged.timings().totalMs()).isEqualTo(merged.decomposition().tookMs());
        assertThat(merged.query()).isEqualTo("how do I roll back and abort the canary | roll back a release | abort the canary");
        assertThat(merged.candidates()).isEqualTo(36);
        assertThat(merged.decomposed()).isTrue();
        assertThat(merged.multiPass()).isTrue();
        assertThat(traces.find(merged.traceId())).contains(merged);
    }

    @Test
    void blankRepeatedAndEchoedPartsAreNotSearchedFor() {
        RetrievalResult merged = search.search(RetrievalQuery.of("  how do I roll back  "),
                List.of(" roll back a release ", "", "  ", "roll back a release", "HOW DO I ROLL BACK"), 0,
                passesOver(Map.of("  how do I roll back  ", List.of("c1"), "roll back a release", List.of("c2"))));

        assertThat(asked).extracting(RetrievalQuery::query)
                .containsExactly("  how do I roll back  ", "roll back a release");
        assertThat(merged.decomposition().subQuestions()).containsExactly("roll back a release");
        assertThat(merged.query()).isEqualTo("how do I roll back | roll back a release");
    }

    @Test
    void withoutPartsTheQuestionKeepsItsPlainSinglePassResult() {
        RetrievalResult only = search.search(RetrievalQuery.of("how do I roll back"), List.of(), 30,
                passesOver(Map.of("how do I roll back", List.of("c1"))));

        assertThat(asked).extracting(RetrievalQuery::query).containsExactly("how do I roll back");
        // Unmarked: the question was searched once, so the widening branch of Phase 9a may still fire.
        assertThat(only.decomposed()).isFalse();
        assertThat(only.multiPass()).isFalse();
    }

    @Test
    void theMergedResultIsCappedAtTopKAndItsSufficiencyIsDecidedAgain() {
        RetrievalResult merged = search.search(RetrievalQuery.of("weak whole question"), List.of("strong part"), 0,
                queries -> List.of(
                        new RetrievalResult(UUID.randomUUID().toString(), "weak whole question", RetrievalMode.HYBRID, 4,
                                12, List.of(hit("c9", 1, 0.2)), false, 0.2, new RetrievalTimings(1, 1, 0, 3), Instant.now()),
                        result("strong part", List.of("c1", "c2", "c3", "c4", "c5"))));

        assertThat(merged.hits()).hasSize(4);
        assertThat(merged.evidenceSufficient()).isTrue();
        assertThat(merged.maxVectorScore()).isEqualTo(0.6);
    }

    @Test
    void neighboursOfASurvivingHitAreCarriedIntoTheMergedEvidence() {
        RetrievedChunk hit = hit("c3", 1, 0.6);
        RetrievedChunk neighbour = new RetrievedChunk("c4", "text of c4", provenance("c4"), null, null, 1.0, 1, "c3");
        RetrievalResult merged = search.search(RetrievalQuery.of("whole"), List.of("part"), 0,
                queries -> List.of(result("whole", List.of("c9")),
                        new RetrievalResult(UUID.randomUUID().toString(), "part", RetrievalMode.HYBRID, 4, 12,
                                List.of(hit, neighbour), true, 0.6, new RetrievalTimings(1, 1, 0, 2), Instant.now())));

        assertThat(merged.hits()).extracting(RetrievedChunk::chunkId).containsExactly("c9", "c3", "c4");
        assertThat(merged.hits().getLast().neighbourOf()).isEqualTo("c3");
    }

    @Test
    void aCallerThatLosesAPassIsAMistakeRatherThanHalfAnAnswer() {
        assertThatThrownBy(() -> search.search(RetrievalQuery.of("whole"), List.of("part"), 0,
                queries -> List.of(result("whole", List.of("c1")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 retrieval passes");
    }

    private static RetrievalResult result(String query, List<String> chunkIds) {
        List<RetrievedChunk> hits = new ArrayList<>();
        for (String chunkId : chunkIds) {
            hits.add(hit(chunkId, hits.size() + 1, 0.6));
        }
        return new RetrievalResult(UUID.randomUUID().toString(), query, RetrievalMode.HYBRID, 4, 12, hits,
                !hits.isEmpty(), hits.isEmpty() ? -1 : 0.6, new RetrievalTimings(2, 1, 0, 5), Instant.now());
    }

    private static RetrievedChunk hit(String chunkId, int rank, double cosine) {
        return new RetrievedChunk(chunkId, "text of " + chunkId, provenance(chunkId), cosine, 0.1, 1.0 / rank, rank, null);
    }

    private static Provenance provenance(String chunkId) {
        return new Provenance("doc-1", "Runbook", 1, "Restart", List.of("Restart"), chunkId, 1, 0, 5, "text/markdown");
    }
}
