package com.personal.chatbot.support;

import com.personal.chatbot.observability.ChatObservations;
import com.personal.chatbot.observability.Observations;
import com.personal.chatbot.observability.RetrievalObservations;
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
                               ChatObservations chatObservations, RetrievalObservations retrievalObservations) {

    public static TestObservations create() {
        MockClock clock = new MockClock();
        SimpleMeterRegistry meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        Observations observations = Observations.standalone(meters, clock::monotonicTime);
        return new TestObservations(meters, clock, observations, new ChatObservations(observations),
                new RetrievalObservations(observations));
    }

    /** Chat facade over a registry nobody reads; for services a test builds for other reasons. */
    public static ChatObservations chat() {
        return create().chatObservations();
    }

    /** Retrieval facade over a registry nobody reads. */
    public static RetrievalObservations retrieval() {
        return create().retrievalObservations();
    }

    /** Moves both clocks on by {@code elapsed}; nothing here waits for real time. */
    public void advance(Duration elapsed) {
        clock.add(elapsed);
    }
}
