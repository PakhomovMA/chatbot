package com.personal.chatbot.service.retrieval;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.ContentElement;
import com.personal.chatbot.models.retrieval.Provenance;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class NeighbourExpansionTest {

    /** A section of five chunks; the fake expander answers as the store does: sequence order, hit included. */
    private static final Map<String, List<String>> SECTION = Map.of(
            "c1", List.of("c1", "c2"),
            "c2", List.of("c1", "c2", "c3"),
            "c3", List.of("c2", "c3", "c4"),
            "c4", List.of("c3", "c4", "c5"),
            "c5", List.of("c4", "c5"));

    private static NeighbourExpansion expansion(int chunksEachSide) {
        return new NeighbourExpansion((chunkId, each) -> SECTION.getOrDefault(chunkId, List.of()).stream()
                .map(NeighbourExpansionTest::chunk).toList(), chunksEachSide);
    }

    private static ContentElement chunk(String id) {
        return Chunk.create("text of " + id, "section", Map.of(ProvenanceChunkTransformer.URTEXT, "text of " + id),
                id, "text of " + id, null);
    }

    private static RetrievedChunk hit(String id, int rank) {
        Provenance provenance = new Provenance("doc-1", "Runbook", 1, "Restart", List.of("Restart"), id, rank, 0, 5, "text/markdown");
        return new RetrievedChunk(id, "text of " + id, provenance, 0.6, 0.2, 1.0 / rank, rank, null);
    }

    @Test
    void offByDefaultAndTheHitsComeBackUntouched() {
        List<RetrievedChunk> hits = List.of(hit("c2", 1));
        assertThat(expansion(0).enabled()).isFalse();
        assertThat(expansion(0).expand(hits)).isSameAs(hits);
        assertThat(expansion(1).expand(List.of())).isEmpty();
    }

    @Test
    void everyHitIsShownWithItsSectionNeighboursInReadingOrder() {
        List<RetrievedChunk> expanded = expansion(1).expand(List.of(hit("c2", 1)));

        assertThat(expanded).extracting(RetrievedChunk::chunkId).containsExactly("c1", "c2", "c3");
        assertThat(expanded).filteredOn(RetrievedChunk::isHit).extracting(RetrievedChunk::chunkId).containsExactly("c2");
        RetrievedChunk neighbour = expanded.getFirst();
        assertThat(neighbour.neighbourOf()).isEqualTo("c2");
        assertThat(neighbour.rank()).isEqualTo(1);
        assertThat(neighbour.fusedScore()).isEqualTo(1.0);
        assertThat(neighbour.vectorScore()).isNull();
        assertThat(neighbour.textScore()).isNull();
        assertThat(neighbour.text()).isEqualTo("text of c1");
    }

    @Test
    void aChunkIsShownOnceAndNeverDemotedFromHitToNeighbour() {
        // c2 and c3 are both hits and each other's neighbours; c4 and c1 are only context.
        List<RetrievedChunk> expanded = expansion(1).expand(List.of(hit("c3", 1), hit("c2", 2)));

        assertThat(expanded).extracting(RetrievedChunk::chunkId).containsExactly("c3", "c4", "c1", "c2");
        assertThat(expanded).filteredOn(RetrievedChunk::isHit).extracting(RetrievedChunk::chunkId).containsExactly("c3", "c2");
        assertThat(expanded).filteredOn(chunk -> !chunk.isHit())
                .extracting(RetrievedChunk::chunkId, RetrievedChunk::neighbourOf, RetrievedChunk::rank)
                .containsExactly(tuple("c4", "c3", 1), tuple("c1", "c2", 2));
    }

    @Test
    void aHitWithoutSectionMetadataIsStillShown() {
        List<RetrievedChunk> expanded = expansion(1).expand(List.of(hit("unknown", 1), hit("c5", 2)));
        assertThat(expanded).extracting(RetrievedChunk::chunkId).containsExactly("unknown", "c4", "c5");
        assertThat(expanded.getFirst().isHit()).isTrue();
    }
}
