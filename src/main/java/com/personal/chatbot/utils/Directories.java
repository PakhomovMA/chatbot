package com.personal.chatbot.utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Directory operations shared by the stores that own a slice of {@code chatbot.data-dir}. */
public final class Directories {

    private Directories() {
    }

    /** True when {@code dir} is an existing directory holding at least one entry. */
    public static boolean hasFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.findAny().isPresent();
        }
    }

    /** Removes a directory and everything below it; a missing directory is not an error. */
    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** {@link #deleteTree} for callers that already work in unchecked-IO terms. */
    public static void deleteTreeUnchecked(Path dir) {
        try {
            deleteTree(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + dir, e);
        }
    }
}
