package com.personal.chatbot.observability;

import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 8 gate: health components, gauges, request ids and the ingestion failure log (docs/system-plan.md D14). */
@AutoConfigureMockMvc
class ObservabilityTest extends AbstractChatbotIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedDataDir(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("management.endpoints.web.exposure.include", () -> "health,info,metrics");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requestIdIsEchoedOrGenerated() throws Exception {
        mockMvc.perform(get("/api/knowledge-base/status").header("X-Request-Id", "abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "abc-123"));
        mockMvc.perform(get("/api/knowledge-base/status").header("X-Request-Id", "not safe / at all"))
                .andExpect(header().string("X-Request-Id", Matchers.not("not safe / at all")))
                .andExpect(header().string("X-Request-Id", Matchers.matchesRegex("[0-9a-f-]{36}")));
        mockMvc.perform(get("/api/knowledge-base/status"))
                .andExpect(header().string("X-Request-Id", Matchers.matchesRegex("[0-9a-f-]{36}")));
    }

    @Test
    void healthExposesIndexEmbeddingAndOllamaComponents() throws Exception {
        // Ollama is not configured in the hermetic profile: the component is UNKNOWN, overall health still UP.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.luceneIndex.status").value("UP"))
                .andExpect(jsonPath("$.components.luceneIndex.details.state").value(Matchers.in(java.util.List.of("EMPTY", "READY"))))
                .andExpect(jsonPath("$.components.luceneIndex.details.fingerprint").value(Matchers.startsWith("fake/")))
                .andExpect(jsonPath("$.components.embedding.status").value("UP"))
                .andExpect(jsonPath("$.components.ollama.status").value("UNKNOWN"));
    }

    @Test
    void gaugesAndFailureLogFollowIngestion() throws Exception {
        mockMvc.perform(get("/actuator/metrics/chatbot.index.chunks")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics/chatbot.ingestion.queue")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics/chatbot.documents").param("tag", "status:ready")).andExpect(status().isOk());

        MvcResult upload = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "broken.pdf", "application/pdf", "%PDF-1.7 not really".getBytes())))
                .andExpect(status().isAccepted()).andReturn();
        String id = JsonPath.read(upload.getResponse().getContentAsString(), "$.documentId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(get("/api/documents/{id}/status", id)).andExpect(jsonPath("$.status").value("FAILED")));

        mockMvc.perform(get("/api/knowledge-base/status"))
                .andExpect(jsonPath("$.recentFailures[0].documentId").value(id))
                .andExpect(jsonPath("$.recentFailures[0].stage").value("parse"))
                .andExpect(jsonPath("$.recentFailures[0].message").isString());
        MvcResult failures = mockMvc.perform(get("/actuator/metrics/chatbot.ingestion.failures").param("tag", "stage:parse"))
                .andExpect(status().isOk()).andReturn();
        assertThat((Double) JsonPath.read(failures.getResponse().getContentAsString(), "$.measurements[0].value")).isGreaterThanOrEqualTo(1.0);
        mockMvc.perform(get("/actuator/metrics/chatbot.ingestion.stage").param("tag", "stage:parse")).andExpect(status().isOk());
    }
}
