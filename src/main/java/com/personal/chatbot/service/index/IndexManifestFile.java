package com.personal.chatbot.service.index;

import com.personal.chatbot.models.index.IndexManifest;
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

/**
 * The {@code manifest.json} beside the Lucene directory: it pins the embedding fingerprint and the
 * chunker the index was built with (INV-05). Written through a temp file and an atomic move, so a
 * crash mid-write never leaves a half-manifest. An in-memory index has no file and reads as absent.
 */
public class IndexManifestFile {

    private static final Logger log = LoggerFactory.getLogger(IndexManifestFile.class);

    static final String FILE_NAME = "manifest.json";
    private static final String TEMP_SUFFIX = ".tmp";

    private final @Nullable Path file;
    private final JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** @param indexDir directory holding the manifest; null for an in-memory index */
    public IndexManifestFile(@Nullable Path indexDir) {
        this.file = indexDir != null ? indexDir.resolve(FILE_NAME) : null;
    }

    /** @return the stored manifest, or null when there is none or it cannot be read */
    public @Nullable IndexManifest read() {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(file), IndexManifest.class);
        } catch (IOException | RuntimeException e) {
            log.error("Manifest {} is unreadable: {}", file, e.toString());
            return null;
        }
    }

    /** No-op for an in-memory index. */
    public void write(IndexManifest content) {
        if (file == null) {
            return;
        }
        Path temp = file.resolveSibling(FILE_NAME + TEMP_SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temp, mapper.writeValueAsString(content));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write manifest " + file, e);
        }
    }
}
