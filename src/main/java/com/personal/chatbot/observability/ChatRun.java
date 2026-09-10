package com.personal.chatbot.observability;

import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.chat.ChatTimings;
import com.personal.chatbot.models.chat.Grounding;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The measurement of one accepted chat run (docs/observability/metric-catalog.json,
 * {@code chatbot.chat.request}). It spans everything the run does — the wait for the conversation
 * lease included — and ends exactly once, on the answer or on the error, cancellation or timeout that
 * replaced it.
 *
 * <p>It also carries the marks the diagnostics of the v1 API are built from. {@code ChatTimings} keeps
 * the boundaries it always had: {@code totalMs} up to the end of the agent invocation, and
 * {@code llmMs} as what is left of it after retrieval — a residual that includes orchestration,
 * verification and the wait, and is not measured model time (docs/observability-plan.md §4.3). This
 * class is the one place those milliseconds are computed; the canonical timer above measures the whole
 * run instead.
 */
public final class ChatRun implements AutoCloseable {

    private final Observations observations;
    private final Measured request;
    private final ExecutionDiagnostics diagnostics = ExecutionDiagnostics.open();
    private final ExecutionDiagnostics.Scope installed;

    ChatRun(Observations observations, boolean streaming, AnswerMode answerMode) {
        this.observations = observations;
        this.request = observations.start(MeasuredOperation.CHAT_REQUEST,
                MeasuredOperation.Labels.MODE, streaming ? "stream" : "sync",
                MeasuredOperation.Labels.ANSWER_MODE, answerMode.name().toLowerCase(Locale.ROOT),
                MeasuredOperation.Labels.GROUNDING, MeasuredOperation.Labels.NONE);
        this.installed = diagnostics.install();
    }

    /**
     * What this run measured for itself, for the diagnostics of its own answer. Bounded and released
     * with the run, and independent of the shared trace ring, of sampling and of any exporter
     * (docs/observability-plan.md §4.2).
     */
    public ExecutionDiagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * Measures the wait for the conversation lease as a boundary of its own, so that queueing behind
     * the previous question of the same conversation is not read later as time spent in the model.
     *
     * @param lease              the attempt, which comes back empty when the request was abandoned
     *                           while it waited
     * @param cancellationReason why it was abandoned, asked for only once it was
     */
    public <T> Optional<T> awaitConversation(Supplier<Optional<T>> lease,
                                             Supplier<@Nullable String> cancellationReason) {
        try (Measured wait = observations.start(MeasuredOperation.CHAT_WAIT)) {
            try {
                Optional<T> acquired = lease.get();
                if (acquired.isPresent()) {
                    wait.succeeded();
                } else {
                    wait.finished(Cancellations.outcomeOf(cancellationReason.get()));
                }
                return acquired;
            } catch (RuntimeException e) {
                wait.failed(e, cancellationReason.get());
                throw e;
            }
        }
    }

    /**
     * The agent has produced its answer. This is where the legacy {@code totalMs} was read, before the
     * diagnostics, the history and the final SSE event that follow it.
     */
    public void agentFinished() {
        request.legacyEnds();
    }

    /** The v1 diagnostics of this run, with the boundaries described above. */
    public ChatTimings timings(long retrievalMs) {
        long totalMs = request.legacyElapsed().toMillis();
        return new ChatTimings(retrievalMs, Math.max(0, totalMs - retrievalMs), totalMs);
    }

    /** The run answered; {@code grounding} is how well the answer is backed by evidence. */
    public void succeeded(Grounding grounding) {
        request.label(MeasuredOperation.Labels.GROUNDING, grounding.name().toLowerCase(Locale.ROOT));
        request.succeeded();
    }

    /**
     * The run ended in {@code error}. A run abandoned by its caller ends as a cancellation or, when
     * its own deadline stopped it, as a timeout — neither is counted as a failure of the application.
     */
    public void failed(Throwable error, @Nullable String cancellationReason) {
        request.failed(error, cancellationReason);
    }

    /** For a terminal state that is not an exception here, such as a refused executor. */
    public void finished(Outcome outcome) {
        request.finished(outcome);
    }

    public boolean isFinished() {
        return request.isFinished();
    }

    @Override
    public void close() {
        try {
            installed.close();
        } finally {
            request.close();
        }
    }
}
