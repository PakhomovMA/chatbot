package com.personal.chatbot.service.index;

import com.embabel.agent.rag.ingestion.ChunkTransformer;
import com.embabel.agent.rag.ingestion.ContentChunker;
import com.embabel.agent.rag.lucene.LuceneSearchOperations;
import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.ContentElement;
import com.embabel.agent.rag.model.ContentRoot;
import com.embabel.agent.rag.model.NavigableDocument;
import com.embabel.agent.rag.service.CoreSearchOperations;
import com.embabel.agent.rag.service.ResultExpander;
import com.embabel.common.ai.model.EmbeddingService;
import com.personal.chatbot.exceptions.IndexUnavailableException;
import com.personal.chatbot.exceptions.IndexWriteException;
import com.personal.chatbot.models.embedding.EmbeddingFingerprint;
import com.personal.chatbot.models.index.IndexInfo;
import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.models.index.IndexState;
import com.personal.chatbot.utils.EmbeddingAudit;
import org.apache.lucene.util.Version;
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
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Owns the Embabel Lucene store (docs/system-plan.md D4): opens it from {@code <index-dir>/lucene}
 * or in memory, keeps the {@code manifest.json} that pins the embedding fingerprint and chunker
 * (INV-05), serialises writers behind a read/write lock (INV-11), verifies that every chunk got a
 * vector (INV-09), recovers from a corrupt directory and rebuilds on demand.
 */
public class LuceneIndexStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexStore.class);

    static final String LUCENE_DIR = "lucene";
    static final String MANIFEST_FILE = "manifest.json";
    static final String STORE_NAME = "knowledge";

    private final @Nullable Path indexDir;
    private final EmbeddingService embeddingService;
    private final EmbeddingFingerprint fingerprint;
    private final IndexManifest.Chunker chunkerSpec;
    private final int embeddingBatchSize;
    private final ChunkTransformer chunkTransformer;
    private final JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Object searchMonitor = new Object();

    private @Nullable LuceneSearchOperations operations;
    private @Nullable IndexManifest manifest;
    private volatile IndexState state = IndexState.EMPTY;
    private volatile @Nullable String incompatibilityReason;
    private volatile @Nullable String recoveredFrom;

    /**
     * @param indexDir root for {@code lucene/} and {@code manifest.json}; null for an in-memory index (tests)
     */
    public LuceneIndexStore(@Nullable Path indexDir, EmbeddingService embeddingService, EmbeddingFingerprint fingerprint,
                            IndexManifest.Chunker chunkerSpec, int embeddingBatchSize, ChunkTransformer chunkTransformer) {
        this.indexDir = indexDir;
        this.embeddingService = embeddingService;
        this.fingerprint = fingerprint;
        this.chunkerSpec = chunkerSpec;
        this.embeddingBatchSize = embeddingBatchSize;
        this.chunkTransformer = chunkTransformer;
    }

    /** Opens (or creates) the index and loads existing content. Must be called exactly once before use. */
    public LuceneIndexStore open() {
        lock.writeLock().lock();
        try {
            if (indexDir == null) {
                manifest = newManifest();
                operations = build(null);
                refreshState();
                log.info("In-memory Lucene index opened ({})", fingerprint.value());
                return this;
            }
            Path lucenePath = indexDir.resolve(LUCENE_DIR);
            Files.createDirectories(lucenePath);
            IndexManifest existing = readManifest();
            boolean hasIndexFiles = hasFiles(lucenePath);
            if (existing == null && hasIndexFiles) {
                markIncompatible("index has no manifest; its embedding model is unknown");
                return this;
            }
            if (existing != null && !existing.isCompatibleWith(fingerprint, chunkerSpec)) {
                markIncompatible("index was built with " + existing.embedding().fingerprint() + " / " + existing.chunker()
                        + " but the application runs " + fingerprint.value() + " / " + chunkerSpec);
                return this;
            }
            manifest = existing != null ? existing : newManifest();
            operations = openOrRecover(lucenePath);
            if (existing == null) {
                writeManifest(manifest);
            }
            refreshState();
            log.info("Lucene index opened at {}: {} chunks, {} documents, state {}", lucenePath,
                    operations.info().getChunkCount(), operations.info().getDocumentCount(), state);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open index at " + indexDir, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private LuceneSearchOperations openOrRecover(Path lucenePath) throws IOException {
        try {
            return build(lucenePath);
        } catch (Exception first) { // Lucene surfaces checked IOExceptions through the Kotlin constructor
            if (!hasFiles(lucenePath)) {
                throw first instanceof RuntimeException runtime ? runtime : new IllegalStateException(first);
            }
            Path quarantine = lucenePath.resolveSibling(LUCENE_DIR + ".corrupt-"
                    + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now()));
            log.error("Lucene index at {} cannot be opened ({}); moving it to {} and starting empty", lucenePath,
                    first.toString(), quarantine);
            Files.move(lucenePath, quarantine, StandardCopyOption.ATOMIC_MOVE);
            Files.createDirectories(lucenePath);
            recoveredFrom = quarantine.toString();
            return build(lucenePath);
        }
    }

    private LuceneSearchOperations build(@Nullable Path lucenePath) {
        var builder = LuceneSearchOperations.builder()
                .withName(STORE_NAME)
                .withEmbeddingService(embeddingService)
                .withChunkerConfig(new ContentChunker.Config(chunkerSpec.maxChunkSize(), chunkerSpec.overlapSize(), embeddingBatchSize))
                .withChunkTransformer(chunkTransformer);
        if (lucenePath == null) {
            return builder.build();
        }
        builder = builder.withIndexPath(lucenePath);
        try {
            // buildAndLoadChunks() logs an error for a directory without segments; only load when there is something to load.
            return hasFiles(lucenePath) ? builder.buildAndLoadChunks() : builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot inspect " + lucenePath, e);
        }
    }

    /**
     * Indexes a document, replacing any previous content stored under the same URI. Either every
     * chunk is written with its vector, or nothing of the document remains in the index.
     */
    public List<String> writeDocument(NavigableDocument document) {
        lock.writeLock().lock();
        try {
            LuceneSearchOperations ops = requireWritable();
            if (ops.findContentRootByUri(document.getUri()) != null) {
                ops.deleteRootAndDescendants(document.getUri());
            }
            EmbeddingAudit.Result<List<String>> result;
            try {
                result = EmbeddingAudit.record(() -> ops.writeAndChunkDocument(document));
            } catch (RuntimeException e) {
                purgeQuietly(ops, document.getUri());
                throw new IndexWriteException("indexing", "Indexing failed: " + e.getMessage(), e);
            }
            List<String> chunkIds = result.value();
            EmbeddingAudit audit = result.audit();
            if (!audit.failures().isEmpty() || audit.vectors() < chunkIds.size()) {
                purgeQuietly(ops, document.getUri());
                Throwable cause = audit.failures().isEmpty() ? null : audit.failures().getFirst();
                throw new IndexWriteException("embedding", "Embedded " + audit.vectors() + " of " + chunkIds.size()
                        + " chunks" + (cause != null ? ": " + cause.getMessage() : ""), cause);
            }
            refreshState();
            return chunkIds;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** @return true if something was removed. */
    public boolean deleteDocument(String uri) {
        lock.writeLock().lock();
        try {
            if (operations == null) {
                return false;
            }
            boolean removed = operations.deleteRootAndDescendants(uri) != null;
            refreshState();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean containsDocument(String uri) {
        lock.readLock().lock();
        try {
            return operations != null && operations.findContentRootByUri(uri) != null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** URIs of every document root currently in the index. */
    public Set<String> documentUris() {
        lock.readLock().lock();
        try {
            if (operations == null) {
                return Set.of();
            }
            return operations.findAll(ContentRoot.class).stream().map(ContentRoot::getUri).collect(Collectors.toSet());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Chunks in index order (by section and sequence); intended for tests and diagnostics. */
    public List<Chunk> allChunks() {
        lock.readLock().lock();
        try {
            return operations == null ? List.of() : operations.findAll();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Runs a read against the store. Searches are serialised because the Embabel store reopens its
     * reader on every query; writers are excluded by the read lock.
     */
    public <T> T search(Function<CoreSearchOperations, T> query) {
        lock.readLock().lock();
        try {
            LuceneSearchOperations ops = operations;
            if (ops == null || !state.isWritable()) {
                throw new IndexUnavailableException(state, "Index is " + state
                        + (incompatibilityReason != null ? ": " + incompatibilityReason : ""));
            }
            synchronized (searchMonitor) {
                return query.apply(ops);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Expands a chunk to its neighbours or parent section (used by the agentic tools). */
    public List<ContentElement> expand(String id, ResultExpander.Method method, int elementsToAdd) {
        lock.readLock().lock();
        try {
            LuceneSearchOperations ops = operations;
            if (ops == null || !state.isWritable()) {
                throw new IndexUnavailableException(state, "Index is " + state);
            }
            synchronized (searchMonitor) {
                return ops.expandResult(id, method, elementsToAdd);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Search capabilities for Embabel tools, guarded by this store's lock and state (Phase 9c). */
    public LockedSearchOperations searchOperations() {
        return new LockedSearchOperations(this);
    }

    /** Drops all content and starts a fresh index under the current fingerprint. */
    public void rebuild() {
        lock.writeLock().lock();
        try {
            state = IndexState.REBUILDING;
            closeOperations();
            if (indexDir != null) {
                Path lucenePath = indexDir.resolve(LUCENE_DIR);
                deleteTree(lucenePath);
                Files.createDirectories(lucenePath);
                manifest = newManifest();
                writeManifest(manifest);
                operations = build(lucenePath);
            } else {
                manifest = newManifest();
                operations = build(null);
            }
            incompatibilityReason = null;
            refreshState();
            log.info("Index rebuilt under {}", fingerprint.value());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot rebuild index at " + indexDir, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public IndexState state() {
        return state;
    }

    public IndexInfo info() {
        lock.readLock().lock();
        try {
            int chunks = 0;
            int documents = 0;
            if (operations != null) {
                var stats = operations.info();
                chunks = stats.getChunkCount();
                documents = stats.getDocumentCount();
            }
            return new IndexInfo(state, chunks, documents,
                    indexDir != null ? indexDir.resolve(LUCENE_DIR).toString() : null, indexDir != null,
                    manifest, incompatibilityReason, recoveredFrom);
        } finally {
            lock.readLock().unlock();
        }
    }

    public EmbeddingFingerprint fingerprint() {
        return fingerprint;
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            closeOperations();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private LuceneSearchOperations requireWritable() {
        if (operations == null || !state.isWritable()) {
            throw new IndexUnavailableException(state, "Index is " + state
                    + (incompatibilityReason != null ? ": " + incompatibilityReason : ""));
        }
        return operations;
    }

    private void purgeQuietly(LuceneSearchOperations ops, String uri) {
        try {
            ops.deleteRootAndDescendants(uri);
        } catch (RuntimeException e) {
            log.warn("Could not purge partially indexed document {}: {}", uri, e.toString());
        }
        refreshState();
    }

    private void markIncompatible(String reason) {
        incompatibilityReason = reason;
        state = IndexState.INCOMPATIBLE;
        manifest = readManifest();
        log.error("Lucene index at {} is INCOMPATIBLE: {}. Rebuild it via POST /api/knowledge-base/reindex", indexDir, reason);
    }

    private void refreshState() {
        if (operations == null) {
            return;
        }
        state = operations.info().getChunkCount() == 0 ? IndexState.EMPTY : IndexState.READY;
    }

    private void closeOperations() {
        if (operations != null) {
            try {
                operations.close();
            } catch (RuntimeException e) {
                log.warn("Error closing Lucene store: {}", e.toString());
            }
            operations = null;
        }
    }

    private IndexManifest newManifest() {
        return IndexManifest.create(fingerprint, chunkerSpec, Version.LATEST.toString(), Instant.now());
    }

    private @Nullable IndexManifest readManifest() {
        if (indexDir == null) {
            return null;
        }
        Path file = indexDir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(file), IndexManifest.class);
        } catch (IOException | RuntimeException e) {
            log.error("Manifest {} is unreadable: {}", file, e.toString());
            return null;
        }
    }

    private void writeManifest(IndexManifest content) {
        if (indexDir == null) {
            return;
        }
        Path file = indexDir.resolve(MANIFEST_FILE);
        Path temp = file.resolveSibling(MANIFEST_FILE + ".tmp");
        try {
            Files.createDirectories(indexDir);
            Files.writeString(temp, mapper.writeValueAsString(content));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write manifest " + file, e);
        }
    }

    private static boolean hasFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.findAny().isPresent();
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
