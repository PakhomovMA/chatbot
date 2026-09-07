package com.personal.chatbot.service.retrieval;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.ChunkStructure;
import com.personal.chatbot.models.retrieval.Provenance;
import com.personal.chatbot.models.retrieval.RetrievedChunk;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Turns an Embabel {@link Chunk} back into the domain shape the API speaks: the original passage
 * text and the provenance written at ingestion time (INV-02). Every path that shows a chunk to a
 * caller or to the model goes through here, so deterministic and agentic retrieval describe the
 * same chunk identically.
 */
public final class ChunkMapper {

    private static final String NO_TITLE = "";

    private ChunkMapper() {
    }

    /**
     * Search hits are rebuilt from Lucene documents, where Embabel sets {@code urtext = text} (the indexed
     * text with the provenance header). The original fragment is kept in metadata by the transformer.
     */
    public static String originalText(Chunk chunk) {
        Object urtext = chunk.getMetadata().get(ProvenanceChunkTransformer.URTEXT);
        return urtext != null ? urtext.toString() : chunk.getUrtext();
    }

    /** Builds provenance from the metadata written by {@link ProvenanceChunkTransformer} and the chunker. */
    public static Provenance provenanceOf(Chunk chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        ChunkStructure structure = chunk.getStructure();
        String path = string(metadata.get(ProvenanceChunkTransformer.SECTION_PATH), NO_TITLE);
        String separator = path.contains(ProvenanceChunkTransformer.PATH_SEPARATOR)
                ? ProvenanceChunkTransformer.PATH_SEPARATOR : ProvenanceChunkTransformer.LIST_SEPARATOR;
        List<String> sectionPath = path.isEmpty() ? List.of()
                : Arrays.stream(path.split(Pattern.quote(separator))).map(String::strip).toList();
        return new Provenance(
                string(metadata.get(ProvenanceChunkTransformer.DOCUMENT_ID), string(structure.getRootDocumentId(), "")),
                string(metadata.get(ProvenanceChunkTransformer.DOCUMENT_TITLE), string(structure.getRootDocumentTitle(), NO_TITLE)),
                parseInt(metadata.get(ProvenanceChunkTransformer.DOCUMENT_VERSION), 1),
                string(metadata.get(ProvenanceChunkTransformer.SECTION_TITLE), NO_TITLE),
                sectionPath,
                chunk.getId(),
                Objects.requireNonNullElse(structure.getSequenceNumber(), parseInt(metadata.get(ChunkStructure.SEQUENCE_NUMBER), 0)),
                structure.getChunkIndex() != null ? structure.getChunkIndex() : parseInteger(metadata.get(ChunkStructure.CHUNK_INDEX)),
                structure.getTotalChunks() != null ? structure.getTotalChunks() : parseInteger(metadata.get(ChunkStructure.TOTAL_CHUNKS)),
                metadata.get(ProvenanceChunkTransformer.MEDIA_TYPE) != null ? metadata.get(ProvenanceChunkTransformer.MEDIA_TYPE).toString() : null);
    }

    /**
     * @param cosine     vector similarity, null when the chunk was not found by the vector facet
     * @param bm25       normalised lexical score, null when the chunk was not found by the text facet
     * @param fusedScore the score the ranking was done on
     * @param rank       1-based position in the result
     */
    public static RetrievedChunk toRetrievedChunk(Chunk chunk, @Nullable Double cosine, @Nullable Double bm25,
                                                  double fusedScore, int rank) {
        return new RetrievedChunk(chunk.getId(), originalText(chunk), provenanceOf(chunk), cosine, bm25, fusedScore, rank);
    }

    private static String string(@Nullable Object value, String fallback) {
        return value != null ? value.toString() : fallback;
    }

    private static int parseInt(@Nullable Object value, int fallback) {
        Integer parsed = parseInteger(value);
        return parsed != null ? parsed : fallback;
    }

    private static @Nullable Integer parseInteger(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
