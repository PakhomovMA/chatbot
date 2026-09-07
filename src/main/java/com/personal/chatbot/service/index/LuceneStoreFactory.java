package com.personal.chatbot.service.index;

import com.embabel.agent.rag.ingestion.ChunkTransformer;
import com.embabel.agent.rag.ingestion.ContentChunker;
import com.embabel.agent.rag.lucene.LuceneSearchOperations;
import com.embabel.common.ai.model.EmbeddingService;
import com.personal.chatbot.models.index.IndexManifest;
import com.personal.chatbot.utils.Directories;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Creates the Embabel Lucene store the {@link LuceneIndexStore} owns, always with the same
 * embedding service, chunker configuration and transformer, and quarantines a directory Lucene
 * refuses to open instead of failing startup.
 */
public class LuceneStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(LuceneStoreFactory.class);

    private static final DateTimeFormatter QUARANTINE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** @param recoveredFrom where a corrupt directory was moved, if that happened */
    public record Opened(LuceneSearchOperations operations, @Nullable String recoveredFrom) {
    }

    private final EmbeddingService embeddingService;
    private final IndexManifest.Chunker chunkerSpec;
    private final int embeddingBatchSize;
    private final ChunkTransformer chunkTransformer;

    public LuceneStoreFactory(EmbeddingService embeddingService, IndexManifest.Chunker chunkerSpec,
                              int embeddingBatchSize, ChunkTransformer chunkTransformer) {
        this.embeddingService = embeddingService;
        this.chunkerSpec = chunkerSpec;
        this.embeddingBatchSize = embeddingBatchSize;
        this.chunkTransformer = chunkTransformer;
    }

    /** A store that keeps everything in memory (tests, experiments). */
    public LuceneSearchOperations inMemory() {
        return open(null);
    }

    /** Opens the directory, loading whatever chunks it already holds. */
    public LuceneSearchOperations open(@Nullable Path lucenePath) {
        var builder = LuceneSearchOperations.builder()
                .withName(LuceneIndexStore.STORE_NAME)
                .withEmbeddingService(embeddingService)
                .withChunkerConfig(new ContentChunker.Config(chunkerSpec.maxChunkSize(), chunkerSpec.overlapSize(), embeddingBatchSize))
                .withChunkTransformer(chunkTransformer);
        if (lucenePath == null) {
            return builder.build();
        }
        builder = builder.withIndexPath(lucenePath);
        try {
            // buildAndLoadChunks() logs an error for a directory without segments; only load when there is something to load.
            return Directories.hasFiles(lucenePath) ? builder.buildAndLoadChunks() : builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot inspect " + lucenePath, e);
        }
    }

    /** {@link #open} but a directory Lucene cannot read is moved aside and replaced by an empty one. */
    public Opened openOrRecover(Path lucenePath) throws IOException {
        try {
            return new Opened(open(lucenePath), null);
        } catch (Exception first) { // Lucene surfaces checked IOExceptions through the Kotlin constructor
            if (!Directories.hasFiles(lucenePath)) {
                throw (RuntimeException) first;
            }
            Path quarantine = lucenePath.resolveSibling(
                    LuceneIndexStore.LUCENE_DIR + ".corrupt-" + QUARANTINE_STAMP.format(LocalDateTime.now()));
            log.error("Lucene index at {} cannot be opened ({}); moving it to {} and starting empty", lucenePath,
                    first, quarantine);
            Files.move(lucenePath, quarantine, StandardCopyOption.ATOMIC_MOVE);
            Files.createDirectories(lucenePath);
            return new Opened(open(lucenePath), quarantine.toString());
        }
    }
}
