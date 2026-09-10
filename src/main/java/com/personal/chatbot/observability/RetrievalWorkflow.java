package com.personal.chatbot.observability;

import org.jspecify.annotations.Nullable;

/**
 * The measurement of retrieving one question over more than one pass
 * (docs/observability/metric-catalog.json, {@code chatbot.retrieval.workflow}). It opens before the
 * model call that produces the extra queries and closes after the passes have been merged, so it
 * measures the branch rather than the searches inside it — the passes have their own boundary, and
 * when they run in parallel their durations do not add up to this one (docs/observability-plan.md
 * §4.1).
 *
 * <p>It also carries the one mark the v1 diagnostics are built from. {@code tookMs} of
 * {@code SearchExpansion} and {@code QuestionDecomposition} stops before the merge, as it always has,
 * and this is where that boundary is recorded — the search code asks for the number instead of adding
 * durations up itself (§4.2, §4.3). Without the mark the whole workflow is reported, which is what an
 * abandoned branch has to say anyway.
 */
public final class RetrievalWorkflow implements AutoCloseable {

    private final Measured workflow;

    RetrievalWorkflow(Observations observations, RetrievalStrategy strategy) {
        this.workflow = observations.start(MeasuredOperation.RETRIEVAL_WORKFLOW,
                MeasuredOperation.Labels.STRATEGY, strategy.label());
    }

    /**
     * The passes are in, and merging them starts now. This is where the v1 diagnostics stop counting;
     * the canonical measurement carries on to the end of the branch.
     */
    public void merging() {
        workflow.legacyEnds();
    }

    /** What the v1 {@code tookMs} of this branch reports: the workflow up to {@link #merging()}. */
    public long tookMs() {
        return workflow.legacyElapsed().toMillis();
    }

    /** The branch produced evidence — including the case where it found nothing to add. */
    public void succeeded() {
        workflow.succeeded();
    }

    /**
     * The branch ended in {@code error}, or in the cancellation of the request underneath it. A branch
     * that recovered and answered from the first pass reports {@link #succeeded()} instead: that is
     * what it did (docs/observability/metric-catalog.json, {@code chatbot.retrieval.workflow}).
     */
    public void failed(Throwable error, @Nullable String cancellationReason) {
        workflow.failed(error, cancellationReason);
    }

    @Override
    public void close() {
        workflow.close();
    }
}
