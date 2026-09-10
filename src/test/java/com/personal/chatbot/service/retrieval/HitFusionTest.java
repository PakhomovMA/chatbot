package com.personal.chatbot.service.retrieval;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.common.core.types.SimpleSimilaritySearchResult;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The document quota of {@link DocumentSpread} as fusion applies it. The failing case is the one
 * observed live on 2026-09-10 (docs/eval-log.md): a question naming two documents, where the lexical
 * facet ranks one document's chunks wholesale above the other's.
 */
class HitFusionTest {

    private static final double PRODUCTION_SHARE = 0.6;

    @Test
    void oneDocumentDoesNotTakeEverySlotWhileAnotherHasCandidates() {
        // The vector facet answers both halves of the question: the documents alternate, and the
        // passage that answers the second half is the second hit overall.
        List<SimilarityResult<Chunk>> vector = new ArrayList<>();
        IntStream.rangeClosed(1, 8).forEach(i -> {
            vector.addAll(hits(chunk("kimi", i)));
            vector.addAll(hits(chunk("glm", i)));
        });
        // BM25 matches the document's own name in every chunk of it, so it ranks documents rather
        // than chunks and one document exhausts the candidate budget on its own.
        List<SimilarityResult<Chunk>> lexical = hits(IntStream.rangeClosed(1, 8)
                .mapToObj(i -> chunk("kimi", i)).toArray(Chunk[]::new));

        List<RetrievedChunk> unlimited = new HitFusion(60, 1.0).fuse(RetrievalMode.HYBRID, vector, lexical, null, 8);
        assertThat(documentsOf(unlimited)).containsOnly("kimi");

        List<RetrievedChunk> spread = new HitFusion(60, PRODUCTION_SHARE).fuse(RetrievalMode.HYBRID, vector, lexical, null, 8);
        assertThat(documentsOf(spread)).containsExactly("kimi", "kimi", "kimi", "kimi", "kimi", "glm", "glm", "glm");
        assertThat(spread).extracting(RetrievedChunk::rank).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }

    @Test
    void aDocumentThatHasTheAnswerOnItsOwnStillFillsTheResult() {
        List<SimilarityResult<Chunk>> vector = hits(chunk("glm", 1), chunk("glm", 2), chunk("glm", 3), chunk("glm", 4));

        List<RetrievedChunk> hits = new HitFusion(60, PRODUCTION_SHARE)
                .fuse(RetrievalMode.HYBRID, vector, List.of(), null, 4);

        assertThat(documentsOf(hits)).containsOnly("glm");
        assertThat(hits).extracting(RetrievedChunk::chunkId).containsExactly("glm:1", "glm:2", "glm:3", "glm:4");
    }

    @Test
    void theQuotaChoosesWhichPassagesAreShownAndNeverTheirOrder() {
        List<SimilarityResult<Chunk>> vector = hits(chunk("kimi", 1), chunk("kimi", 2), chunk("kimi", 3),
                chunk("glm", 1), chunk("glm", 2));

        List<RetrievedChunk> hits = new HitFusion(60, 0.5).fuse(RetrievalMode.VECTOR, vector, List.of(), null, 4);

        // glm:1 is promoted into the result over kimi:3, but it is read where fusion put it, last.
        assertThat(hits).extracting(RetrievedChunk::chunkId).containsExactly("kimi:1", "kimi:2", "glm:1", "glm:2");
    }

    private static List<String> documentsOf(List<RetrievedChunk> hits) {
        return hits.stream().map(hit -> hit.provenance().documentId()).toList();
    }

    private static List<SimilarityResult<Chunk>> hits(Chunk... chunks) {
        List<SimilarityResult<Chunk>> results = new ArrayList<>(chunks.length);
        // Descending scores in the order given: rank is what fusion reads, the score only travels along.
        IntStream.range(0, chunks.length).forEach(i ->
                results.add(new SimpleSimilaritySearchResult<>(chunks[i], 0.9 - i * 0.01)));
        return results;
    }

    private static Chunk chunk(String documentId, int number) {
        String id = documentId + ":" + number;
        String text = "passage " + number + " of " + documentId;
        return Chunk.create(text, documentId, Map.of(
                ProvenanceChunkTransformer.DOCUMENT_ID, documentId,
                ProvenanceChunkTransformer.DOCUMENT_TITLE, documentId,
                ProvenanceChunkTransformer.SECTION_PATH, "Section",
                ProvenanceChunkTransformer.URTEXT, text), id, text, null);
    }
}
