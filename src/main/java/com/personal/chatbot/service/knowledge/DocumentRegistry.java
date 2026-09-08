package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.exceptions.RegistryCorruptedException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import org.jspecify.annotations.Nullable;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Document metadata store (docs/system-plan.md D6): an in-memory map mirrored to a JSON file.
 * Every mutation is persisted before it returns, with an atomic replace (write temp file, keep the
 * previous file as {@code .bak}, move temp into place). On load a corrupt main file falls back to
 * the backup; if both are unreadable startup fails rather than silently starting empty.
 *
 * <p>Every change goes through one lock, and so do the compound operations built on top of it:
 * {@link #update} for conditional single-entry changes and {@link #exclusively} for a caller that has
 * to read, decide and write as one step. It is a plain reentrant lock, not a read/write one: reads
 * are served by the concurrent map without any lock at all, so a read side would only add cost
 * (docs/concurrency-plan.md C09). Reentrancy is used — {@link #update} holds the lock while
 * {@link #save} takes it again. Lock order in the ingestion path is <em>queue monitor → registry
 * lock</em>; nothing may wait for the ingestion worker or the index while holding this lock.
 */
public class DocumentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocumentRegistry.class);

    static final int SCHEMA_VERSION = 1;
    static final String FILE_NAME = "registry.json";
    private static final String BACKUP_SUFFIX = ".bak";
    private static final String TEMP_SUFFIX = ".tmp";

    /** Version expectation that matches whatever the registry holds; real versions start at 1. */
    private static final int ANY_VERSION = 0;

    /**
     * Outcome of a conditional change. The three cases must be told apart by callers: a job that
     * lost its document may neither resurrect a deleted entry nor push a newer version back.
     */
    public sealed interface Change {

        /** The change was applied and persisted. */
        record Applied(Document document) implements Change {
        }

        /** The document no longer exists; nothing was written. */
        record Missing(String documentId) implements Change {
        }

        /** Another writer moved the document on; {@code current} is what the registry holds now. */
        record Stale(Document current) implements Change {
        }

        /** The stored document if the change went through, {@code null} for {@link Missing} and {@link Stale}. */
        default @Nullable Document applied() {
            return this instanceof Applied result ? result.document() : null;
        }
    }

    /** On-disk envelope; {@code schemaVersion} lets later phases migrate the layout. */
    record RegistryFile(int schemaVersion, List<Document> documents) {
    }

    private final Path file;
    private final JsonMapper mapper;
    private final Map<String, Document> documents = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

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

    /** Stores {@code document} unconditionally; the in-memory map only changes if the file was written. */
    public Document save(Document document) {
        lock.lock();
        try {
            Document previous = documents.put(document.id(), document);
            try {
                persist();
            } catch (RuntimeException e) {
                restore(document.id(), previous);
                throw e;
            }
            return document;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies {@code change} to the current entry if the document still exists. Reading the entry,
     * computing the new one and writing it happen under one lock.
     */
    public Change update(String id, UnaryOperator<Document> change) {
        return update(id, ANY_VERSION, change);
    }

    /** {@link #update(String, UnaryOperator)}, but only while the document is at {@code expectedVersion}. */
    public Change update(String id, int expectedVersion, UnaryOperator<Document> change) {
        lock.lock();
        try {
            Document current = documents.get(id);
            if (current == null) {
                return new Change.Missing(id);
            }
            if (expectedVersion != ANY_VERSION && current.version() != expectedVersion) {
                return new Change.Stale(current);
            }
            return new Change.Applied(save(change.apply(current)));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code work} with exclusive access to the registry, so a caller can look up a content
     * hash, allocate a version, place a blob and store the entry without another writer slipping in
     * between. Keep the callback short and free of index or queue calls (see the class comment).
     */
    public <T> T exclusively(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    public boolean delete(String id) {
        lock.lock();
        try {
            Document previous = documents.remove(id);
            if (previous == null) {
                return false;
            }
            try {
                persist();
            } catch (RuntimeException e) {
                restore(id, previous);
                throw e;
            }
            return true;
        } finally {
            lock.unlock();
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

    /** Must be called under the lock. */
    private void restore(String id, @Nullable Document previous) {
        if (previous == null) {
            documents.remove(id);
        } else {
            documents.put(id, previous);
        }
    }

    /** Must be called under the lock. */
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
