package com.personal.chatbot.controller;

import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import com.personal.chatbot.support.TestDocuments;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Retrieval playground and diagnostics contracts (docs/system-plan.md §8). */
@AutoConfigureMockMvc
class RetrievalControllerTest extends AbstractChatbotIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedDataDir(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void searchReturnsScoredHitsWithProvenanceAndKeepsTrace() throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "runbook.md", "text/markdown", TestDocuments.markdown())))
                .andExpect(status().isAccepted()).andReturn();
        String id = JsonPath.read(upload.getResponse().getContentAsString(), "$.documentId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(get("/api/documents/{id}/status", id)).andExpect(jsonPath("$.status").value("READY")));

        MvcResult search = mockMvc.perform(post("/api/retrieval/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"systemctl restart payments\",\"topK\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("HYBRID"))
                .andExpect(jsonPath("$.topK").value(3))
                .andExpect(jsonPath("$.hits", Matchers.not(Matchers.empty())))
                .andExpect(jsonPath("$.hits[0].rank").value(1))
                .andExpect(jsonPath("$.hits[0].text").value(Matchers.containsString("systemctl")))
                .andExpect(jsonPath("$.hits[0].provenance.documentId").value(id))
                .andExpect(jsonPath("$.hits[0].provenance.documentTitle").value("runbook"))
                .andExpect(jsonPath("$.hits[0].provenance.chunkId").value(Matchers.startsWith(id + ":1:")))
                .andExpect(jsonPath("$.timings.totalMs").isNumber())
                .andReturn();
        String traceId = JsonPath.read(search.getResponse().getContentAsString(), "$.traceId");

        mockMvc.perform(get("/api/diagnostics/retrieval/{traceId}", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("systemctl restart payments"));
        mockMvc.perform(get("/api/diagnostics/retrieval").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].traceId").value(traceId));
        mockMvc.perform(get("/api/diagnostics/retrieval/{traceId}", "unknown")).andExpect(status().isNotFound());

        mockMvc.perform(post("/api/retrieval/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"deploy\",\"mode\":\"TEXT\",\"documentIds\":[\"nope\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits").isEmpty());
    }

    @Test
    void blankQueryIsRejected() throws Exception {
        mockMvc.perform(post("/api/retrieval/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(post("/api/retrieval/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"x\",\"topK\":500}"))
                .andExpect(status().isBadRequest());
    }
}
