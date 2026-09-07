package com.personal.chatbot.controller;

import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.models.knowledge.DocumentEvent;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API contracts over MockMvc (docs/system-plan.md §8): documents, ingestion status, re-index, events. */
@AutoConfigureMockMvc
@RecordApplicationEvents
class DocumentControllerTest extends AbstractChatbotIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedDataDir(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("chatbot.knowledge.max-upload-size", () -> "64KB");
        registry.add("spring.servlet.multipart.max-file-size", () -> "64KB");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationEvents applicationEvents;

    private static MockMultipartFile file(String name, String contentType, String content) {
        return new MockMultipartFile("file", name, contentType, content.getBytes());
    }

    private String uploadAndGetId(String name, String content) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/documents").file(file(name, "text/markdown", content)))
                .andExpect(status().isAccepted())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.documentId");
    }

    private void awaitStatus(String id, String expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(get("/api/documents/{id}/status", id)).andExpect(jsonPath("$.status").value(expected)));
    }

    @Test
    void uploadIsIndexedThenReindexedAndDeleted() throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/documents")
                        .file(file("Restart Guide.md", "text/markdown", "# Restart\nRun systemctl restart payments."))
                        .param("title", "Restart guide"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn();
        String id = JsonPath.read(upload.getResponse().getContentAsString(), "$.documentId");

        // The ingestion worker (fake embeddings, real Tika) indexes the upload in the background.
        awaitStatus(id, "READY");
        mockMvc.perform(get("/api/documents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Restart guide"))
                .andExpect(jsonPath("$.originalFilename").value("Restart Guide.md"))
                .andExpect(jsonPath("$.mediaType").value("text/markdown"))
                .andExpect(jsonPath("$.contentHash").isString())
                .andExpect(jsonPath("$.chunkCount").value(Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.embeddingFingerprint").value(Matchers.startsWith("fake/")));

        mockMvc.perform(get("/api/documents").param("q", "restart").param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
        mockMvc.perform(get("/api/documents").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/knowledge-base/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.documentsByStatus.READY").value(1))
                .andExpect(jsonPath("$.index.state").value("READY"))
                .andExpect(jsonPath("$.index.chunkCount").value(Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.index.persistent").value(false))
                .andExpect(jsonPath("$.queue.pending").value(0))
                .andExpect(jsonPath("$.embedding.provider").value("fake"));

        mockMvc.perform(post("/api/documents/{id}/reindex", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_REINDEX"));
        awaitStatus(id, "READY");

        mockMvc.perform(post("/api/knowledge-base/reindex"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queued").value(1));
        awaitStatus(id, "READY");
        assertThat(applicationEvents.stream(DocumentEvent.StatusChanged.class)).isNotEmpty();

        mockMvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/documents/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Document not found"))
                .andExpect(jsonPath("$.documentId").value(id));
        assertThat(dataDir.resolve("blobs").resolve(id)).doesNotExist();
        mockMvc.perform(get("/api/knowledge-base/status"))
                .andExpect(jsonPath("$.index.state").value("EMPTY"))
                .andExpect(jsonPath("$.index.chunkCount").value(0));
    }

    @Test
    void duplicateContentReturnsExistingDocument() throws Exception {
        String id = uploadAndGetId("dup-a.md", "identical bytes");
        mockMvc.perform(multipart("/api/documents").file(file("dup-b.md", "text/markdown", "identical bytes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.documentId").value(id));
        awaitStatus(id, "READY");
        mockMvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isNoContent());
    }

    @Test
    void replaceContentCreatesNewVersion() throws Exception {
        String id = uploadAndGetId("versioned.md", "version one");
        awaitStatus(id, "READY");
        mockMvc.perform(multipart("/api/documents/{id}/content", id).file(file("versioned.md", "text/markdown", "version two"))
                        .with(request -> { request.setMethod("PUT"); return request; }))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.duplicate").value(false));
        awaitStatus(id, "READY");
        mockMvc.perform(get("/api/documents/{id}", id)).andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(delete("/api/documents/{id}", id)).andExpect(status().isNoContent());
    }

    @Test
    void eventsEndpointStreamsServerSentEvents() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/knowledge-base/events"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
    }

    @Test
    void rejectsInvalidUploadsAsProblemDetails() throws Exception {
        mockMvc.perform(multipart("/api/documents").file(file("empty.md", "text/markdown", "")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.reason").value("EMPTY"));
        mockMvc.perform(multipart("/api/documents").file(file("virus.exe", "application/octet-stream", "MZ")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.reason").value("UNSUPPORTED_TYPE"));
        mockMvc.perform(multipart("/api/documents").file(file("big.md", "text/markdown", "x".repeat(100_000))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(multipart("/api/documents"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get("/api/documents").param("status", "NOPE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/documents/{id}/reindex", "missing")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/documents")).andExpect(jsonPath("$.total").value(0));
    }
}
