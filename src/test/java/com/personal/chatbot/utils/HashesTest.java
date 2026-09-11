package com.personal.chatbot.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

class HashesTest {

    @Test
    void sha256PrefixIsDeterministicAndShort(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("weights.bin");
        Files.write(file, "hello world".getBytes());
        String hash = Hashes.sha256Prefix(file);
        assertThat(hash).hasSize(Hashes.SHORT_DIGEST_LENGTH);
        // sha256("hello world") = b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
        assertThat(hash).isEqualTo("b94d27b9934d");
        assertThat(Hashes.sha256Prefix(file)).isEqualTo(hash);
    }

    @Test
    void cachedHashUsesSidecarUntilFileChanges(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("weights.bin");
        Files.write(file, "hello world".getBytes());
        assertThat(Hashes.cachedSha256Prefix(file)).isEqualTo("b94d27b9934d");
        Path sidecar = dir.resolve("weights.bin.sha256");
        assertThat(sidecar).exists();

        // A sidecar for the same size+mtime is trusted (that is the point of the cache).
        String key = Files.readAllLines(sidecar).get(1);
        Files.writeString(sidecar, "cafebabecafe\n" + key + "\n");
        assertThat(Hashes.cachedSha256Prefix(file)).isEqualTo("cafebabecafe");

        // Changing the file invalidates the sidecar.
        Files.write(file, "hello world!".getBytes());
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertThat(Hashes.cachedSha256Prefix(file)).isEqualTo(Hashes.sha256Prefix(file)).isNotEqualTo("cafebabecafe");
    }

    @Test
    void sha256OfATextIsTheFullHexDigestOfItsUtf8Bytes() {
        assertThat(Hashes.sha256("hello world")).isEqualTo("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
        assertThat(Hashes.sha256("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        // UTF-8 rather than the platform charset, so a Cyrillic question hashes the same everywhere.
        assertThat(Hashes.sha256("привет")).isEqualTo("e58f1e8c55fa105bdd3f40e5037eb0b039b5998d52c05e6cd98878dd2da5cab2");
    }

    @Test
    void shortDigestStripsAlgorithmPrefix() {
        assertThat(Hashes.shortDigest("sha256:85462619ee721b466c5927d109d4cb765861907d")).isEqualTo("85462619ee72");
        assertThat(Hashes.shortDigest("abc")).isEqualTo("abc");
    }
}
