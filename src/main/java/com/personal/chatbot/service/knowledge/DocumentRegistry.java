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
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
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
 * <p>The file is written first and the map published afterwards, so a reader never sees a change
 * that the disk does not hold: a write that fails takes nothing back, because nothing was handed out
 * (docs/concurrency-plan.md C10).
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

    /** Stores {@code document} unconditionally; readers see it only once the file holds it. */
    public Document save(Document document) {
        lock.lock();
        try {
            persist(stateWith(document));
            documents.put(document.id(), document);
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
            if (!documents.containsKey(id)) {
                return false;
            }
            persist(stateWithout(id));
            documents.remove(id);
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
        return newestFirst(documents.values());
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
                persist(findAll());
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

    /** The stored documents with {@code document} added or replacing its predecessor. Under the lock. */
    private List<Document> stateWith(Document document) {
        Map<String, Document> next = new HashMap<>(documents);
        next.put(document.id(), document);
        return newestFirst(next.values());
    }

    /** The stored documents without {@code id}. Under the lock. */
    private List<Document> stateWithout(String id) {
        Map<String, Document> next = new HashMap<>(documents);
        next.remove(id);
        return newestFirst(next.values());
    }

    private static List<Document> newestFirst(Collection<Document> documents) {
        return documents.stream()
                .sorted(Comparator.comparing(Document::uploadedAt).reversed().thenComparing(Document::id))
                .toList();
    }

    /**
     * Writes {@code state} to disk. Must be called under the lock, and before the in-memory map is
     * changed to match: readers work without the lock, so anything they can see has to be on disk
     * already — a blob cleanup that acted on a version a failed write then took back would delete
     * the original of the version that is still current (docs/concurrency-plan.md C10).
     *
     * <p>Package-private, not private, so a test can watch what readers see mid-write.
     */
    void persist(List<Document> state) {
        Path temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        Path backup = file.resolveSibling(file.getFileName() + BACKUP_SUFFIX);
        try {
            String json = mapper.writeValueAsString(new RegistryFile(SCHEMA_VERSION, state));
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
