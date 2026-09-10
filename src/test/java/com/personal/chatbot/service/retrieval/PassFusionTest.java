package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.models.retrieval.Provenance;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PassFusionTest {

    @Test
    void neighboursSharedByPassesAndHitsUseTheEvidenceBudgetOnlyOnce() {
        RetrievedChunk first = hit("first", 0.2);
        RetrievedChunk second = hit("second", 0.6);
        var merged = PassFusion.merge(List.of(
                pass(first, neighbour("shared", first)),
                pass(first, neighbour("shared", first), second, neighbour("shared", second))), 2, 60, DocumentSpread.UNLIMITED);

        assertThat(merged.hits()).extracting(RetrievedChunk::chunkId)
                .containsExactly("first", "shared", "second");
    }

    @Test
    void aNeighbourThatIsAlsoASelectedHitKeepsItsOwnRank() {
        RetrievedChunk first = hit("first", 0.2);
        RetrievedChunk second = hit("second", 0.6);
        var merged = PassFusion.merge(List.of(pass(first, neighbour("second", first)), pass(second)), 2, 60, DocumentSpread.UNLIMITED);

        assertThat(merged.hits()).extracting(RetrievedChunk::chunkId).containsExactly("first", "second");
        assertThat(merged.hits().getLast().isHit()).isTrue();
        assertThat(merged.hits().getLast().rank()).isEqualTo(2);
    }

    @Test
    void aNeighbourIsRetainedEvenWhenItsOwnHitRankFallsOutsideTopK() {
        RetrievedChunk first = hit("first", 0.2);
        var merged = PassFusion.merge(List.of(pass(first, neighbour("second", first)),
                pass(first, hit("second", 0.6))), 1, 60, DocumentSpread.UNLIMITED);

        assertThat(merged.hits()).extracting(RetrievedChunk::chunkId).containsExactly("first", "second");
        assertThat(merged.hits().getLast().neighbourOf()).isEqualTo("first");
    }

    @Test
    void aStrongMatchInALaterPassIsNotLostToAWeakFirstMatch() {
        var merged = PassFusion.merge(List.of(pass(hit("same", 0.2)), pass(hit("same", 0.8))), 1, 60, DocumentSpread.UNLIMITED);

        double score = RetrievalService.maxVectorScore(merged.hits());
        assertThat(score).isEqualTo(0.8);
        assertThat(RetrievalService.sufficient(merged.hits(), score, 0.5)).isTrue();
    }

    @Test
    void absentFacetScoresStayAbsentWhenAChunkMatchesSeveralPasses() {
        RetrievedChunk base = hit("same", 0.6);
        RetrievedChunk vectorOnly = new RetrievedChunk(base.chunkId(), base.text(), base.provenance(),
                0.6, null, 1.0, 1, null);
        RetrievedChunk textOnly = new RetrievedChunk(base.chunkId(), base.text(), base.provenance(),
                null, 0.4, 1.0, 1, null);

        assertThat(PassFusion.merge(List.of(pass(vectorOnly), pass(vectorOnly)), 1, 60, DocumentSpread.UNLIMITED)
                .hits().getFirst().textScore()).isNull();
        assertThat(PassFusion.merge(List.of(pass(textOnly), pass(textOnly)), 1, 60, DocumentSpread.UNLIMITED)
                .hits().getFirst().vectorScore()).isNull();
        RetrievedChunk combined = PassFusion.merge(List.of(pass(vectorOnly), pass(textOnly)), 1, 60, DocumentSpread.UNLIMITED)
                .hits().getFirst();
        assertThat(combined.vectorScore()).isEqualTo(0.6);
        assertThat(combined.textScore()).isEqualTo(0.4);
    }

    @Test
    void mergingPassesGivesEveryDocumentItsShareJustAsOnePassDoes() {
        RetrievalResult whole = pass(hit("kimi", "kimi:1", 0.6), hit("kimi", "kimi:2", 0.5),
                hit("kimi", "kimi:3", 0.4), hit("glm", "glm:1", 0.3));
        RetrievalResult part = pass(hit("kimi", "kimi:1", 0.6), hit("kimi", "kimi:2", 0.5),
                hit("kimi", "kimi:3", 0.4), hit("glm", "glm:1", 0.3));

        assertThat(PassFusion.merge(List.of(whole, part), 3, 60, DocumentSpread.UNLIMITED).hits())
                .extracting(RetrievedChunk::chunkId).containsExactly("kimi:1", "kimi:2", "kimi:3");
        assertThat(PassFusion.merge(List.of(whole, part), 3, 60, new DocumentSpread(0.6)).hits())
                .extracting(RetrievedChunk::chunkId).containsExactly("kimi:1", "kimi:2", "glm:1");
    }

    private static RetrievalResult pass(RetrievedChunk... hits) {
        return new RetrievalResult("trace", "query", RetrievalMode.HYBRID, 2, 6, List.of(hits),
                true, 0.8, new RetrievalTimings(10, 2, 1, 20), Instant.now());
    }

    private static RetrievedChunk hit(String id, double cosine) {
        return hit("doc", id, cosine);
    }

    private static RetrievedChunk hit(String documentId, String id, double cosine) {
        return new RetrievedChunk(id, "text of " + id,
                new Provenance(documentId, documentId, 1, "Section", List.of("Section"), id, 1, 0, 10, "text/markdown"),
                cosine, 0.1, 1.0, 1, null);
    }

    private static RetrievedChunk neighbour(String id, RetrievedChunk parent) {
        RetrievedChunk chunk = hit(id, 0);
        return new RetrievedChunk(id, chunk.text(), chunk.provenance(), null, null,
                parent.fusedScore(), parent.rank(), parent.chunkId());
    }
}
