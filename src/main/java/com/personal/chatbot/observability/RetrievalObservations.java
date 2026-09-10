package com.personal.chatbot.observability;

import com.personal.chatbot.models.retrieval.ExpansionStrategy;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What retrieval measures (docs/observability/metric-catalog.json): one deterministic pass and the
 * stages inside it, the branch that searches more than once, how many passages a pass came back with,
 * and the outcome of a widened search.
 *
 * <p>Unlike the timer it replaces, {@code chatbot.retrieval.search} counts the passes that failed or
 * were abandoned too, which is what makes it usable for availability. The passages of a pass are
 * sampled once per successful pass, empty results included: finding nothing is a valid observation
 * about the corpus, not a missing measurement.
 *
 * <p>The three boundaries nest but do not add up: a workflow's wall time is its own, because its
 * passes may have run in parallel and because the model call that produced their queries belongs to
 * it and to no pass (docs/observability-plan.md §4.1).
 */
public final class RetrievalObservations {

    private final Observations observations;
    private final DistributionSummary hits;
    private final Map<String, Counter> expansions = new HashMap<>();

    public RetrievalObservations(Observations observations) {
        this.observations = observations;
        this.hits = DistributionSummary.builder("chatbot.retrieval.hits")
                .description("Passages one deterministic retrieval pass returned")
                .register(observations.meterRegistry());
        // NONE is the branch switched off, which never gets as far as widening anything.
        for (ExpansionStrategy strategy : ExpansionStrategy.values()) {
            if (strategy == ExpansionStrategy.NONE) {
                continue;
            }
            for (String outcome : new String[]{"sufficient", "insufficient"}) {
                String name = strategy.name().toLowerCase(Locale.ROOT);
                expansions.put(name + '/' + outcome, observations.counter("chatbot.retrieval.expansion",
                        "Widened searches and whether the merged evidence was good enough",
                        "strategy", name, "outcome", outcome));
            }
        }
    }

    /** One deterministic pass, including the index lock, the query embedding and the postprocessing. */
    public Measured startSearch(RetrievalMode mode) {
        return observations.start(MeasuredOperation.RETRIEVAL_SEARCH,
                MeasuredOperation.Labels.MODE, mode.name().toLowerCase(Locale.ROOT));
    }

    /**
     * One stage of a pass. {@code mode} is the pass the stage belongs to: {@code hybrid} for a facet
     * of a hybrid search, and the facet's own mode where it is the whole search — which is what a
     * search tool of the agentic branch runs.
     */
    public Measured startStage(RetrievalStage stage, RetrievalMode mode) {
        return observations.start(MeasuredOperation.RETRIEVAL_STAGE,
                MeasuredOperation.Labels.STAGE, stage.label(),
                MeasuredOperation.Labels.MODE, mode.name().toLowerCase(Locale.ROOT));
    }

    /**
     * The branch that searches a question more than once, from before the model call that produces its
     * extra queries to after the passes have been merged.
     */
    public RetrievalWorkflow startWorkflow(RetrievalStrategy strategy) {
        return new RetrievalWorkflow(observations, strategy);
    }

    /** What a successful pass found; zero is a result. */
    public void passages(int count) {
        hits.record(count);
    }

    /** One completed widening, no-op strategies included. */
    public void expansion(ExpansionStrategy strategy, boolean sufficient) {
        Counter counter = expansions.get(strategy.name().toLowerCase(Locale.ROOT)
                + (sufficient ? "/sufficient" : "/insufficient"));
        if (counter != null) {
            counter.increment();
        }
    }
}
