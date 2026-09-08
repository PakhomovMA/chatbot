package com.personal.chatbot.service.index;

import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.ChunkStructure;
import com.embabel.agent.rag.service.SectionSummary;
import com.personal.chatbot.service.parsing.ProvenanceChunkTransformer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The corpus as a table of contents: the sections of every indexed document, and the chunks of one
 * section in document order. Backs the Embabel {@code SectionReader} tools the agentic branch offers
 * the model (docs/system-plan.md Phase 9c follow-up).
 *
 * <p>Nothing is stored: the outline is derived from the provenance metadata every chunk already
 * carries (INV-02), so it cannot drift from what is searchable. A document that is not in the index —
 * still parsing, or failed — has no chunks and so no sections: this lists what can be searched, not
 * the registry.
 *
 * <p>A section is identified by its <b>path</b> ({@code Rollback › Database rollback}), not by its leaf
 * heading, because that is the string the model already sees in the header of every retrieved chunk
 * (written by {@link ProvenanceChunkTransformer}). The two therefore agree, and headings that repeat
 * under different parents stay distinct. {@code readSection} also accepts a bare leaf heading, since
 * the model may well type what it read at the end of a path.
 *
 * <p>One chunk can cover several headings: the chunker packs consecutive short sections together, and
 * the transformer then records them as a list ({@code Restart, Rollback}) rather than as a hierarchy.
 * Such a chunk is listed under each heading it covers and is returned for any of them, so the outline
 * reads like the document's own table of contents instead of exposing where the chunker drew its lines.
 */
public class SectionCatalog {

    /** Sections of a document without headings: a name the model can still pass to readSection. */
    static final String UNTITLED_SECTION = "(untitled section)";

    private final LuceneIndexStore store;

    public SectionCatalog(LuceneIndexStore store) {
        this.store = store;
    }

    /**
     * The outline, documents in index order and sections in the order they appear in their document.
     *
     * @param documentTitlePart case-insensitive part of a document title, null for the whole corpus
     * @param documentIds       documents the request is restricted to, null or empty for no restriction
     */
    public List<SectionSummary> list(@Nullable String documentTitlePart, @Nullable Set<String> documentIds) {
        Map<String, Integer> documentOrder = new LinkedHashMap<>();
        Map<Key, Tally> tallies = new LinkedHashMap<>();
        for (Chunk chunk : matching(documentTitlePart, documentIds)) {
            String document = documentLabel(chunk);
            documentOrder.putIfAbsent(document, documentOrder.size());
            for (String section : sections(chunk)) {
                Tally tally = tallies.computeIfAbsent(new Key(document, section), _ -> new Tally());
                tally.chunks++;
                tally.firstSequence = Math.min(tally.firstSequence, sequence(chunk));
            }
        }
        List<Map.Entry<Key, Tally>> ordered = new ArrayList<>(tallies.entrySet());
        ordered.sort(Comparator.<Map.Entry<Key, Tally>>comparingInt(e -> documentOrder.get(e.getKey().document()))
                .thenComparingInt(e -> e.getValue().firstSequence));
        return ordered.stream()
                .map(e -> new SectionSummary(e.getKey().section(), e.getKey().document(), e.getValue().chunks))
                .toList();
    }

    /**
     * Every chunk of one section, in document order. Matches the section path first and falls back to
     * the leaf heading, so both what {@link #list} printed and what a chunk header ended with resolve.
     *
     * <p>Chunks from more than one document may come back: telling the model to narrow by document is
     * the caller's job — {@code SectionReadingTools} does exactly that before showing anything.
     */
    public List<Chunk> read(String sectionTitle, @Nullable String documentTitlePart, @Nullable Set<String> documentIds) {
        String wanted = sectionTitle.strip();
        List<Chunk> candidates = matching(documentTitlePart, documentIds);
        List<Chunk> found = candidates.stream().filter(chunk -> covers(chunk, wanted)).toList();
        if (found.isEmpty()) {
            found = candidates.stream().filter(chunk -> leafTitle(chunk).equalsIgnoreCase(wanted)).toList();
        }
        return found.stream().sorted(Comparator.comparingInt(SectionCatalog::sequence)).toList();
    }

    private List<Chunk> matching(@Nullable String documentTitlePart, @Nullable Set<String> documentIds) {
        String needle = documentTitlePart == null ? "" : documentTitlePart.strip().toLowerCase(Locale.ROOT);
        boolean scoped = documentIds != null && !documentIds.isEmpty();
        return store.indexedChunks().stream()
                .filter(chunk -> !scoped || documentIds.contains(metadata(chunk, ProvenanceChunkTransformer.DOCUMENT_ID)))
                .filter(chunk -> needle.isEmpty() || documentLabel(chunk).toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    /** The title, or the id when a document has none: whatever is shown has to be what readSection accepts. */
    private static String documentLabel(Chunk chunk) {
        String title = metadata(chunk, ProvenanceChunkTransformer.DOCUMENT_TITLE);
        return title.isBlank() ? metadata(chunk, ProvenanceChunkTransformer.DOCUMENT_ID) : title;
    }

    /**
     * The headings a chunk stands for: one nested path, or every heading of a chunk the chunker packed
     * out of several short sections. The two forms are mutually exclusive by construction — the
     * transformer writes a hierarchy when it knows the leaf, and a list when it had to recover the
     * headings from the text.
     */
    private static List<String> sections(Chunk chunk) {
        String path = metadata(chunk, ProvenanceChunkTransformer.SECTION_PATH);
        if (path.isBlank()) {
            return List.of(UNTITLED_SECTION);
        }
        if (path.contains(ProvenanceChunkTransformer.PATH_SEPARATOR) || !path.contains(ProvenanceChunkTransformer.LIST_SEPARATOR)) {
            return List.of(path);
        }
        return Arrays.stream(path.split(Pattern.quote(ProvenanceChunkTransformer.LIST_SEPARATOR)))
                .map(String::strip).filter(heading -> !heading.isEmpty()).toList();
    }

    private static boolean covers(Chunk chunk, String section) {
        return sections(chunk).stream().anyMatch(heading -> heading.equalsIgnoreCase(section));
    }

    private static String leafTitle(Chunk chunk) {
        String title = metadata(chunk, ProvenanceChunkTransformer.SECTION_TITLE);
        return title.isBlank() ? UNTITLED_SECTION : title;
    }

    private static String metadata(Chunk chunk, String key) {
        Object value = chunk.getMetadata().get(key);
        return value != null ? value.toString() : "";
    }

    /** Position of the chunk in its document; the structure of chunks reloaded from disk may be empty. */
    private static int sequence(Chunk chunk) {
        ChunkStructure structure = chunk.getStructure();
        if (structure.getSequenceNumber() != null) {
            return structure.getSequenceNumber();
        }
        Object stored = chunk.getMetadata().get(ChunkStructure.SEQUENCE_NUMBER);
        if (stored instanceof Number number) {
            return number.intValue();
        }
        try {
            return stored != null ? Integer.parseInt(stored.toString()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record Key(String document, String section) {
    }

    private static final class Tally {
        private int chunks;
        private int firstSequence = Integer.MAX_VALUE;
    }
}
