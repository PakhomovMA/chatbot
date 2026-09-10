package com.personal.chatbot.observability;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.observation.ChatModelMeterObservationHandler;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.function.FunctionToolCallback;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O01: real Ollama adapter + HTTP stub, not mocked model observations or invented usage. */
class ProviderObservationContractTest {
    private final PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final AtomicInteger attempts = new AtomicInteger();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> stopped = new CopyOnWriteArrayList<>();
    private final CountDownLatch modelStopped = new CountDownLatch(1);
    private HttpServer server;
    private OllamaChatModel model;
    private IntFunction<Reply> respond;

    @BeforeEach
    void start() throws Exception {
        observations.observationConfig()
                // Micrometer 1.17.1 invokes onStop in reverse registration order. Signal only after
                // both meter handlers have finished, not merely after downstream Flux completion.
                .observationHandler(new ObservationHandler<>() {
                    @Override public boolean supportsContext(Observation.Context context) { return true; }
                    @Override public void onStop(Observation.Context context) {
                        stopped.add(context.getName());
                        if (context.getName().equals("gen_ai.client.operation")) modelStopped.countDown();
                    }
                })
                .observationHandler(new DefaultMeterObservationHandler(meters))
                .observationHandler(new ChatModelMeterObservationHandler(meters));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply reply = respond.apply(attempts.incrementAndGet());
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", reply.type());
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        model = OllamaChatModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
                .options(OllamaChatOptions.builder().model("o01-stub").build())
                .observationRegistry(observations).retryTemplate(RetryUtils.SHORT_RETRY_TEMPLATE).build();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        meters.close();
        assertThat(observations.getCurrentObservation()).isNull();
    }

    @Test
    void retryIsTwoHttpAttemptsButOneModelObservationAndOneUsage() throws Exception {
        respond = attempt -> attempt == 1 ? new Reply(500, "application/json", "{\"error\":\"synthetic transient failure\"}")
                : ok("answer", 7, 3);
        assertThat(model.call(new Prompt("synthetic question")).getResult().getOutput().getText()).isEqualTo("answer");
        verifyAndRecord("retry", 2, 1, 7, 3, 0);
    }

    @Test
    void toolLoopHasOneGenerationAndUsagePerModelTurn() throws Exception {
        AtomicInteger toolCalls = new AtomicInteger();
        respond = attempt -> attempt == 1 ? new Reply(200, "application/json", """
                {"model":"o01-stub","message":{"role":"assistant","content":"","tool_calls":[
                  {"id":"call-1","function":{"name":"lookup","arguments":{}}}]},
                 "done":true,"done_reason":"stop","prompt_eval_count":7,"eval_count":3}
                """) : ok("answer", 11, 5);
        var tool = FunctionToolCallback.builder("lookup", () -> { toolCalls.incrementAndGet(); return "synthetic fact"; })
                .description("Get a synthetic fact").build();
        var advisor = ToolCallingAdvisor.builder().toolCallingManager(
                ToolCallingManager.builder().observationRegistry(observations).build()).build();
        String answer = ChatClient.builder(model).defaultAdvisors(advisor).build().prompt()
                .user("Look up the fact").tools(tool).call().content();
        assertThat(answer).isEqualTo("answer");
        assertThat(toolCalls).hasValue(1);
        assertThat(requests.get(1)).contains("synthetic fact", "tool");
        verifyAndRecord("tools", 2, 2, 18, 8, 1);
    }

    @Test
    void failedCallHasAnErrorTimerButNoInventedUsage() throws Exception {
        respond = _ -> new Reply(400, "application/json", "{\"error\":\"synthetic invalid request\"}");
        assertThatThrownBy(() -> model.call(new Prompt("invalid"))).isInstanceOf(NonTransientAiException.class);
        assertThat(meters.get("gen_ai.client.operation").tag("error", "NonTransientAiException").timer().count()).isEqualTo(1);
        verifyAndRecord("failure", 1, 1, 0, 0, 0);
    }

    @Test
    void streamingUsageIsRecordedOnceAtTerminalResponse() throws Exception {
        respond = _ -> new Reply(200, "application/x-ndjson", """
                {"model":"o01-stub","message":{"role":"assistant","content":"answer"},"done":false}
                {"model":"o01-stub","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":7,"eval_count":3}
                """);
        var chunks = model.stream(new Prompt("stream")).collectList().block(Duration.ofSeconds(5));
        assertThat(chunks).isNotEmpty();
        assertThat(requests.getFirst()).contains("\"stream\":true");
        // Spring AI stops in doFinally, after downstream completion has already unblocked block().
        assertThat(modelStopped.await(5, TimeUnit.SECONDS)).isTrue();
        verifyAndRecord("stream", 1, 1, 7, 3, 0);
    }

    private void verifyAndRecord(String scenario, int expectedAttempts, int generations, int input, int output, int tools) throws Exception {
        assertThat(attempts).hasValue(expectedAttempts);
        assertThat(stopped.stream().filter("gen_ai.client.operation"::equals).count()).isEqualTo(generations);
        assertThat(meters.find("gen_ai.client.operation").timers().stream().mapToLong(t -> t.count()).sum()).isEqualTo(generations);
        assertThat(tokens("input")).isEqualTo(input);
        assertThat(tokens("output")).isEqualTo(output);
        assertThat(tokens("total")).isEqualTo(input + output);
        String scrape = meters.scrape();
        assertThat(scrape).contains("gen_ai_client_operation_seconds_count", "gen_ai_client_operation_seconds_sum");
        if (input + output > 0) assertThat(scrape).contains("gen_ai_client_token_usage_total");
        else assertThat(scrape).doesNotContain("gen_ai_client_token_usage_total");
        String destination = System.getProperty("o01.provider.output");
        if (destination != null) {
            Path dir = Path.of(destination); Files.createDirectories(dir);
            Map<String, Object> evidence = Map.of("scenario", scenario, "httpAttempts", attempts.get(),
                    "modelObservations", generations, "input", tokens("input"), "output", tokens("output"),
                    "total", tokens("total"), "toolCalls", tools, "observations", new ArrayList<>(stopped));
            Files.writeString(dir.resolve(scenario + ".json"), JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n");
            Files.writeString(dir.resolve(scenario + ".prometheus.txt"), scrape);
        }
    }

    private double tokens(String type) {
        return meters.find("gen_ai.client.token.usage").tag("gen_ai.token.type", type).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private static Reply ok(String content, int input, int output) {
        return new Reply(200, "application/json", """
                {"model":"o01-stub","message":{"role":"assistant","content":"%s"},"done":true,
                "done_reason":"stop","prompt_eval_count":%d,"eval_count":%d}
                """.formatted(content, input, output));
    }
    private record Reply(int status, String type, String body) {}
}
