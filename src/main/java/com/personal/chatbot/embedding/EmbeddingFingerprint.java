package com.personal.chatbot.embedding;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identity of the vector space an index was built in (docs/system-plan.md INV-05). Two services
 * with equal fingerprints produce comparable vectors; anything else must not share an index.
 *
 * @param provider      backend family, e.g. {@code onnx} or {@code ollama}
 * @param model         logical model name
 * @param artifactHash  short hash of the actual weights (file digest or registry digest)
 * @param dimensions    vector length
 * @param prefixVersion {@link EmbeddingPrompts#PREFIX_VERSION}
 * @param normalized    whether vectors are L2-normalised
 */
public record EmbeddingFingerprint(
        String provider,
        String model,
        String artifactHash,
        int dimensions,
        String prefixVersion,
        boolean normalized
) {

    public static final int ARTIFACT_HASH_LENGTH = 12;

    /** Canonical single-line form stored in the index manifest and reported by health. */
    public String value() {
        return String.join("/", provider, model, artifactHash, Integer.toString(dimensions),
                prefixVersion, normalized ? "l2" : "raw");
    }

    /** First {@value #ARTIFACT_HASH_LENGTH} hex chars of the SHA-256 of a file. */
    public static String sha256Prefix(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, ARTIFACT_HASH_LENGTH);
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
        Path sidecar = file.resolveSibling(file.getFileName() + ".sha256");
        String key;
        try {
            key = Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot stat " + file, e);
        }
        try {
            if (Files.isRegularFile(sidecar)) {
                java.util.List<String> lines = Files.readAllLines(sidecar);
                if (lines.size() >= 2 && key.equals(lines.get(1).trim()) && lines.get(0).trim().length() == ARTIFACT_HASH_LENGTH) {
                    return lines.get(0).trim();
                }
            }
        } catch (IOException ignored) {
            // unreadable cache: fall through and recompute
        }
        String hash = sha256Prefix(file);
        try {
            Files.writeString(sidecar, hash + System.lineSeparator() + key + System.lineSeparator());
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(EmbeddingFingerprint.class).debug("Cannot write hash cache {}: {}", sidecar, e.toString());
        }
        return hash;
    }

    public static String shortHash(String digest) {
        String clean = digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
        return clean.length() > ARTIFACT_HASH_LENGTH ? clean.substring(0, ARTIFACT_HASH_LENGTH) : clean;
    }
}
