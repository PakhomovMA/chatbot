package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.DocumentNotFoundException;
import com.personal.chatbot.exceptions.InvalidUploadException;
import com.personal.chatbot.exceptions.InvalidUploadException.Reason;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentEvent;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.knowledge.StagedBlob;
import com.personal.chatbot.models.knowledge.dto.DocumentPage;
import com.personal.chatbot.models.knowledge.dto.UploadResponse;
import com.personal.chatbot.utils.Filenames;
import com.personal.chatbot.utils.MediaTypes;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Knowledge-base document management (docs/system-plan.md D5): validation, de-duplication by
 * content hash (INV-04), versioned replacement and deletion. Indexing is driven by
 * {@link IngestionService}, which reacts to the {@link DocumentEvent}s published here.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** What the controller hands over; the stream is consumed exactly once. */
    public record Upload(String filename, @Nullable String declaredMediaType, long declaredSize, InputStream content,
                         @Nullable String title) {
    }

    public record Query(@Nullable DocumentStatus status, @Nullable String text, int page, int size) {
        public Query {
            page = Math.max(page, 0);
            size = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        }
    }

    private final DocumentRegistry registry;
    private final BlobStore blobStore;
    private final UploadValidator validator;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public DocumentService(DocumentRegistry registry, BlobStore blobStore, ChatbotProperties.Knowledge settings, Clock clock,
                           ApplicationEventPublisher events) {
        this.registry = registry;
        this.blobStore = blobStore;
        this.validator = new UploadValidator(settings);
        this.clock = clock;
        this.events = events;
    }

    public UploadResponse upload(Upload upload) {
        String filename = validator.validateName(upload.filename(), upload.declaredSize());
        StagedBlob staged = stage(upload);
        Registered registered;
        try {
            registered = registry.exclusively(() -> {
                Document existing = registry.findByContentHash(staged.contentHash()).orElse(null);
                if (existing != null) {
                    log.info("Upload of {} duplicates document {} (hash {})", filename, existing.id(), staged.contentHash());
                    return new Registered(existing, true);
                }
                String title = upload.title() != null && !upload.title().isBlank() ? upload.title().strip() : Filenames.baseName(filename);
                Document document = Document.uploaded(title, filename, MediaTypes.resolve(upload.declaredMediaType(), filename),
                        staged.sizeBytes(), staged.contentHash(), now());
                store(staged, document, filename);
                return new Registered(document, false);
            });
        } finally {
            blobStore.discard(staged);
        }
        Document document = registered.document();
        if (!registered.duplicate()) {
            log.info("Registered document {} '{}' ({} bytes, {})", document.id(), document.title(), staged.sizeBytes(), filename);
            events.publishEvent(new DocumentEvent.Uploaded(document.id(), document.version()));
        }
        return new UploadResponse(document.id(), document.status(), document.version(), registered.duplicate());
    }

    /**
     * Replaces the content of an existing document (new version) unless the bytes are unchanged.
     * The previous version's blob is left on disk: an ingestion run may still be reading it, and the
     * ingestion pipeline drops superseded versions once it is done with them.
     */
    public UploadResponse replaceContent(String documentId, Upload upload) {
        String filename = validator.validateName(upload.filename(), upload.declaredSize());
        StagedBlob staged = stage(upload);
        Registered registered;
        try {
            registered = registry.exclusively(() -> {
                Document current = registry.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
                if (current.contentHash().equals(staged.contentHash())) {
                    return new Registered(current, true);
                }
                registry.findByContentHash(staged.contentHash()).ifPresent(other -> {
                    if (!other.id().equals(documentId)) {
                        throw new InvalidUploadException(Reason.BAD_FILENAME,
                                "Identical content is already registered as document " + other.id());
                    }
                });
                Document replaced = current.replacedContent(filename, MediaTypes.resolve(upload.declaredMediaType(), filename),
                        staged.sizeBytes(), staged.contentHash(), now());
                store(staged, replaced, filename);
                log.info("Replaced content of document {}: version {} -> {}", documentId, current.version(), replaced.version());
                return new Registered(replaced, false);
            });
        } finally {
            blobStore.discard(staged);
        }
        Document document = registered.document();
        if (!registered.duplicate()) {
            events.publishEvent(new DocumentEvent.ContentReplaced(document.id(), document.version()));
        }
        return new UploadResponse(document.id(), document.status(), document.version(), registered.duplicate());
    }

    public Document get(String documentId) {
        return registry.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    public DocumentPage list(Query query) {
        String needle = query.text() == null ? null : query.text().strip().toLowerCase(Locale.ROOT);
        List<Document> matching = registry.findAll().stream()
                .filter(d -> query.status() == null || d.status() == query.status())
                .filter(d -> needle == null || needle.isEmpty()
                        || d.title().toLowerCase(Locale.ROOT).contains(needle)
                        || d.originalFilename().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        List<Document> items = matching.stream().skip((long) query.page() * query.size()).limit(query.size()).toList();
        return new DocumentPage(items, matching.size(), query.page(), query.size());
    }

    public void delete(String documentId) {
        Document document = registry.exclusively(() -> {
            Document current = registry.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
            registry.delete(current.id());
            return current;
        });
        events.publishEvent(new DocumentEvent.Deleted(document.id()));
        blobStore.delete(document.id());
        log.info("Deleted document {} '{}'", document.id(), document.title());
    }

    /** What {@link #upload} and {@link #replaceContent} agreed on while holding the registry lock. */
    private record Registered(Document document, boolean duplicate) {
    }

    /**
     * Puts the staged bytes in their final place and registers the entry. If the registry cannot be
     * written the new blob goes away again, so an unregistered version never survives.
     */
    private void store(StagedBlob staged, Document document, String filename) {
        blobStore.commit(staged, document.id(), document.version(), Filenames.extension(filename));
        try {
            registry.save(document);
        } catch (RuntimeException e) {
            blobStore.deleteVersion(document.id(), document.version());
            throw e;
        }
    }

    private StagedBlob stage(Upload upload) {
        StagedBlob staged = blobStore.stage(upload.content());
        try {
            validator.validateStaged(staged);
        } catch (RuntimeException e) {
            blobStore.discard(staged);
            throw e;
        }
        return staged;
    }


    private Instant now() {
        return Instant.now(clock);
    }
}
