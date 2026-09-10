package com.personal.chatbot.support;

import com.personal.chatbot.observability.ChatObservations;
import com.personal.chatbot.observability.EmbeddingObservations;
import com.personal.chatbot.observability.IngestionObservations;
import com.personal.chatbot.observability.Observations;
import com.personal.chatbot.observability.RetrievalObservations;
import com.personal.chatbot.observability.RetrievalStrategy;
import com.personal.chatbot.observability.RetrievalWorkflow;
import com.personal.chatbot.observability.SseObservations;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;

/**
 * The observability facades over a registry of their own, for a test that instantiates a service
 * directly. One clock drives both the timers Micrometer records and the elapsed time the facades
 * measure, so a test advances time instead of sleeping for it.
 */
public record TestObservations(SimpleMeterRegistry meters, MockClock clock, Observations observations,
                               ChatObservations chatObservations, RetrievalObservations retrievalObservations,
                               IngestionObservations ingestionObservations, SseObservations sseObservations) {

    public static TestObservations create() {
        MockClock clock = new MockClock();
        SimpleMeterRegistry meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        Observations observations = Observations.standalone(meters, clock::monotonicTime);
        return new TestObservations(meters, clock, observations, new ChatObservations(observations),
                new RetrievalObservations(observations), new IngestionObservations(observations),
                new SseObservations(observations));
    }

    /** Chat facade over a registry nobody reads; for services a test builds for other reasons. */
    public static ChatObservations chat() {
        return create().chatObservations();
    }

    /** Retrieval facade over a registry nobody reads. */
    public static RetrievalObservations retrieval() {
        return create().retrievalObservations();
    }

    /** Ingestion facade over a registry nobody reads. */
    public static IngestionObservations ingestion() {
        return create().ingestionObservations();
    }

    /** SSE facade over a registry nobody reads. */
    public static SseObservations sse() {
        return create().sseObservations();
    }

    /** Embedding facade over a registry nobody reads, for the backend a test brought along. */
    public static EmbeddingObservations embedding(String provider, String model) {
        return create().embeddingObservations(provider, model);
    }

    public EmbeddingObservations embeddingObservations(String provider, String model) {
        return new EmbeddingObservations(observations, provider, model);
    }

    /**
     * A retrieval workflow that has already been open for {@code alreadySpent} — what a branch looks
     * like by the time the model has produced its extra queries. Because this clock only moves when a
     * test moves it, whatever the branch then reports as its v1 {@code tookMs} is exactly this value
     * plus whatever the test advances afterwards.
     */
    public RetrievalWorkflow workflowAfter(RetrievalStrategy strategy, Duration alreadySpent) {
        RetrievalWorkflow workflow = retrievalObservations.startWorkflow(strategy);
        advance(alreadySpent);
        return workflow;
    }

    /** Moves both clocks on by {@code elapsed}; nothing here waits for real time. */
    public void advance(Duration elapsed) {
        clock.add(elapsed);
    }
}
