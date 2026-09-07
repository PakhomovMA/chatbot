package com.personal.chatbot.service.parsing;

import com.embabel.agent.rag.ingestion.ChunkTransformationContext;
import com.embabel.agent.rag.ingestion.ChunkTransformer;
import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.ChunkStructure;
import com.embabel.agent.rag.model.ContentRoot;
import com.embabel.agent.rag.model.HierarchicalContentElement;
import com.embabel.agent.rag.model.LeafSection;
import com.embabel.agent.rag.model.NavigableContainerSection;
import com.embabel.agent.rag.model.NavigableSection;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gives every chunk a deterministic id and full provenance (docs/system-plan.md D5, D8, INV-02):
 * <ul>
 *   <li>id {@code <documentId>:<version>:<sequence>} — stable across re-indexing of the same version;</li>
 *   <li>indexed text prefixed with {@code Document: <title> › <section path>} so BM25 and the
 *       embedding see the referent; the original fragment is kept as {@code urtext} and also in
 *       metadata, because the Lucene store does not persist {@code urtext} across restarts;</li>
 *   <li>metadata keys {@link #DOCUMENT_ID}, {@link #DOCUMENT_VERSION}, {@link #DOCUMENT_TITLE},
 *       {@link #SECTION_TITLE}, {@link #SECTION_PATH}, {@link #URTEXT}, {@link #MEDIA_TYPE}.</li>
 * </ul>
 * The document-level values are read from the root document's metadata, which {@link DocumentParser}
 * populates.
 */
public final class ProvenanceChunkTransformer implements ChunkTransformer {

    public static final String TRANSFORMER_VERSION = "provenance-v1";

    public static final String DOCUMENT_ID = "document_id";
    public static final String DOCUMENT_VERSION = "document_version";
    public static final String DOCUMENT_TITLE = "document_title";
    public static final String SECTION_TITLE = "section_title";
    public static final String SECTION_PATH = "section_path";
    public static final String URTEXT = "urtext";
    public static final String MEDIA_TYPE = "media_type";
    /** Embabel's metadata key backing {@code Chunk.uri}. */
    public static final String URL = "url";

    public static final String PATH_SEPARATOR = " › ";
    public static final String LIST_SEPARATOR = ", ";
    private static final int MAX_LISTED_SECTIONS = 5;
    private static final int PROBE_LENGTH = 80;

    @Override
    public @NonNull String getName() {
        return TRANSFORMER_VERSION;
    }

    @Override
    public @NonNull Chunk transform(Chunk chunk, ChunkTransformationContext context) {
        ContentRoot document = context.getDocument();
        Map<String, Object> rootMetadata = document != null ? document.getMetadata() : Map.of();
        String documentId = stringOr(rootMetadata.get(DOCUMENT_ID), document != null ? document.getId() : chunk.getParentId());
        String version = stringOr(rootMetadata.get(DOCUMENT_VERSION), "1");
        String title = document != null ? document.getTitle() : stringOr(chunk.getMetadata().get(ChunkStructure.ROOT_DOCUMENT_TITLE), "");
        ChunkStructure structure = chunk.getStructure();
        int sequence = Objects.requireNonNullElse(structure.getSequenceNumber(), 0);

        String sectionPath = sectionPath(document, structure, chunk.getText());
        String sectionTitle = leafTitle(sectionPath);
        String header = sectionPath.isEmpty() ? "Document: " + title : "Document: " + title + PATH_SEPARATOR + sectionPath;

        Map<String, Object> metadata = new LinkedHashMap<>(chunk.getMetadata());
        metadata.put(DOCUMENT_ID, documentId);
        metadata.put(DOCUMENT_VERSION, version);
        metadata.put(DOCUMENT_TITLE, title);
        metadata.put(SECTION_TITLE, sectionTitle);
        metadata.put(SECTION_PATH, sectionPath);
        metadata.put(URTEXT, chunk.getUrtext());
        if (document != null) {
            // Chunk.uri reads this key. The Lucene store deletes descendants by parent chain OR by uri, and the
            // parent chain of chunks reloaded from disk is broken (parentId == id), so the uri is what makes
            // deletion and re-indexing work after a restart.
            metadata.put(URL, document.getUri());
        }
        Object mediaType = rootMetadata.get(MEDIA_TYPE);
        if (mediaType != null) {
            metadata.put(MEDIA_TYPE, mediaType);
        }
        return Chunk.create(header + "\n\n" + chunk.getText(), chunk.getParentId(), metadata,
                chunkId(documentId, version, sequence), chunk.getUrtext(), structure);
    }

    public static String chunkId(String documentId, String version, int sequence) {
        return documentId + ":" + version + ":" + sequence;
    }

    /** Last element of a hierarchical path, or the first of a listed one. */
    private static String leafTitle(String sectionPath) {
        if (sectionPath.isEmpty()) {
            return "";
        }
        if (sectionPath.contains(PATH_SEPARATOR)) {
            return sectionPath.substring(sectionPath.lastIndexOf(PATH_SEPARATOR) + PATH_SEPARATOR.length());
        }
        int comma = sectionPath.indexOf(LIST_SEPARATOR);
        return comma < 0 ? sectionPath : sectionPath.substring(0, comma);
    }

    /**
     * Heading path for the chunk. A chunk split out of one leaf carries {@code leaf_section_id} and gets
     * the full heading hierarchy ({@code A › B}); a chunk the chunker assembled from several small leaves
     * carries no leaf id, so the leaves it covers are recovered by matching their text and listed ({@code A, B}).
     */
    private static String sectionPath(@Nullable ContentRoot document, ChunkStructure structure, String chunkText) {
        if (!(document instanceof NavigableContainerSection root)) {
            return "";
        }
        String leafId = structure.getLeafSectionId();
        if (leafId == null) {
            return withoutDocumentTitle(coveredLeafTitles(root, chunkText), document.getTitle(), LIST_SEPARATOR);
        }
        Map<String, NavigableSection> byId = new HashMap<>();
        for (NavigableSection section : root.descendants()) {
            byId.put(section.getId(), section);
        }
        Deque<String> titles = new ArrayDeque<>();
        NavigableSection current = byId.get(leafId);
        while (current != null) {
            String sectionTitle = current.getTitle();
            if (!sectionTitle.isBlank() && (titles.isEmpty() || !titles.peekFirst().equals(sectionTitle))) {
                titles.addFirst(sectionTitle);
            }
            HierarchicalContentElement element = current;
            String parentId = element.getParentId();
            current = parentId != null ? byId.get(parentId) : null;
        }
        return withoutDocumentTitle(String.join(PATH_SEPARATOR, titles), document.getTitle(), PATH_SEPARATOR);
    }

    /** A top-level heading that merely repeats the document title carries no extra provenance. */
    private static String withoutDocumentTitle(String path, String documentTitle, String separator) {
        if (path.equals(documentTitle)) {
            return "";
        }
        return path.startsWith(documentTitle + separator) ? path.substring(documentTitle.length() + separator.length()) : path;
    }

    private static String coveredLeafTitles(NavigableContainerSection root, String chunkText) {
        List<String> titles = new ArrayList<>();
        for (LeafSection leaf : root.leaves()) {
            String title = leaf.getTitle();
            if (title.isBlank() || titles.contains(title)) {
                continue;
            }
            String body = leaf.getText().strip();
            String probe = body.length() > PROBE_LENGTH ? body.substring(0, PROBE_LENGTH) : body;
            if (!probe.isEmpty() && chunkText.contains(probe)) {
                titles.add(title);
            }
        }
        if (titles.size() > MAX_LISTED_SECTIONS) {
            return String.join(LIST_SEPARATOR, titles.subList(0, MAX_LISTED_SECTIONS)) + LIST_SEPARATOR + "…";
        }
        return String.join(LIST_SEPARATOR, titles);
    }

    private static String stringOr(@Nullable Object value, String fallback) {
        return value != null ? value.toString() : fallback;
    }
}
