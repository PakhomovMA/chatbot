package com.personal.chatbot.eval;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class EvalFixtures {
    private static final Path ROOT = Path.of("src/test/resources/eval");
    private static final Set<String> SUPPORTED = Set.of(".md", ".pdf", ".java", ".py", ".sql");

    private EvalFixtures() {
    }

    public static EvalQuestionSet questions() {
        try {
            return JsonMapper.builder().build().readValue(Files.readString(ROOT.resolve("questions.json")),
                    EvalQuestionSet.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Path> documentPaths() {
        try (Stream<Path> files = Files.list(ROOT.resolve("docs"))) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> SUPPORTED.contains(extension(path)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String mediaType(Path path) {
        return switch (extension(path)) {
            case ".md" -> "text/markdown";
            case ".pdf" -> "application/pdf";
            case ".java", ".py", ".sql" -> "text/plain";
            default -> throw new IllegalArgumentException("Unsupported eval document " + path);
        };
    }

    public static String documentKey(Path path) {
        String filename = path.getFileName().toString();
        return filename.substring(0, filename.length() - extension(path).length());
    }

    private static String extension(Path path) {
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot);
    }
}
