package com.personal.chatbot.observability;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks at startup that the trace pipeline is the one that was asked for
 * (docs/observability-plan.md §9, O05).
 *
 * <p>The failure this prevents is the quiet one. A destination that cannot be built, a profile that
 * turns tracing off underneath a mode that needs it, a second exporter nobody meant to add — none of
 * those announce themselves at runtime: the application answers questions perfectly well while its
 * traces go somewhere else, or nowhere. So the mode is verified against what the context actually
 * contains, once, and a mismatch stops the start with what is wrong in it.
 *
 * <p>What it verifies is that the destination the mode names is there and the one it does not name is
 * not — a mode never quietly becomes another — and that a single SDK owns the spans, so that no tree
 * is exported twice. A destination installed deliberately by something that is not one of the modes,
 * as a probe or a test does, is left alone: it is a choice, and the modes are about the ones the
 * application makes for itself.
 *
 * <p>Configuration is checked here; reachability is not. A collector that is down is an outage, not a
 * misconfiguration, and it must not keep the application from starting (§3.1 rule 6).
 */
final class TraceExportCheck implements SmartInitializingSingleton {

    static final String OTLP_ENDPOINT = "management.opentelemetry.tracing.export.otlp.endpoint";
    static final String OTEL_ENABLED = "management.opentelemetry.enabled";
    static final String EXPORT_ENABLED = "management.tracing.export.enabled";
    static final String OTLP_EXPORT_ENABLED = "management.tracing.export.otlp.enabled";
    static final String EMBABEL_TRACING = "embabel.agent.platform.observability.tracing-enabled";

    /** The path an OTLP/HTTP trace exporter posts to; a collector's own endpoint has no suffix. */
    private static final String TRACES_PATH = "/v1/traces";

    /** Credentials never belong in a message; an endpoint that carries some is printed without them. */
    private static final Pattern USER_INFO = Pattern.compile("://[^/@\\s]*@");

    private static final Logger log = LoggerFactory.getLogger(TraceExportCheck.class);

    private final TraceExport mode;
    private final Environment environment;
    private final ListableBeanFactory beans;

    TraceExportCheck(TraceExport mode, Environment environment, ListableBeanFactory beans) {
        this.mode = mode;
        this.environment = environment;
        this.beans = beans;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String endpoint = environment.getProperty(OTLP_ENDPOINT, "").trim();
        List<String> problems = new ArrayList<>();

        switch (mode) {
            case NONE -> {
                rejectEndpoint(endpoint, problems);
                absent(LoggingSpanExporter.class, problems);
                absent(OtlpHttpSpanExporter.class, problems);
            }
            case LOGGING -> {
                rejectEndpoint(endpoint, problems);
                present(LoggingSpanExporter.class, problems);
                absent(OtlpHttpSpanExporter.class, problems);
            }
            case OTLP -> {
                if (endpoint.isEmpty()) {
                    problems.add("trace-export=otlp needs " + OTLP_ENDPOINT
                            + "; there is no destination to fall back to");
                } else if (!endpoint.endsWith(TRACES_PATH)) {
                    problems.add(OTLP_ENDPOINT + " is " + redact(endpoint) + ", which is not a trace signal endpoint:"
                            + " an OTLP/HTTP exporter posts to a URL ending in " + TRACES_PATH
                            + ", while a collector's own endpoint is the one without it");
                }
                requireEnabled(OTEL_ENABLED, problems);
                requireEnabled(EXPORT_ENABLED, problems);
                requireEnabled(OTLP_EXPORT_ENABLED, problems);
                requireEnabled(EMBABEL_TRACING, problems);
                present(OtlpHttpSpanExporter.class, problems);
                absent(LoggingSpanExporter.class, problems);
            }
        }

        int providers = count(SdkTracerProvider.class);
        if (providers > 1 || (mode != TraceExport.NONE && providers != 1)) {
            problems.add("exactly one SdkTracerProvider should own the spans of a run, found " + providers
                    + "; a second SDK exports its own copy of the tree");
        }
        int processors = count(BatchSpanProcessor.class);
        if (processors > 1) {
            problems.add("one batch processor should queue the spans, found " + processors
                    + "; each of them sends the same span again");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Trace export is configured inconsistently: " + String.join("; ", problems));
        }
        log.info("Trace export: {}{}", mode, mode == TraceExport.OTLP ? " to " + redact(endpoint) : "");
    }

    private void rejectEndpoint(String endpoint, List<String> problems) {
        if (!endpoint.isEmpty()) {
            problems.add(OTLP_ENDPOINT + " is set to " + redact(endpoint) + " while " + setting()
                    + "; set trace-export=otlp to use it, or remove the endpoint");
        }
    }

    private void requireEnabled(String property, List<String> problems) {
        if (!environment.getProperty(property, Boolean.class, true)) {
            problems.add(property + "=false turns the trace pipeline off underneath " + setting());
        }
    }

    private void present(Class<? extends SpanExporter> exporter, List<String> problems) {
        long found = beans.getBeansOfType(SpanExporter.class).values().stream()
                .map(SanitizingSpanExporter::unwrap).filter(exporter::isInstance).count();
        if (found != 1) {
            problems.add(setting() + " expects one " + exporter.getSimpleName() + ", found " + found
                    + (found == 0 ? "; the mode is not falling back to another destination" : ""));
        }
    }

    private void absent(Class<? extends SpanExporter> exporter, List<String> problems) {
        var found = beans.getBeansOfType(SpanExporter.class).entrySet().stream()
                .filter(entry -> exporter.isInstance(SanitizingSpanExporter.unwrap(entry.getValue()))).toList();
        if (!found.isEmpty()) {
            problems.add(setting() + ", but the context also exports spans through "
                    + found.stream().map(Map.Entry::getKey).toList());
        }
    }

    private int count(Class<?> type) {
        return beans.getBeanNamesForType(type).length;
    }

    private String setting() {
        return "trace-export=" + mode.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** @return {@code url} without any {@code user:password@} it may carry */
    static String redact(String url) {
        return USER_INFO.matcher(url).replaceAll("://***@");
    }
}
