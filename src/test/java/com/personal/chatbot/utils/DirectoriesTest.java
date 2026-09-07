package com.personal.chatbot.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoriesTest {

    @TempDir
    Path dir;

    @Test
    void hasFilesOnlyForNonEmptyDirectories() throws IOException {
        assertThat(Directories.hasFiles(dir)).isFalse();
        assertThat(Directories.hasFiles(dir.resolve("missing"))).isFalse();
        Files.writeString(dir.resolve("a.txt"), "x");
        assertThat(Directories.hasFiles(dir)).isTrue();
        assertThat(Directories.hasFiles(dir.resolve("a.txt"))).isFalse();
    }

    @Test
    void deleteTreeRemovesNestedContentAndIgnoresMissingDirectories() throws IOException {
        Path nested = dir.resolve("index").resolve("segments");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("_0.cfs"), "data");
        Directories.deleteTree(dir.resolve("index"));
        assertThat(Files.exists(dir.resolve("index"))).isFalse();
        Directories.deleteTree(dir.resolve("index"));
        Directories.deleteTreeUnchecked(dir.resolve("never-existed"));
    }
}
