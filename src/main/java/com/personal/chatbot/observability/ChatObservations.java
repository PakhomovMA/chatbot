package com.personal.chatbot.observability;

import com.personal.chatbot.models.chat.AnswerMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What the chat side of the application measures (docs/observability-plan.md §3.1): the lifecycle of
 * a run, the logical AI operations inside it, and the branch decisions that are events rather than
 * boundaries.
 *
 * <p>The facade is what the services see. They say which operation is running and how it ended; the
 * names, labels and their allowed values live here and in
 * docs/observability/metric-catalog.json — never in a service.
 *
 * <p>Every counter is registered once, at startup, with every value of its vocabulary, so a series
 * that has not happened yet reads zero instead of being missing from the scrape.
 */
public final class ChatObservations {

    /** One stream's first delta and terminal event, independent of the thread doing the writing. */
    public final class Stream {
        private final long started = observations.clock().nanoTime();
        private final String answerMode;
        private boolean delta;
        private boolean terminal;

        private Stream(AnswerMode mode) { this.answerMode = mode.name().toLowerCase(Locale.ROOT); }

        public synchronized void delta() {
            if (!delta) {
                delta = true;
                observations.timer("chatbot.sse.first.delta", "Time to first delta queued for sending", "answer.mode", answerMode)
                        .record(observations.clock().since(started));
            }
        }

        public synchronized void finished(Outcome outcome) {
            if (terminal) return;
            terminal = true;
            String label = outcome == Outcome.SUCCESS && !delta ? "no_delta" : outcome.label();
            observations.counter("chatbot.sse.completed", "Terminal stream events", "answer.mode", answerMode,
                    "outcome", label).increment();
        }
    }

    public Stream startStream(AnswerMode mode) { return new Stream(mode); }

    /** What became of one attempt to resolve a question against its history (Phase 9b). */
    public enum RewriteOutcome {
        REWRITTEN, UNCHANGED, FALLBACK
    }

    /** What became of one decomposition workflow (Phase 9d). */
    public enum DecompositionOutcome {
        SPLIT, SINGLE, FAILED
    }

    /** What one comparison of the sources found (Phase 9d). */
    public enum ComparisonOutcome {
        CONFLICT, AGREEMENT, NONE, FAILED
    }

    /** What the search budget decided about one agentic search tool call (Phase 9c). */
    public enum SearchDecision {
        RAN, REPEATED, REFUSED
    }

    /** Why a question was refused before it was accepted; not a failure of an accepted run. */
    public enum Rejection {
        STOPPING, CAPACITY
    }

    private final Observations observations;
    private final Map<Rejection, Counter> rejected = new EnumMap<>(Rejection.class);
    private final Map<RewriteOutcome, Counter> rewrites = new EnumMap<>(RewriteOutcome.class);
    private final Map<DecompositionOutcome, Counter> decompositions = new EnumMap<>(DecompositionOutcome.class);
    private final Map<ComparisonOutcome, Counter> comparisons = new EnumMap<>(ComparisonOutcome.class);
    private final Map<SearchDecision, Counter> agenticSearches = new EnumMap<>(SearchDecision.class);
    private final Counter agenticDraftUnparsable;
    private final Counter agenticFallbackWithPassages;
    private final Counter agenticFallbackWithNothing;

    public ChatObservations(Observations observations) {
        this.observations = observations;
        counters(rejected, Rejection.values(), "chatbot.chat.rejected", "reason",
                "Questions refused before a run was accepted");
        counters(rewrites, RewriteOutcome.values(), "chatbot.chat.query.rewrite", "outcome",
                "Attempts to resolve a question against its conversation history");
        counters(decompositions, DecompositionOutcome.values(), "chatbot.chat.decomposition", "outcome",
                "Questions split into parts before retrieval");
        counters(comparisons, ComparisonOutcome.values(), "chatbot.chat.comparison", "outcome",
                "Comparisons of the sources behind the evidence");
        counters(agenticSearches, SearchDecision.values(), "chatbot.chat.agentic.search", "outcome",
                "Search tool calls the agentic budget admitted or refused");
        this.agenticDraftUnparsable = observations.counter("chatbot.chat.agentic.draft",
                "Agentic drafts the model did not return in the expected shape", "outcome", "unparsable");
        this.agenticFallbackWithPassages = observations.counter("chatbot.chat.agentic.fallback",
                "Deterministic retrieval after a tool loop that used no tool", "outcome", "passages");
        this.agenticFallbackWithNothing = observations.counter("chatbot.chat.agentic.fallback",
                "Deterministic retrieval after a tool loop that used no tool", "outcome", "nothing");
    }

    /**
     * Opens the measurement of an accepted run. Everything the run does is inside it, including the
     * wait for the conversation lease; it ends once, whatever the run ends in.
     */
    public ChatRun startRun(boolean streaming, AnswerMode answerMode) {
        return new ChatRun(observations, streaming, answerMode);
    }

    /** One logical AI operation; the caller closes it exactly once. */
    public Measured startAiOperation(AiOperation operation) {
        return observations.start(MeasuredOperation.AI_OPERATION,
                MeasuredOperation.Labels.OPERATION, operation.label());
    }

    /**
     * A question refused before a run was accepted. It is deliberately not a {@code chatbot.chat.request}
     * record: it belongs to no run, and counting it as one would move the error ratio of the runs that
     * were accepted (docs/observability-plan.md §5.2).
     */
    public void rejected(Rejection reason) {
        rejected.get(reason).increment();
    }

    /**
     * A run that was accepted and then refused a thread of its own. It never entered, so only the
     * window between admission and refusal is measured — but the run is accounted for, because its
     * caller was told the request failed.
     */
    public void rejectedAfterAdmission(boolean streaming, AnswerMode answerMode) {
        endedBeforeStart(streaming, answerMode, Outcome.REJECTED);
    }

    public void endedBeforeStart(boolean streaming, AnswerMode answerMode, Outcome outcome) {
        try (ChatRun run = startRun(streaming, answerMode)) {
            run.finished(outcome);
        }
    }

    /** Publishes how many accepted runs have not finished yet; registered once, by their owner. */
    public void trackActiveRuns(Supplier<Number> accepted) {
        Gauge.builder("chatbot.chat.active", accepted)
                .description("Accepted chat runs that have not finished")
                .register(observations.meterRegistry());
    }

    public void queryRewrite(RewriteOutcome outcome) {
        rewrites.get(outcome).increment();
    }

    public void decomposition(DecompositionOutcome outcome) {
        decompositions.get(outcome).increment();
    }

    public void comparison(ComparisonOutcome outcome) {
        comparisons.get(outcome).increment();
    }

    public void agenticSearch(SearchDecision decision) {
        agenticSearches.get(decision).increment();
    }

    public void agenticDraftUnparsable() {
        agenticDraftUnparsable.increment();
    }

    /** @param passages whether the deterministic retrieval behind a toolless tool loop found anything */
    public void agenticFallback(boolean passages) {
        (passages ? agenticFallbackWithPassages : agenticFallbackWithNothing).increment();
    }

    private <E extends Enum<E>> void counters(Map<E, Counter> into, E[] values, String name, String tag,
                                              String description) {
        Arrays.stream(values).forEach(value ->
                into.put(value, observations.counter(name, description, tag, value.name().toLowerCase(Locale.ROOT))));
    }
}
