package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.models.knowledge.StagedBlob;
import com.personal.chatbot.utils.Directories;
import com.personal.chatbot.utils.Hashes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Uploaded originals on the filesystem: {@code <data-dir>/blobs/<documentId>/v<n>/original.<ext>}
 * (docs/system-plan.md §9). Uploads are first streamed into a staging area while their SHA-256 is
 * computed, so de-duplication can decide before anything lands in a document directory.
 */
public class BlobStore {

    private static final Logger log = LoggerFactory.getLogger(BlobStore.class);

    static final String STAGING_DIR = ".staging";
    static final String ORIGINAL_BASENAME = "original";
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Path root;

    public BlobStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root.resolve(STAGING_DIR));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create blob store at " + root, e);
        }
    }

    /** Copies the upload into staging, computing its size and full SHA-256 on the way. */
    public StagedBlob stage(InputStream content) {
        Path temp = root.resolve(STAGING_DIR).resolve(UUID.randomUUID() + ".upload");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), digest)) {
                size = content.transferTo(out);
            }
            return new StagedBlob(temp, HexFormat.of().formatHex(digest.digest()), size);
        } catch (IOException e) {
            discard(new StagedBlob(temp, "", 0));
            throw new UncheckedIOException("Cannot stage upload", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Moves a staged upload into its final place and returns that path. */
    public Path commit(StagedBlob staged, String documentId, int version, String extension) {
        Path target = locate(documentId, version, extension);
        try {
            Files.createDirectories(target.getParent());
            Files.move(staged.tempFile(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store blob for document " + documentId, e);
        }
    }

    public void discard(StagedBlob staged) {
        try {
            Files.deleteIfExists(staged.tempFile());
        } catch (IOException e) {
            log.warn("Cannot delete staged upload {}: {}", staged.tempFile(), e.toString());
        }
    }

    public Path locate(String documentId, int version, String extension) {
        requireSafe(documentId);
        String fileName = extension.isEmpty() ? ORIGINAL_BASENAME : ORIGINAL_BASENAME + "." + requireSafe(extension);
        return root.resolve(documentId).resolve("v" + version).resolve(fileName);
    }

    /** The stored original of a given version, whatever its extension. */
    public Optional<Path> find(String documentId, int version) {
        Path dir = root.resolve(requireSafe(documentId)).resolve("v" + version);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
    }

    /** Removes every stored version of a document. */
    public void delete(String documentId) {
        Directories.deleteTreeUnchecked(root.resolve(requireSafe(documentId)));
    }

    public void deleteVersion(String documentId, int version) {
        Directories.deleteTreeUnchecked(root.resolve(requireSafe(documentId)).resolve("v" + version));
    }

    public Path root() {
        return root;
    }

    private static String requireSafe(String segment) {
        if (!SAFE_SEGMENT.matcher(segment).matches()) {
            throw new IllegalArgumentException("Unsafe path segment: " + segment);
        }
        return segment;
    }
}
