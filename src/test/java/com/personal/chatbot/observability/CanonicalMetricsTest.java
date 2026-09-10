package com.personal.chatbot.observability;

import com.embabel.agent.core.support.InvalidLlmReturnFormatException;
import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import com.personal.chatbot.support.TestDocuments;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O02 gate, in the running application: a real request through the controller, the agent and
 * deterministic retrieval publishes the canonical measurements of
 * docs/observability/metric-catalog.json, and the legacy names beside them.
 *
 * <p>Tracing is off in this profile and the sampling probability is set to zero here on purpose:
 * metrics and the diagnostics of the response must not depend on a trace pipeline, a sampler or an
 * exporter (docs/observability-plan.md §5.3).
 */
@AutoConfigureMockMvc
class CanonicalMetricsTest extends AbstractChatbotIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedDataDirWithoutTraces(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
        registry.add("management.tracing.sampling.probability", () -> "0.0");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meters;

    private static String documentId;

    @BeforeEach
    void indexRunbookOnce() throws Exception {
        if (documentId != null) {
            return;
        }
        MvcResult upload = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "runbook.md", "text/markdown", TestDocuments.markdown()))
                        .param("title", "Payments Runbook"))
                .andExpect(status().isAccepted()).andReturn();
        documentId = JsonPath.read(upload.getResponse().getContentAsString(), "$.documentId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(get("/api/documents/{id}/status", documentId)).andExpect(jsonPath("$.status").value("READY")));
    }

    @Test
    void anAnsweredRunPublishesEveryCanonicalBoundaryAndTheLegacyNamesBesideThem() throws Exception {
        String question = "How do I restart the payment service?";
        whenCreateObject(p -> p.contains("Question: " + question), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("Run `systemctl restart payments` [1].", List.of(1), true, null));
        long runs = count("chatbot.chat.request", "outcome", "success");
        long drafts = count("chatbot.ai.operation", "operation", "draft-answer");
        long passes = count("chatbot.retrieval.search", "outcome", "success");
        long legacyRuns = count("chatbot.chat", "mode", "sync");

        ask(question).andExpect(status().isOk()).andExpect(jsonPath("$.grounding").value("GROUNDED"));

        assertThat(count("chatbot.chat.request", "outcome", "success")).isEqualTo(runs + 1);
        assertThat(count("chatbot.chat.wait", "outcome", "success")).isPositive();
        assertThat(count("chatbot.ai.operation", "operation", "draft-answer")).isEqualTo(drafts + 1);
        assertThat(count("chatbot.retrieval.search", "outcome", "success")).isEqualTo(passes + 1);
        assertThat(meters.get("chatbot.retrieval.hits").summary().count()).isPositive();
        // The same run, under the names the old dashboards read; a different population, never a sum.
        assertThat(count("chatbot.chat", "mode", "sync")).isEqualTo(legacyRuns + 1);
        assertThat(count("chatbot.llm", "operation", "draft-answer")).isEqualTo(drafts + 1);
        assertThat(count("chatbot.retrieval", "mode", "hybrid")).isPositive();

        // The catalog's label set, and nothing the standard handler added on top of it.
        assertThat(meters.get("chatbot.chat.request").tag("outcome", "success").timer().getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("mode", "answer.mode", "grounding", "outcome");
        assertThat(meters.get("chatbot.chat.request").tag("outcome", "success").timer()
                .takeSnapshot().histogramCounts()).isNotEmpty();
        // Nothing is left counted as running once the answer is out.
        assertThat(meters.get("chatbot.chat.active").gauge().value()).isZero();
    }

    @Test
    void aRunWithNothingToAnswerFromIsAnOutcomeAndNotAnAiOperation() throws Exception {
        long drafts = count("chatbot.ai.operation", "operation", "draft-answer");
        long passes = count("chatbot.retrieval.search", "outcome", "success");

        // Filtered to a document that is not in the knowledge base, so retrieval comes back empty.
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"What does the compliance handbook say?\","
                                + "\"options\":{\"documentIds\":[\"not-in-the-knowledge-base\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounding").value("INSUFFICIENT_EVIDENCE"));

        assertThat(count("chatbot.chat.request", "grounding", "insufficient_evidence")).isPositive();
        // A pass that found nothing is a measurement, not a missing one.
        assertThat(count("chatbot.retrieval.search", "outcome", "success")).isGreaterThan(passes);
        // The application wrote that answer itself, so no drafting operation was attempted for it.
        assertThat(count("chatbot.ai.operation", "operation", "draft-answer")).isEqualTo(drafts);
    }

    @Test
    void aFailedRunEndsInAnErrorAndStaysOutOfTheLegacyTimer() throws Exception {
        String question = "How is the service stopped?";
        whenCreateObject(p -> p.contains("Question: " + question), GroundedAnswerDraft.class)
                .thenThrow(new IllegalStateException("the model is unreachable"));
        long legacySuccesses = count("chatbot.chat", "mode", "sync");
        long errors = count("chatbot.chat.request", "outcome", "error");
        long attempts = count("chatbot.llm", "operation", "draft-answer");

        ask(question).andExpect(status().is5xxServerError());

        assertThat(count("chatbot.chat.request", "outcome", "error")).isEqualTo(errors + 1);
        assertThat(count("chatbot.ai.operation", "operation", "draft-answer", "outcome", "error")).isPositive();
        // The legacy chat timer only ever saw runs that answered; the legacy AI timer saw every attempt.
        assertThat(count("chatbot.chat", "mode", "sync")).isEqualTo(legacySuccesses);
        assertThat(count("chatbot.llm", "operation", "draft-answer")).isGreaterThan(attempts);
        assertThat(meters.get("chatbot.chat.active").gauge().value()).isZero();
    }

    @Test
    void aRecoveredDraftIsAFallbackForTheOperationAndASuccessForTheRun() throws Exception {
        String question = "How do I roll the release back?";
        String prose = "Run `systemctl restart payments` on the host [1].";
        whenCreateObject(p -> p.contains("Question: " + question), GroundedAnswerDraft.class)
                .thenThrow(new InvalidLlmReturnFormatException(prose, GroundedAnswerDraft.class,
                        new RuntimeException("No content to map due to end-of-input")));
        long fallbacks = count("chatbot.ai.operation", "operation", "draft-answer", "outcome", "fallback");
        long runs = count("chatbot.chat.request", "outcome", "success");

        ask(question).andExpect(status().isOk());

        assertThat(count("chatbot.ai.operation", "operation", "draft-answer", "outcome", "fallback"))
                .isEqualTo(fallbacks + 1);
        assertThat(count("chatbot.chat.request", "outcome", "success")).isEqualTo(runs + 1);
    }

    private ResultActions ask(String message) throws Exception {
        return mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + message + "\"}"));
    }

    /** Records of one meter name carrying {@code tags}; zero when it has not been created yet. */
    private long count(String name, String... tags) {
        return meters.find(name).tags(tags).timers().stream().mapToLong(Timer::count).sum();
    }
}
