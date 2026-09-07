package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.DocumentNotFoundException;
import com.personal.chatbot.exceptions.InvalidUploadException;
import com.personal.chatbot.exceptions.InvalidUploadException.Reason;
import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.knowledge.dto.DocumentPage;
import com.personal.chatbot.models.knowledge.dto.UploadResponse;
import com.personal.chatbot.models.knowledge.DocumentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentServiceTest {

    @TempDir
    Path dir;

    private final List<Object> events = new ArrayList<>();
    private DocumentRegistry registry;
    private BlobStore blobStore;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry(dir.resolve("documents"));
        blobStore = new BlobStore(dir.resolve("blobs"));
        ChatbotProperties properties = new ChatbotProperties(dir,
                new ChatbotProperties.Embedding("fake", null, null, 16, 2, true),
                new ChatbotProperties.Knowledge(DataSize.ofKilobytes(1), Set.of("md", "txt")),
                new ChatbotProperties.Index(null, true, 800, 100, 32),
                new ChatbotProperties.Ingestion(true, true),
                new ChatbotProperties.Retrieval(8, 3, 60, 0.0, 0.0, 0.5, 200),
                new ChatbotProperties.Chat(com.personal.chatbot.models.chat.AnswerMode.DETERMINISTIC, 4, 0.2, 0.1, 6000, 600, 10, 1000, java.time.Duration.ofHours(24)));
        service = new DocumentService(registry, blobStore, properties,
                Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC), events::add);
    }

    private static DocumentService.Upload upload(String name, String content) {
        byte[] bytes = content.getBytes();
        return new DocumentService.Upload(name, null, bytes.length, new ByteArrayInputStream(bytes), null);
    }

    @Test
    void uploadRegistersDocumentAndStoresBlob() {
        UploadResponse response = service.upload(upload("Runbook.md", "# Runbook\nrestart"));
        assertThat(response.duplicate()).isFalse();
        assertThat(response.status()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(response.version()).isEqualTo(1);

        Document document = service.get(response.documentId());
        assertThat(document.title()).isEqualTo("Runbook");
        assertThat(document.mediaType()).isEqualTo("text/markdown");
        assertThat(document.sizeBytes()).isEqualTo(17);
        assertThat(document.uploadedAt()).isEqualTo(Instant.parse("2026-09-07T10:00:00Z"));
        assertThat(blobStore.find(document.id(), 1)).isPresent();
        assertThat(dir.resolve("blobs/.staging")).isEmptyDirectory();
    }

    @Test
    void identicalContentIsNotRegisteredTwice() {
        UploadResponse first = service.upload(upload("a.md", "same bytes"));
        UploadResponse again = service.upload(upload("renamed.md", "same bytes"));
        assertThat(again.duplicate()).isTrue();
        assertThat(again.documentId()).isEqualTo(first.documentId());
        assertThat(registry.count()).isEqualTo(1);
        assertThat(dir.resolve("blobs/.staging")).isEmptyDirectory();
    }

    @Test
    void replaceContentBumpsVersionAndDropsOldBlob() {
        String id = service.upload(upload("a.md", "v1")).documentId();
        UploadResponse unchanged = service.replaceContent(id, upload("a.md", "v1"));
        assertThat(unchanged.duplicate()).isTrue();
        assertThat(unchanged.version()).isEqualTo(1);

        UploadResponse replaced = service.replaceContent(id, upload("a-new.txt", "v2 content"));
        assertThat(replaced.duplicate()).isFalse();
        assertThat(replaced.version()).isEqualTo(2);
        Document document = service.get(id);
        assertThat(document.originalFilename()).isEqualTo("a-new.txt");
        assertThat(document.status()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(blobStore.find(id, 1)).isEmpty();
        assertThat(blobStore.find(id, 2)).isPresent();
    }

    @Test
    void rejectsInvalidUploadsWithoutLeavingFiles() {
        assertThatThrownBy(() -> service.upload(upload("a.md", "")))
                .isInstanceOf(InvalidUploadException.class).extracting("reason").isEqualTo(Reason.EMPTY);
        assertThatThrownBy(() -> service.upload(upload("a.exe", "x")))
                .isInstanceOf(InvalidUploadException.class).extracting("reason").isEqualTo(Reason.UNSUPPORTED_TYPE);
        assertThatThrownBy(() -> service.upload(upload("../", "x")))
                .isInstanceOf(InvalidUploadException.class).extracting("reason").isEqualTo(Reason.BAD_FILENAME);
        assertThatThrownBy(() -> service.upload(upload("big.md", "x".repeat(2048))))
                .isInstanceOf(InvalidUploadException.class).extracting("reason").isEqualTo(Reason.TOO_LARGE);
        assertThat(registry.count()).isZero();
        assertThat(dir.resolve("blobs/.staging")).isEmptyDirectory();
    }

    @Test
    void listFiltersAndPaginates() {
        service.upload(upload("alpha.md", "one"));
        service.upload(upload("beta.txt", "two"));
        service.upload(upload("gamma.md", "three"));

        DocumentPage all = service.list(new DocumentService.Query(null, null, 0, 2));
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.items()).hasSize(2);
        assertThat(service.list(new DocumentService.Query(null, null, 1, 2)).items()).hasSize(1);
        assertThat(service.list(new DocumentService.Query(null, "BETA", 0, 20)).items())
                .singleElement().extracting(Document::originalFilename).isEqualTo("beta.txt");
        assertThat(service.list(new DocumentService.Query(DocumentStatus.READY, null, 0, 20)).total()).isZero();
        assertThat(service.list(new DocumentService.Query(null, null, -5, 10_000)).size()).isEqualTo(DocumentService.MAX_PAGE_SIZE);
    }

    @Test
    void deleteRemovesRegistryEntryAndBlob() {
        String id = service.upload(upload("a.md", "bytes")).documentId();
        service.delete(id);
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(DocumentNotFoundException.class);
        assertThat(dir.resolve("blobs").resolve(id)).doesNotExist();
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void publishesLifecycleEvents() {
        String id = service.upload(upload("a.md", "bytes")).documentId();
        service.replaceContent(id, upload("a.md", "other bytes"));
        service.delete(id);
        assertThat(events).containsExactly(
                new DocumentEvent.Uploaded(id, 1),
                new DocumentEvent.ContentReplaced(id, 2),
                new DocumentEvent.Deleted(id));
    }
}
