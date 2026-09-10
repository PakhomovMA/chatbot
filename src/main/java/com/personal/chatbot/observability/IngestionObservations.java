package com.personal.chatbot.observability;

import io.micrometer.core.instrument.Counter;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * What ingestion measures (docs/observability/metric-catalog.json): how long a request waits for the
 * single writer, how long the run that claims it takes, the stages inside that run and the failures
 * that are really failures.
 *
 * <p>Ingestion says how a run ended rather than letting a wrapper guess. Most of its endings are
 * handled inside the pipeline and never reach a caller as an exception, so "returned normally" would
 * report a document that vanished, one that a newer upload took over and one that could not be parsed
 * as the same success (docs/observability-plan.md §5.2). A run stopped by shutdown is a cancellation,
 * not a failure: the document stays where startup reconciliation will queue it again, and paging
 * somebody about a clean restart helps nobody.
 */
public final class IngestionObservations {

    /**
     * Where a failure was recorded. Bounded here rather than taken from whatever an exception carried:
     * a stage name is a label, and an open set of them is a new series each time
     * (docs/observability-plan.md §5.3).
     */
    public enum FailedStage {

        PARSE, INDEXING, EMBEDDING, INGESTION;

        private final String label = name().toLowerCase(Locale.ROOT);

        public String label() {
            return label;
        }

        /** The stage {@code name} reports, or {@link #INGESTION} for anything this vocabulary does not know. */
        public static FailedStage of(String name) {
            for (FailedStage stage : values()) {
                if (stage.label.equalsIgnoreCase(name)) {
                    return stage;
                }
            }
            return INGESTION;
        }
    }

    private final Observations observations;
    private final Map<FailedStage, Counter> failures = new EnumMap<>(FailedStage.class);

    public IngestionObservations(Observations observations) {
        this.observations = observations;
        for (FailedStage stage : FailedStage.values()) {
            failures.put(stage, observations.counter("chatbot.ingestion.failures",
                    "Documents whose ingestion failed, by the stage it failed at",
                    MeasuredOperation.Labels.STAGE, stage.label()));
        }
    }

    /**
     * A request accepted into the queue, measured until the worker claims it — or until a deletion,
     * a newer request, a rebuild or shutdown means it never will be. Started on the thread that
     * accepted the request and stopped on whichever one settles it, so it opens no context scope.
     */
    public Measured startQueueWait() {
        return observations.start(MeasuredOperation.INGESTION_QUEUE_WAIT);
    }

    /** One claimed run, from the claim to the outcome it reports, cleanup included. */
    public Measured startProcessing() {
        return observations.start(MeasuredOperation.INGESTION_PROCESSING);
    }

    public Measured startStage(IngestionStage stage) {
        return observations.start(MeasuredOperation.INGESTION_STAGE,
                MeasuredOperation.Labels.STAGE, stage.label());
    }

    /**
     * A document that really failed to be ingested. A skip, a supersession and a document parked for
     * an index that cannot take it are not failures and are visible as outcomes of the run instead.
     */
    public void failed(FailedStage stage) {
        failures.get(stage).increment();
    }
}
