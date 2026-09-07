package com.personal.chatbot.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Content hashing helpers used for model fingerprints (and, later, document identity). */
public final class Hashes {

    private static final Logger log = LoggerFactory.getLogger(Hashes.class);

    /** Length of the short digests used in fingerprints. */
    public static final int SHORT_DIGEST_LENGTH = 12;
    private static final String SIDECAR_SUFFIX = ".sha256";

    private Hashes() {
    }

    /** First {@value #SHORT_DIGEST_LENGTH} hex chars of the SHA-256 of a file. */
    public static String sha256Prefix(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return shortDigest(HexFormat.of().formatHex(digest.digest()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot hash " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@link #sha256Prefix(Path)} with a sidecar cache ({@code <file>.sha256}) keyed by size and
     * modification time, so a multi-gigabyte weights file is hashed once, not on every startup.
     */
    public static String cachedSha256Prefix(Path file) {
        Path sidecar = file.resolveSibling(file.getFileName() + SIDECAR_SUFFIX);
        String key = cacheKey(file);
        try {
            if (Files.isRegularFile(sidecar)) {
                List<String> lines = Files.readAllLines(sidecar);
                if (lines.size() >= 2 && key.equals(lines.get(1).trim())
                        && lines.getFirst().trim().length() == SHORT_DIGEST_LENGTH) {
                    return lines.getFirst().trim();
                }
            }
        } catch (IOException e) {
            log.debug("Ignoring unreadable hash cache {}: {}", sidecar, e.toString());
        }
        String hash = sha256Prefix(file);
        try {
            Files.writeString(sidecar, hash + System.lineSeparator() + key + System.lineSeparator());
        } catch (IOException e) {
            log.debug("Cannot write hash cache {}: {}", sidecar, e.toString());
        }
        return hash;
    }

    /** Shortens a hex digest, dropping an optional {@code sha256:} prefix (Ollama style). */
    public static String shortDigest(String digest) {
        String clean = digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
        return clean.length() > SHORT_DIGEST_LENGTH ? clean.substring(0, SHORT_DIGEST_LENGTH) : clean;
    }

    private static String cacheKey(Path file) {
        try {
            return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot stat " + file, e);
        }
    }
}
