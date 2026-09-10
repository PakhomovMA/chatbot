package com.personal.chatbot.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.opentelemetry.api.trace.Span;
import org.slf4j.helpers.MessageFormatter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Logs stay metadata-only even when generation content is explicitly enabled for spans. */
final class SafeLog {
    private static final Set<String> TEMPLATES = templates();
    private static final TelemetrySanitizer SANITIZER = new TelemetrySanitizer(ContentPolicy.METADATA_ONLY, List.of());

    private static Set<String> templates() {
        try (var input = SafeLog.class.getResourceAsStream("/observability/log-templates.txt")) {
            if (input == null) return Set.of();
            return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).lines()
                    .collect(Collectors.toUnmodifiableSet());
        } catch (java.io.IOException e) {
            return Set.of();
        }
    }

    static String message(ILoggingEvent event) {
        if (event.getLoggerName().equals("com.embabel.agent.config.models.ollama.OllamaModelsConfig")
                && event.getMessage().equals("Discovered {} Ollama models from {}: {}")) {
            Object[] args = event.getArgumentArray();
            if (args != null && args.length == 3 && args[0] instanceof Number && args[2] instanceof List<?> models) {
                return "Discovered " + args[0] + " Ollama models: " + models.stream().limit(16)
                        .map(value -> SANITIZER.text(String.valueOf(value)))
                        .map(value -> value.substring(0, Math.min(128, value.length()))).toList();
            }
        }
        // The logging exporter is downstream of TelemetrySanitizer, including names, status and events.
        if (event.getLoggerName().equals("io.opentelemetry.exporter.logging.LoggingSpanExporter")) {
            return event.getFormattedMessage();
        }
        if (!event.getLoggerName().startsWith("com.personal.chatbot.") || !TEMPLATES.contains(event.getMessage())) {
            return "Event details suppressed" + exception(event);
        }
        Object[] args = event.getArgumentArray();
        Object[] safe = args == null ? new Object[0] : java.util.Arrays.stream(args).map(SafeLog::argument).toArray();
        return MessageFormatter.arrayFormat(event.getMessage(), safe).getMessage() + exception(event);
    }

    private static String exception(ILoggingEvent event) {
        return event.getThrowableProxy() == null ? "" : " [" + event.getThrowableProxy().getClassName() + "]";
    }

    private static Object argument(Object value) {
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return value;
        if (value instanceof String text && text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}")) return text;
        return "[REDACTED]";
    }

    static Map<String, String> correlation(ILoggingEvent event) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String key : ExecutionContext.KEYS) {
            String value = event.getMDCPropertyMap().get(key);
            if (value != null) result.put(key, SANITIZER.text(value).replaceAll("[\\r\\n\\t]", "_"));
        }
        var span = Span.current().getSpanContext();
        if (span.isValid()) {
            result.put("traceId", span.getTraceId());
            result.put("spanId", span.getSpanId());
        } else {
            for (String key : List.of("traceId", "spanId")) {
                String value = event.getMDCPropertyMap().get(key);
                if (value != null && value.matches("[0-9a-f]{16}(?:[0-9a-f]{16})?")) result.put(key, value);
            }
        }
        return result;
    }
}
