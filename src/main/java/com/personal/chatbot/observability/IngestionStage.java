package com.personal.chatbot.observability;

import java.util.Locale;

/**
 * The parts of ingesting one document that are timed apart
 * (docs/observability/metric-catalog.json, {@code chatbot.ingestion.stage}). The family keeps the
 * shape it had: no outcome, so a stage that was skipped, failed or succeeded shares one population —
 * what it reports is how long that part of the pipeline takes, not how often it works.
 */
public enum IngestionStage {

    /** Finding the stored original and turning it into a navigable document. */
    PARSE,

    /** Writing the parsed document, its chunks and their vectors to the index. */
    INDEX,

    /** The whole run, up to and including the pruning of superseded originals. */
    TOTAL;

    private final String label = name().toLowerCase(Locale.ROOT);

    public String label() {
        return label;
    }
}
