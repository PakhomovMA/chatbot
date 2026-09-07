package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.exceptions.RegistryCorruptedException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Document metadata store (docs/system-plan.md D6): an in-memory map mirrored to a JSON file.
 * Every mutation is persisted before it returns, with an atomic replace (write temp file, keep the
 * previous file as {@code .bak}, move temp into place). On load a corrupt main file falls back to
 * the backup; if both are unreadable startup fails rather than silently starting empty.
 */
public class DocumentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocumentRegistry.class);

    static final int SCHEMA_VERSION = 1;
    static final String FILE_NAME = "registry.json";
    private static final String BACKUP_SUFFIX = ".bak";
    private static final String TEMP_SUFFIX = ".tmp";

    /** On-disk envelope; {@code schemaVersion} lets later phases migrate the layout. */
    record RegistryFile(int schemaVersion, List<Document> documents) {
    }

    private final Path file;
    private final JsonMapper mapper;
    private final Map<String, Document> documents = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public DocumentRegistry(Path directory) {
        this.file = directory.resolve(FILE_NAME);
        this.mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create registry directory " + directory, e);
        }
        load();
    }

    public Document save(Document document) {
        lock.writeLock().lock();
        try {
            documents.put(document.id(), document);
            persist();
            return document;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean delete(String id) {
        lock.writeLock().lock();
        try {
            boolean removed = documents.remove(id) != null;
            if (removed) {
                persist();
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<Document> findById(String id) {
        return Optional.ofNullable(documents.get(id));
    }

    public Optional<Document> findByContentHash(String contentHash) {
        return documents.values().stream().filter(d -> d.contentHash().equals(contentHash)).findFirst();
    }

    /** All documents, newest upload first. */
    public List<Document> findAll() {
        return documents.values().stream()
                .sorted(Comparator.comparing(Document::uploadedAt).reversed().thenComparing(Document::id))
                .toList();
    }

    public long count() {
        return documents.size();
    }

    public Map<DocumentStatus, Long> countByStatus() {
        Map<DocumentStatus, Long> counts = new EnumMap<>(DocumentStatus.class);
        documents.values().forEach(d -> counts.merge(d.status(), 1L, Long::sum));
        return counts;
    }

    public Path file() {
        return file;
    }

    private void load() {
        Path backup = file.resolveSibling(file.getFileName() + BACKUP_SUFFIX);
        if (Files.exists(file)) {
            try {
                loadFrom(file);
                return;
            } catch (RuntimeException e) {
                log.error("Registry {} is unreadable ({}); trying backup {}", file, e.getMessage(), backup);
                if (!Files.exists(backup)) {
                    throw new RegistryCorruptedException("Registry file " + file + " is corrupt and no backup exists", e);
                }
            }
        }
        if (Files.exists(backup)) {
            try {
                loadFrom(backup);
                log.warn("Loaded registry from backup {} ({} documents)", backup, documents.size());
                persist();
            } catch (RuntimeException e) {
                throw new RegistryCorruptedException("Registry backup " + backup + " is corrupt too", e);
            }
        } else {
            log.info("No registry at {}; starting empty", file);
        }
    }

    private void loadFrom(Path source) {
        try {
            RegistryFile content = mapper.readValue(Files.readString(source), RegistryFile.class);
            if (content.schemaVersion() != SCHEMA_VERSION) {
                throw new IllegalStateException("Unsupported registry schema version " + content.schemaVersion());
            }
            documents.clear();
            content.documents().forEach(d -> documents.put(d.id(), d));
            log.info("Loaded {} documents from {}", documents.size(), source);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + source, e);
        }
    }

    /** Must be called under the write lock. */
    private void persist() {
        Path temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        Path backup = file.resolveSibling(file.getFileName() + BACKUP_SUFFIX);
        try {
            String json = mapper.writeValueAsString(new RegistryFile(SCHEMA_VERSION, findAll()));
            Files.writeString(temp, json);
            if (Files.exists(file)) {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot persist registry " + file + " at " + Instant.now(), e);
        }
    }

    @Override
    public String toString() {
        return "DocumentRegistry[" + file + ", " + documents.size() + " documents, "
                + countByStatus().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")) + "]";
    }
}
