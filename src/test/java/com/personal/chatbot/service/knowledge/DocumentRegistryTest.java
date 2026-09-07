package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.exceptions.RegistryCorruptedException;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentError;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.service.knowledge.DocumentRegistry.Change;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentRegistryTest {

    private static Document doc(String title, Instant uploadedAt) {
        return Document.uploaded(title, title + ".md", "text/markdown", 42, "hash-" + title, uploadedAt);
    }

    @Test
    void persistsAtomicallyAndKeepsBackup(@TempDir Path dir) throws Exception {
        DocumentRegistry registry = new DocumentRegistry(dir);
        registry.save(doc("one", Instant.parse("2026-09-07T10:00:00Z")));
        Path file = dir.resolve(DocumentRegistry.FILE_NAME);
        assertThat(file).exists();
        assertThat(dir.resolve(DocumentRegistry.FILE_NAME + ".bak")).doesNotExist();

        registry.save(doc("two", Instant.parse("2026-09-07T11:00:00Z")));
        assertThat(dir.resolve(DocumentRegistry.FILE_NAME + ".bak")).exists();
        assertThat(dir.resolve(DocumentRegistry.FILE_NAME + ".tmp")).doesNotExist();
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("registry.json", "registry.json.bak");
        }
        assertThat(Files.readString(file)).contains("\"schemaVersion\" : 1").contains("\"two\"");
    }

    @Test
    void survivesRestartWithAllFields(@TempDir Path dir) {
        Instant now = Instant.parse("2026-09-07T10:00:00Z");
        Document failed = doc("failed", now)
                .withStatus(DocumentStatus.FAILED)
                .withError(new DocumentError("parse", "boom", now))
                .withChunkCount(7)
                .withEmbeddingFingerprint("onnx/x/y/768/v1/l2");
        new DocumentRegistry(dir).save(failed);

        DocumentRegistry reloaded = new DocumentRegistry(dir);
        assertThat(reloaded.findById(failed.id())).contains(failed);
        assertThat(reloaded.findByContentHash("hash-failed")).contains(failed);
        assertThat(reloaded.count()).isEqualTo(1);
        assertThat(reloaded.countByStatus()).isEqualTo(Map.of(DocumentStatus.FAILED, 1L));
    }

    @Test
    void listsNewestFirstAndDeletes(@TempDir Path dir) {
        DocumentRegistry registry = new DocumentRegistry(dir);
        Document older = registry.save(doc("older", Instant.parse("2026-09-07T10:00:00Z")));
        Document newer = registry.save(doc("newer", Instant.parse("2026-09-07T12:00:00Z")));
        assertThat(registry.findAll()).containsExactly(newer, older);

        assertThat(registry.delete(older.id())).isTrue();
        assertThat(registry.delete(older.id())).isFalse();
        assertThat(new DocumentRegistry(dir).findAll()).containsExactly(newer);
    }

    @Test
    void conditionalUpdatesSeparateAppliedMissingAndStale(@TempDir Path dir) {
        DocumentRegistry registry = new DocumentRegistry(dir);
        Document stored = registry.save(doc("one", Instant.parse("2026-09-07T10:00:00Z")));

        Change applied = registry.update(stored.id(), stored.version(), d -> d.withStatus(DocumentStatus.READY));
        assertThat(applied).isInstanceOf(Change.Applied.class);
        assertThat(applied.applied()).isNotNull().extracting(Document::status).isEqualTo(DocumentStatus.READY);

        Change stale = registry.update(stored.id(), stored.version() + 1, d -> d.withStatus(DocumentStatus.FAILED));
        assertThat(stale).isInstanceOf(Change.Stale.class);
        assertThat(((Change.Stale) stale).current().status()).isEqualTo(DocumentStatus.READY);

        registry.delete(stored.id());
        Change missing = registry.update(stored.id(), d -> d.withStatus(DocumentStatus.READY));
        assertThat(missing).isEqualTo(new Change.Missing(stored.id()));
        assertThat(registry.count()).isZero();
        assertThat(new DocumentRegistry(dir).findAll()).isEmpty();
    }

    @Test
    void fallsBackToBackupWhenMainFileIsCorrupt(@TempDir Path dir) throws Exception {
        DocumentRegistry registry = new DocumentRegistry(dir);
        Document first = registry.save(doc("first", Instant.now()));
        registry.save(doc("second", Instant.now()));
        Files.writeString(dir.resolve(DocumentRegistry.FILE_NAME), "{ not json");

        DocumentRegistry recovered = new DocumentRegistry(dir);
        assertThat(recovered.findAll()).containsExactly(first);
        assertThat(Files.readString(dir.resolve(DocumentRegistry.FILE_NAME))).contains("\"first\"");
    }

    @Test
    void refusesToStartWhenNothingIsReadable(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(DocumentRegistry.FILE_NAME), "{ not json");
        assertThatThrownBy(() -> new DocumentRegistry(dir)).isInstanceOf(RegistryCorruptedException.class);
    }
}
