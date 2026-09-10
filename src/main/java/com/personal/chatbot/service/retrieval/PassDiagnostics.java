package com.personal.chatbot.service.retrieval;

import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.models.retrieval.RetrievalTimings;

import java.util.List;

/**
 * The v1 diagnostics of a retrieval that ran more than one pass (docs/observability-plan.md §4.3).
 * The two branches that merge passes — the widened second search of Phase 9a and the per-part
 * searches of Phase 9d — report the same shape, and this is the single place it is assembled.
 *
 * <p>Facet times are added up over the passes and the total is not: the passes may have run in
 * parallel, so their durations are the work the branch cost, while the wall time is what the workflow
 * measured around them (§4.1). Adding the stages up and calling the sum a latency is exactly the
 * mistake this separation exists to prevent; the canonical {@code chatbot.retrieval.workflow} timer
 * measures the wall time on its own.
 */
final class PassDiagnostics {

    private PassDiagnostics() {
    }

    /**
     * @param wallMs how long the branch took, as its {@code RetrievalWorkflow} measured it; the stages
     *               below are summed work and may exceed it
     */
    static RetrievalTimings timings(List<RetrievalResult> passes, long wallMs) {
        return new RetrievalTimings(
                passes.stream().mapToLong(pass -> pass.timings().vectorMs()).sum(),
                passes.stream().mapToLong(pass -> pass.timings().textMs()).sum(),
                passes.stream().mapToLong(pass -> pass.timings().fusionMs()).sum(),
                wallMs);
    }
}
