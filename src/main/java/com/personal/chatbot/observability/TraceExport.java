package com.personal.chatbot.observability;

/**
 * Where the spans of one run go (docs/observability-plan.md §9, O05). The mode is stated, never
 * inferred: there is no exporter that appears because another one is missing, and no destination that
 * silently becomes the log because the one that was configured could not be built.
 *
 * <p>Metrics and the local diagnostics of the API are published in every mode. Nothing here decides
 * anything about a request (§3.1 rule 6).
 */
public enum TraceExport {

    /**
     * Nothing leaves the process. The default, and what the build and the tests run on: no exporter,
     * no batch processor and no trace pipeline to be available or unavailable.
     */
    NONE,

    /**
     * The spans are written to the log. For reading a single run locally — the shape of the tree, the
     * attributes, the errors — without any infrastructure.
     */
    LOGGING,

    /**
     * The spans are sent over OTLP HTTP to the collector, which is what talks to Langfuse
     * (docs/observability-plan.md §7.1). The endpoint is Boot's
     * {@code management.opentelemetry.tracing.export.otlp.endpoint}, and it is the full signal URL.
     */
    OTLP
}
