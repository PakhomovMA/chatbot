package com.personal.chatbot.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingFingerprintTest {

    @Test
    void valueIsCanonicalAndStable() {
        EmbeddingFingerprint a = new EmbeddingFingerprint("onnx", "embeddinggemma-300m", "abcdef012345", 768, "gemma-prefix-v1", true);
        EmbeddingFingerprint b = new EmbeddingFingerprint("onnx", "embeddinggemma-300m", "abcdef012345", 768, "gemma-prefix-v1", true);
        assertThat(a.value()).isEqualTo("onnx/embeddinggemma-300m/abcdef012345/768/gemma-prefix-v1/l2");
        assertThat(a).isEqualTo(b);
        assertThat(a.value()).isNotEqualTo(new EmbeddingFingerprint("onnx", "embeddinggemma-300m", "abcdef012345", 256, "gemma-prefix-v1", true).value());
        assertThat(a.value()).isNotEqualTo(new EmbeddingFingerprint("ollama", "embeddinggemma-300m", "abcdef012345", 768, "gemma-prefix-v1", true).value());
    }

    @Test
    void sha256PrefixIsDeterministicAndShort(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("weights.bin");
        Files.write(file, "hello world".getBytes());
        String hash = EmbeddingFingerprint.sha256Prefix(file);
        assertThat(hash).hasSize(EmbeddingFingerprint.ARTIFACT_HASH_LENGTH);
        // sha256("hello world") = b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
        assertThat(hash).isEqualTo("b94d27b9934d");
        assertThat(EmbeddingFingerprint.sha256Prefix(file)).isEqualTo(hash);
    }

    @Test
    void cachedHashUsesSidecarUntilFileChanges(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("weights.bin");
        Files.write(file, "hello world".getBytes());
        assertThat(EmbeddingFingerprint.cachedSha256Prefix(file)).isEqualTo("b94d27b9934d");
        Path sidecar = dir.resolve("weights.bin.sha256");
        assertThat(sidecar).exists();

        // A stale sidecar for the same size+mtime is trusted (that is the point of the cache).
        String key = Files.readAllLines(sidecar).get(1);
        Files.writeString(sidecar, "cafebabecafe\n" + key + "\n");
        assertThat(EmbeddingFingerprint.cachedSha256Prefix(file)).isEqualTo("cafebabecafe");

        // Changing the file invalidates the sidecar.
        Files.write(file, "hello world!".getBytes());
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertThat(EmbeddingFingerprint.cachedSha256Prefix(file)).isEqualTo(EmbeddingFingerprint.sha256Prefix(file)).isNotEqualTo("cafebabecafe");
    }

    @Test
    void shortHashStripsAlgorithmPrefix() {
        assertThat(EmbeddingFingerprint.shortHash("sha256:85462619ee721b466c5927d109d4cb765861907d")).isEqualTo("85462619ee72");
        assertThat(EmbeddingFingerprint.shortHash("abc")).isEqualTo("abc");
    }
}
