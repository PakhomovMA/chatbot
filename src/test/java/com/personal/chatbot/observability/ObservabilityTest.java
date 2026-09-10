package com.personal.chatbot.observability;

import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.models.chat.ChatTimings;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.models.retrieval.RetrievalQuery;
import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;
import com.personal.chatbot.models.retrieval.QuestionDecomposition;
import com.personal.chatbot.models.retrieval.SearchExpansion;
import com.personal.chatbot.models.retrieval.ExpansionStrategy;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import com.personal.chatbot.service.retrieval.SearchExpander;
import com.personal.chatbot.service.retrieval.SubQuestionSearch;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    @Autowired
    private ObjectMapper mapper;

    @Test
    void legacyTimingWireFormatMatchesO01FixtureUsingTheApplicationMapper() throws Exception {
        // These are representative values, not a claim that wall-clock tests can assert exact milliseconds.
        // Service boundary/empty/fallback semantics are pinned in LegacyRetrievalTimingTest as well.
        Map<String, Object> values = Map.of(
                "chat", new ChatTimings(12, 88, 100),
                "chatClamped", new ChatTimings(120, 0, 100),
                "retrieval", new RetrievalTimings(4, 3, 2, 12),
                "parallelWork", new RetrievalTimings(40, 30, 20, 35),
                "agentic", new RetrievalTimings(0, 0, 0, 95),
                "expansion", new SearchExpansion(ExpansionStrategy.REWRITE, List.of("restart"), 1, 25),
                "emptyExpansion", SearchExpansion.none(ExpansionStrategy.REWRITE, 25),
                "decomposition", new QuestionDecomposition(List.of("restart", "rollback"), 1, 35));
        try (var fixture = getClass().getResourceAsStream("/observability/legacy-timing-values.json")) {
            assertThat(fixture).isNotNull();
            var actual = mapper.readTree(mapper.writeValueAsString(values));
            assertThat(actual).isEqualTo(mapper.readTree(fixture));
        }
    }

    @Test
    void fallbackAndEmptyExpansionKeepTheirLegacyJsonShape() throws Exception {
        var settings = new ChatbotProperties.Retrieval(4, 3, 60, 0, 0, .5, 0, 1, 20);
        var traces = new RetrievalTraceStore(20);
        var first = new RetrievalResult("trace", "whole", RetrievalMode.HYBRID, 4, 12, List.of(), false, -1,
                new RetrievalTimings(4, 3, 2, 12), Instant.EPOCH);
        var fallback = new SubQuestionSearch(traces, settings)
                .search(RetrievalQuery.of("whole"), List.of(), 999, _ -> List.of(first));
        var expanded = new SearchExpander(_ -> first, traces, settings)
                .expand(RetrievalQuery.of("whole"), first, ExpansionStrategy.REWRITE, List.of(), 25);
        var json = mapper.readTree(mapper.writeValueAsString(Map.of("fallback", fallback, "emptyExpansion", expanded)));
        // Only the generated identifiers/timestamp are normalized; all public fields and durations remain.
        ((ObjectNode) json.get("emptyExpansion")).put("traceId", "trace").put("at", "1970-01-01T00:00:00Z");
        try (var fixture = getClass().getResourceAsStream("/observability/legacy-fallback.json")) {
            assertThat(fixture).isNotNull();
            assertThat(json).isEqualTo(mapper.readTree(fixture));
        }
    }

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
