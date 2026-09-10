package com.personal.chatbot.observability;

import com.personal.chatbot.exceptions.ChatCancelledException;
import com.personal.chatbot.models.chat.AnswerMode;
import com.personal.chatbot.models.chat.ChatTimings;
import com.personal.chatbot.models.chat.Grounding;
import com.personal.chatbot.models.retrieval.RetrievalMode;
import com.personal.chatbot.support.TestObservations;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O02 gate: the boundaries, outcomes and terminal records of the canonical measurements
 * (docs/observability-plan.md §9, docs/observability/metric-catalog.json).
 *
 * <p>Nothing here sleeps: one mock clock drives both the timers Micrometer records and the elapsed
 * time the facades measure, so a boundary is asserted rather than approximated. Nothing here
 * configures tracing either — these are the meters an application with no tracer, no sampling and no
 * exporter publishes.
 */
class OperationLifecycleTest {

    private final TestObservations observed = TestObservations.create();
    private final SimpleMeterRegistry meters = observed.meters();

    @Test
    void aChatRunIsMeasuredToItsEndAndTheLeaseWaitInsideIt() {
        ChatTimings timings;
        try (ChatRun run = observed.chatObservations().startRun(false, AnswerMode.DETERMINISTIC)) {
            Optional<String> lease = run.awaitConversation(() -> {
                observed.advance(Duration.ofMillis(300)); // queued behind the previous question
                return Optional.of("lease");
            }, () -> null);
            assertThat(lease).contains("lease");
            observed.advance(Duration.ofMillis(700)); // the agent answering
            run.agentFinished();
            timings = run.timings(200);
            observed.advance(Duration.ofMillis(50)); // diagnostics and the conversation history
            run.succeeded(Grounding.GROUNDED);
        }

        Timer request = meters.get("chatbot.chat.request").tags("mode", "sync", "answer.mode", "deterministic",
                "grounding", "grounded", "outcome", "success").timer();
        assertThat(request.count()).isEqualTo(1);
        assertThat(request.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1050);
        assertThat(meters.get("chatbot.chat.wait").tag("outcome", "success").timer().totalTime(
                TimeUnit.MILLISECONDS)).isEqualTo(300);

        // The legacy timer keeps the boundary it had: up to the end of the agent invocation, and only
        // for a run that answered. It is a different measurement, not a second copy of the one above.
        assertThat(meters.get("chatbot.chat").tags("grounding", "grounded", "mode", "sync",
                        "answerMode", "deterministic").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1000);
        assertThat(timings).isEqualTo(new ChatTimings(200, 800, 1000));
    }

    @Test
    void anAbandonedRunEndsInACancellationAndIsNotACompletedOne() {
        try (ChatRun run = observed.chatObservations().startRun(true, AnswerMode.AGENTIC)) {
            run.failed(new ChatCancelledException("m", "client closed the stream"), "client closed the stream");
        }
        try (ChatRun timedOut = observed.chatObservations().startRun(true, AnswerMode.AGENTIC)) {
            timedOut.failed(new ChatCancelledException("m", "stream timed out after PT10M"),
                    "stream timed out after PT10M");
        }

        assertThat(meters.get("chatbot.chat.request").tag("outcome", "cancelled").timer().count()).isEqualTo(1);
        assertThat(meters.get("chatbot.chat.request").tag("outcome", "timeout").timer().count()).isEqualTo(1);
        assertThat(meters.get("chatbot.chat.request").tag("grounding", "none").timers()).hasSize(2);
        // Neither is a successful run, so the legacy timer never saw them at all.
        assertThatThrownBy(() -> meters.get("chatbot.chat").timer()).isInstanceOf(MeterNotFoundException.class);
    }

    @Test
    void everyAiOperationEndsInExactlyOneRecord() {
        ChatObservations chat = observed.chatObservations();
        try (Measured drafted = chat.startAiOperation(AiOperation.DRAFT_ANSWER)) {
            drafted.succeeded();
        }
        try (Measured recovered = chat.startAiOperation(AiOperation.DECOMPOSE_QUESTION)) {
            recovered.recovered(new IllegalStateException("the model returned nothing usable"));
        }
        try (Measured broken = chat.startAiOperation(AiOperation.COMPARE_SOURCES)) {
            broken.failed(new IllegalStateException("boom"));
        }
        try (Measured abandoned = chat.startAiOperation(AiOperation.RESEARCH_AGENTIC)) {
            abandoned.recovered(new IllegalStateException("boom"), "client closed the stream");
        }

        assertThat(meters.find("chatbot.ai.operation").timers().stream().mapToLong(Timer::count).sum()).isEqualTo(4);
        assertThat(meters.get("chatbot.ai.operation").tags("operation", "draft-answer", "outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("chatbot.ai.operation").tags("operation", "decompose-question", "outcome", "fallback")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("chatbot.ai.operation").tags("operation", "compare-sources", "outcome", "error")
                .timer().count()).isEqualTo(1);
        // A best-effort branch of a request nobody is waiting for ended in that, not in a fallback.
        assertThat(meters.get("chatbot.ai.operation").tags("operation", "research-agentic", "outcome", "cancelled")
                .timer().count()).isEqualTo(1);
        // The legacy timer recorded whatever the operation ended in, as its try/finally always did.
        assertThat(meters.find("chatbot.llm").timers().stream().mapToLong(Timer::count).sum()).isEqualTo(4);
    }

    @Test
    void theAgenticLegacyTimerKeepsTheToolLoopWindowInsideTheWiderOperation() {
        try (Measured research = observed.chatObservations().startAiOperation(AiOperation.RESEARCH_AGENTIC)) {
            observed.advance(Duration.ofMillis(500)); // seed decomposition, before the tool loop
            research.legacyBegins();
            observed.advance(Duration.ofMillis(2000)); // the tool loop the old timer measured
            research.legacyEnds();
            observed.advance(Duration.ofMillis(300)); // mapping, fallback retrieval and the repair
            research.succeeded();
        }

        assertThat(meters.get("chatbot.ai.operation").tags("operation", "research-agentic", "outcome", "success")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2800);
        assertThat(meters.get("chatbot.llm").tag("operation", "research-agentic").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2000);
    }

    @Test
    void aRecoveredFailureIsNotAFailedRun() {
        ChatObservations chat = observed.chatObservations();
        try (ChatRun run = chat.startRun(false, AnswerMode.DETERMINISTIC)) {
            try (Measured rewrite = chat.startAiOperation(AiOperation.CONVERSATION_QUERY_REWRITE)) {
                rewrite.recovered(new IllegalStateException("the model timed out on the rewrite"));
            }
            chat.queryRewrite(ChatObservations.RewriteOutcome.FALLBACK);
            run.agentFinished();
            run.succeeded(Grounding.GROUNDED);
        }

        assertThat(meters.get("chatbot.ai.operation")
                .tags("operation", "conversation-query-rewrite", "outcome", "fallback").timer().count()).isEqualTo(1);
        assertThat(meters.get("chatbot.chat.request").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(meters.get("chatbot.chat.query.rewrite").tag("outcome", "fallback").counter().count()).isEqualTo(1);
        assertThat(meters.get("chatbot.chat").timer().count()).isEqualTo(1);
    }

    @Test
    void aRetrievalPassIsMeasuredWhateverItEndsIn() {
        RetrievalObservations retrieval = observed.retrievalObservations();
        try (Measured pass = retrieval.startSearch(RetrievalMode.HYBRID)) {
            observed.advance(Duration.ofMillis(40));
            retrieval.passages(7);
            pass.succeeded();
        }
        try (Measured failed = retrieval.startSearch(RetrievalMode.VECTOR)) {
            failed.failed(new IllegalStateException("the index is not readable"));
        }

        assertThat(meters.get("chatbot.retrieval.search").tags("mode", "hybrid", "outcome", "success").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(40);
        assertThat(meters.get("chatbot.retrieval.search").tags("mode", "vector", "outcome", "error").timer()
                .count()).isEqualTo(1);
        assertThat(meters.get("chatbot.retrieval.hits").summary().count()).isEqualTo(1);
        // The legacy timer counted the passes that came back with something, and only those.
        assertThat(meters.get("chatbot.retrieval").tag("mode", "hybrid").timer().count()).isEqualTo(1);
        assertThat(meters.find("chatbot.retrieval").tag("mode", "vector").timer()).isNull();
    }

    @Test
    void aBoundaryIsRecordedOnceHoweverOftenItIsClosed() {
        Measured pass = observed.retrievalObservations().startSearch(RetrievalMode.TEXT);
        pass.succeeded();
        pass.failed(new IllegalStateException("too late: the boundary already ended"));
        pass.close();
        pass.close();

        assertThat(meters.get("chatbot.retrieval.search").tags("mode", "text", "outcome", "success").timer()
                .count()).isEqualTo(1);
        assertThat(meters.find("chatbot.retrieval.search").tag("outcome", "error").timer()).isNull();
    }

    @Test
    void aBoundaryLeftWithoutAnOutcomeIsNotASuccess() {
        try (Measured ignored = observed.retrievalObservations().startSearch(RetrievalMode.TEXT)) {
            assertThat(ignored.isFinished()).isFalse();
        }
        assertThat(meters.get("chatbot.retrieval.search").tag("outcome", "error").timer().count()).isEqualTo(1);
    }

    @Test
    void theExceptionOfAFailedOperationDoesNotBecomeALabel() {
        try (Measured failed = observed.chatObservations().startAiOperation(AiOperation.DRAFT_ANSWER)) {
            failed.failed(new IllegalStateException("boom"));
        }
        assertThat(meters.get("chatbot.ai.operation").timer().getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("operation", "outcome");
    }

    @Test
    void whatIsRunningRightNowSaysWhichOperationItIs() {
        try (Measured research = observed.chatObservations().startAiOperation(AiOperation.RESEARCH_AGENTIC)) {
            // The long-task timer is tagged when the boundary opens, so what is known by then is set first.
            assertThat(meters.get("chatbot.ai.operation.active").tag("operation", "research-agentic")
                    .longTaskTimer().activeTasks()).isEqualTo(1);
            research.succeeded();
        }
        assertThat(meters.get("chatbot.ai.operation.active").tag("operation", "research-agentic")
                .longTaskTimer().activeTasks()).isZero();
    }

    @Test
    void theLegacyNamesCanBeSwitchedOff() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Observations plumbing = Observations.standalone(registry);
        ChatObservations chat = new ChatObservations(new Observations(plumbing.observationRegistry(), registry,
                plumbing.clock(), LegacyMetrics.DISABLED));
        try (ChatRun run = chat.startRun(false, AnswerMode.DETERMINISTIC)) {
            run.succeeded(Grounding.GROUNDED);
        }
        try (Measured drafted = chat.startAiOperation(AiOperation.DRAFT_ANSWER)) {
            drafted.succeeded();
        }

        assertThat(registry.get("chatbot.chat.request").timer().count()).isEqualTo(1);
        assertThat(registry.find("chatbot.chat").timer()).isNull();
        assertThat(registry.find("chatbot.llm").timer()).isNull();
    }

    @Test
    void aRefusedQuestionIsCountedApartFromTheRunsThatWereAccepted() {
        ChatObservations chat = observed.chatObservations();
        chat.rejected(ChatObservations.Rejection.STOPPING);
        chat.rejectedAfterAdmission(true, AnswerMode.DETERMINISTIC);

        assertThat(meters.get("chatbot.chat.rejected").tag("reason", "stopping").counter().count()).isEqualTo(1);
        assertThat(meters.get("chatbot.chat.rejected").tag("reason", "capacity").counter().count()).isZero();
        assertThat(meters.get("chatbot.chat.request").tag("outcome", "rejected").timer().count()).isEqualTo(1);
    }
}
