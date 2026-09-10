package com.personal.chatbot.observability;

import java.util.List;

/**
 * The canonical measurements this application publishes, with the label keys each of them always
 * carries (docs/observability/metric-catalog.json). The schema lives here rather than at the call
 * sites: one name has one set of label keys, and a value that is not known yet is still written —
 * as {@code none} — so that no series ever appears or disappears with the shape of a request
 * (docs/observability-plan.md §5.3).
 *
 * <p>{@code outcome} is not listed among the keys: the measurements that carry one have it written by
 * {@link Measured} itself, and {@link Boundary} says which those are.
 */
public enum MeasuredOperation {

    CHAT_REQUEST("chatbot.chat.request", "Accepted chat runs, whatever they end in",
            List.of(Labels.MODE, Labels.ANSWER_MODE, Labels.GROUNDING)),

    CHAT_WAIT("chatbot.chat.wait", "Waiting for the lease of a conversation", List.of()),

    AI_OPERATION("chatbot.ai.operation", "One logical AI operation of the assistant",
            List.of(Labels.OPERATION)),

    RETRIEVAL_SEARCH("chatbot.retrieval.search", "One deterministic retrieval pass",
            List.of(Labels.MODE)),

    RETRIEVAL_WORKFLOW("chatbot.retrieval.workflow", "Retrieving one question over more than one pass",
            List.of(Labels.STRATEGY)),

    RETRIEVAL_STAGE("chatbot.retrieval.stage", "One stage of a retrieval pass",
            List.of(Labels.STAGE, Labels.MODE)),

    /** Kept without an outcome, as the timer it replaces was: a failed batch is a separate event. */
    EMBEDDING("chatbot.embedding", "Embedding one batch of texts",
            List.of(Labels.MODE, Labels.PROVIDER, Labels.MODEL), Boundary.SCOPED_WITHOUT_OUTCOME),

    EMBEDDING_WAIT("chatbot.embedding.wait", "Waiting for a free embedding batch slot",
            List.of(Labels.MODE)),

    INGESTION_PROCESSING("chatbot.ingestion.processing", "Ingesting one claimed document", List.of()),

    /** Accepted on the caller's thread and claimed on the worker's, so it opens no scope. */
    INGESTION_QUEUE_WAIT("chatbot.ingestion.queue.wait", "Waiting for the ingestion worker to claim a request",
            List.of(), Boundary.HANDED_OVER),

    /** Kept without an outcome, as the timer it replaces was: skips, failures and successes share it. */
    INGESTION_STAGE("chatbot.ingestion.stage", "One stage of ingesting a document",
            List.of(Labels.STAGE), Boundary.SCOPED_WITHOUT_OUTCOME);

    /** How a measurement's boundary behaves, where it differs from the ordinary case. */
    public enum Boundary {

        /** Opened and closed on one thread, carrying the outcome it ended in. */
        SCOPED,

        /**
         * The same, except that the meter keeps exactly the labels it had before the catalog gave the
         * canonical families an {@code outcome}. The outcome is still reported — it reaches the span,
         * and it decides what the compatibility adapter records — it just does not become a label
         * (docs/observability/metric-catalog.json, {@code compatibility: preserved}).
         */
        SCOPED_WITHOUT_OUTCOME,

        /**
         * Started where the work is accepted and stopped where it is taken up, which is another
         * thread. A context scope is opened and closed on one thread only, so this boundary opens
         * none (docs/observability-plan.md §7.2).
         */
        HANDED_OVER
    }

    /** Label keys of the canonical schema; {@code answer.mode} keeps the dotted form of the catalog. */
    public static final class Labels {

        public static final String MODE = "mode";
        public static final String ANSWER_MODE = "answer.mode";
        public static final String GROUNDING = "grounding";
        public static final String OPERATION = "operation";
        public static final String STRATEGY = "strategy";
        public static final String STAGE = "stage";
        public static final String PROVIDER = "provider";
        public static final String MODEL = "model";
        public static final String OUTCOME = "outcome";
        /** What a label reads when its value is not known, or does not apply to this run. */
        public static final String NONE = "none";

        private Labels() {
        }
    }

    private final String metricName;
    private final String description;
    private final List<String> labelKeys;
    private final Boundary boundary;

    MeasuredOperation(String metricName, String description, List<String> labelKeys) {
        this(metricName, description, labelKeys, Boundary.SCOPED);
    }

    MeasuredOperation(String metricName, String description, List<String> labelKeys, Boundary boundary) {
        this.metricName = metricName;
        this.description = description;
        this.labelKeys = labelKeys;
        this.boundary = boundary;
    }

    public String metricName() {
        return metricName;
    }

    public String description() {
        return description;
    }

    /** The keys this measurement declares, besides {@code outcome}. */
    public List<String> labelKeys() {
        return labelKeys;
    }

    public Boundary boundary() {
        return boundary;
    }

    /** Whether the outcome this measurement ended in becomes one of its labels. */
    public boolean labelsOutcome() {
        return boundary != Boundary.SCOPED_WITHOUT_OUTCOME;
    }
}
