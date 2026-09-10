package com.personal.chatbot.baseline;

import com.personal.chatbot.ChatbotApplication;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in, local-only O01 evidence collector; never included in the production jar. */
public final class ObservabilityBaseline {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("chatbot-o01-data-");
        List<String> arguments = new ArrayList<>(List.of(args));
        // Always isolate writes, including when a caller supplies a data-dir argument.
        arguments.addAll(List.of("--spring.profiles.active=observability,o01-baseline", "--server.port=0",
                "--chatbot.data-dir=" + data, "--chatbot.index.dir=" + data.resolve("index"),
                "--chatbot.embedding.onnx.model-dir=" + System.getProperty("user.home") + "/.chatbot/models/embeddinggemma-300m",
                "--chatbot.chat.decompose.enabled=true",
                // The probe installs a destination of its own, so the application installs none: the
                // spans are recorded here instead of also being written to the log (O05 trace-export).
                "--chatbot.observability.trace-export=none",
                "--embabel.agent.platform.observability.capture-message-content=false",
                "--embabel.agent.platform.observability.trace-http-details=false"));
        ConfigurableApplicationContext context = SpringApplication.run(ChatbotApplication.class, arguments.toArray(String[]::new));
        RecordingExporter exporter = context.getBean(RecordingExporter.class);
        ObservationRegistry observations = context.getBean(ObservationRegistry.class);
        Tracer tracer = context.getBean(Tracer.class);
        Path output = Path.of(context.getEnvironment().getProperty("o01.output", "/tmp/chatbot-o01-baseline"));
        Files.createDirectories(output);
        try {
            write(output, "beans.json", beans(context));
            Map<String, Object> conditions = new TreeMap<>();
            ConditionEvaluationReport.get(context.getBeanFactory()).getConditionAndOutcomesBySource().forEach((name, outcomes) -> {
                if (name.matches("(?i).*(observ|tracing|telemetry|prometheus|meterregistry).*")) {
                    List<Object> rows = new ArrayList<>();
                    outcomes.forEach(c -> rows.add(Map.of("condition", c.getCondition().getClass().getName(),
                            "match", c.getOutcome().isMatch(), "message", String.valueOf(c.getOutcome().getMessage()))));
                    conditions.put(name, Map.of("fullMatch", outcomes.isFullMatch(), "outcomes", rows));
                }
            });
            write(output, "conditions.json", conditions);
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            try (HttpClient client = HttpClient.newHttpClient()) {
                new Scenarios(client, "http://127.0.0.1:" + port, output, context).run();
            }
            // Explicit scope nesting and restoration on both normal and exceptional exits.
            Observation outer = Observation.start("o01.scope.outer", observations);
            boolean nestedRestored;
            try (var ignored = outer.openScope()) {
                try {
                    Observation.createNotStarted("o01.scope.inner", observations).observe(() -> {
                        throw new IllegalStateException("synthetic scope failure");
                    });
                } catch (IllegalStateException expected) {
                    // Preserve the original exception: this is a lifecycle probe, not application recovery.
                }
                nestedRestored = observations.getCurrentObservation() == outer && tracer.currentSpan() != null;
            } finally {
                outer.stop();
            }
            write(output, "scope.json", Map.of("nestedRestored", nestedRestored,
                    "observationCleared", observations.getCurrentObservation() == null,
                    "spanCleared", tracer.currentSpan() == null));
            if (!nestedRestored || observations.getCurrentObservation() != null || tracer.currentSpan() != null) {
                throw new IllegalStateException("Scope was not restored");
            }
        } finally {
            var flush = context.getBean(SdkTracerProvider.class).forceFlush();
            flush.join(10, TimeUnit.SECONDS);
            write(output, "meters.json", meters(context.getBean(MeterRegistry.class)));
            context.close();
            write(output, "spans.json", exporter.spans);
            long unique = exporter.spans.stream().map(s -> s.get("spanId")).distinct().count();
            write(output, "shutdown.json", Map.of("contextActive", context.isActive(), "flushSucceeded", flush.isSuccess(),
                    "exporterShutdownCalls", exporter.shutdowns.get(), "exportedSpans", exporter.spans.size(),
                    "uniqueSpans", unique, "observationCleared", observations.getCurrentObservation() == null,
                    "spanCleared", tracer.currentSpan() == null));
            if (context.isActive() || !flush.isSuccess() || exporter.shutdowns.get() < 1 || unique != exporter.spans.size()) {
                throw new IllegalStateException("Shutdown/export lifecycle failed; see " + output);
            }
            System.out.println("O01 evidence: " + output + "; isolated data: " + data);
        }
    }

    static void write(Path dir, String name, Object value) throws Exception {
        Files.writeString(dir.resolve(name), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    static List<Object> meters(MeterRegistry registry) {
        List<Object> meters = new ArrayList<>();
        registry.getMeters().stream().sorted(Comparator.comparing(m -> m.getId().toString())).forEach(m -> {
            Map<String, String> tags = new TreeMap<>();
            m.getId().getTags().forEach(t -> tags.put(t.getKey(), t.getValue()));
            Map<String, Object> values = new TreeMap<>();
            m.measure().forEach(v -> values.put(v.getStatistic().name(), Double.isFinite(v.getValue()) ? v.getValue() : null));
            Map<String, Object> row = new TreeMap<>();
            row.put("name", m.getId().getName()); row.put("type", m.getId().getType()); row.put("labels", tags);
            row.put("baseUnit", m.getId().getBaseUnit()); row.put("measurements", values);
            meters.add(row);
        });
        return meters;
    }

    private static List<Object> beans(ConfigurableApplicationContext context) {
        List<Object> beans = new ArrayList<>();
        for (Class<?> type : List.of(OpenTelemetry.class, SdkTracerProvider.class, Tracer.class,
                SpanProcessor.class, SpanExporter.class, ObservationHandler.class, ObservationRegistry.class, MeterRegistry.class)) {
            context.getBeansOfType(type).forEach((name, bean) -> {
                var definition = context.getBeanFactory().getBeanDefinition(name);
                beans.add(Map.of("role", type.getName(), "name", name, "class", bean.getClass().getName(),
                        "factoryBean", String.valueOf(definition.getFactoryBeanName()),
                        "factoryMethod", String.valueOf(definition.getFactoryMethodName()),
                        "scope", definition.isSingleton() ? "singleton" : definition.getScope()));
            });
            if (List.of(OpenTelemetry.class, SdkTracerProvider.class, Tracer.class).contains(type)
                    && context.getBeansOfType(type).size() != 1) {
                throw new IllegalStateException("Expected one " + type.getName());
            }
        }
        return beans;
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("o01-baseline")
    static class ProbeConfiguration {
        @Bean
        RecordingExporter baselineSpanExporter() { return new RecordingExporter(); }
    }

    static final class RecordingExporter implements SpanExporter {
        final List<Map<String, Object>> spans = new CopyOnWriteArrayList<>();
        final AtomicInteger shutdowns = new AtomicInteger();
        @Override public CompletableResultCode export(Collection<SpanData> batch) {
            for (SpanData span : batch) {
                Map<String, Object> attributes = new TreeMap<>();
                // Capture structural/model/usage attributes only, even if future framework defaults change.
                span.getAttributes().forEach((k, v) -> {
                    if (k.getKey().matches(".*(content|prompt|completion|input\\.value|output\\.value|query|arguments|result|exception\\.message|exception\\.stacktrace).*")) return;
                    attributes.put(k.getKey(), v);
                });
                Map<String, Object> row = new TreeMap<>();
                row.put("name", span.getName()); row.put("traceId", span.getTraceId());
                row.put("spanId", span.getSpanId()); row.put("parentSpanId", span.getParentSpanId());
                row.put("kind", span.getKind().name()); row.put("status", span.getStatus().getStatusCode().name());
                row.put("startEpochNanos", span.getStartEpochNanos()); row.put("durationNanos", span.getEndEpochNanos() - span.getStartEpochNanos());
                row.put("attributes", attributes); row.put("scope", span.getInstrumentationScopeInfo().getName());
                spans.add(row);
            }
            return CompletableResultCode.ofSuccess();
        }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { shutdowns.incrementAndGet(); return CompletableResultCode.ofSuccess(); }
    }

    private record Scenarios(HttpClient client, String base, Path output, ConfigurableApplicationContext context) {
        void run() throws Exception {
            String health = request("GET", "/actuator/health", null, null);
            Files.writeString(output.resolve("health.json"), health);
            if (!JSON.readTree(health).path("status").asText().equals("UP")) throw new IllegalStateException("Health is not UP");
            chat("empty", "Where is the runbook?", false, false);
            upload("runbook.md", "text/markdown", """
                    # O01 synthetic runbook
                    ## Restart
                    To restart the payment service run systemctl restart payments. Confirm readiness with GET /health.
                    ## Rollback
                    To roll back a release run deploy rollback payments. The operator then verifies GET /health.
                    """, "READY");
            chat("sync", "How do I restart the payment service?", false, false);
            chat("sse", "How do I restart the payment service?", true, false);
            chat("agentic", "How do I roll back the payment service? Use the knowledge base.", false, true);
            chat("decomposition", "How do I restart the payment service and how do I roll back a release?", false, false);
            upload("broken.pdf", "application/pdf", "%PDF-1.7 not really", "FAILED");
            Files.writeString(output.resolve("prometheus.txt"), request("GET", "/actuator/prometheus", null, null));
            write(output, "environment.json", Map.of("llm", context.getEnvironment().getProperty("embabel.models.default-llm"),
                    "java", System.getProperty("java.version"), "profiles", context.getEnvironment().getActiveProfiles(),
                    "embedding", context.getEnvironment().getProperty("chatbot.embedding.provider"),
                    "decompositionEnabled", true, "contentCapture", false, "httpDetails", false));
        }
        void chat(String name, String question, boolean stream, boolean agentic) throws Exception {
            long start = System.currentTimeMillis();
            String body = JSON.writeValueAsString(Map.of("message", question, "options",
                    Map.of("includeDiagnostics", true, "mode", agentic ? "AGENTIC" : "DETERMINISTIC")));
            String response = request("POST", stream ? "/api/chat/stream" : "/api/chat", body, "application/json");
            Files.writeString(output.resolve(name + (stream ? ".sse" : ".json")), response);
            if (stream ? !response.contains("event:final") || response.contains("event:error") : !JSON.readTree(response).has("timings")) {
                throw new IllegalStateException(name + " did not finish successfully");
            }
            write(output, name + "-window.json", Map.of("startEpochMillis", start, "endEpochMillis", System.currentTimeMillis()));
            write(output, name + "-meters.json", meters(context.getBean(MeterRegistry.class)));
            System.out.println("O01 scenario completed: " + name);
        }
        void upload(String file, String type, String content, String expected) throws Exception {
            String boundary = "o01-boundary";
            String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + file
                    + "\"\r\nContent-Type: " + type + "\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n";
            String response = request("POST", "/api/documents", body, "multipart/form-data; boundary=" + boundary);
            String id = JSON.readTree(response).path("documentId").asText();
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                String status = request("GET", "/api/documents/" + id + "/status", null, null);
                if (JSON.readTree(status).path("status").asText().equals(expected)) {
                    Files.writeString(output.resolve(file + "-status.json"), status); return;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException(file + " did not reach " + expected);
        }
        String request(String method, String path, String body, String contentType) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(12))
                    .header("X-Request-Id", "o01-" + UUID.randomUUID());
            if (contentType != null) request.header("Content-Type", contentType);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw new IllegalStateException(path + ": " + response.statusCode() + " " + response.body());
            return response.body();
        }
    }
}
