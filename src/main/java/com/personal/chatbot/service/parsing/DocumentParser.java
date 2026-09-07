package com.personal.chatbot.service.parsing;

import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader;
import com.embabel.agent.rag.model.MaterializedDocument;
import com.embabel.agent.rag.model.NavigableDocument;
import com.personal.chatbot.exceptions.DocumentParseException;
import com.personal.chatbot.models.knowledge.Document;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a stored original into Embabel's hierarchical document model via Apache Tika (docs/system-plan.md §5.2).
 * Markdown headings become sections; PDF/DOCX/HTML are extracted to text first. The Embabel reader
 * reports parse failures as a synthetic "Parse Error" document instead of throwing; that is turned
 * back into a {@link DocumentParseException} here.
 */
public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);

    public static final String URI_PREFIX = "kb://documents/";
    private static final String ERROR_METADATA_KEY = "error";

    private final TikaHierarchicalContentReader reader = new TikaHierarchicalContentReader();

    public static String uriOf(String documentId) {
        return URI_PREFIX + documentId;
    }

    public NavigableDocument parse(Document document, Path original) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, document.originalFilename());
        metadata.set(TikaCoreProperties.CONTENT_TYPE_HINT, document.mediaType());
        MaterializedDocument parsed;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(original))) {
            parsed = reader.parseContent(in, uriOf(document.id()), metadata);
        } catch (IOException e) {
            throw new DocumentParseException("Cannot read stored original " + original, e);
        }
        Object error = parsed.getMetadata().get(ERROR_METADATA_KEY);
        if (error != null) {
            throw new DocumentParseException("Parser failed: " + error);
        }
        long textLength = 0;
        for (var leaf : parsed.leaves()) {
            textLength += leaf.getText().strip().length();
        }
        if (textLength == 0) {
            throw new DocumentParseException("No text content found in " + document.originalFilename());
        }
        Map<String, Object> rootMetadata = new LinkedHashMap<>(parsed.getMetadata());
        rootMetadata.put(ProvenanceChunkTransformer.DOCUMENT_ID, document.id());
        rootMetadata.put(ProvenanceChunkTransformer.DOCUMENT_VERSION, Integer.toString(document.version()));
        rootMetadata.put(ProvenanceChunkTransformer.DOCUMENT_TITLE, document.title());
        rootMetadata.put(ProvenanceChunkTransformer.MEDIA_TYPE, document.mediaType());
        MaterializedDocument result = new MaterializedDocument(parsed.getId(), parsed.getUri(), document.title(),
                Instant.now(), parsed.getChildren(), rootMetadata);
        log.debug("Parsed {} ({}): {} chars in {} sections", document.id(), document.originalFilename(), textLength,
                result.getChildren().size());
        return result;
    }
}
