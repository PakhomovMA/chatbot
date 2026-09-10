package com.personal.chatbot.observability;

import java.util.List;

/**
 * The canonical measurements this checkpoint publishes, with the label keys each of them always
 * carries (docs/observability/metric-catalog.json). The schema lives here rather than at the call
 * sites: one name has one set of label keys, and a value that is not known yet is still written —
 * as {@code none} — so that no series ever appears or disappears with the shape of a request
 * (docs/observability-plan.md §5.3).
 *
 * <p>{@code outcome} is not listed: every measurement has one, and it is written by
 * {@link Measured} itself.
 */
public enum MeasuredOperation {

    CHAT_REQUEST("chatbot.chat.request", "Accepted chat runs, whatever they end in",
            List.of(Labels.MODE, Labels.ANSWER_MODE, Labels.GROUNDING)),

    CHAT_WAIT("chatbot.chat.wait", "Waiting for the lease of a conversation", List.of()),

    AI_OPERATION("chatbot.ai.operation", "One logical AI operation of the assistant",
            List.of(Labels.OPERATION)),

    RETRIEVAL_SEARCH("chatbot.retrieval.search", "One deterministic retrieval pass",
            List.of(Labels.MODE));

    /** Label keys of the canonical schema; {@code answer.mode} keeps the dotted form of the catalog. */
    public static final class Labels {

        public static final String MODE = "mode";
        public static final String ANSWER_MODE = "answer.mode";
        public static final String GROUNDING = "grounding";
        public static final String OPERATION = "operation";
        public static final String OUTCOME = "outcome";
        /** What a label reads when its value is not known, or does not apply to this run. */
        public static final String NONE = "none";

        private Labels() {
        }
    }

    private final String metricName;
    private final String description;
    private final List<String> labelKeys;

    MeasuredOperation(String metricName, String description, List<String> labelKeys) {
        this.metricName = metricName;
        this.description = description;
        this.labelKeys = labelKeys;
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
}
