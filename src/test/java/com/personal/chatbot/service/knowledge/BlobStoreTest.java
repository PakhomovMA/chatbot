package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.models.knowledge.StagedBlob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlobStoreTest {

    @Test
    void stagesWithSha256ThenCommitsIntoVersionedLayout(@TempDir Path dir) throws Exception {
        BlobStore store = new BlobStore(dir);
        StagedBlob staged = store.stage(new ByteArrayInputStream("hello world".getBytes()));
        assertThat(staged.sizeBytes()).isEqualTo(11);
        assertThat(staged.contentHash()).isEqualTo("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
        assertThat(staged.tempFile()).exists().hasParent(dir.resolve(BlobStore.STAGING_DIR));

        Path stored = store.commit(staged, "doc-1", 1, "md");
        assertThat(stored).isEqualTo(dir.resolve("doc-1/v1/original.md")).exists();
        assertThat(staged.tempFile()).doesNotExist();
        assertThat(Files.readString(stored)).isEqualTo("hello world");
        assertThat(store.find("doc-1", 1)).contains(stored);
        assertThat(store.find("doc-1", 2)).isEmpty();
    }

    @Test
    void discardAndDeleteRemoveFiles(@TempDir Path dir) {
        BlobStore store = new BlobStore(dir);
        StagedBlob staged = store.stage(new ByteArrayInputStream("x".getBytes()));
        store.discard(staged);
        assertThat(staged.tempFile()).doesNotExist();

        store.commit(store.stage(new ByteArrayInputStream("v1".getBytes())), "doc-2", 1, "txt");
        store.commit(store.stage(new ByteArrayInputStream("v2".getBytes())), "doc-2", 2, "txt");
        store.deleteVersion("doc-2", 1);
        assertThat(store.find("doc-2", 1)).isEmpty();
        assertThat(store.find("doc-2", 2)).isPresent();
        store.delete("doc-2");
        assertThat(dir.resolve("doc-2")).doesNotExist();
        store.delete("never-existed");
    }

    @Test
    void keepsTheVersionInUseAndDropsTheOlderOnes(@TempDir Path dir) {
        BlobStore store = new BlobStore(dir);
        for (int version = 1; version <= 3; version++) {
            store.commit(store.stage(new ByteArrayInputStream(("v" + version).getBytes())), "doc-3", version, "txt");
        }

        store.deleteVersionsBefore("doc-3", 3);

        assertThat(store.find("doc-3", 1)).isEmpty();
        assertThat(store.find("doc-3", 2)).isEmpty();
        assertThat(store.find("doc-3", 3)).isPresent();
        store.deleteVersionsBefore("never-existed", 2);
    }

    @Test
    void rejectsUnsafePathSegments(@TempDir Path dir) {
        BlobStore store = new BlobStore(dir);
        assertThatThrownBy(() -> store.locate("../x", 1, "md")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.locate("doc", 1, "m/d")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.locate("doc", 3, "")).isEqualTo(dir.resolve("doc/v3/original"));
    }
}
