package com.personal.chatbot.observability;

import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** O04: actual HTTP listener and Prometheus exposition, including every terminal population. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@ActiveProfiles({"hermetic", "metrics"})
class PrometheusMetricsTest extends AbstractChatbotIntegrationTest {
    @TempDir static Path data;
    @DynamicPropertySource
    static void data(DynamicPropertyRegistry properties) {
        properties.add("chatbot.data-dir", data::toString);
    }

    @Autowired Environment environment;
    @Autowired ChatObservations chat;
    @Autowired PrometheusMeterRegistry registry;

    @Test
    void managementListenerIsSeparateAndDoesNotPublishDiagnosticsOrHealthDetails() throws Exception {
        int management = environment.getRequiredProperty("local.management.port", Integer.class);
        int application = environment.getRequiredProperty("local.server.port", Integer.class);
        assertThat(management).isNotEqualTo(application);
        assertThat(get(management, "/actuator/prometheus").statusCode()).isEqualTo(200);
        assertThat(get(application, "/actuator/prometheus").statusCode()).isEqualTo(404);
        assertThat(get(management, "/actuator/health").body()).doesNotContain("components", "details", "indexPath");
        for (String path : List.of("/api/diagnostics/retrieval", "/api/knowledge-base/status", "/actuator/metrics", "/actuator/env")) {
            assertThat(get(management, path).statusCode()).as(path).isEqualTo(404);
        }
    }

    @Test
    void scrapeCountsErrorsTimeoutsAndCancellationsExactlyOnceWithHistogramBuckets() throws Exception {
        List<Outcome> outcomes = List.of(Outcome.SUCCESS, Outcome.ERROR, Outcome.TIMEOUT, Outcome.CANCELLED, Outcome.REJECTED);
        for (Outcome outcome : outcomes) {
            try (ChatRun run = chat.startRun(true, AnswerMode.AGENTIC)) {
                run.finished(outcome);
                run.close(); // Deliberately exercise a completion/cancellation race's repeated close.
            }
        }
        String scrape = get(environment.getRequiredProperty("local.management.port", Integer.class), "/actuator/prometheus").body();
        List<String> counts = scrape.lines().filter(s -> s.startsWith("chatbot_chat_request_seconds_count{"))
                .filter(s -> s.contains("answer_mode=\"agentic\"") && s.contains("mode=\"stream\"")).toList();
        assertThat(counts).hasSize(5);
        for (Outcome outcome : outcomes) {
            assertThat(counts).anyMatch(s -> s.contains("outcome=\"" + outcome.name().toLowerCase() + "\"") && s.endsWith(" 1"));
        }
        assertThat(scrape).contains("chatbot_chat_request_seconds_bucket{", "le=\"600.0\"", "application=\"chatbot\"");
        assertThat(scrape).doesNotContain("requestId=", "conversationId=", "documentId=", "query=");
        assertThat(registry.get("chatbot.chat.active").gauge().value()).isZero();
        // Optional output is the exact fixture consumed by the Docker query verifier.
        String output = System.getProperty("o04.scrape.output");
        if (output != null) Files.writeString(Path.of(output), scrape);
    }

    @Test
    void untrustedProviderModelsCollapseToUnknownAndLabelCapStopsGrowth() {
        for (int i = 0; i < 200; i++) {
            Timer.builder("gen_ai.client.operation").tags("gen_ai.request.model", "untrusted-" + i,
                    "gen_ai.response.model", "untrusted-" + i, "error", "Exception" + i)
                    .register(registry).record(Duration.ofMillis(20));
        }
        assertThat(registry.find("gen_ai.client.operation").timers()).hasSize(1);
        assertThat(registry.get("gen_ai.client.operation").tag("gen_ai.request.model", "unknown").timer().count()).isEqualTo(200);
        assertThat(registry.scrape()).contains("gen_ai_client_operation_seconds_bucket").doesNotContain("untrusted-");
        for (int i = 0; i < 200; i++) {
            Timer.builder("http.server.requests.o04").tag("uri", "/unexpected/" + i).register(registry);
        }
        assertThat(registry.find("http.server.requests.o04").timers().size()).isLessThanOrEqualTo(100);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
