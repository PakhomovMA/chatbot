package com.personal.chatbot.service.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The stop order and what it reports (docs/concurrency-plan.md C08). */
class ShutdownSequenceTest {

    private static final Duration GRACE = Duration.ofMillis(50);

    private final List<String> steps = new CopyOnWriteArrayList<>();

    /** Work that goes quiet at a chosen point of the sequence and records what it was asked to do. */
    private final class FakeWork implements ActiveWork {

        private final String name;
        private final Quiet quiet;
        private boolean stopped;

        enum Quiet {
            /** Ends as soon as it is told to stop, like an idle worker. */
            WHEN_TOLD,
            /** Only lets go when interrupted, like a thread parked on a socket. */
            WHEN_INTERRUPTED,
            /** Never lets go, like a native call in progress. */
            NEVER
        }

        FakeWork(String name, Quiet quiet) {
            this.name = name;
            this.quiet = quiet;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void stopAccepting() {
            steps.add("stop:" + name);
            stopped = quiet == Quiet.WHEN_TOLD;
        }

        @Override
        public boolean awaitQuiet(Duration timeout) {
            steps.add("await:" + name);
            return stopped;
        }

        @Override
        public void interruptActive() {
            steps.add("interrupt:" + name);
            stopped = quiet == Quiet.WHEN_INTERRUPTED;
        }
    }

    private ShutdownSequence sequence(ActiveWork... work) {
        return new ShutdownSequence(List.of(work), GRACE, GRACE);
    }

    @Test
    void everyoneIsToldToStopBeforeAnyoneIsWaitedFor() {
        FakeWork chat = new FakeWork("chat", FakeWork.Quiet.WHEN_TOLD);
        FakeWork ingestion = new FakeWork("ingestion", FakeWork.Quiet.WHEN_TOLD);

        assertThat(sequence(chat, ingestion).stop().complete()).isTrue();
        assertThat(steps).containsExactly("stop:chat", "stop:ingestion", "await:chat", "await:ingestion");
    }

    @Test
    void workThatDoesNotStopByItselfIsInterruptedAndWaitedForAgain() {
        FakeWork stubborn = new FakeWork("ingestion", FakeWork.Quiet.WHEN_INTERRUPTED);

        assertThat(sequence(stubborn).stop().complete()).isTrue();
        assertThat(steps).containsExactly("stop:ingestion", "await:ingestion", "interrupt:ingestion", "await:ingestion");
    }

    @Test
    void onlyWorkThatIsStillBusyIsInterrupted() {
        FakeWork quiet = new FakeWork("chat", FakeWork.Quiet.WHEN_TOLD);
        FakeWork stubborn = new FakeWork("sse", FakeWork.Quiet.WHEN_INTERRUPTED);

        assertThat(sequence(quiet, stubborn).stop().complete()).isTrue();
        assertThat(steps).containsExactly("stop:chat", "stop:sse", "await:chat", "await:sse", "interrupt:sse", "await:sse");
    }

    /** What survives the interrupt is named, so the caller knows resources were left open on purpose. */
    @Test
    void workThatSurvivesTheInterruptIsReported() {
        ShutdownSequence.Result result = sequence(
                new FakeWork("chat", FakeWork.Quiet.WHEN_TOLD),
                new FakeWork("embedding", FakeWork.Quiet.NEVER)).stop();

        assertThat(result.complete()).isFalse();
        assertThat(result.stillRunning()).containsExactly("embedding");
    }

    /** One participant blowing up must not leave the rest of the application running. */
    @Test
    void aFailingParticipantDoesNotStopTheSequence() {
        ActiveWork broken = new ActiveWork() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void stopAccepting() {
                throw new IllegalStateException("cannot stop");
            }

            @Override
            public boolean awaitQuiet(Duration timeout) {
                throw new IllegalStateException("cannot wait");
            }

            @Override
            public void interruptActive() {
                throw new IllegalStateException("cannot interrupt");
            }
        };
        FakeWork ingestion = new FakeWork("ingestion", FakeWork.Quiet.WHEN_TOLD);

        ShutdownSequence.Result result = sequence(broken, ingestion).stop();

        assertThat(result.stillRunning()).containsExactly("broken");
        assertThat(steps).contains("stop:ingestion", "await:ingestion");
    }
}
